package com.hesta.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceAnomalyResponse {

    private UUID id;
    private UUID homeId;
    private UUID deviceId;
    private String deviceName;
    private String deviceType;
    private String roomName;
    private AnomalyType anomalyType;
    private String metric;
    private String currentValue;
    private String expectedRange;
    private SeverityLevel severity;
    private AnomalyStatus status;
    private String source;
    private BigDecimal score;
    private String reason;
    private Long readingId;
    private OffsetDateTime detectedAt;
    private OffsetDateTime lastSeenAt;
    private Integer occurrenceCount;
    private OffsetDateTime acknowledgedAt;
    private UUID acknowledgedBy;
    private OffsetDateTime resolvedAt;
    private UUID resolvedBy;
    private boolean falsePositive;
    private String resolutionNote;

    public static DeviceAnomalyResponse fromEntity(DeviceAnomaly entity) {
        if (entity == null) {
            return null;
        }

        return DeviceAnomalyResponse.builder()
                .id(entity.getId())
                .homeId(entity.getHome() != null ? entity.getHome().getId() : null)
                .deviceId(entity.getDevice() != null ? entity.getDevice().getId() : null)
                .deviceName(entity.getDevice() != null ? entity.getDevice().getName() : null)
                .deviceType(entity.getDevice() != null ? entity.getDevice().getDeviceType() : null)
                .roomName(entity.getDevice() != null && entity.getDevice().getRoom() != null
                        ? entity.getDevice().getRoom().getName() : null)
                .anomalyType(entity.getAnomalyType())
                .metric(entity.getMetric())
                .currentValue(entity.getCurrentValue())
                .expectedRange(entity.getExpectedRange())
                .severity(entity.getSeverity())
                .status(entity.getStatus())
                .source(entity.getSource())
                .score(entity.getScore())
                .reason(entity.getReason())
                .readingId(entity.getReading() != null ? entity.getReading().getId() : null)
                .detectedAt(entity.getDetectedAt())
                .lastSeenAt(entity.getLastSeenAt())
                .occurrenceCount(entity.getOccurrenceCount())
                .acknowledgedAt(entity.getAcknowledgedAt())
                .acknowledgedBy(entity.getAcknowledgedBy() != null ? entity.getAcknowledgedBy().getId() : null)
                .resolvedAt(entity.getResolvedAt())
                .resolvedBy(entity.getResolvedBy() != null ? entity.getResolvedBy().getId() : null)
                .falsePositive(entity.isFalsePositive())
                .resolutionNote(entity.getResolutionNote())
                .build();
    }
}
