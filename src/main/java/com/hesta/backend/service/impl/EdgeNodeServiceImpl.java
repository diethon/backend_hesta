package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.EdgeNodeResponse;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.service.EdgeNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EdgeNodeServiceImpl implements EdgeNodeService {

    private final EdgeNodeRepository edgeNodeRepository;

    @Override
    public List<EdgeNodeResponse> getNodesByHomeId(UUID homeId) {
        return edgeNodeRepository.findByHome_Id(homeId).stream()
                .map(EdgeNodeResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public EdgeNodeResponse getNodeById(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new AppException(ErrorCode.NODE_NOT_FOUND));
        return EdgeNodeResponse.fromEntity(node);
    }
}
