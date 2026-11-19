package com.hesta.backend.service;

import com.hesta.backend.dto.response.EdgeNodeResponse;
import java.util.List;
import java.util.UUID;

public interface EdgeNodeService {
    List<EdgeNodeResponse> getNodesByHomeId(UUID homeId);
    EdgeNodeResponse getNodeById(UUID nodeId);
}
