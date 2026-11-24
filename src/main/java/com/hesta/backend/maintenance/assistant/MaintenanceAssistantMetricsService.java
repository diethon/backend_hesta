package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Truy vấn SQL tổng hợp (không nạp toàn bộ bảng vào bộ nhớ) cho Dashboard Trợ lý AI của Admin.
 * Chỉ trả về số liệu tổng hợp, tuyệt đối không trả nội dung tin nhắn người dùng.
 */
@Service
@RequiredArgsConstructor
public class MaintenanceAssistantMetricsService {

    @PersistenceContext
    private EntityManager em;

    private final AiProperties aiProperties;
    private final Clock clock;

    public record LabelCount(String key, long count) {
    }

    public record DailyTokens(String day, long tokens) {
    }

    public record AlertsDto(
            boolean highMisleadingRate,
            boolean highFallbackRate,
            boolean safetySpike,
            long safetyCurrentCount,
            long safetyPreviousCount
    ) {
    }

    public record MetricsResponse(
            OffsetDateTime from,
            OffsetDateTime to,
            Map<String, Long> conversationsByStatus,
            double selfResolutionRate,
            Map<String, Long> ticketsBySource,
            Map<String, Long> chatbotTicketsByQuality,
            double misleadingRate,
            List<LabelCount> deviceTypeDistribution,
            List<LabelCount> topSymptoms,
            long llmTotalAttempts,
            long llmFallbackCount,
            double llmFallbackRate,
            long llmLowConfidenceCount,
            double llmLowConfidenceRate,
            double avgTokensPerConversation,
            long totalTokens,
            List<DailyTokens> tokensByDay,
            Double estimatedCostUsd,
            long safetyLayer1Count,
            long safetyLayer2Count,
            long cautionPromptedCount,
            Map<String, Long> cautionAnswers,
            long imagesAnalyzedCount,
            long imageRetakeCount,
            double imageRetakeRate,
            long imagePrivacyRejectCount,
            double imagePrivacyRejectRate,
            long userRatingPositive,
            long userRatingNegative,
            double userPositiveRate,
            long homeWideOutageCount,
            AlertsDto alerts
    ) {
    }

    @Transactional(readOnly = true)
    public MetricsResponse computeMetrics(OffsetDateTime fromParam, OffsetDateTime toParam) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime to = toParam != null ? toParam : now;
        OffsetDateTime from = fromParam != null ? fromParam : to.minusDays(30);
        if (!to.isAfter(from)) {
            from = to.minusDays(1);
        }

        // 1. Conversations by status
        Map<String, Long> convByStatus = new LinkedHashMap<>();
        for (String st : List.of("ACTIVE", "RESOLVED", "ESCALATED", "ABANDONED")) {
            convByStatus.put(st, 0L);
        }
        for (Object[] row : queryRows("""
                SELECT status, COUNT(*) FROM public.maintenance_chat_conversations
                WHERE created_at >= :from AND created_at <= :to
                GROUP BY status
                """, from, to)) {
            convByStatus.put(String.valueOf(row[0]), toLong(row[1]));
        }
        long resolved = convByStatus.getOrDefault("RESOLVED", 0L);
        long finished = resolved + convByStatus.getOrDefault("ESCALATED", 0L) + convByStatus.getOrDefault("ABANDONED", 0L);
        double selfResolutionRate = pct(resolved, finished);

        // 2. Tickets by source & admin diagnosis quality
        Map<String, Long> ticketsBySource = new LinkedHashMap<>();
        ticketsBySource.put("CHATBOT", 0L);
        ticketsBySource.put("MANUAL", 0L);
        for (Object[] row : queryRows("""
                SELECT source, COUNT(*) FROM public.maintenance_tickets
                WHERE created_at >= :from AND created_at <= :to
                GROUP BY source
                """, from, to)) {
            ticketsBySource.put(String.valueOf(row[0]), toLong(row[1]));
        }

