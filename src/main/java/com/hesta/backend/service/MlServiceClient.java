package com.hesta.backend.service;

import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.MlRecommendationProposal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MlServiceClient {
    /** Status of one home's habit model (each home has its own). */
    MlModelStatusResponse getModelStatus(UUID homeId);

    List<MlRecommendationProposal> generateRecommendations(UUID homeId, OffsetDateTime from,
                                                          OffsetDateTime to, Double confidenceThreshold);

    /** Retrains one home's model, or every home when homeId is null. */
    MlModelStatusResponse triggerRetraining(UUID homeId);
}
