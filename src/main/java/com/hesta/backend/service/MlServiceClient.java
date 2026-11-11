package com.hesta.backend.service;

import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.MlPredictionResponse;
import com.hesta.backend.dto.response.MlRecommendationProposal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface MlServiceClient {
    MlModelStatusResponse getModelStatus();

    MlPredictionResponse predict(UUID homeId, UUID deviceId, String deviceType, UUID roomId,
                                 Map<String, Object> currentState, OffsetDateTime at);

    List<MlRecommendationProposal> generateRecommendations(UUID homeId, OffsetDateTime from,
                                                          OffsetDateTime to, Double confidenceThreshold);

    MlModelStatusResponse triggerRetraining(UUID homeId);
}