        Map<String, Long> qualityCounts = new LinkedHashMap<>();
        for (String q : List.of("HELPFUL", "PARTIAL", "MISLEADING")) {
            qualityCounts.put(q, 0L);
        }
        for (Object[] row : queryRows("""
                SELECT diagnosis_quality, COUNT(*) FROM public.maintenance_tickets
                WHERE created_at >= :from AND created_at <= :to
                  AND source = 'CHATBOT' AND diagnosis_quality IS NOT NULL
                GROUP BY diagnosis_quality
                """, from, to)) {
            qualityCounts.put(String.valueOf(row[0]), toLong(row[1]));
        }
        long totalRatedTickets = qualityCounts.values().stream().mapToLong(Long::longValue).sum();
        double misleadingRate = pct(qualityCounts.getOrDefault("MISLEADING", 0L), totalRatedTickets);

        // 3. DeviceType distribution & top symptoms
        List<LabelCount> deviceTypes = queryRows("""
                SELECT COALESCE(NULLIF(context_json->>'deviceType', ''), 'UNKNOWN') AS dt, COUNT(*)
                FROM public.maintenance_chat_conversations
                WHERE created_at >= :from AND created_at <= :to
                GROUP BY dt ORDER BY COUNT(*) DESC LIMIT 12
                """, from, to).stream()
                .map(r -> new LabelCount(String.valueOf(r[0]), toLong(r[1])))
                .toList();

        List<LabelCount> topSymptoms = queryRows("""
                SELECT context_json->>'symptomId' AS sym, COUNT(*)
                FROM public.maintenance_chat_conversations
                WHERE created_at >= :from AND created_at <= :to
                  AND NULLIF(context_json->>'symptomId', '') IS NOT NULL
                GROUP BY sym ORDER BY COUNT(*) DESC LIMIT 10
                """, from, to).stream()
                .map(r -> new LabelCount(String.valueOf(r[0]), toLong(r[1])))
                .toList();

        // 4. Token usage & user ratings
        Object[] tokenRow = singleRow("""
                SELECT COALESCE(SUM(llm_total_tokens), 0),
                       COALESCE(AVG(llm_total_tokens), 0),
                       COALESCE(SUM(llm_call_count), 0),
                       COUNT(*) FILTER (WHERE user_rating = 5),
                       COUNT(*) FILTER (WHERE user_rating = 1)
                FROM public.maintenance_chat_conversations
                WHERE created_at >= :from AND created_at <= :to
                """, from, to);
        long totalTokens = toLong(tokenRow[0]);
        double avgTokens = round1(toDouble(tokenRow[1]));
        long llmSuccessCalls = toLong(tokenRow[2]);
        long ratingPos = toLong(tokenRow[3]);
        long ratingNeg = toLong(tokenRow[4]);
        double userPosRate = pct(ratingPos, ratingPos + ratingNeg);

        List<DailyTokens> tokensByDay = queryRows("""
                SELECT TO_CHAR(DATE_TRUNC('day', created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'), 'YYYY-MM-DD') AS d,
                       COALESCE(SUM(llm_total_tokens), 0)
                FROM public.maintenance_chat_conversations
                WHERE created_at >= :from AND created_at <= :to
                GROUP BY d ORDER BY d ASC
                """, from, to).stream()
                .map(r -> new DailyTokens(String.valueOf(r[0]), toLong(r[1])))
                .toList();

