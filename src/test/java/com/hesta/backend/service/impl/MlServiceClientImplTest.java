package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.MlModelStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MlServiceClientImplTest {

    private static final UUID HOME = UUID.fromString("37fd02b2-e87c-4e73-86fb-df5b70e97fb2");
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

        mockServer.expect(requestTo("http://localhost:8000/model/status?home_id=" + HOME))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        MlModelStatusResponse response = client.getModelStatus(HOME);

        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getVersion()).isEqualTo("rf-v1.0.0");
        assertThat(response.getAccuracy()).isEqualTo(0.825);
        mockServer.verify();
    }

    @Test
    void getModelStatusHandlesOfflineFallbackGracefully() {
        mockServer.expect(requestTo("http://localhost:8000/model/status?home_id=" + HOME))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        MlModelStatusResponse response = client.getModelStatus(HOME);

        assertThat(response.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getConfidenceThreshold()).isEqualTo(0.80);
    }

    @Test
    void sendsApiKeyWhenConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient.Builder keyed = builder.defaultHeader("X-API-Key", "secret");
        MlServiceClientImpl keyedClient = new MlServiceClientImpl("http://localhost:8000", 2000, 0.80, keyed);
        server.expect(requestTo("http://localhost:8000/model/status?home_id=" + HOME))
                .andExpect(header("X-API-Key", "secret"))
                .andRespond(withSuccess("{\"status\":\"COLD_START\",\"version\":\"none\",\"confidenceThreshold\":0.8}",
                        MediaType.APPLICATION_JSON));

        assertThat(keyedClient.getModelStatus(HOME).getStatus()).isEqualTo("COLD_START");
        server.verify();
    }
}
