package com.hesta.backend.controller;

import com.hesta.backend.dto.request.DeviceGroupRequest;
import com.hesta.backend.dto.request.GroupCommandRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceGroupResponse;
import com.hesta.backend.dto.response.GroupCommandResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceGroupController {

    private final DeviceGroupService groupService;

    @GetMapping("/homes/{homeId}/device-groups")
    public ResponseEntity<ApiResponse<List<DeviceGroupResponse>>> getGroupsByHome(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {
        List<DeviceGroupResponse> result = groupService.getGroupsByHome(userDetails.getId(), homeId);
        return ResponseEntity.ok(ApiResponse.<List<DeviceGroupResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping("/homes/{homeId}/device-groups")
    public ResponseEntity<ApiResponse<DeviceGroupResponse>> createGroup(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @Valid @RequestBody DeviceGroupRequest request) {
        DeviceGroupResponse result = groupService.createGroup(userDetails.getId(), homeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<DeviceGroupResponse>builder()
                        .code(1000)
                        .message("Tạo nhóm thiết bị thành công")
                        .result(result)
                        .build()
        );
    }

    @GetMapping("/device-groups/{groupId}")
    public ResponseEntity<ApiResponse<DeviceGroupResponse>> getGroupDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID groupId) {
        DeviceGroupResponse result = groupService.getGroupDetail(userDetails.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.<DeviceGroupResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PutMapping("/device-groups/{groupId}")
    public ResponseEntity<ApiResponse<DeviceGroupResponse>> updateGroup(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID groupId,
            @Valid @RequestBody DeviceGroupRequest request) {
        DeviceGroupResponse result = groupService.updateGroup(userDetails.getId(), groupId, request);
        return ResponseEntity.ok(ApiResponse.<DeviceGroupResponse>builder()
                .code(1000)
                .message("Cập nhật nhóm thiết bị thành công")
                .result(result)
                .build());
    }

    @DeleteMapping("/device-groups/{groupId}")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID groupId) {
        groupService.deleteGroup(userDetails.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Xóa nhóm thiết bị thành công")
                .build());
    }

    @PostMapping("/device-groups/{groupId}/command")
    public CompletableFuture<ResponseEntity<ApiResponse<GroupCommandResponse>>> executeGroupCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID groupId,
            @Valid @RequestBody GroupCommandRequest request) {
        return groupService.executeGroupCommand(userDetails.getId(), groupId, request)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<GroupCommandResponse>builder()
                        .code(1000)
                        .message("Thực thi lệnh nhóm thành công (" + result.getSuccessCount() + "/" + result.getTotalDevices() + " thiết bị)")
                        .result(result)
                        .build()));
    }
}