        Double estimatedCostUsd = null;
        BigDecimal pricePerMillion = aiProperties.getPricing() != null
                ? aiProperties.getPricing().getUsdPerMillionTokens()
                : null;
        if (pricePerMillion != null && pricePerMillion.compareTo(BigDecimal.ZERO) > 0) {
            estimatedCostUsd = BigDecimal.valueOf(totalTokens)
                    .multiply(pricePerMillion)
                    .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        // 5. Event counts from maintenance_chat_events
        Map<String, Long> eventCounts = new HashMap<>();
        for (Object[] row : queryRows("""
                SELECT event_type, COUNT(*) FROM public.maintenance_chat_events
                WHERE created_at >= :from AND created_at <= :to
                GROUP BY event_type
                """, from, to)) {
            eventCounts.put(String.valueOf(row[0]), toLong(row[1]));
        }

        Map<String, Long> cautionAnswers = new LinkedHashMap<>();
        cautionAnswers.put("YES", 0L);
        cautionAnswers.put("NO", 0L);
        cautionAnswers.put("UNSURE", 0L);
        for (Object[] row : queryRows("""
                SELECT metadata->>'answer', COUNT(*) FROM public.maintenance_chat_events
                WHERE created_at >= :from AND created_at <= :to
                  AND event_type = 'CAUTION_ANSWERED'
                GROUP BY metadata->>'answer'
                """, from, to)) {
            if (row[0] != null) {
                cautionAnswers.put(String.valueOf(row[0]), toLong(row[1]));
            }
        }

        long llmFallback = eventCounts.getOrDefault("LLM_FALLBACK", 0L);
        long llmTotalAttempts = llmSuccessCalls + llmFallback;
        double llmFallbackRate = pct(llmFallback, llmTotalAttempts);
        long llmLowConf = eventCounts.getOrDefault("LLM_LOW_CONFIDENCE", 0L);
        double llmLowConfRate = pct(llmLowConf, llmSuccessCalls);

        long safetyL1 = eventCounts.getOrDefault("SAFETY_LAYER1", 0L);
        long safetyL2 = eventCounts.getOrDefault("SAFETY_LAYER2", 0L);
        long cautionPrompted = eventCounts.getOrDefault("CAUTION_PROMPTED", 0L);
        long imagesAnalyzed = eventCounts.getOrDefault("IMAGE_ANALYZED", 0L);
        long imageRetake = eventCounts.getOrDefault("IMAGE_RETAKE", 0L);
        long imagePrivacy = eventCounts.getOrDefault("IMAGE_PRIVACY_REJECT", 0L);
        long homeWideOutage = eventCounts.getOrDefault("HOME_WIDE_OUTAGE", 0L);

        // 6. Safety spike comparison vs previous 7-day window
        long currentSafetyTotal = safetyL1 + safetyL2 + cautionPrompted;
        Duration span = Duration.between(from, to);
        OffsetDateTime prevTo = from;
        OffsetDateTime prevFrom = prevTo.minusDays(Math.max(7L, Math.min(30L, Math.max(1L, span.toDays()))));
        Object[] prevSafetyRow = singleRow("""
                SELECT COUNT(*) FROM public.maintenance_chat_events
                WHERE created_at >= :from AND created_at < :to
                  AND event_type IN ('SAFETY_LAYER1', 'SAFETY_LAYER2', 'CAUTION_PROMPTED')
                """, prevFrom, prevTo);
        long prevSafetyTotal = toLong(prevSafetyRow[0]);
        boolean safetySpike = currentSafetyTotal >= 3
                && ((prevSafetyTotal == 0 && currentSafetyTotal >= 5)
                || (prevSafetyTotal > 0 && currentSafetyTotal > prevSafetyTotal * 2));

        AlertsDto alerts = new AlertsDto(
                totalRatedTickets > 0 && misleadingRate > 20.0,
                llmTotalAttempts > 0 && llmFallbackRate > 30.0,
                safetySpike,
                currentSafetyTotal,
                prevSafetyTotal
        );

        return new MetricsResponse(
                from, to, convByStatus, selfResolutionRate,
                ticketsBySource, qualityCounts, misleadingRate,
                deviceTypes, topSymptoms,
                llmTotalAttempts, llmFallback, llmFallbackRate,
                llmLowConf, llmLowConfRate,
                avgTokens, totalTokens, tokensByDay, estimatedCostUsd,
                safetyL1, safetyL2, cautionPrompted, cautionAnswers,
                imagesAnalyzed, imageRetake, pct(imageRetake, imagesAnalyzed),
                imagePrivacy, pct(imagePrivacy, imagesAnalyzed),
                ratingPos, ratingNeg, userPosRate,
                homeWideOutage, alerts
        );
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryRows(String sql, OffsetDateTime from, OffsetDateTime to) {
        return em.createNativeQuery(sql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    private Object[] singleRow(String sql, OffsetDateTime from, OffsetDateTime to) {
        Object res = em.createNativeQuery(sql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return res instanceof Object[] arr ? arr : new Object[]{res};
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double pct(long num, long denom) {
        if (denom <= 0) return 0.0;
        return round1((100.0 * num) / denom);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
