package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.MlPredictionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MlServiceClientImplTest {

    private MockRestServiceServer mockServer;
    private MlServiceClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new MlServiceClientImpl("http://localhost:8000", 2000, 0.80, builder);
    }

    @Test
    void getModelStatusReturnsValidResponse() {
        String json = """
            {
                "status": "READY",
                "version": "rf-v1.0.0",
                "modelType": "RandomForestClassifier",
                "accuracy": 0.825,
                "confidenceThreshold": 0.80,
                "trainSamples": 150,
                "testSamples": 30
            }
            """;

        mockServer.expect(requestTo("http://localhost:8000/model/status"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        MlModelStatusResponse response = client.getModelStatus();

        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getVersion()).isEqualTo("rf-v1.0.0");
        assertThat(response.getAccuracy()).isEqualTo(0.825);
        mockServer.verify();
    }

    @Test
    void getModelStatusHandlesOfflineFallbackGracefully() {
        mockServer.expect(requestTo("http://localhost:8000/model/status"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        MlModelStatusResponse response = client.getModelStatus();

        assertThat(response.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getConfidenceThreshold()).isEqualTo(0.80);
    }

    @Test
    void predictReturnsCorrectPrediction() {
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();

        String json = """
            {
                "deviceId": "%s",
                "deviceName": "Bed Light",
                "action": "TURN_ON",
                "confidence": 0.89,
                "eligible": true,
                "threshold": 0.80,
                "reason": "Predicted user bedtime habit",
                "modelVersion": "rf-v1.0.0",
                "status": "SUCCESS"
            }
            """.formatted(deviceId);

        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        MlPredictionResponse response = client.predict(
                homeId, deviceId, "LED_RGB", roomId, Map.of("power", "OFF"), OffsetDateTime.now()
        );

        assertThat(response.getAction()).isEqualTo("TURN_ON");
        assertThat(response.getConfidence()).isEqualTo(0.89);
        assertThat(response.getEligible()).isTrue();
        mockServer.verify();
    }
}
