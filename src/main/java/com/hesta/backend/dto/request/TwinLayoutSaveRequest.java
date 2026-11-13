package com.hesta.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record TwinLayoutSaveRequest(
        @NotNull @Min(value = 0, message = "INVALID_REQUEST") Long expectedRevision,
        @NotNull(message = "INVALID_REQUEST") List<@Valid TwinRoomLayoutRequest> rooms,
        @NotNull(message = "INVALID_REQUEST") List<@Valid TwinNodeLayoutRequest> nodes,
        TwinArchitectureRequest architecture
) {
    public TwinLayoutSaveRequest(Long revision, List<TwinRoomLayoutRequest> rooms, List<TwinNodeLayoutRequest> nodes) {
        this(revision, rooms, nodes, null);
    }
}
