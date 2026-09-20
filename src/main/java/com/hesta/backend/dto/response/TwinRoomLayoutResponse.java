package com.hesta.backend.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record TwinRoomLayoutResponse(UUID roomId, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
}
