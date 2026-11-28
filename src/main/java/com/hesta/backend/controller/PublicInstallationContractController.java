package com.hesta.backend.controller;

import com.hesta.backend.dto.contract.ContractRequests.PublicInstallationRequest;
import com.hesta.backend.dto.contract.ContractResponses.PublicRequestCreatedResponse;
import com.hesta.backend.dto.contract.ContractResponses.PublicShowcaseResponse;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.service.InstallationContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicInstallationContractController {

    private final InstallationContractService contractService;

    @GetMapping("/installation-packages")
    public ResponseEntity<ApiResponse<PublicShowcaseResponse>> getShowcase() {
        return ResponseEntity.ok(ApiResponse.<PublicShowcaseResponse>builder()
                .code(1000)
                .message("Lấy thông tin gói lắp đặt thành công")
                .result(contractService.getPublicShowcase())
                .build());
    }

    @PostMapping("/installation-requests")
    public ResponseEntity<ApiResponse<PublicRequestCreatedResponse>> createRequest(
            @Valid @RequestBody PublicInstallationRequest request) {
        PublicRequestCreatedResponse created = contractService.createPublicRequest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<PublicRequestCreatedResponse>builder()
                        .code(1000)
                        .message("Đã tiếp nhận yêu cầu khảo sát và lập hợp đồng " + created.code())
                        .result(created)
                        .build()
        );
    }
}
