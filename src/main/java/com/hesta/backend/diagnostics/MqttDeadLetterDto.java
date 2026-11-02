package com.hesta.backend.diagnostics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MqttDeadLetterDto {
    private String id;
    private String nodeId;
    private String topic;
    private String payload;
    private String failureReason;
    private OffsetDateTime receivedAt;
    private boolean replayed;
    private OffsetDateTime replayedAt;
}
