package com.hesta.backend.controller;

import com.hesta.backend.dto.request.GenerateBehaviorDataRequest;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.BehaviorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/behavior")
@RequiredArgsConstructor
public class BehaviorController {
    private final BehaviorService behaviorService;

    @PostMapping("/datasets")
    public ResponseEntity<ApiResponse<BehaviorDatasetResponse>> generate(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @Valid @RequestBody GenerateBehaviorDataRequest request) {
        return ResponseEntity.ok(ApiResponse.<BehaviorDatasetResponse>builder().code(1000)
                .message("Tạo dữ liệu hành vi giả lập thành công")
                .result(behaviorService.generate(user.getId(), homeId, request)).build());
    }

    @GetMapping("/patterns")
    public ResponseEntity<ApiResponse<List<BehaviorPatternResponse>>> patterns(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(ApiResponse.<List<BehaviorPatternResponse>>builder().code(1000)
                .result(behaviorService.detectPatterns(user.getId(), homeId, from, to)).build());
    }
}
