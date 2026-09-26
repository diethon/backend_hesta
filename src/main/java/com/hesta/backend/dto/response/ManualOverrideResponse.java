package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class ManualOverrideResponse {
    UUID id;
    String action;
    UUID userId;
    OffsetDateTime occurredAt;
    OffsetDateTime expiresAt;
}
