package com.hesta.backend.controller;

import com.hesta.backend.ai.LlmClient;
import com.hesta.backend.ai.LlmRequest;
import com.hesta.backend.ai.LlmResponse;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@HomeAccessPolicy(value = AccessPolicyType.PLATFORM_ADMIN, description = "Admin AI health check")
public class AdminAiController {

    private final LlmClient llmClient;

    @PostMapping("/ping")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ping() {
        long start = System.currentTimeMillis();
        LlmResponse response = llmClient.generate(new LlmRequest(
                "You are a connectivity smoke test. Reply briefly.",
                List.of(LlmRequest.Message.userText("Trả lời đúng 1 từ: OK")),
                List.of(),
                null,
                0.0
        ));
        long latencyMs = System.currentTimeMillis() - start;
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .code(1000)
                .message("Kết nối LLM thành công")
                .result(Map.of(
                        "enabled", llmClient.isEnabled(),
                        "provider", llmClient.providerName(),
                        "model", response.model(),
                        "reply", response.text() != null ? response.text().trim() : "",
                        "latencyMs", latencyMs
                ))
                .build());
    }
}
