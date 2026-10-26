package com.hesta.backend.shadow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceShadowDto {
    private UUID deviceId;
    private String deviceName;
    private Map<String, Object> desiredState;
    private Map<String, Object> reportedState;
    private Map<String, Object> deltaState;
    private long desiredVersion;
    private long reportedVersion;
    private OffsetDateTime desiredUpdatedAt;
    private OffsetDateTime reportedUpdatedAt;
    private boolean inSync;
}
