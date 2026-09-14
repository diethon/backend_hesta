package com.hesta.backend.dto.response;

import com.hesta.backend.entity.DeviceStateHistory;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DeviceStateHistoryResponse {
    private UUID id;
    private UUID deviceId;
    private Map<String, Object> previousState;
    private Map<String, Object> newState;
    private String source;
    private UUID changedById;
    private Boolean isTest;
    private OffsetDateTime changedAt;

    public static DeviceStateHistoryResponse fromEntity(DeviceStateHistory history) {
        return DeviceStateHistoryResponse.builder()
                .id(history.getId())
                .deviceId(history.getDevice().getId())
                .previousState(history.getPreviousState())
                .newState(history.getNewState())
                .source(history.getSource().name())
                .changedById(history.getChangedBy() != null ? history.getChangedBy().getId() : null)
                .isTest(history.getIsTest())
                .changedAt(history.getChangedAt())
                .build();
    }
}
