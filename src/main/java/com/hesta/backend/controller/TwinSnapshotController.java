package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.TwinHomeSnapshotResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.TwinSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/twin")
@RequiredArgsConstructor
public class TwinSnapshotController {
    private final TwinSnapshotService twinSnapshotService;

    @GetMapping
    public ApiResponse<TwinHomeSnapshotResponse> getSnapshot(
            @AuthenticationPrincipal CustomUserDetails userDetails, @PathVariable UUID homeId) {
        return ApiResponse.<TwinHomeSnapshotResponse>builder()
                .result(twinSnapshotService.getSnapshot(userDetails.getId(), homeId)).build();
    }
}
