package com.hesta.backend.ai;

import java.util.List;
import java.util.Map;

public record LlmResponse(
        String text,
        List<ToolCall> toolCalls,
        String finishReason,
        String model,
        int totalTokens
) {
    public LlmResponse(String text, List<ToolCall> toolCalls, String finishReason, String model) {
        this(text, toolCalls, finishReason, model, 0);
    }

    public record ToolCall(String name, Map<String, Object> arguments) {
    }
}
