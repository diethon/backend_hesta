package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @GetMapping("/my-homes")
    public ResponseEntity<ApiResponse<Object>> getMyHomes(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Object result = homeService.getUserHomes(userDetails.getId());
        return ResponseEntity.ok(ApiResponse.builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createHome(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, String> request) {
        
        String name = request.get("name");
        if (name == null || name.trim().isEmpty()) {
            name = "Nhà của " + userDetails.getUsername();
        }
        
        Map<String, Object> result = homeService.createHome(userDetails.getId(), name);
        
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .code(1000)
                .message("Tạo nhà thành công")
                .result(result)
                .build());
    }

    @PostMapping("/{homeId}/invitations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateInvitation(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @RequestParam(required = false) String email) {

        Map<String, Object> result = homeService.generateInvitation(userDetails.getId(), homeId, email);

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .code(1000)
                .message("Tạo lời mời thành công")
                .result(result)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<Void>> joinHome(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam String codeOrToken) {

        homeService.joinHome(userDetails.getId(), codeOrToken);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code(1000)
                .message("Gia nhập nhà thành công")
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{homeId}/rooms")
    public ResponseEntity<ApiResponse<Object>> getHomeRooms(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {

        Object result = homeService.getHomeRooms(userDetails.getId(), homeId);

        ApiResponse<Object> response = ApiResponse.builder()
                .code(1000)
                .result(result)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{homeId}/rooms")
    public ResponseEntity<ApiResponse<Object>> createHomeRoom(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @RequestBody Map<String, Object> request) {

        String roomName = (String) request.get("name");
        if (roomName == null || roomName.trim().isEmpty()) {
            throw new com.hesta.backend.exception.AppException(com.hesta.backend.exception.ErrorCode.INVALID_CREDENTIALS);
        }

        String icon = (String) request.get("icon");
        java.math.BigDecimal layoutX = null;
        java.math.BigDecimal layoutY = null;
        
        if (request.get("layoutX") != null) {
            layoutX = new java.math.BigDecimal(request.get("layoutX").toString());
        }
        if (request.get("layoutY") != null) {
            layoutY = new java.math.BigDecimal(request.get("layoutY").toString());
        }

        homeService.createHomeRoom(userDetails.getId(), homeId, roomName, icon, layoutX, layoutY);

        return ResponseEntity.ok(ApiResponse.builder()
                .code(1000)
                .message("Tạo phòng thành công")
                .build());
    }

    @GetMapping("/{homeId}/members")
    public ResponseEntity<ApiResponse<Object>> getMembers(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {

        Object result = homeService.getHomeMembers(userDetails.getId(), homeId);

        ApiResponse<Object> response = ApiResponse.builder()
                .code(1000)
                .result(result)
                .build();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{homeId}/members/{memberId}/role")
    public ResponseEntity<ApiResponse<Void>> updateMemberRole(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID memberId,
            @RequestParam HomeRole role) {

        homeService.updateMemberRole(userDetails.getId(), homeId, memberId, role);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code(1000)
                .message("Cập nhật quyền thành công")
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{homeId}/members/{memberId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID memberId) {

        homeService.removeMember(userDetails.getId(), homeId, memberId);

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code(1000)
                .message("Xóa thành viên thành công")
                .build();

        return ResponseEntity.ok(response);
    }
}
