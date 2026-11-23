package com.hesta.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hesta.maintenance.rate-limit")
@Getter
@Setter
public class MaintenanceRateLimitProperties {

    private int messagesPerDay = 100;
    private int uploadsPerDay = 30;
    private int maxActiveConversations = 5;
    private int llmCallsPerDay = 60;
    private int imageAnalysesPerDay = 20;
}
