package com.hesta.backend.dto.response;

import com.hesta.backend.enums.TwinNodeType;

import java.math.BigDecimal;
import java.util.UUID;

public record TwinNodeLayoutResponse(TwinNodeType nodeType, String nodeId, UUID roomId, BigDecimal x, BigDecimal y) {
}
