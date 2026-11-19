package com.hesta.backend.dto.response;

import com.hesta.backend.enums.TwinHealthStatus;
import com.hesta.backend.enums.TwinNodeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TwinHealthStatusChangedPayload(
        TwinNodeType nodeType, String nodeId, UUID deviceId, UUID roomId,
        TwinHealthStatus previousStatus, TwinHealthStatus healthStatus,
        OffsetDateTime referenceTime, Instant evaluatedAt
) {
}
