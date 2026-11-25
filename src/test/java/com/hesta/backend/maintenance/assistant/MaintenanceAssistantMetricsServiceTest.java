package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceAssistantMetricsServiceTest {

    @Mock private EntityManager em;

    private AiProperties aiProperties;
    private MaintenanceAssistantMetricsService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        aiProperties = new AiProperties();
        service = new MaintenanceAssistantMetricsService(aiProperties, clock);
        Field emField = MaintenanceAssistantMetricsService.class.getDeclaredField("em");
        emField.setAccessible(true);
        emField.set(service, em);
    }

    private Query mockListQuery(List<Object[]> rows) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList()).thenReturn(rows);
        return q;
    }

    private Query mockSingleQuery(Object single) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(single);
        return q;
    }

    @Test
    void computesAggregatesRatesCostAndRedAlertsCorrectly() {
        aiProperties.getPricing().setUsdPerMillionTokens(new BigDecimal("0.20"));

        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("GROUP BY status")) {
                return mockListQuery(List.of(
                        new Object[]{"RESOLVED", 6L},
                        new Object[]{"ESCALATED", 3L},
                        new Object[]{"ABANDONED", 1L},
                        new Object[]{"ACTIVE", 2L}
                ));
            }
            if (sql.contains("GROUP BY source")) {
                return mockListQuery(List.of(new Object[]{"CHATBOT", 8L}, new Object[]{"MANUAL", 2L}));
            }
            if (sql.contains("GROUP BY diagnosis_quality")) {
                return mockListQuery(List.of(
                        new Object[]{"HELPFUL", 3L},
                        new Object[]{"PARTIAL", 1L},
                        new Object[]{"MISLEADING", 2L} // 2/6 = 33.3% > 20% -> alert!
                ));
            }
            if (sql.contains("deviceType")) {
                return mockListQuery(List.of(new Object[]{"LIGHT", 5L}, new Object[]{"SMOKE_SENSOR", 3L}));
            }
            if (sql.contains("symptomId")) {
                return mockListQuery(List.<Object[]>of(new Object[]{"LIGHT_OFFLINE", 4L}));
            }
            if (sql.contains("SUM(llm_total_tokens)")) {
                if (sql.contains("DATE_TRUNC")) {
                    return mockListQuery(List.<Object[]>of(new Object[]{"2026-11-23", 10000L}));
                }
                return mockSingleQuery(new Object[]{10000L, 1000.0, 10L, 8L, 2L});
            }
            if (sql.contains("GROUP BY event_type")) {
                return mockListQuery(List.of(
                        new Object[]{"LLM_FALLBACK", 6L},       // 6 / (10+6) = 37.5% > 30% -> alert!
                        new Object[]{"LLM_LOW_CONFIDENCE", 2L},
                        new Object[]{"SAFETY_LAYER1", 4L},
                        new Object[]{"CAUTION_PROMPTED", 2L},   // current safety = 6 vs prev 1 -> spike!
                        new Object[]{"IMAGE_ANALYZED", 5L},
                        new Object[]{"IMAGE_RETAKE", 1L},
                        new Object[]{"IMAGE_PRIVACY_REJECT", 1L},
                        new Object[]{"HOME_WIDE_OUTAGE", 3L}
                ));
            }
            if (sql.contains("CAUTION_ANSWERED")) {
                return mockListQuery(List.of(new Object[]{"YES", 1L}, new Object[]{"NO", 1L}));
            }
            // Previous 7-day safety count
            return mockSingleQuery(1L);
        });

        MaintenanceAssistantMetricsService.MetricsResponse res = service.computeMetrics(
                OffsetDateTime.parse("2026-11-16T00:00:00Z"),
                OffsetDateTime.parse("2026-11-23T23:59:59Z"));

        assertThat(res.selfResolutionRate()).isEqualTo(60.0); // 6 / (6+3+1)
        assertThat(res.misleadingRate()).isEqualTo(33.3);
        assertThat(res.llmFallbackRate()).isEqualTo(37.5);
        assertThat(res.totalTokens()).isEqualTo(10000L);
        assertThat(res.estimatedCostUsd()).isEqualTo(0.002); // 10000 * 0.20 / 1e6
        assertThat(res.userPositiveRate()).isEqualTo(80.0);
        assertThat(res.homeWideOutageCount()).isEqualTo(3L);
        assertThat(res.alerts().highMisleadingRate()).isTrue();
        assertThat(res.alerts().highFallbackRate()).isTrue();
        assertThat(res.alerts().safetySpike()).isTrue();
    }

    @Test
    void handlesEmptyDateRangeGracefullyAndHidesCostWhenPricingUnset() {
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("SUM(llm_total_tokens)") && !sql.contains("DATE_TRUNC")) {
                return mockSingleQuery(new Object[]{0L, 0.0, 0L, 0L, 0L});
            }
            if (sql.contains("SAFETY_LAYER1")) {
                return mockSingleQuery(0L);
            }
            return mockListQuery(List.of());
        });

        MaintenanceAssistantMetricsService.MetricsResponse res = service.computeMetrics(null, null);

        assertThat(res.selfResolutionRate()).isEqualTo(0.0);
        assertThat(res.estimatedCostUsd()).isNull();
        assertThat(res.alerts().highMisleadingRate()).isFalse();
        assertThat(res.alerts().highFallbackRate()).isFalse();
        assertThat(res.alerts().safetySpike()).isFalse();
    }
}
