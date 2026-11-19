package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.EdgeNodeResponse;
import com.hesta.backend.service.EdgeNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/nodes")
@RequiredArgsConstructor
public class EdgeNodeController {

    private final EdgeNodeService edgeNodeService;

    @GetMapping("/home/{homeId}")
    public ResponseEntity<ApiResponse<List<EdgeNodeResponse>>> getNodesByHomeId(@PathVariable UUID homeId) {
        List<EdgeNodeResponse> nodes = edgeNodeService.getNodesByHomeId(homeId);
        return ResponseEntity.ok(ApiResponse.<List<EdgeNodeResponse>>builder().result(nodes).build());
    }

    @GetMapping("/{nodeId}")
    public ResponseEntity<ApiResponse<EdgeNodeResponse>> getNodeById(@PathVariable UUID nodeId) {
        EdgeNodeResponse node = edgeNodeService.getNodeById(nodeId);
        return ResponseEntity.ok(ApiResponse.<EdgeNodeResponse>builder().result(node).build());
    }
}
