package com.hesta.backend.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "hesta.ai")
@Getter
@Setter
public class AiProperties {

    private boolean enabled = false;
    private String provider = "gemini";
    private Gemini gemini = new Gemini();
    private TextInterpreter textInterpreter = new TextInterpreter();
    private ImageAnalyzer imageAnalyzer = new ImageAnalyzer();
    private TicketSummary ticketSummary = new TicketSummary();
    private Pricing pricing = new Pricing();

    @Getter
    @Setter
    public static class Gemini {
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com";
        /** Pinned stable model for text classification/summary (avoid -latest in production). */
        private String model = "gemini-3.5-flash-lite";
        /** Pinned stable model for image/vision analysis. */
        private String visionModel = "gemini-3.5-flash-lite";
        private Duration timeout = Duration.ofSeconds(20);
        private int maxRetries = 2;
        private long retryBackoffMs = 500L;
    }

    @Getter
    @Setter
    public static class ImageAnalyzer {
        private int maxDimensionPx = 1280;
        private double confidenceThreshold = 0.65;
        private double hazardCautionThreshold = 0.40;
        private double hazardUrgentThreshold = 0.70;
        private int maxRetakes = 2;
        private int maxImagesPerConversation = 3;
    }

    @Getter
    @Setter
    public static class TextInterpreter {
        private int maxHistoryMessages = 6;
        private int maxInputChars = 500;
        private double confidenceThreshold = 0.6;
        private int maxConsecutiveClarifies = 2;
        private int maxClarifyChars = 320;
        private int maxLlmCallsPerConversation = 15;
        private List<String> forbiddenOutputPhrases = new ArrayList<>(List.of(
                "tháo vỏ", "mở vỏ", "mở nắp", "đụng mạch", "chạm mạch",
                "220v", "đấu nối", "tự sửa mạch", "hàn mạch", "cắt dây",
                "dùng tua vít", "tháo ốc", "cạy vỏ"
        ));
    }

    @Getter
    @Setter
    public static class TicketSummary {
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class Pricing {
        /** Đơn giá ước tính USD / 1 triệu token. Mặc định null (ẩn chi phí trên dashboard). */
        private java.math.BigDecimal usdPerMillionTokens;
    }
}
