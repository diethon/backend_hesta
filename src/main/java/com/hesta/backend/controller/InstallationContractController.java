package com.hesta.backend.controller;

import com.hesta.backend.dto.contract.ContractRequests.*;
import com.hesta.backend.dto.contract.ContractResponses.ContractDetailResponse;
import com.hesta.backend.dto.contract.ContractResponses.ContractSummaryResponse;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.InstallationContractService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class InstallationContractController {

    private final InstallationContractService contractService;

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<ContractSummaryResponse>>> listMyContracts(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.<List<ContractSummaryResponse>>builder()
                .code(1000)
                .result(contractService.listMyContracts(userDetails.getId()))
                .build());
    }

    @GetMapping("/{contractId}")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> getMyContract(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID contractId) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .result(contractService.getMyContract(userDetails.getId(), contractId))
                .build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ContractDetailResponse>> createRequest(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PublicInstallationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Tạo yêu cầu hợp đồng lắp đặt thành công")
                .result(contractService.createUserRequest(userDetails.getId(), request))
                .build());
    }

    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> claimContract(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ClaimContractRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Liên kết hồ sơ hợp đồng vào tài khoản thành công")
                .result(contractService.claimContract(userDetails.getId(), request.claimToken()))
                .build());
    }

    @PostMapping("/{contractId}/request-revision")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> requestRevision(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody RevisionRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã gửi yêu cầu điều chỉnh hợp đồng tới HESTA")
                .result(contractService.requestRevision(userDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/comments")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> addComment(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody ContractCommentRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .result(contractService.addUserComment(userDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/sign")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> signContract(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody SignContractRequest request,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = httpRequest.getRemoteAddr();
        }
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Ký hợp đồng điện tử thành công")
                .result(contractService.signContract(userDetails.getId(), contractId, ip, request))
                .build());
    }

    @PostMapping("/upload-deposit-proof")
    public ResponseEntity<ApiResponse<String>> uploadDepositProof(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .code(1000)
                .message("Tải ảnh biên lai thành công")
                .result(contractService.uploadDepositProofImage(userDetails.getId(), file))
                .build());
    }

    @PostMapping("/{contractId}/deposit-proof")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> submitDepositProof(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID contractId,
            @Valid @RequestBody SubmitDepositProofRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã gửi xác nhận chuyển khoản đặt cọc. HESTA sẽ sớm đối soát.")
                .result(contractService.submitDepositProof(userDetails.getId(), contractId, request))
                .build());
    }

    @PostMapping("/{contractId}/cancel")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> cancel(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID contractId,
            @RequestBody(required = false) CancelContractRequest request) {
        return ResponseEntity.ok(ApiResponse.<ContractDetailResponse>builder()
                .code(1000)
                .message("Đã hủy hợp đồng")
                .result(contractService.cancelByUser(userDetails.getId(), contractId, request))
                .build());
    }
}
