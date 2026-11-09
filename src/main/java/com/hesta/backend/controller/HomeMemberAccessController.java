package com.hesta.backend.controller;

import com.hesta.backend.dto.access.MemberAccessDetailResponse;
import com.hesta.backend.dto.access.UpdateMemberAccessRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import com.hesta.backend.service.HomeMemberAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/members/{memberUserId}/access")
@RequiredArgsConstructor
public class HomeMemberAccessController {

    private final HomeMemberAccessService memberAccessService;

    @GetMapping
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "View member access matrix (Owner or self)")
    public ResponseEntity<ApiResponse<MemberAccessDetailResponse>> getMemberAccess(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID memberUserId) {

        MemberAccessDetailResponse response = memberAccessService.getMemberAccess(
                userDetails.getId(), homeId, memberUserId);

        return ResponseEntity.ok(ApiResponse.<MemberAccessDetailResponse>builder()
                .code(1000)
                .result(response)
                .build());
    }

    @PutMapping
    @HomeAccessPolicy(value = AccessPolicyType.HOME_OWNER, description = "Update member access scope and matrix")
    public ResponseEntity<ApiResponse<MemberAccessDetailResponse>> updateMemberAccess(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID memberUserId,
            @Valid @RequestBody UpdateMemberAccessRequest request) {

        MemberAccessDetailResponse response = memberAccessService.updateMemberAccess(
                userDetails.getId(), homeId, memberUserId, request);

        return ResponseEntity.ok(ApiResponse.<MemberAccessDetailResponse>builder()
                .code(1000)
                .message("Cập nhật phân quyền thành công")
                .result(response)
                .build());
    }
}

