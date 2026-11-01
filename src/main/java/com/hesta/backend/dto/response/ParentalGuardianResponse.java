package com.hesta.backend.dto.response;

import com.hesta.backend.enums.GuardianStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParentalGuardianResponse {
    private UUID id;
    private UUID homeId;
    private UUID targetDeviceId;
    private String targetDeviceName;
    private String targetDeviceType;
    private UUID cameraDeviceId;
    private String cameraDeviceName;
    private String name;
    private boolean enabled;
    private boolean parentAway;
    private int allowedDurationMinutes;
    private int warningMinutesBefore;
    private GuardianStatus status;
    private OffsetDateTime sessionStartedAt;
    private OffsetDateTime sessionExpiresAt;
    private long remainingSeconds;
    private boolean autoLockout;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
