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
public class MqttPacketDto {
    private String id;
    private OffsetDateTime timestamp;
    private String topic;
    private String direction;
    private int qos;
    private String payloadSummary;
}
