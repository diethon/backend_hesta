package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.util.UUID;

public record TwinRoomLayoutRequest(
        @NotNull(message = "TWIN_LAYOUT_ROOM_INVALID") UUID roomId,
        @Min(value = 1, message = "TWIN_LAYOUT_GEOMETRY_INVALID")
        @Max(value = 100, message = "TWIN_LAYOUT_GEOMETRY_INVALID") Integer floor,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal x,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal y,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal width,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal height
) {
    public TwinRoomLayoutRequest {
        floor = floor == null ? 1 : floor;
    }

    public TwinRoomLayoutRequest(UUID roomId, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        this(roomId, 1, x, y, width, height);
    }
}
