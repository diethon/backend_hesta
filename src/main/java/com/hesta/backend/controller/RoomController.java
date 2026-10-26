package com.hesta.backend.controller;

import com.hesta.backend.dto.request.RoomCreateRequest;
import com.hesta.backend.dto.request.RoomUpdateRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.RoomResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getRooms(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {
        List<RoomResponse> result = roomService.getRoomsByHome(userDetails.getId(), homeId);
        return ResponseEntity.ok(ApiResponse.<List<RoomResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoomDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID roomId) {
        RoomResponse result = roomService.getRoomDetail(userDetails.getId(), homeId, roomId);
        return ResponseEntity.ok(ApiResponse.<RoomResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RoomResponse>> createRoom(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @Valid @RequestBody RoomCreateRequest request) {
        RoomResponse result = roomService.createRoom(userDetails.getId(), homeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<RoomResponse>builder()
                .code(1000)
                .message("Tạo phòng thành công")
                .result(result)
                .build());
    }

    @PutMapping("/{roomId}")
    public ResponseEntity<ApiResponse<RoomResponse>> updateRoom(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID roomId,
            @Valid @RequestBody RoomUpdateRequest request) {
        RoomResponse result = roomService.updateRoom(userDetails.getId(), homeId, roomId, request);
        return ResponseEntity.ok(ApiResponse.<RoomResponse>builder()
                .code(1000)
                .message("Cập nhật phòng thành công")
                .result(result)
                .build());
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID roomId) {
        roomService.deleteRoom(userDetails.getId(), homeId, roomId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Xoá phòng thành công. Các thiết bị trong phòng đã được chuyển sang trạng thái chưa gán phòng.")
                .build());
    }
}
