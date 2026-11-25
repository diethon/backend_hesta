package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.ai.LlmClient;
import com.hesta.backend.ai.LlmRequest;
import com.hesta.backend.ai.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tóm tắt ticket ngắn gọn (<= 3 câu) từ dữ kiện có cấu trúc. Bật qua hesta.ai.ticket-summary.enabled.
 * Chỉ tóm tắt dữ kiện đã có, cấm suy diễn nguyên nhân hỏng; lỗi sẽ trả Optional.empty() để fallback về template Giai đoạn 1.
 */
@Service
@Slf4j
public class MaintenanceTicketSummarizer {

    private final LlmClient llmClient;
    private final AiProperties aiProperties;
    private final TextInterpretationHandler outputFilter;
    private final String systemPrompt;

    public record SummaryResult(String summary, int totalTokens) {
    }

    public MaintenanceTicketSummarizer(
            LlmClient llmClient,
            AiProperties aiProperties,
            TextInterpretationHandler outputFilter) {
        this.llmClient = llmClient;
        this.aiProperties = aiProperties;
        this.outputFilter = outputFilter;
        this.systemPrompt = loadPrompt("prompts/maintenance-ticket-summary.txt");
    }

    public boolean isEnabled() {
        return aiProperties.isEnabled()
                && aiProperties.getTicketSummary().isEnabled()
                && llmClient.isEnabled();
    }

    public Optional<SummaryResult> summarize(UUID conversationId, String structuredFacts) {
        if (!isEnabled() || structuredFacts == null || structuredFacts.isBlank()) {
            return Optional.empty();
        }
        long startMs = System.currentTimeMillis();
        try {
            LlmResponse response = llmClient.generate(new LlmRequest(
                    systemPrompt,
                    List.of(LlmRequest.Message.userText("DỮ KIỆN CÓ CẤU TRÚC:\n" + structuredFacts)),
                    List.of(),
                    null,
                    0.2
            ));
            long latencyMs = System.currentTimeMillis() - startMs;
            String text = response.text() != null ? response.text().trim() : "";
            if (text.isBlank() || text.length() > 500) {
                return Optional.empty();
            }
            // Dùng chung bộ lọc đầu ra để chặn URL/SĐT/từ khóa thao tác điện
            String checked = outputFilter.filterClarifyQuestion(text.length() > 220 ? text.substring(0, 220) : text);
            if (TextInterpretationHandler.DEFAULT_CLARIFY_QUESTION.equals(checked)) {
                return Optional.empty();
            }
            log.info("LLM ticket summary conv={} latencyMs={} tokens={}",
                    conversationId, latencyMs, response.totalTokens());
            return Optional.of(new SummaryResult(text, response.totalTokens()));
        } catch (Exception e) {
            log.warn("LLM ticket summary failed conv={} error={}", conversationId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String loadPrompt(String classpathLocation) {
        try {
            ClassPathResource res = new ClassPathResource(classpathLocation);
            return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file prompt: " + classpathLocation, e);
        }
    }
}
