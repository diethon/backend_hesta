package com.hesta.backend.dto.response;

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
public class ParentalGuardianLogResponse {
    private UUID id;
    private UUID ruleId;
    private UUID homeId;
    private String eventType;
    private String message;
    private OffsetDateTime recordedAt;
}
