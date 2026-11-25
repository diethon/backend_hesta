package com.hesta.backend.controller;

import com.hesta.backend.dto.contract.ContractRequests.*;
import com.hesta.backend.dto.contract.ContractResponses.*;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.InstallationContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/contracts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminInstallationContractController {

    private final InstallationContractService contractService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ContractSummaryResponse>>> listAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.<List<ContractSummaryResponse>>builder()
                .code(1000)
                .result(contractService.listAllForAdmin(status, keyword))
                .build());
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AdminContractStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.<AdminContractStatsResponse>builder()
                .code(1000)
                .result(contractService.getAdminStats())
                .build());
    }

    @GetMapping("/provision-options")
    public ResponseEntity<ApiResponse<ProvisionOptionsResponse>> getProvisionOptions() {
        return ResponseEntity.ok(ApiResponse.<ProvisionOptionsResponse>builder()
                .code(1000)
                .result(contractService.getProvisionOptions())
                .build());
    }

    @GetMapping("/{contractId}")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> getById(@PathVariable UUID contractId) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .result(contractService.getForAdmin(contractId))
                .build());
    }

    @PutMapping("/{contractId}/survey")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> updateSurvey(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @RequestBody AdminSurveyUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã cập nhật hồ sơ khảo sát kỹ thuật")
                .result(contractService.updateSurvey(adminDetails.getId(), contractId, request))
                .build());
    }

    @PutMapping("/{contractId}/proposal")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> saveProposal(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody AdminContractProposalRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã gửi bản thảo hợp đồng và báo giá tới khách hàng")
                .result(contractService.saveProposal(adminDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/comments")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> addComment(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody ContractCommentRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .result(contractService.addAdminComment(adminDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/deposit/confirm")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> confirmDeposit(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @RequestBody(required = false) ConfirmDepositRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã xác nhận nhận tiền đặt cọc")
                .result(contractService.confirmDeposit(adminDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/deposit/reject")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> rejectDeposit(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody RejectDepositRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã gửi yêu cầu kiểm tra lại giao dịch đặt cọc")
                .result(contractService.rejectDeposit(adminDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/start-installation")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> startInstallation(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @RequestBody(required = false) StartInstallationRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã chuyển hợp đồng sang giai đoạn lắp đặt")
                .result(contractService.startInstallation(adminDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/handover")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> handover(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody HandoverContractRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Bàn giao ngôi nhà và thiết bị thành công")
                .result(contractService.handover(adminDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/cancel")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> cancel(
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @PathVariable UUID contractId,
            @RequestBody(required = false) CancelContractRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã hủy hợp đồng")
                .result(contractService.cancelByAdmin(adminDetails.getId(), contractId, request))
                .build());
    }
}
