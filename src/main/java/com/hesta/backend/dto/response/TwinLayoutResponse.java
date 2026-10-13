package com.hesta.backend.dto.response;

import java.util.List;
import java.util.UUID;

public record TwinLayoutResponse(UUID homeId, long revision,
                                 List<TwinRoomLayoutResponse> rooms,
                                 List<TwinNodeLayoutResponse> nodes) {
}
