package com.hesta.backend.ai;

import java.util.List;
import java.util.Map;

public record LlmRequest(
        String systemPrompt,
        List<Message> messages,
        List<ToolDeclaration> tools,
        Map<String, Object> responseJsonSchema,
        Double temperature,
        String modelOverride
) {
    public LlmRequest(
            String systemPrompt,
            List<Message> messages,
            List<ToolDeclaration> tools,
            Map<String, Object> responseJsonSchema,
            Double temperature) {
        this(systemPrompt, messages, tools, responseJsonSchema, temperature, null);
    }
    public enum Role {
        USER,
        MODEL
    }

    public record ImageAttachment(byte[] bytes, String mimeType) {
    }

    public record Message(Role role, String text, List<ImageAttachment> images) {
        public static Message userText(String text) {
            return new Message(Role.USER, text, List.of());
        }

        public static Message modelText(String text) {
            return new Message(Role.MODEL, text, List.of());
        }
    }

    public record ToolDeclaration(
            String name,
            String description,
            Map<String, Object> parametersSchema
    ) {
    }
}
