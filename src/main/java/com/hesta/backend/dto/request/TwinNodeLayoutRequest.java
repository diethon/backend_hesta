package com.hesta.backend.dto.request;

import com.hesta.backend.enums.TwinNodeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TwinNodeLayoutRequest(
        @NotNull(message = "INVALID_REQUEST") TwinNodeType nodeType,
        @NotBlank(message = "INVALID_REQUEST") String nodeId,
        UUID roomId,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal x,
        @NotNull(message = "TWIN_LAYOUT_GEOMETRY_INVALID") BigDecimal y
) {
}
