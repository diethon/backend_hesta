package com.hesta.backend.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hesta.backend.dto.response.MlModelStatusResponse;
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
    private final RestClient trainClient;
    private final String baseUrl;
    private final double defaultThreshold;

    @org.springframework.beans.factory.annotation.Autowired
    public MlServiceClientImpl(
            @Value("${hesta.ml-service.url:http://localhost:8000}") String baseUrl,
            @Value("${hesta.ml-service.timeout-ms:5000}") int timeoutMs,
            @Value("${hesta.ml-service.train-timeout-ms:300000}") int trainTimeoutMs,
            @Value("${hesta.recommendation.confidence-threshold:0.80}") double defaultThreshold,
            @Value("${hesta.ml-service.api-key:}") String apiKey,
            RestClient.Builder restClientBuilder) {
        // Timeouts so a hung ML service cannot block scheduler threads forever; training gets a longer budget.
        this(baseUrl, defaultThreshold,
                withApiKey(restClientBuilder.clone().requestFactory(timeouts(timeoutMs)), apiKey),
                withApiKey(restClientBuilder.clone().requestFactory(timeouts(trainTimeoutMs)), apiKey));
    }

    /** Shared secret the ML service checks (X-API-Key); blank only for local development. */
    private static RestClient.Builder withApiKey(RestClient.Builder builder, String apiKey) {
        return apiKey == null || apiKey.isBlank() ? builder : builder.defaultHeader("X-API-Key", apiKey);
    }

    /** Keeps the builder's own request factory (e.g. a MockRestServiceServer in tests). */
    public MlServiceClientImpl(String baseUrl, int timeoutMs, double defaultThreshold, RestClient.Builder restClientBuilder) {
        this(baseUrl, defaultThreshold, restClientBuilder, restClientBuilder);
    }

    private MlServiceClientImpl(String baseUrl, double defaultThreshold, RestClient.Builder builder, RestClient.Builder trainBuilder) {
        this.baseUrl = baseUrl;
        this.defaultThreshold = defaultThreshold;
        this.restClient = builder.baseUrl(baseUrl).build();
        this.trainClient = trainBuilder.baseUrl(baseUrl).build();
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory timeouts(int readTimeoutMs) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    @Override
    public MlModelStatusResponse getModelStatus(UUID homeId) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/model/status").queryParam("home_id", homeId).build())
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
            return trainClient.post()
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
