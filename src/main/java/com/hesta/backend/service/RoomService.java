package com.hesta.backend.service;

import com.hesta.backend.dto.request.RoomCreateRequest;
import com.hesta.backend.dto.request.RoomUpdateRequest;
import com.hesta.backend.dto.response.RoomResponse;

import java.util.List;
import java.util.UUID;

public interface RoomService {
    List<RoomResponse> getRoomsByHome(UUID userId, UUID homeId);
    RoomResponse getRoomDetail(UUID userId, UUID homeId, UUID roomId);
    RoomResponse createRoom(UUID userId, UUID homeId, RoomCreateRequest request);
    RoomResponse updateRoom(UUID userId, UUID homeId, UUID roomId, RoomUpdateRequest request);
    void deleteRoom(UUID userId, UUID homeId, UUID roomId);
}
