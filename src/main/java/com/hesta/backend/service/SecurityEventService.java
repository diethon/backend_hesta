package com.hesta.backend.service;

import com.hesta.backend.dto.response.SecurityEventResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.SecurityEventStatus;
import com.hesta.backend.enums.SecurityEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface SecurityEventService {

    Page<SecurityEventResponse> list(UUID userId, UUID homeId, SecurityEventStatus status,
                                     SecurityEventType eventType, Pageable pageable);

    SecurityEventResponse updateStatus(UUID userId, UUID homeId, UUID eventId, SecurityEventStatus target);

    /** Records a MOTION event for a motion sensor, at most once per cooldown window per device. */
    void recordMotion(Device device);
}
