package com.hesta.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiLlmClientTest {

    private AiProperties properties;
    private ObjectMapper objectMapper;
    private MockRestServiceServer mockServer;
    private GeminiLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setProvider("gemini");
        properties.getGemini().setApiKey("test-key-not-real");
        properties.getGemini().setBaseUrl("https://generativelanguage.googleapis.com");
        properties.getGemini().setModel("gemini-2.5-flash");
        properties.getGemini().setTimeout(Duration.ofSeconds(5));
        properties.getGemini().setMaxRetries(1);
        properties.getGemini().setRetryBackoffMs(1);

        objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getGemini().getBaseUrl());
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiLlmClient(properties, objectMapper, builder.build());
    }

    @Test
    void disabledOrMissingKeyThrowsDisabledWithoutCrashing() {
        properties.setEnabled(false);
        assertThat(client.isEnabled()).isFalse();
        assertThatThrownBy(() -> client.generate(new LlmRequest(null, List.of(LlmRequest.Message.userText("hi")), List.of(), null, null)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVICE_DISABLED);

        properties.setEnabled(true);
        properties.getGemini().setApiKey("");
        assertThat(client.isEnabled()).isFalse();
    }

    @Test
    void generateSendsHeaderInlineDataToolsAndStructuredSchema() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key-not-real"))
                .andExpect(jsonPath("$.system_instruction.parts[0].text").value("sys"))
                .andExpect(jsonPath("$.contents[0].parts[1].inline_data.mime_type").value("image/jpeg"))
                .andExpect(jsonPath("$.tools[0].function_declarations[0].name").value("lookup"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.type").value("OBJECT"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "finishReason": "STOP",
                            "content": {
                              "parts": [
                                {"text": "{\\"ok\\":true}"},
                                {"functionCall": {"name": "lookup", "args": {"id": "123"}}}
                              ]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        LlmRequest req = new LlmRequest(
                "sys",
                List.of(new LlmRequest.Message(LlmRequest.Role.USER, "hello",
                        List.of(new LlmRequest.ImageAttachment(new byte[]{1, 2, 3}, "image/jpeg")))),
                List.of(new LlmRequest.ToolDeclaration("lookup", "desc", Map.of("type", "OBJECT"))),
                Map.of("type", "OBJECT"),
                0.2
        );
        LlmResponse resp = client.generate(req);
        assertThat(resp.text()).isEqualTo("{\"ok\":true}");
        assertThat(resp.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.name()).isEqualTo("lookup");
            assertThat(tc.arguments()).containsEntry("id", "123");
        });
        mockServer.verify();
    }

    @Test
    void retriesOn429ThenThrowsRateLimited() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.generate(new LlmRequest(null, List.of(LlmRequest.Message.userText("hi")), List.of(), null, null)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVICE_RATE_LIMITED);
        mockServer.verify();
    }

    @Test
    void safetyBlockThrowsImmediatelyWithoutRetry() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [{
                            "finishReason": "SAFETY",
                            "content": {"parts": []}
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate(new LlmRequest(null, List.of(LlmRequest.Message.userText("hi")), List.of(), null, null)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVICE_SAFETY_BLOCKED);
        mockServer.verify();
    }
}
