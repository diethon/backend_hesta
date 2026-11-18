package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.ResolveAnomalyRequest;
import com.hesta.backend.dto.response.DeviceAnomalyResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AnomalyDetectionService;
import com.hesta.backend.service.DeviceAnomalyManagementService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceAnomalyManagementServiceImpl implements DeviceAnomalyManagementService {

    private final DeviceAnomalyRepository deviceAnomalyRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final AnomalyDetectionService anomalyDetectionService;

    @Override
    @Transactional(readOnly = true)
    public Page<DeviceAnomalyResponse> listAnomalies(
            UUID userId,
            UUID homeId,
            AnomalyStatus status,
            SeverityLevel severity,
            Pageable pageable) {

        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireAccess(userId, homeId);

        MemberAccessContext context =
                homeAuthorizationService.loadAccessContext(homeId, userId);

        Page<DeviceAnomaly> anomalyPage;

        if (context.isOwner() || context.scope() == AccessScope.ALL) {
            anomalyPage = deviceAnomalyRepository.findByHomeIdWithFilters(homeId, status, severity, pageable);
        } else {
            List<Device> devices = deviceRepository.findByHomeIdWithRoomAndNode(homeId);
            Set<UUID> allowedDeviceIds = devices.stream()
                    .filter(device -> homeAuthorizationService.effectiveLevel(context, device) != AccessLevel.NONE)
                    .map(Device::getId)
                    .collect(Collectors.toSet());

            if (allowedDeviceIds.isEmpty()) {
                return Page.empty(pageable);
            }

            anomalyPage = deviceAnomalyRepository.findByHomeIdAndDeviceIdsWithFilters(
                    homeId, allowedDeviceIds, status, severity, pageable);
        }

        return anomalyPage.map(DeviceAnomalyResponse::fromEntity);
    }

    @Override
    @Transactional
    public DeviceAnomalyResponse acknowledgeAnomaly(UUID userId, UUID homeId, UUID anomalyId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireAccess(userId, homeId);

        DeviceAnomaly anomaly = deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ANOMALY_NOT_FOUND));

        homeAuthorizationService.requireDeviceAccess(userId, anomaly.getDevice().getId(), AccessLevel.VIEW);

        // Idempotent check
        if (anomaly.getStatus() == AnomalyStatus.ACKNOWLEDGED) {
            log.info("Anomaly {} in home {} already acknowledged; returning current state", anomalyId, homeId);
            return DeviceAnomalyResponse.fromEntity(anomaly);
        }

        if (anomaly.getStatus() == AnomalyStatus.RESOLVED) {
            log.warn("Cannot acknowledge anomaly {} in home {} because it is already RESOLVED", anomalyId, homeId);
            throw new AppException(ErrorCode.ANOMALY_INVALID_STATUS);
        }

        User user = userRepository.findById(userId).orElse(null);
        anomaly.setStatus(AnomalyStatus.ACKNOWLEDGED);
        anomaly.setAcknowledgedAt(OffsetDateTime.now());
        anomaly.setAcknowledgedBy(user);

        DeviceAnomaly saved = deviceAnomalyRepository.save(anomaly);
        log.info("Anomaly {} acknowledged by user {}", anomalyId, userId);
        return DeviceAnomalyResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public DeviceAnomalyResponse resolveAnomaly(
            UUID userId,
            UUID homeId,
            UUID anomalyId,
            ResolveAnomalyRequest request) {

        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireAccess(userId, homeId);

        DeviceAnomaly anomaly = deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ANOMALY_NOT_FOUND));

        // Resolving an anomaly requires CONTROL access on the associated device
        homeAuthorizationService.requireDeviceAccess(userId, anomaly.getDevice().getId(), AccessLevel.CONTROL);

        // Idempotent check: if already resolved, do not overwrite resolvedAt / resolvedBy
        if (anomaly.getStatus() == AnomalyStatus.RESOLVED) {
            log.info("Anomaly {} in home {} already resolved; returning current state", anomalyId, homeId);
            return DeviceAnomalyResponse.fromEntity(anomaly);
        }

        User user = userRepository.findById(userId).orElse(null);
        anomaly.setStatus(AnomalyStatus.RESOLVED);
        anomaly.setResolvedAt(OffsetDateTime.now());
        anomaly.setResolvedBy(user);
        anomaly.setFalsePositive(request != null && request.isFalsePositive());
        anomaly.setResolutionNote(request != null ? request.getNote() : null);

        DeviceAnomaly saved = deviceAnomalyRepository.save(anomaly);

        // Record resolution in detection engine to activate suppression cooldown window
        anomalyDetectionService.recordManualResolution(anomaly.getDevice().getId(), anomaly.getAnomalyType());

        log.info("Anomaly {} resolved by user {} (falsePositive={}, note={})",
                anomalyId, userId, anomaly.isFalsePositive(), anomaly.getResolutionNote());

        return DeviceAnomalyResponse.fromEntity(saved);
    }
}
