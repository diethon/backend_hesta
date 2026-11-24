package com.hesta.backend.ai;

public interface LlmClient {

    boolean isEnabled();

    String providerName();

    String modelName();

    LlmResponse generate(LlmRequest request);
}
