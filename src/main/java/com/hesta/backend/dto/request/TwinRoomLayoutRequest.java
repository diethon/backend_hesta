package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TwinRoomLayoutRequest(
        @NotNull(message = "TWIN_LAYOUT_ROOM_INVALID") UUID roomId,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal x,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal y,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal width,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal height
) {
}
