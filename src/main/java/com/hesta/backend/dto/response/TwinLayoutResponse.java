package com.hesta.backend.dto.response;

import java.util.List;
import java.util.UUID;

public record TwinLayoutResponse(UUID homeId, long revision,
                                 List<TwinRoomLayoutResponse> rooms,
                                 List<TwinNodeLayoutResponse> nodes,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                 com.hesta.backend.dto.request.TwinArchitectureRequest architecture) {
    public TwinLayoutResponse(UUID homeId, long revision, List<TwinRoomLayoutResponse> rooms, List<TwinNodeLayoutResponse> nodes) {
        this(homeId, revision, rooms, nodes, null);
    }
}
