package com.hesta.backend.dto.response;

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
public class HomeActivityItemResponse {
    private UUID id;
    private UUID deviceId;
    private String deviceName;
    private String deviceType;
    private Map<String, Object> previousState;
    private Map<String, Object> newState;
    private String source;
    private OffsetDateTime changedAt;
}
