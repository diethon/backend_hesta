package com.hesta.backend.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

/**
 * REST client cho Gemini API (v1beta/models/{model}:generateContent).
 * Hỗ trợ system_instruction, inline_data (ảnh), function_declarations (tools), và responseJsonSchema.
 * Không log API key hay dữ liệu base64 ảnh.
 */
@Component
@Slf4j
public class GeminiLlmClient implements LlmClient {

    private static final Set<String> SAFETY_FINISH_REASONS = Set.of(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII"
    );

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public GeminiLlmClient(AiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildRestClient(properties));
    }

    GeminiLlmClient(AiProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Math.max(1000L, properties.getGemini().getTimeout().toMillis());
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .baseUrl(properties.getGemini().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled()
                && "gemini".equalsIgnoreCase(properties.getProvider())
                && properties.getGemini().getApiKey() != null
                && !properties.getGemini().getApiKey().isBlank();
    }

    @Override
    public String providerName() {
        return properties.getProvider();
    }

    @Override
    public String modelName() {
        return properties.getGemini().getModel();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        if (!isEnabled()) {
            throw new AppException(ErrorCode.AI_SERVICE_DISABLED);
        }
        String model = (request.modelOverride() != null && !request.modelOverride().isBlank())
                ? request.modelOverride().trim()
                : properties.getGemini().getModel();
        Map<String, Object> payload = buildPayload(request);
        int maxRetries = Math.max(0, properties.getGemini().getMaxRetries());
        long backoffMs = Math.max(0L, properties.getGemini().getRetryBackoffMs());

        AppException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String rawBody = restClient.post()
                        .uri("/v1beta/models/{model}:generateContent", model)
                        .header("x-goog-api-key", properties.getGemini().getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(String.class);
                return parseResponse(rawBody, model);
            } catch (AppException e) {
                // Safety block or non-retryable domain error
                throw e;
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                int code = status.value();
                log.warn("Gemini API returned HTTP {} on attempt {}/{}", code, attempt + 1, maxRetries + 1);
                if (code == 429) {
                    lastFailure = new AppException(ErrorCode.AI_SERVICE_RATE_LIMITED);
                } else if (code >= 500) {
                    lastFailure = new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
                } else {
                    throw new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
                }
            } catch (ResourceAccessException e) {
                log.warn("Gemini API timeout/network error on attempt {}/{}: {}", attempt + 1, maxRetries + 1, e.getMessage());
                lastFailure = new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            } catch (Exception e) {
                log.warn("Gemini API unexpected error on attempt {}/{}: {}", attempt + 1, maxRetries + 1, e.getMessage());
                lastFailure = new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }

            if (attempt < maxRetries && backoffMs > 0) {
                sleepBackoff(backoffMs * (1L << attempt));
            }
        }
        throw lastFailure != null ? lastFailure : new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
    }

    Map<String, Object> buildPayload(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("system_instruction", Map.of(
                    "parts", List.of(Map.of("text", request.systemPrompt()))
            ));
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        if (request.messages() != null) {
            for (LlmRequest.Message msg : request.messages()) {
                List<Map<String, Object>> parts = new ArrayList<>();
                if (msg.text() != null && !msg.text().isEmpty()) {
                    parts.add(Map.of("text", msg.text()));
                }
                if (msg.images() != null) {
                    for (LlmRequest.ImageAttachment img : msg.images()) {
                        if (img != null && img.bytes() != null && img.bytes().length > 0) {
                            parts.add(Map.of("inline_data", Map.of(
                                    "mime_type", img.mimeType(),
                                    "data", Base64.getEncoder().encodeToString(img.bytes())
                            )));
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    contents.add(Map.of(
                            "role", msg.role() == LlmRequest.Role.MODEL ? "model" : "user",
                            "parts", parts
                    ));
                }
            }
        }
        body.put("contents", contents);

        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> declarations = new ArrayList<>();
            for (LlmRequest.ToolDeclaration tool : request.tools()) {
                Map<String, Object> decl = new LinkedHashMap<>();
                decl.put("name", tool.name());
                decl.put("description", tool.description());
                if (tool.parametersSchema() != null) {
                    decl.put("parameters", tool.parametersSchema());
                }
                declarations.add(decl);
            }
            body.put("tools", List.of(Map.of("function_declarations", declarations)));
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (request.temperature() != null) {
            generationConfig.put("temperature", request.temperature());
        }
        if (request.responseJsonSchema() != null && !request.responseJsonSchema().isEmpty()) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", request.responseJsonSchema());
        }
        if (!generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }

        return body;
    }

    LlmResponse parseResponse(String rawBody, String model) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode promptFeedback = root.path("promptFeedback");
            if (!promptFeedback.isMissingNode() && promptFeedback.hasNonNull("blockReason")) {
                throw new AppException(ErrorCode.AI_SERVICE_SAFETY_BLOCKED);
            }
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
            }
            JsonNode first = candidates.get(0);
            String finishReason = first.path("finishReason").asText(null);
            if (finishReason != null && SAFETY_FINISH_REASONS.contains(finishReason)) {
                throw new AppException(ErrorCode.AI_SERVICE_SAFETY_BLOCKED);
            }

            StringBuilder textBuilder = new StringBuilder();
            List<LlmResponse.ToolCall> toolCalls = new ArrayList<>();
            JsonNode parts = first.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.hasNonNull("text")) {
                        textBuilder.append(part.get("text").asText());
                    }
                    if (part.hasNonNull("functionCall")) {
                        JsonNode fc = part.get("functionCall");
                        String name = fc.path("name").asText();
                        Map<String, Object> args = fc.has("args") && fc.get("args").isObject()
                                ? objectMapper.convertValue(fc.get("args"), new TypeReference<>() {})
                                : Map.of();
                        toolCalls.add(new LlmResponse.ToolCall(name, args));
                    }
                }
            }
            int totalTokens = root.path("usageMetadata").path("totalTokenCount").asInt(0);
            return new LlmResponse(textBuilder.toString(), toolCalls, finishReason, model, totalTokens);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response: {}", e.getMessage());
            throw new AppException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }

    private static void sleepBackoff(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
