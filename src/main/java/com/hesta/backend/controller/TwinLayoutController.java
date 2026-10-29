package com.hesta.backend.controller;

import com.hesta.backend.dto.request.TwinLayoutSaveRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.TwinLayoutResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.TwinLayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/twin-layout")
@RequiredArgsConstructor
public class TwinLayoutController {
    private final TwinLayoutService twinLayoutService;

    @GetMapping
    public ApiResponse<TwinLayoutResponse> getLayout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {
        return ApiResponse.<TwinLayoutResponse>builder()
                .result(twinLayoutService.getLayout(userDetails.getId(), homeId)).build();
    }

    @PutMapping
    public ApiResponse<TwinLayoutResponse> saveLayout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @Valid @RequestBody TwinLayoutSaveRequest request) {
        return ApiResponse.<TwinLayoutResponse>builder()
                .result(twinLayoutService.saveLayout(userDetails.getId(), homeId, request)).build();
    }
}
