package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hesta.backend.entity.EdgeNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeNodeResponse {
    private UUID id;
    private String nodeCode;
    private String status;
    private List<Map<String, String>> supportedTypes;
    private OffsetDateTime pairedAt;

    public static EdgeNodeResponse fromEntity(EdgeNode node) {
        return EdgeNodeResponse.builder()
                .id(node.getId())
                .nodeCode(node.getNodeCode())
                .status(node.getStatus() != null ? node.getStatus().name() : null)
                .supportedTypes(node.getSupportedTypes())
                .pairedAt(node.getPairedAt())
                .build();
    }
}
