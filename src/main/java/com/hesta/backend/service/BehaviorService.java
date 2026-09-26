package com.hesta.backend.service;

import com.hesta.backend.dto.request.GenerateBehaviorDataRequest;
import com.hesta.backend.dto.response.BehaviorDatasetResponse;
import com.hesta.backend.dto.response.BehaviorPatternResponse;
import com.hesta.backend.dto.response.BehaviorPredictionResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BehaviorService {
    BehaviorDatasetResponse generate(UUID userId, UUID homeId, GenerateBehaviorDataRequest request);
    List<BehaviorPatternResponse> detectPatterns(UUID userId, UUID homeId, OffsetDateTime from, OffsetDateTime to);
    List<BehaviorPredictionResponse> predict(UUID userId, UUID homeId, OffsetDateTime from,
                                             OffsetDateTime to, OffsetDateTime at);
}
