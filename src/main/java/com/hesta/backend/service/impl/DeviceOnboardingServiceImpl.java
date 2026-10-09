package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.AdminCreateDeviceRequest;
import com.hesta.backend.dto.request.DeviceClaimRequest;
import com.hesta.backend.dto.response.AdminDeviceCreatedResponse;
import com.hesta.backend.dto.response.DevicePublicPreviewResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.QrInfoResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRegistrationTokenRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.DeviceOnboardingService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceOnboardingServiceImpl implements DeviceOnboardingService {

    private final DeviceRepository deviceRepository;
    private final DeviceRegistrationTokenRepository tokenRepository;
    private final EdgeNodeRepository edgeNodeRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final HomeAuthorizationService homeAuthorizationService;

    private static final int TOKEN_EXPIRY_DAYS = 7;
    private static final String QR_SCHEME_PREFIX = "syna://device/register?token=";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional
    public AdminDeviceCreatedResponse adminCreateDevice(UUID adminId, AdminCreateDeviceRequest request) {
        log.info("Admin [{}] creating device with serial: [{}]", adminId, request.getSerialNumber());

        if (request.getSerialNumber() == null || request.getSerialNumber().trim().isEmpty()) {
            throw new AppException(ErrorCode.SERIAL_NUMBER_REQUIRED);
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new AppException(ErrorCode.DEVICE_NAME_REQUIRED);
        }
        if (request.getDeviceType() == null || request.getDeviceType().trim().isEmpty()) {
            throw new AppException(ErrorCode.DEVICE_TYPE_REQUIRED);
        }

        String serialNumber = request.getSerialNumber().trim();
        if (deviceRepository.existsBySerialNumber(serialNumber)) {
            throw new AppException(ErrorCode.DEVICE_SERIAL_EXISTS);
        }

        User admin = adminId != null ? userRepository.findById(adminId).orElse(null) : null;

        Device device = Device.builder()
                .name(request.getName().trim())
                .deviceType(request.getDeviceType().trim().toUpperCase())
                .model(request.getModel() != null ? request.getModel().trim() : null)
                .serialNumber(serialNumber)
                .localId(request.getLocalId() != null && !request.getLocalId().trim().isEmpty()
                        ? request.getLocalId().trim()
                        : "dev-" + serialNumber.toLowerCase().replaceAll("[^a-z0-9_-]", ""))
                .status(DeviceStatus.UNCLAIMED)
                .currentState(new HashMap<>(Map.of("power", "OFF")))
                .icon(request.getIcon() != null ? request.getIcon().trim() : null)
                .capabilities(request.getCapabilities() != null
                        ? new DeviceCapabilities(request.getCapabilities())
                        : new DeviceCapabilities())
                .build();

        Device savedDevice = deviceRepository.save(device);

        // Generate QR token
        QrTokenBundle bundle = createTokenBundle(savedDevice, admin);
        tokenRepository.save(bundle.tokenEntity);

        QrInfoResponse qrResponse = QrInfoResponse.builder()
                .payload(QR_SCHEME_PREFIX + bundle.rawToken)
                .token(bundle.rawToken)
                .expiresAt(bundle.tokenEntity.getExpiresAt())
                .singleUse(true)
                .build();

        return AdminDeviceCreatedResponse.builder()
                .device(DeviceResponse.fromEntity(savedDevice))
                .qr(qrResponse)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceResponse getDeviceById(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        return DeviceResponse.fromEntity(device);
    }

    @Override
    @Transactional
    public QrInfoResponse getDeviceQr(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        List<DeviceRegistrationToken> activeTokens = tokenRepository.findActiveTokensByDeviceId(deviceId);
        DeviceRegistrationToken tokenEntity = activeTokens.stream()
                .filter(DeviceRegistrationToken::isValid)
                .findFirst()
                .orElse(null);

        if (tokenEntity == null) {
            // Generate a fresh one if none exists or all expired
            QrTokenBundle bundle = createTokenBundle(device, null);
            tokenRepository.save(bundle.tokenEntity);
            return QrInfoResponse.builder()
                    .payload(QR_SCHEME_PREFIX + bundle.rawToken)
                    .token(bundle.rawToken)
                    .expiresAt(bundle.tokenEntity.getExpiresAt())
                    .singleUse(true)
                    .build();
        }

        // Return info for the active token (re-encoded or regeneration recommended if raw token wasn't preserved)
        // Since token is hashed in DB, if needed generate fresh to ensure full plaintext token available
        QrTokenBundle bundle = createTokenBundle(device, null);
        tokenRepository.save(bundle.tokenEntity);
        return QrInfoResponse.builder()
                .payload(QR_SCHEME_PREFIX + bundle.rawToken)
                .token(bundle.rawToken)
                .expiresAt(bundle.tokenEntity.getExpiresAt())
                .singleUse(true)
                .build();
    }

    @Override
    @Transactional
    public QrInfoResponse regenerateQr(UUID adminId, UUID deviceId) {
        log.info("Admin [{}] regenerating QR for device [{}]", adminId, deviceId);
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        User admin = adminId != null ? userRepository.findById(adminId).orElse(null) : null;

        // Invalidate all existing tokens for this device
        List<DeviceRegistrationToken> existingTokens = tokenRepository.findActiveTokensByDeviceId(deviceId);
        OffsetDateTime now = OffsetDateTime.now();
        for (DeviceRegistrationToken t : existingTokens) {
            t.setRevokedAt(now);
        }
        tokenRepository.saveAll(existingTokens);

        // Generate new token
        QrTokenBundle bundle = createTokenBundle(device, admin);
        tokenRepository.save(bundle.tokenEntity);

        return QrInfoResponse.builder()
                .payload(QR_SCHEME_PREFIX + bundle.rawToken)
                .token(bundle.rawToken)
                .expiresAt(bundle.tokenEntity.getExpiresAt())
                .singleUse(true)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DevicePublicPreviewResponse resolveQrToken(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new AppException(ErrorCode.QR_TOKEN_INVALID);
        }

        String hash = hashToken(rawToken.trim());
        DeviceRegistrationToken token = tokenRepository.findByTokenHashWithDevice(hash)
                .orElseThrow(() -> new AppException(ErrorCode.QR_TOKEN_INVALID));

        if (token.isRevoked()) {
            throw new AppException(ErrorCode.QR_TOKEN_INVALID);
        }
        if (token.isUsed()) {
            throw new AppException(ErrorCode.QR_TOKEN_USED);
        }
        if (token.isExpired()) {
            throw new AppException(ErrorCode.QR_TOKEN_EXPIRED);
        }

        Device device = token.getDevice();
        if (device == null) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }
        if (device.getStatus() != DeviceStatus.UNCLAIMED) {
            throw new AppException(ErrorCode.DEVICE_ALREADY_CLAIMED);
        }

        return DevicePublicPreviewResponse.builder()
                .deviceId(device.getId())
                .name(device.getName())
                .deviceType(device.getDeviceType())
                .model(device.getModel())
                .serialNumber(device.getSerialNumber())
                .status(device.getStatus().name())
                .build();
    }

    @Override
    @Transactional
    public DeviceResponse claimDevice(UUID userId, UUID deviceId, DeviceClaimRequest request) {
        log.info("User [{}] claiming device [{}] into home [{}] room [{}]",
                userId, deviceId, request.getHomeId(), request.getRoomId());

        if (request.getToken() == null || request.getToken().trim().isEmpty()) {
            throw new AppException(ErrorCode.QR_TOKEN_INVALID);
        }

        String hash = hashToken(request.getToken().trim());
        DeviceRegistrationToken token = tokenRepository.findByTokenHashWithDevice(hash)
                .orElseThrow(() -> new AppException(ErrorCode.QR_TOKEN_INVALID));

        if (!token.getDevice().getId().equals(deviceId)) {
            throw new AppException(ErrorCode.QR_TOKEN_INVALID);
        }
        if (token.isRevoked()) {
            throw new AppException(ErrorCode.QR_TOKEN_INVALID);
        }
        if (token.isUsed()) {
            throw new AppException(ErrorCode.QR_TOKEN_USED);
        }
        if (token.isExpired()) {
            throw new AppException(ErrorCode.QR_TOKEN_EXPIRED);
        }

        Device device = token.getDevice();
        if (device.getStatus() != DeviceStatus.UNCLAIMED) {
            throw new AppException(ErrorCode.DEVICE_ALREADY_CLAIMED);
        }

        // Authorize user access to Home
        Home home = homeAuthorizationService.requireAccess(userId, request.getHomeId());

        // Validate Room belongs to Home & user has access
        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));
        if (room.getHome() == null || !room.getHome().getId().equals(home.getId())) {
            throw new AppException(ErrorCode.ROOM_NOT_FOUND);
        }
        if (!roomRepository.hasAccessToRoom(room.getId(), userId)) {
            throw new AppException(ErrorCode.ROOM_ACCESS_DENIED);
        }

        // Associate physical EdgeNode if nodeCode is provided
        if (request.getNodeCode() != null && !request.getNodeCode().trim().isEmpty()) {
            String nodeCode = request.getNodeCode().trim();
            EdgeNode edgeNode = edgeNodeRepository.findByHomeIdAndNodeCode(home.getId(), nodeCode)
                    .orElseGet(() -> edgeNodeRepository.findByNodeCode(nodeCode)
                            .map(existing -> {
                                if (existing.getHome() == null || !existing.getHome().getId().equals(home.getId())) {
                                    existing.setHome(home);
                                    return edgeNodeRepository.save(existing);
                                }
                                return existing;
                            })
                            .orElseGet(() -> {
                                EdgeNode newNode = EdgeNode.builder()
                                        .home(home)
                                        .nodeCode(nodeCode)
                                        .status(EdgeNodeStatus.ONLINE)
                                        .build();
                                return edgeNodeRepository.save(newNode);
                            })
                    );
            device.setNode(edgeNode);
        }

        // Apply custom name if supplied
        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            device.setName(request.getName().trim());
        }

        device.setRoom(room);
        device.setStatus(DeviceStatus.ONLINE);

        // Generate MQTT topic if needed
        String nodeCodeStr = device.getNode() != null ? device.getNode().getNodeCode() : "default";
        String localIdStr = device.getLocalId() != null ? device.getLocalId() : device.getId().toString();
        device.setMqttTopic(String.format("hesta/nodes/%s/devices/%s/state", nodeCodeStr, localIdStr));

        // Mark token as used
        token.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(token);

        Device saved = deviceRepository.save(device);
        log.info("Device [{}] claimed successfully as [{}] in room [{}]", saved.getId(), saved.getName(), room.getName());
        return DeviceResponse.fromEntity(saved);
    }

    private QrTokenBundle createTokenBundle(Device device, User admin) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = HexFormat.of().formatHex(randomBytes);
        String tokenHash = hashToken(rawToken);

        DeviceRegistrationToken tokenEntity = DeviceRegistrationToken.builder()
                .device(device)
                .tokenHash(tokenHash)
                .expiresAt(OffsetDateTime.now().plusDays(TOKEN_EXPIRY_DAYS))
                .createdBy(admin)
                .build();

        return new QrTokenBundle(rawToken, tokenEntity);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private record QrTokenBundle(String rawToken, DeviceRegistrationToken tokenEntity) {}
}
