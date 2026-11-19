package com.hesta.backend.ota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.diagnostics.MqttDiagnosticsService;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtaCampaignServiceImpl implements OtaCampaignService {

    private static final long MAX_FIRMWARE_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> INTERMEDIATE_STATUSES = Set.of(
            "DOWNLOADING", "VERIFYING", "FLASHING", "REBOOTING", "PROGRESS"
    );
    private static final Set<String> TERMINAL_NODE_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED"
    );

    private final EdgeNodeRepository edgeNodeRepository;
    private final OtaFirmwarePackageRepository packageRepository;
    private final OtaCampaignRepository campaignRepository;
    private final OtaCampaignNodeRepository campaignNodeRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private DeviceRepository deviceRepository;

    @Autowired(required = false)
    private MqttGateway mqttGateway;

    @Autowired(required = false)
    private MqttDiagnosticsService diagnosticsService;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    @Value("${mqtt.topic.prefix:hesta/nodes}")
    private String topicPrefix = "hesta/nodes";

    @Value("${hesta.ota.storage-dir:uploads/firmware}")
    private String storageDir = "uploads/firmware";

    @Value("${hesta.ota.public-base-url:}")
    private String publicBaseUrl = "";

    @Value("${hesta.ota.node-timeout-seconds:180}")
    private long nodeTimeoutSeconds = 180L;

    @Override
    @Transactional
    public FirmwarePackageDto uploadPackage(
            MultipartFile file,
            String deviceType,
            String version,
            String releaseNotes,
            String requestBaseUrl) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID, "Vui lòng chọn tệp firmware .bin để tải lên");
        }
        if (file.getSize() > MAX_FIRMWARE_BYTES) {
            throw new AppException(ErrorCode.OTA_FILE_TOO_LARGE);
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "firmware.bin";
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID, "Chỉ hỗ trợ định dạng tệp firmware .bin");
        }

        String cleanType = requireNonBlank(deviceType, "Loại thiết bị không được để trống").toUpperCase(Locale.ROOT);
        String cleanVersion = requireNonBlank(version, "Phiên bản firmware không được để trống");

        UUID packageId = UUID.randomUUID();
        String storageKey = packageId + ".bin";
        Path rootDir = resolveStorageRoot();
        Path targetPath = rootDir.resolve(storageKey).normalize();
        if (!targetPath.startsWith(rootDir)) {
            throw new AppException(ErrorCode.OTA_FILE_STORAGE_FAILED);
        }

        String sha256Hex;
        long totalBytes = 0L;
        Path tempFile = null;
        try {
            Files.createDirectories(rootDir);
            tempFile = Files.createTempFile(rootDir, "ota-upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream is = new DigestInputStream(file.getInputStream(), digest);
                 OutputStream os = Files.newOutputStream(tempFile)) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                    totalBytes += read;
                }
            }
            if (totalBytes <= 0 || totalBytes > MAX_FIRMWARE_BYTES) {
                Files.deleteIfExists(tempFile);
                throw new AppException(totalBytes > MAX_FIRMWARE_BYTES ? ErrorCode.OTA_FILE_TOO_LARGE : ErrorCode.OTA_PACKAGE_INVALID);
            }
            sha256Hex = HexFormat.of().formatHex(digest.digest());
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (AppException e) {
            cleanupTempQuietly(tempFile);
            throw e;
        } catch (IOException | NoSuchAlgorithmException e) {
            cleanupTempQuietly(tempFile);
            log.error("Failed to store firmware binary [{}]: {}", originalName, e.getMessage(), e);
            throw new AppException(ErrorCode.OTA_FILE_STORAGE_FAILED);
        }

        String base = resolvePublicBaseUrl(requestBaseUrl);
        String downloadUrl = base + "/api/v1/ota/packages/" + packageId + "/download";

        OtaFirmwarePackageEntity entity = OtaFirmwarePackageEntity.builder()
                .id(packageId)
                .deviceType(cleanType)
                .version(cleanVersion)
                .fileUrl(downloadUrl)
                .storageKey(storageKey)
                .originalFileName(originalName)
                .uploaded(true)
                .checksumSha256(sha256Hex)
                .fileSizeBytes(totalBytes)
                .releaseNotes(releaseNotes != null ? releaseNotes.trim() : null)
                .createdAt(OffsetDateTime.now())
                .build();

        OtaFirmwarePackageEntity saved = packageRepository.save(entity);
        log.info("Uploaded OTA Firmware Package [{}] type={} version={} size={} sha256={}",
                saved.getId(), saved.getDeviceType(), saved.getVersion(), totalBytes, sha256Hex);
        return toPackageDto(saved);
    }

    @Override
    @Transactional
    public FirmwarePackageDto registerPackage(FirmwarePackageDto dto) {
        if (dto == null) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID);
        }
        String cleanType = requireNonBlank(dto.getDeviceType(), "Loại thiết bị không được để trống").toUpperCase(Locale.ROOT);
        String cleanVersion = requireNonBlank(dto.getVersion(), "Phiên bản firmware không được để trống");
        String cleanUrl = requireNonBlank(dto.getFileUrl(), "URL tệp firmware không được để trống");
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID, "URL firmware phải bắt đầu bằng http:// hoặc https://");
        }
        String cleanSha = requireNonBlank(dto.getChecksumSha256(), "Checksum SHA-256 không được để trống").toLowerCase(Locale.ROOT);
        if (!cleanSha.matches("^[0-9a-f]{64}$")) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID, "Checksum SHA-256 phải gồm đúng 64 ký tự hex");
        }

        UUID id = dto.getId() != null ? dto.getId() : UUID.randomUUID();
        OtaFirmwarePackageEntity entity = OtaFirmwarePackageEntity.builder()
                .id(id)
                .deviceType(cleanType)
                .version(cleanVersion)
                .fileUrl(cleanUrl)
                .storageKey(null)
                .originalFileName(dto.getOriginalFileName())
                .uploaded(false)
                .checksumSha256(cleanSha)
                .fileSizeBytes(Math.max(0L, dto.getFileSizeBytes()))
                .releaseNotes(dto.getReleaseNotes())
                .createdAt(OffsetDateTime.now())
                .build();

        OtaFirmwarePackageEntity saved = packageRepository.save(entity);
        log.info("Registered external OTA Firmware Package [{}] for type [{}] version [{}]",
                saved.getId(), saved.getDeviceType(), saved.getVersion());
        return toPackageDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FirmwarePackageDto> listPackages() {
        return packageRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toPackageDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public FirmwarePackageDto getPackage(UUID packageId) {
        if (packageId == null) {
            throw new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND);
        }
        return packageRepository.findById(packageId)
                .map(this::toPackageDto)
                .orElseThrow(() -> new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public FirmwareDownloadPayload loadFirmwareForDownload(UUID packageId) {
        OtaFirmwarePackageEntity entity = packageRepository.findById(packageId)
                .orElseThrow(() -> new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND));

        if (!entity.isUploaded() || entity.getStorageKey() == null || entity.getStorageKey().isBlank()) {
            throw new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND, "Gói firmware này được host bên ngoài, không lưu trên máy chủ");
        }

        Path rootDir = resolveStorageRoot();
        Path filePath = rootDir.resolve(entity.getStorageKey()).normalize();
        if (!filePath.startsWith(rootDir) || !Files.exists(filePath) || !Files.isReadable(filePath)) {
            throw new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND, "Tệp nhị phân firmware không tồn tại trên ổ đĩa");
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND);
            }
            return new FirmwareDownloadPayload(resource, toPackageDto(entity));
        } catch (MalformedURLException e) {
            throw new AppException(ErrorCode.OTA_FILE_STORAGE_FAILED);
        }
    }

    @Override
    @Transactional
    public OtaCampaignDto createAndStartCampaign(CreateOtaCampaignRequest request) {
        if (request == null || request.getFirmwarePackageId() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        OtaFirmwarePackageEntity pkg = packageRepository.findById(request.getFirmwarePackageId())
                .orElseThrow(() -> new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND));

        if (campaignRepository.existsActiveCampaignForDeviceType(pkg.getDeviceType())) {
            throw new AppException(ErrorCode.OTA_CAMPAIGN_CONFLICT);
        }

        List<EdgeNode> targetNodes = resolveTargetNodes(pkg.getDeviceType());
        if (targetNodes.isEmpty()) {
            throw new AppException(ErrorCode.OTA_NO_TARGET_NODES);
        }

        String strategy = (request.getRolloutStrategy() != null && !request.getRolloutStrategy().isBlank())
                ? request.getRolloutStrategy().trim().toUpperCase(Locale.ROOT)
                : "CANARY";
        if (!"CANARY".equals(strategy) && !"IMMEDIATE".equals(strategy)) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID, "Chiến lược rollout phải là CANARY hoặc IMMEDIATE");
        }

        double threshold = (request.getFailureThresholdPercent() != null && request.getFailureThresholdPercent() > 0)
                ? Math.min(100.0, request.getFailureThresholdPercent())
                : 20.0;

        int totalNodes = targetNodes.size();
        UUID campaignId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        int wave1Count = "IMMEDIATE".equals(strategy)
                ? totalNodes
                : Math.max(1, (int) Math.ceil(totalNodes * 0.10));
        int wave2Cumulative = "IMMEDIATE".equals(strategy)
                ? totalNodes
                : Math.max(wave1Count, (int) Math.ceil(totalNodes * 0.50));

        String initialStatus = "CANARY".equals(strategy) ? "RUNNING_CANARY_10" : "RUNNING_FULL_100";

        OtaCampaignEntity campaign = OtaCampaignEntity.builder()
                .id(campaignId)
                .name((request.getName() != null && !request.getName().isBlank())
                        ? request.getName().trim()
                        : "Chiến dịch nâng cấp " + pkg.getVersion())
                .firmwarePackageId(pkg.getId())
                .deviceType(pkg.getDeviceType())
                .version(pkg.getVersion())
                .status(initialStatus)
                .rolloutStrategy(strategy)
                .failureThresholdPercent(threshold)
                .currentWave(1)
                .totalNodes(totalNodes)
                .attemptedNodes(0)
                .successNodes(0)
                .failedNodes(0)
                .abortReason(null)
                .createdAt(now)
                .updatedAt(now)
                .build();

        campaignRepository.save(campaign);

        List<OtaCampaignNodeEntity> nodeExecutions = new ArrayList<>(totalNodes);
        List<EdgeNode> wave1Nodes = new ArrayList<>();
        for (int i = 0; i < totalNodes; i++) {
            EdgeNode node = targetNodes.get(i);
            int wave;
            if (i < wave1Count) {
                wave = 1;
                wave1Nodes.add(node);
            } else if (i < wave2Cumulative) {
                wave = 2;
            } else {
                wave = 3;
            }
            boolean inWave1 = (wave == 1);
            nodeExecutions.add(OtaCampaignNodeEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(campaignId)
                    .nodeId(node.getId())
                    .nodeCode(node.getNodeCode())
                    .wave(wave)
                    .status(inWave1 ? "DISPATCHED" : "PENDING")
                    .dispatchedAt(inWave1 ? now : null)
                    .updatedAt(now)
                    .build());
        }

        campaignNodeRepository.saveAll(nodeExecutions);

        FirmwarePackageDto pkgDto = toPackageDto(pkg);
        OtaCampaignDto campaignDto = toCampaignDto(campaign, nodeExecutions);
        runAfterCommit(() -> publishOtaNotifyBatch(campaignDto, pkgDto,
                wave1Nodes.stream().map(EdgeNode::getNodeCode).toList()));

        log.info("Created and started OTA Campaign [{}] ({}) for {} target nodes (Wave 1: {}) with threshold {}%",
                campaignId, campaign.getName(), totalNodes, wave1Nodes.size(), threshold);

        return campaignDto;
    }

    @Override
    @Transactional(readOnly = true)
    public OtaCampaignDto getCampaign(UUID campaignId) {
        OtaCampaignEntity campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.OTA_CAMPAIGN_NOT_FOUND));
        List<OtaCampaignNodeEntity> nodes = campaignNodeRepository.findByCampaignIdOrderByWaveAscNodeCodeAsc(campaignId);
        return toCampaignDto(campaign, nodes);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OtaCampaignDto> listCampaigns() {
        List<OtaCampaignEntity> campaigns = campaignRepository.findAllByOrderByCreatedAtDesc();
        if (campaigns.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = campaigns.stream().map(OtaCampaignEntity::getId).toList();
        Map<UUID, List<OtaCampaignNodeEntity>> nodesByCampaign = campaignNodeRepository.findByCampaignIdIn(ids).stream()
                .collect(Collectors.groupingBy(OtaCampaignNodeEntity::getCampaignId));

        return campaigns.stream()
                .map(c -> {
                    List<OtaCampaignNodeEntity> nodes = new ArrayList<>(nodesByCampaign.getOrDefault(c.getId(), List.of()));
                    nodes.sort(Comparator.comparingInt(OtaCampaignNodeEntity::getWave)
                            .thenComparing(OtaCampaignNodeEntity::getNodeCode));
                    return toCampaignDto(c, nodes);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OtaCampaignDto abortCampaign(UUID campaignId, String reason) {
        OtaCampaignEntity campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new AppException(ErrorCode.OTA_CAMPAIGN_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now();
        String finalReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Hủy thủ công bởi Quản trị viên";
        campaign.setStatus("CANCELLED");
        campaign.setAbortReason(finalReason);
        campaign.setUpdatedAt(now);

        List<OtaCampaignNodeEntity> nodes = campaignNodeRepository.findByCampaignIdOrderByWaveAscNodeCodeAsc(campaignId);
        List<String> activeNodeCodes = cancelActiveNodes(nodes, finalReason, now);

        campaignNodeRepository.saveAll(nodes);
        campaignRepository.save(campaign);

        runAfterCommit(() -> publishOtaAbortBatch(campaign.getId(), finalReason, activeNodeCodes));
        log.warn("Admin manually aborted OTA campaign [{}]. Reason: {}", campaignId, finalReason);
        return toCampaignDto(campaign, nodes);
    }

    @Override
    @Transactional
    public void handleNodeOtaResult(String nodeId, String version, boolean success, String error) {
        handleNodeOtaResult(nodeId, null, version, success ? "SUCCESS" : "FAILED", null, error);
    }

    @Override
    @Transactional
    public void handleNodeOtaResult(
            String nodeCode,
            String campaignIdStr,
            String version,
            String status,
            Integer progressPct,
            String error) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return;
        }

        OtaCampaignEntity campaign = resolveLockedRunningCampaign(nodeCode, campaignIdStr, version);
        if (campaign == null) {
            log.debug("No running OTA campaign matched for node [{}] campaignId [{}] version [{}]",
                    nodeCode, campaignIdStr, version);
            return;
        }

        List<OtaCampaignNodeEntity> allNodes = campaignNodeRepository.findByCampaignIdOrderByWaveAscNodeCodeAsc(campaign.getId());
        OtaCampaignNodeEntity targetNode = allNodes.stream()
                .filter(n -> nodeCode.equalsIgnoreCase(n.getNodeCode()))
                .findFirst()
                .orElse(null);

        if (targetNode == null) {
            log.debug("Node [{}] is not enrolled in OTA campaign [{}]", nodeCode, campaign.getId());
            return;
        }

        if (TERMINAL_NODE_STATUSES.contains(targetNode.getStatus())) {
            log.debug("Ignoring duplicate terminal OTA status [{}] for node [{}] in campaign [{}]",
                    targetNode.getStatus(), nodeCode, campaign.getId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        String normalizedStatus = status != null ? status.trim().toUpperCase(Locale.ROOT) : "";

        if (INTERMEDIATE_STATUSES.contains(normalizedStatus)) {
            targetNode.setStatus("PROGRESS".equals(normalizedStatus) ? "DOWNLOADING" : normalizedStatus);
            targetNode.setUpdatedAt(now);
            campaignNodeRepository.save(targetNode);
            return;
        }

        boolean isSuccess = "SUCCESS".equals(normalizedStatus)
                || "OK".equals(normalizedStatus)
                || "COMPLETED".equals(normalizedStatus)
                || "SUCCEEDED".equals(normalizedStatus);

        if (isSuccess) {
            targetNode.setStatus("SUCCEEDED");
            targetNode.setReportedVersion(version != null && !version.isBlank() ? version : campaign.getVersion());
            targetNode.setErrorMessage(null);
            targetNode.setCompletedAt(now);
            targetNode.setUpdatedAt(now);
            updateEdgeNodeFirmwareVersion(targetNode.getNodeId(), targetNode.getNodeCode(), campaign.getVersion());
        } else {
            targetNode.setStatus("FAILED");
            targetNode.setErrorMessage((error != null && !error.isBlank())
                    ? error
                    : ("Cập nhật OTA thất bại (" + (normalizedStatus.isBlank() ? "UNKNOWN_ERROR" : normalizedStatus) + ")"));
            targetNode.setCompletedAt(now);
            targetNode.setUpdatedAt(now);
        }

        recomputeCampaignCounters(campaign, allNodes);
        campaign.setUpdatedAt(now);

        boolean currentWaveFinished = allNodes.stream()
                .filter(n -> n.getWave() <= campaign.getCurrentWave())
                .allMatch(n -> TERMINAL_NODE_STATUSES.contains(n.getStatus()));
        boolean hasPendingFutureWave = allNodes.stream()
                .anyMatch(n -> "PENDING".equals(n.getStatus()));

        int attempted = campaign.getAttemptedNodes();
        double failRate = attempted > 0 ? (campaign.getFailedNodes() * 100.0) / attempted : 0.0;
        boolean breachThreshold = failRate > campaign.getFailureThresholdPercent()
                && (attempted >= 2 || (currentWaveFinished && attempted >= 1 && hasPendingFutureWave));

        if (breachThreshold) {
            String reason = "Tự động ngắt khẩn cấp: Tỷ lệ lỗi (" + Math.round(failRate)
                    + "%) vượt ngưỡng an toàn (" + campaign.getFailureThresholdPercent() + "%)";
            campaign.setStatus("ABORTED_FAILURE_THRESHOLD");
            campaign.setAbortReason(reason);
            List<String> activeToAbort = cancelActiveNodes(allNodes, reason, now);
            campaignNodeRepository.saveAll(allNodes);
            campaignRepository.save(campaign);
            runAfterCommit(() -> publishOtaAbortBatch(campaign.getId(), reason, activeToAbort));
            log.error("OTA Campaign [{}] AUTO-ABORTED! Failure rate: {}%, threshold: {}%",
                    campaign.getId(), failRate, campaign.getFailureThresholdPercent());
            return;
        }

        boolean allFinished = allNodes.stream().allMatch(n -> TERMINAL_NODE_STATUSES.contains(n.getStatus()));
        if (allFinished) {
            campaign.setStatus("COMPLETED");
            campaignNodeRepository.saveAll(allNodes);
            campaignRepository.save(campaign);
            log.info("OTA Campaign [{}] COMPLETED! Success: {}/{}",
                    campaign.getId(), campaign.getSuccessNodes(), campaign.getTotalNodes());
            return;
        }

        if (currentWaveFinished && hasPendingFutureWave) {
            int nextWave = allNodes.stream()
                    .filter(n -> "PENDING".equals(n.getStatus()) && n.getWave() > campaign.getCurrentWave())
                    .mapToInt(OtaCampaignNodeEntity::getWave)
                    .min()
                    .orElse(campaign.getCurrentWave() + 1);

            campaign.setCurrentWave(nextWave);
            campaign.setStatus(nextWave == 2 ? "RUNNING_CANARY_50" : "RUNNING_FULL_100");

            List<String> nextWaveNodeCodes = new ArrayList<>();
            for (OtaCampaignNodeEntity n : allNodes) {
                if (n.getWave() == nextWave && "PENDING".equals(n.getStatus())) {
                    n.setStatus("DISPATCHED");
                    n.setDispatchedAt(now);
                    n.setUpdatedAt(now);
                    nextWaveNodeCodes.add(n.getNodeCode());
                }
            }

            campaignNodeRepository.saveAll(allNodes);
            campaignRepository.save(campaign);

            OtaFirmwarePackageEntity pkg = packageRepository.findById(campaign.getFirmwarePackageId()).orElse(null);
            if (pkg != null && !nextWaveNodeCodes.isEmpty()) {
                FirmwarePackageDto pkgDto = toPackageDto(pkg);
                OtaCampaignDto campaignDto = toCampaignDto(campaign, allNodes);
                runAfterCommit(() -> publishOtaNotifyBatch(campaignDto, pkgDto, nextWaveNodeCodes));
            }
            log.info("OTA Campaign [{}] advanced to wave {} ({}) dispatching {} nodes",
                    campaign.getId(), nextWave, campaign.getStatus(), nextWaveNodeCodes.size());
            return;
        }

        campaignNodeRepository.saveAll(allNodes);
        campaignRepository.save(campaign);
    }

    /**
     * Watchdog periodically checks dispatched OTA nodes that stop responding (power cut / Wi-Fi drop mid-flash)
     * so canary campaigns never stall permanently.
     */
    @Scheduled(fixedDelay = 30000)
    public void timeoutStaleOtaNodes() {
        long timeoutSec = Math.max(30L, nodeTimeoutSeconds);
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(timeoutSec);
        List<OtaCampaignNodeEntity> stale = campaignNodeRepository.findStaleActiveNodes(cutoff);
        if (stale == null || stale.isEmpty()) {
            return;
        }
        for (OtaCampaignNodeEntity node : stale) {
            String msg = "Node không phản hồi kết quả OTA sau " + timeoutSec + "s (mất kết nối hoặc treo cập nhật)";
            Runnable task = () -> handleNodeOtaResult(node.getNodeCode(), node.getCampaignId().toString(), null, "FAILED", null, msg);
            if (transactionManager != null) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> task.run());
            } else {
                task.run();
            }
        }
    }

    private List<EdgeNode> resolveTargetNodes(String deviceType) {
        List<EdgeNode> onlineNodes = edgeNodeRepository.findAll().stream()
                .filter(n -> n.getStatus() == EdgeNodeStatus.ONLINE)
                .sorted(Comparator.comparing(EdgeNode::getNodeCode, Comparator.nullsLast(String::compareTo)))
                .toList();

        if (deviceType == null || deviceType.isBlank()
                || "ALL".equalsIgnoreCase(deviceType)
                || "*".equals(deviceType)) {
            return onlineNodes;
        }

        String normalizedTarget = deviceType.trim().toUpperCase(Locale.ROOT);
        return onlineNodes.stream()
                .filter(node -> matchesDeviceType(node, normalizedTarget))
                .toList();
    }

    private boolean matchesDeviceType(EdgeNode node, String targetType) {
        if (node.getDeviceType() != null && targetType.equalsIgnoreCase(node.getDeviceType().trim())) {
            return true;
        }
        if (node.getSupportedTypes() != null) {
            for (Map<String, Object> entry : node.getSupportedTypes()) {
                if (entry == null) continue;
                Object dt = entry.getOrDefault("deviceType", entry.get("type"));
                if (dt != null && targetType.equalsIgnoreCase(dt.toString().trim())) {
                    return true;
                }
            }
        }
        if (deviceRepository != null && node.getId() != null && node.getNodeCode() != null) {
            try {
                List<Device> childDevices = deviceRepository.findByNodeIdOrNodeCode(node.getId(), node.getNodeCode());
                for (Device dev : childDevices) {
                    if (dev.getDeviceType() != null && targetType.equalsIgnoreCase(dev.getDeviceType().trim())) {
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("Could not inspect child devices for node {}: {}", node.getNodeCode(), e.getMessage());
            }
        }
        return false;
    }

    private OtaCampaignEntity resolveLockedRunningCampaign(String nodeCode, String campaignIdStr, String version) {
        if (campaignIdStr != null && !campaignIdStr.isBlank()) {
            try {
                UUID cid = UUID.fromString(campaignIdStr.trim());
                Optional<OtaCampaignEntity> byId = campaignRepository.findByIdForUpdate(cid);
                if (byId.isPresent() && byId.get().getStatus().startsWith("RUNNING")) {
                    return byId.get();
                }
            } catch (IllegalArgumentException ignored) {
                log.debug("Invalid campaignId string received from MQTT OTA status: {}", campaignIdStr);
            }
        }
        if (version != null && !version.isBlank()) {
            List<OtaCampaignEntity> list = campaignRepository.findActiveCampaignsForNodeAndVersionForUpdate(nodeCode, version.trim());
            if (!list.isEmpty()) {
                return list.get(0);
            }
        }
        return null;
    }

    private void updateEdgeNodeFirmwareVersion(UUID nodeId, String nodeCode, String version) {
        if (version == null || version.isBlank()) return;
        Optional<EdgeNode> nodeOpt = (nodeId != null ? edgeNodeRepository.findById(nodeId) : Optional.<EdgeNode>empty())
                .or(() -> edgeNodeRepository.findByNodeCode(nodeCode));
        nodeOpt.ifPresent(node -> {
            node.setFirmwareVersion(version);
            edgeNodeRepository.save(node);
        });
    }

    private void recomputeCampaignCounters(OtaCampaignEntity campaign, List<OtaCampaignNodeEntity> nodes) {
        int success = 0;
        int failed = 0;
        for (OtaCampaignNodeEntity n : nodes) {
            if ("SUCCEEDED".equals(n.getStatus())) {
                success++;
            } else if ("FAILED".equals(n.getStatus())) {
                failed++;
            }
        }
        campaign.setSuccessNodes(success);
        campaign.setFailedNodes(failed);
        campaign.setAttemptedNodes(success + failed);
    }

    private List<String> cancelActiveNodes(List<OtaCampaignNodeEntity> nodes, String reason, OffsetDateTime now) {
        List<String> activeDispatchedCodes = new ArrayList<>();
        for (OtaCampaignNodeEntity n : nodes) {
            if (!TERMINAL_NODE_STATUSES.contains(n.getStatus())) {
                boolean wasDispatched = !"PENDING".equals(n.getStatus());
                if (wasDispatched) {
                    activeDispatchedCodes.add(n.getNodeCode());
                }
                n.setStatus("CANCELLED");
                n.setErrorMessage(reason);
                n.setCompletedAt(now);
                n.setUpdatedAt(now);
            }
        }
        return activeDispatchedCodes;
    }

    private void publishOtaNotifyBatch(OtaCampaignDto campaign, FirmwarePackageDto pkg, List<String> nodeCodes) {
        if (mqttGateway == null) {
            log.warn("MqttGateway not available. Skipping MQTT OTA dispatch for campaign [{}]", campaign.getId());
            return;
        }
        String prefix = normalizedTopicPrefix();
        for (String nodeCode : nodeCodes) {
            String topic = prefix + "/" + nodeCode + "/ota/notify";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("campaignId", campaign.getId().toString());
            payload.put("packageId", pkg.getId().toString());
            payload.put("deviceType", pkg.getDeviceType());
            payload.put("version", pkg.getVersion());
            payload.put("fileUrl", pkg.getFileUrl());
            payload.put("checksumSha256", pkg.getChecksumSha256());
            payload.put("fileSizeBytes", pkg.getFileSizeBytes());
            payload.put("timestamp", OffsetDateTime.now().toString());

            sendMqttAndRecord(topic, payload);
            log.info("Dispatched OTA notify to topic [{}] for node [{}]", topic, nodeCode);
        }
    }

    private void publishOtaAbortBatch(UUID campaignId, String reason, List<String> nodeCodes) {
        if (mqttGateway == null || nodeCodes.isEmpty()) {
            return;
        }
        String prefix = normalizedTopicPrefix();
        for (String nodeCode : nodeCodes) {
            String topic = prefix + "/" + nodeCode + "/ota/abort";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("campaignId", campaignId.toString());
            payload.put("reason", reason);
            payload.put("timestamp", OffsetDateTime.now().toString());
            sendMqttAndRecord(topic, payload);
            log.info("Dispatched OTA abort to topic [{}] for node [{}]", topic, nodeCode);
        }
    }

    private void sendMqttAndRecord(String topic, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            mqttGateway.sendToMqtt(topic, 1, json);
            if (diagnosticsService != null) {
                diagnosticsService.recordPacket(topic, "OUT", 1, json);
            }
        } catch (Exception e) {
            log.error("Failed to publish OTA MQTT message to topic [{}]: {}", topic, e.getMessage());
        }
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String normalizedTopicPrefix() {
        String prefix = (topicPrefix != null && !topicPrefix.isBlank()) ? topicPrefix.trim() : "hesta/nodes";
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private Path resolveStorageRoot() {
        String dir = (storageDir != null && !storageDir.isBlank()) ? storageDir.trim() : "uploads/firmware";
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    private String resolvePublicBaseUrl(String requestBaseUrl) {
        String base = (publicBaseUrl != null && !publicBaseUrl.isBlank())
                ? publicBaseUrl.trim()
                : (requestBaseUrl != null ? requestBaseUrl.trim() : "");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AppException(ErrorCode.OTA_PACKAGE_INVALID, message);
        }
        return value.trim();
    }

    private void cleanupTempQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}
        }
    }

    private FirmwarePackageDto toPackageDto(OtaFirmwarePackageEntity entity) {
        return FirmwarePackageDto.builder()
                .id(entity.getId())
                .deviceType(entity.getDeviceType())
                .version(entity.getVersion())
                .fileUrl(entity.getFileUrl())
                .checksumSha256(entity.getChecksumSha256())
                .fileSizeBytes(entity.getFileSizeBytes())
                .releaseNotes(entity.getReleaseNotes())
                .uploaded(entity.isUploaded())
                .originalFileName(entity.getOriginalFileName())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private OtaCampaignDto toCampaignDto(OtaCampaignEntity entity, List<OtaCampaignNodeEntity> nodes) {
        List<OtaCampaignNodeDto> nodeDtos = nodes == null ? List.of() : nodes.stream()
                .map(n -> OtaCampaignNodeDto.builder()
                        .id(n.getId())
                        .nodeId(n.getNodeId())
                        .nodeCode(n.getNodeCode())
                        .wave(n.getWave())
                        .status(n.getStatus())
                        .reportedVersion(n.getReportedVersion())
                        .errorMessage(n.getErrorMessage())
                        .dispatchedAt(n.getDispatchedAt())
                        .completedAt(n.getCompletedAt())
                        .build())
                .toList();

        return OtaCampaignDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .firmwarePackageId(entity.getFirmwarePackageId())
                .deviceType(entity.getDeviceType())
                .version(entity.getVersion())
                .status(entity.getStatus())
                .rolloutStrategy(entity.getRolloutStrategy())
                .failureThresholdPercent(entity.getFailureThresholdPercent())
                .currentWave(entity.getCurrentWave())
                .totalNodes(entity.getTotalNodes())
                .attemptedNodes(entity.getAttemptedNodes())
                .successNodes(entity.getSuccessNodes())
                .failedNodes(entity.getFailedNodes())
                .abortReason(entity.getAbortReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .nodeExecutions(nodeDtos)
                .build();
    }
}
