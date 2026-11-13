package com.hesta.backend.ota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtaCampaignServiceImpl implements OtaCampaignService {

    private final EdgeNodeRepository edgeNodeRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private MqttGateway mqttGateway;

    private final ConcurrentHashMap<UUID, FirmwarePackageDto> packageMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OtaCampaignDto> campaignMap = new ConcurrentHashMap<>();

    @Override
    public FirmwarePackageDto registerPackage(FirmwarePackageDto dto) {
        if (dto == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        UUID id = dto.getId() != null ? dto.getId() : UUID.randomUUID();
        dto.setId(id);
        dto.setCreatedAt(OffsetDateTime.now());
        packageMap.put(id, dto);
        log.info("Registered Firmware Package [{}] for type [{}] version [{}]", id, dto.getDeviceType(), dto.getVersion());
        return dto;
    }

    @Override
    public List<FirmwarePackageDto> listPackages() {
        return new ArrayList<>(packageMap.values());
    }

    @Override
    public FirmwarePackageDto getPackage(UUID packageId) {
        FirmwarePackageDto pkg = packageMap.get(packageId);
        if (pkg == null) {
            throw new AppException(ErrorCode.OTA_PACKAGE_NOT_FOUND);
        }
        return pkg;
    }

    @Override
    public OtaCampaignDto createAndStartCampaign(CreateOtaCampaignRequest request) {
        if (request == null || request.getFirmwarePackageId() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        FirmwarePackageDto pkg = getPackage(request.getFirmwarePackageId());

        // Check if there is already an active campaign for this deviceType
        boolean hasActive = campaignMap.values().stream().anyMatch(c ->
                c.getDeviceType().equalsIgnoreCase(pkg.getDeviceType()) &&
                (c.getStatus().startsWith("RUNNING") || c.getStatus().equals("DRAFT")));

        if (hasActive) {
            throw new AppException(ErrorCode.OTA_CAMPAIGN_CONFLICT);
        }

        UUID campaignId = UUID.randomUUID();
        List<EdgeNode> allNodes = edgeNodeRepository.findAll();
        int totalNodes = Math.max(1, allNodes.size());

        double threshold = (request.getFailureThresholdPercent() != null && request.getFailureThresholdPercent() > 0)
                ? request.getFailureThresholdPercent()
                : 20.0;

        String strategy = (request.getRolloutStrategy() != null && !request.getRolloutStrategy().isBlank())
                ? request.getRolloutStrategy().toUpperCase()
                : "CANARY";

        String initialStatus = "CANARY".equals(strategy) ? "RUNNING_CANARY_10" : "RUNNING_FULL_100";

        OtaCampaignDto campaign = OtaCampaignDto.builder()
                .id(campaignId)
                .name(request.getName() != null ? request.getName() : "Chiến dịch nâng cấp " + pkg.getVersion())
                .firmwarePackageId(pkg.getId())
                .deviceType(pkg.getDeviceType())
                .version(pkg.getVersion())
                .status(initialStatus)
                .rolloutStrategy(strategy)
                .failureThresholdPercent(threshold)
                .totalNodes(totalNodes)
                .attemptedNodes(0)
                .successNodes(0)
                .failedNodes(0)
                .abortReason(null)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        campaignMap.put(campaignId, campaign);

        // Dispatch initial OTA wave
        dispatchOtaWave(campaign, pkg, allNodes);

        log.info("Created and started OTA Campaign [{}] ({}) for {} nodes with threshold {}%",
                campaignId, campaign.getName(), totalNodes, threshold);

        return campaign;
    }

    private void dispatchOtaWave(OtaCampaignDto campaign, FirmwarePackageDto pkg, List<EdgeNode> nodes) {
        if (mqttGateway == null) {
            log.warn("MqttGateway not available. Skipping MQTT dispatch for campaign [{}]", campaign.getId());
            return;
        }

        int targetCount = 1;
        if ("RUNNING_CANARY_10".equals(campaign.getStatus())) {
            targetCount = Math.max(1, (int) Math.ceil(campaign.getTotalNodes() * 0.10));
        } else if ("RUNNING_CANARY_50".equals(campaign.getStatus())) {
            targetCount = Math.max(1, (int) Math.ceil(campaign.getTotalNodes() * 0.50));
        } else {
            targetCount = campaign.getTotalNodes();
        }

        List<EdgeNode> targets = nodes.stream().limit(targetCount).collect(Collectors.toList());

        for (EdgeNode node : targets) {
            String topic = "hesta/nodes/" + node.getNodeCode() + "/ota/notify";
            Map<String, Object> payload = new HashMap<>();
            payload.put("campaignId", campaign.getId().toString());
            payload.put("packageId", pkg.getId().toString());
            payload.put("version", pkg.getVersion());
            payload.put("fileUrl", pkg.getFileUrl());
            payload.put("checksumSha256", pkg.getChecksumSha256());
            payload.put("timestamp", OffsetDateTime.now().toString());

            try {
                String json = objectMapper.writeValueAsString(payload);
                mqttGateway.sendToMqtt(topic, 1, json);
                log.info("Dispatched OTA notify to topic [{}] for node [{}]", topic, node.getNodeCode());
            } catch (Exception e) {
                log.error("Failed to send OTA dispatch to node [{}]: {}", node.getNodeCode(), e.getMessage());
            }
        }
    }

    @Override
    public OtaCampaignDto getCampaign(UUID campaignId) {
        OtaCampaignDto campaign = campaignMap.get(campaignId);
        if (campaign == null) {
            throw new AppException(ErrorCode.OTA_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }

    @Override
    public List<OtaCampaignDto> listCampaigns() {
        return new ArrayList<>(campaignMap.values());
    }

    @Override
    public OtaCampaignDto abortCampaign(UUID campaignId, String reason) {
        OtaCampaignDto campaign = getCampaign(campaignId);
        campaign.setStatus("CANCELLED");
        campaign.setAbortReason(reason != null ? reason : "Hủy thủ công bởi Quản trị viên");
        campaign.setUpdatedAt(OffsetDateTime.now());
        log.warn("Admin manually aborted OTA campaign [{}]. Reason: {}", campaignId, reason);
        return campaign;
    }

    @Override
    public synchronized void handleNodeOtaResult(String nodeId, String version, boolean success, String error) {
        // Find running campaign for this version
        for (OtaCampaignDto campaign : campaignMap.values()) {
            if (campaign.getStatus().startsWith("RUNNING") && campaign.getVersion().equalsIgnoreCase(version)) {
                campaign.setAttemptedNodes(campaign.getAttemptedNodes() + 1);
                if (success) {
                    campaign.setSuccessNodes(campaign.getSuccessNodes() + 1);
                } else {
                    campaign.setFailedNodes(campaign.getFailedNodes() + 1);
                }
                campaign.setUpdatedAt(OffsetDateTime.now());

                // Check failure threshold auto-abort
                if (campaign.getAttemptedNodes() >= 2) {
                    double failRate = (campaign.getFailedNodes() * 100.0) / campaign.getAttemptedNodes();
                    if (failRate > campaign.getFailureThresholdPercent()) {
                        campaign.setStatus("ABORTED_FAILURE_THRESHOLD");
                        campaign.setAbortReason("Tự động ngắt khẩn cấp: Tỷ lệ lỗi (" + (int) failRate + "%) vượt ngưỡng an toàn (" + campaign.getFailureThresholdPercent() + "%)");
                        log.error("OTA Campaign [{}] AUTO-ABORTED! Failure rate: {}%, threshold: {}%",
                                campaign.getId(), failRate, campaign.getFailureThresholdPercent());
                        return;
                    }
                }

                // Check Canary wave advancement
                if (campaign.getAttemptedNodes() >= campaign.getTotalNodes()) {
                    campaign.setStatus("COMPLETED");
                    log.info("OTA Campaign [{}] COMPLETED! Success: {}/{}", campaign.getId(), campaign.getSuccessNodes(), campaign.getTotalNodes());
                } else if ("RUNNING_CANARY_10".equals(campaign.getStatus()) && campaign.getAttemptedNodes() >= Math.max(1, (int) Math.ceil(campaign.getTotalNodes() * 0.10))) {
                    campaign.setStatus("RUNNING_CANARY_50");
                    log.info("OTA Campaign [{}] advanced to Canary Wave 2 (50%)", campaign.getId());
                } else if ("RUNNING_CANARY_50".equals(campaign.getStatus()) && campaign.getAttemptedNodes() >= Math.max(1, (int) Math.ceil(campaign.getTotalNodes() * 0.50))) {
                    campaign.setStatus("RUNNING_FULL_100");
                    log.info("OTA Campaign [{}] advanced to Full Fleet Rollout (100%)", campaign.getId());
                }
                break;
            }
        }
    }
}
