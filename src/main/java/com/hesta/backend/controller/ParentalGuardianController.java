package com.hesta.backend.controller;

import com.hesta.backend.dto.request.CreateParentalGuardianRequest;
import com.hesta.backend.dto.request.GuardianSimulateEventRequest;
import com.hesta.backend.dto.request.UpdateParentalGuardianRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.ParentalGuardianLogResponse;
import com.hesta.backend.dto.response.ParentalGuardianResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.ParentalGuardianService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/guardian-rules")
@RequiredArgsConstructor
public class ParentalGuardianController {

    private final ParentalGuardianService guardianService;

    @PostMapping
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> create(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @Valid @RequestBody CreateParentalGuardianRequest request) {
        return ok("Tạo quy tắc Giám hộ con trẻ thành công",
                guardianService.createRule(user.getId(), homeId, request));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ParentalGuardianResponse>>> getAll(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId) {
        return ok(null, guardianService.getRules(user.getId(), homeId));
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> get(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId) {
        return ok(null, guardianService.getRule(user.getId(), homeId, ruleId));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> update(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody UpdateParentalGuardianRequest request) {
        return ok("Cập nhật quy tắc Giám hộ thành công",
                guardianService.updateRule(user.getId(), homeId, ruleId, request));
    }

    @PatchMapping("/{ruleId}/enabled")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> setEnabled(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId,
            @RequestParam boolean enabled) {
        return ok("Cập nhật trạng thái quy tắc thành công",
                guardianService.setEnabled(user.getId(), homeId, ruleId, enabled));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId) {
        guardianService.deleteRule(user.getId(), homeId, ruleId);
        return ok("Xóa quy tắc Giám hộ thành công", null);
    }

    @PatchMapping("/{ruleId}/parent-away")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> setParentAway(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId,
            @RequestParam boolean away) {
        return ok(away ? "Đã kích hoạt chế độ vắng nhà" : "Đã chuyển sang chế độ phụ huynh ở nhà",
                guardianService.setParentAway(user.getId(), homeId, ruleId, away));
    }

    @PostMapping("/{ruleId}/extend")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> extend(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId,
            @RequestParam(defaultValue = "15") int extraMinutes) {
        return ok("Đã gia hạn thêm " + extraMinutes + " phút",
                guardianService.grantExtension(user.getId(), homeId, ruleId, extraMinutes));
    }

    @PostMapping("/{ruleId}/unlock")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> unlock(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId) {
        return ok("Đã mở khóa thiết bị thành công",
                guardianService.unlock(user.getId(), homeId, ruleId));
    }

    @PostMapping("/{ruleId}/simulate")
    public ResponseEntity<ApiResponse<ParentalGuardianResponse>> simulate(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody GuardianSimulateEventRequest request) {
        return ok("Mô phỏng sự kiện thành công",
                guardianService.simulate(user.getId(), homeId, ruleId, request));
    }

    @GetMapping("/{ruleId}/logs")
    public ResponseEntity<ApiResponse<List<ParentalGuardianLogResponse>>> getLogs(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ruleId) {
        return ok(null, guardianService.getLogs(user.getId(), homeId, ruleId));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T result) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .code(1000)
                .message(message)
                .result(result)
                .build());
    }
}
