package com.hesta.backend.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.MlPredictionResponse;
import com.hesta.backend.dto.response.MlRecommendationProposal;
import com.hesta.backend.service.MlServiceClient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
public class MlServiceClientImpl implements MlServiceClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final double defaultThreshold;

    public MlServiceClientImpl(
            @Value("${hesta.ml-service.url:http://localhost:8000}") String baseUrl,
            @Value("${hesta.ml-service.timeout-ms:5000}") int timeoutMs,
            @Value("${hesta.recommendation.confidence-threshold:0.80}") double defaultThreshold,
            RestClient.Builder restClientBuilder) {
        this.baseUrl = baseUrl;
        this.defaultThreshold = defaultThreshold;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public MlModelStatusResponse getModelStatus() {
        try {
            return restClient.get()
                    .uri("/model/status")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(MlModelStatusResponse.class);
        } catch (Exception ex) {
            log.warn("Failed to retrieve ML model status from {}: {}", baseUrl, ex.getMessage());
            return MlModelStatusResponse.builder()
                    .status("UNAVAILABLE")
                    .version("none")
                    .modelType("RandomForestClassifier")
                    .accuracy(0.0)
                    .confidenceThreshold(defaultThreshold)
                    .metrics(Map.of("error", ex.getMessage()))
                    .build();
        }
    }

    @Override
    public MlPredictionResponse predict(UUID homeId, UUID deviceId, String deviceType, UUID roomId,
                                         Map<String, Object> currentState, OffsetDateTime at) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("home_id", homeId != null ? homeId.toString() : null);
        requestBody.put("device_id", deviceId != null ? deviceId.toString() : null);
        requestBody.put("device_type", deviceType);
        requestBody.put("room_id", roomId != null ? roomId.toString() : null);
        requestBody.put("current_state", currentState != null ? currentState : Map.of());
        requestBody.put("timestamp", at != null ? at.toString() : OffsetDateTime.now().toString());

        try {
            return restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(MlPredictionResponse.class);
        } catch (Exception ex) {
            log.warn("ML predict call failed for device {}: {}", deviceId, ex.getMessage());
            return MlPredictionResponse.builder()
                    .deviceId(deviceId)
                    .action("NONE")
                    .confidence(0.0)
                    .eligible(false)
                    .threshold(defaultThreshold)
                    .status("ERROR")
                    .reason("Không thể kết nối dịch vụ ML: " + ex.getMessage())
                    .build();
        }
    }

    @Override
    public List<MlRecommendationProposal> generateRecommendations(UUID homeId, OffsetDateTime from,
                                                                  OffsetDateTime to, Double confidenceThreshold) {
        double threshold = confidenceThreshold != null ? confidenceThreshold : defaultThreshold;
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("home_id", homeId.toString());
        requestBody.put("from_time", from != null ? from.toString() : null);
        requestBody.put("to_time", to != null ? to.toString() : null);
        requestBody.put("threshold", threshold);

        try {
            BatchRecommendationResponse response = restClient.post()
                    .uri("/recommendations/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(BatchRecommendationResponse.class);
            if (response != null && response.getRecommendations() != null) {
                return response.getRecommendations();
            }
            return List.of();
        } catch (Exception ex) {
            log.warn("Failed to generate ML recommendations for home {}: {}", homeId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public MlModelStatusResponse triggerRetraining(UUID homeId) {
        Map<String, Object> requestBody = new HashMap<>();
        if (homeId != null) {
            requestBody.put("home_id", homeId.toString());
        }
        requestBody.put("force", true);

        try {
            return restClient.post()
                    .uri("/train")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(MlModelStatusResponse.class);
        } catch (Exception ex) {
            log.warn("Failed to trigger ML retraining: {}", ex.getMessage());
            return MlModelStatusResponse.builder()
                    .status("ERROR")
                    .metrics(Map.of("error", ex.getMessage()))
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class BatchRecommendationResponse {
        private List<MlRecommendationProposal> recommendations;
        @JsonProperty("total_evaluated")
        private int totalEvaluated;
        @JsonProperty("eligible_count")
        private int eligibleCount;
        @JsonProperty("model_status")
        private String modelStatus;
    }
}
