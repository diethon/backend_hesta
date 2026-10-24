package com.hesta.backend.controller;

import com.hesta.backend.diagnostics.DiagnosticsSummaryResponse;
import com.hesta.backend.diagnostics.MqttDiagnosticsService;
import com.hesta.backend.diagnostics.NodeDiagnosticsResponse;
import com.hesta.backend.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/diagnostics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiagnosticsController {

    private final MqttDiagnosticsService diagnosticsService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DiagnosticsSummaryResponse>> getSummary() {
        DiagnosticsSummaryResponse result = diagnosticsService.getSummary();
        return ResponseEntity.ok(ApiResponse.<DiagnosticsSummaryResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/nodes")
    public ResponseEntity<ApiResponse<List<NodeDiagnosticsResponse>>> getNodeDiagnostics() {
        List<NodeDiagnosticsResponse> result = diagnosticsService.getNodeDiagnostics();
        return ResponseEntity.ok(ApiResponse.<List<NodeDiagnosticsResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping("/nodes/{nodeId}/circuit-breaker/reset")
    public ResponseEntity<ApiResponse<NodeDiagnosticsResponse>> resetCircuitBreaker(@PathVariable UUID nodeId) {
        NodeDiagnosticsResponse result = diagnosticsService.resetCircuitBreaker(nodeId);
        return ResponseEntity.ok(ApiResponse.<NodeDiagnosticsResponse>builder()
                .code(1000)
                .message("Đã reset Circuit Breaker cho node thành công")
                .result(result)
                .build());
    }

    @PostMapping("/nodes/{nodeId}/ping")
    public ResponseEntity<ApiResponse<NodeDiagnosticsResponse>> pingNode(@PathVariable UUID nodeId) {
        NodeDiagnosticsResponse result = diagnosticsService.pingNode(nodeId);
        return ResponseEntity.ok(ApiResponse.<NodeDiagnosticsResponse>builder()
                .code(1000)
                .message("Ping kiểm tra độ trễ node thành công")
                .result(result)
                .build());
    }

    @PostMapping("/nodes/{nodeId}/circuit-breaker/trip")
    public ResponseEntity<ApiResponse<NodeDiagnosticsResponse>> tripCircuitBreakerByNodeId(@PathVariable UUID nodeId) {
        NodeDiagnosticsResponse result = diagnosticsService.tripCircuitBreaker(nodeId);
        return ResponseEntity.ok(ApiResponse.<NodeDiagnosticsResponse>builder()
                .code(1000)
                .message("Đã ngắt mạch bảo vệ (TRIP CIRCUIT) cho node " + nodeId)
                .result(result)
                .build());
    }

    @PostMapping("/circuit-breaker/{nodeCode}/trip")
    public ResponseEntity<ApiResponse<NodeDiagnosticsResponse>> tripCircuitBreaker(@PathVariable String nodeCode) {
        NodeDiagnosticsResponse result = diagnosticsService.tripCircuitBreaker(nodeCode);
        return ResponseEntity.ok(ApiResponse.<NodeDiagnosticsResponse>builder()
                .code(1000)
                .message("Đã ngắt mạch bảo vệ (TRIP CIRCUIT) cho node " + nodeCode)
                .result(result)
                .build());
    }

    @GetMapping("/mqtt-sniffer")
    public ResponseEntity<ApiResponse<List<com.hesta.backend.diagnostics.MqttPacketDto>>> getMqttSniffer() {
        List<com.hesta.backend.diagnostics.MqttPacketDto> packets = diagnosticsService.getSnifferPackets();
        return ResponseEntity.ok(ApiResponse.<List<com.hesta.backend.diagnostics.MqttPacketDto>>builder()
                .code(1000)
                .result(packets)
                .build());
    }
}
