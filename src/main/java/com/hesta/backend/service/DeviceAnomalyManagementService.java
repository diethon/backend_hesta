package com.hesta.backend.service;

import com.hesta.backend.dto.request.ResolveAnomalyRequest;
import com.hesta.backend.dto.response.DeviceAnomalyResponse;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.SeverityLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DeviceAnomalyManagementService {

    Page<DeviceAnomalyResponse> listAnomalies(
            UUID userId,
            UUID homeId,
            AnomalyStatus status,
            SeverityLevel severity,
            Pageable pageable
    );

    DeviceAnomalyResponse acknowledgeAnomaly(
            UUID userId,
            UUID homeId,
            UUID anomalyId
    );

    DeviceAnomalyResponse resolveAnomaly(
            UUID userId,
            UUID homeId,
            UUID anomalyId,
            ResolveAnomalyRequest request
    );
}
