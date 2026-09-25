package com.hesta.backend.service;

import com.hesta.backend.dto.response.RecommendationResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RecommendationService {
    List<RecommendationResponse> generate(UUID userId, UUID homeId, OffsetDateTime from, OffsetDateTime to);
    List<RecommendationResponse> list(UUID userId, UUID homeId);
    RecommendationResponse approve(UUID userId, UUID homeId, UUID recommendationId);
    RecommendationResponse reject(UUID userId, UUID homeId, UUID recommendationId);
}
