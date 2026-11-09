package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.RecommendationResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.service.MlServiceClient;
import com.hesta.backend.service.NotificationService;
import com.hesta.backend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "hesta.recommendation.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class RecommendationScheduler {

    private static final UUID SYSTEM_RETRAIN_CLAIM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final HomeRepository homeRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final RecommendationService recommendationService;
    private final MlServiceClient mlServiceClient;
    private final BehaviorEventRepository behaviorEventRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private NotificationService notificationService;

    @Value("${hesta.recommendation.auto-generate.enabled:true}")
    private boolean autoGenerateEnabled;

    @Value("${hesta.recommendation.auto-generate.lookback-days:14}")
    private int lookbackDays;

    @Value("${hesta.recommendation.auto-retrain.enabled:true}")
    private boolean autoRetrainEnabled;

    @Value("${hesta.recommendation.auto-retrain.min-new-events:100}")
    private int minNewEvents;

    /**
     * Tự động quét và sinh các gợi ý thói quen thông minh (Recommendation) chạy nền ở giờ thấp điểm.
     * Mặc định chạy lúc 02:00 sáng hàng ngày.
     */
    @Scheduled(cron = "${hesta.recommendation.auto-generate.cron:0 0 2 * * ?}", zone = "${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    public void runAutoGenerate() {
        if (!autoGenerateEnabled) {
            log.debug("Auto-generation of recommendations is disabled by configuration");
            return;
        }

        log.info("Starting background scheduled recommendation generation...");
        LocalDate today = LocalDate.now();
        List<Home> homes = homeRepository.findAll();

        for (Home home : homes) {
            if (!claim("REC_GENERATE", home.getId(), today)) {
                log.debug("Recommendation generation for home {} already claimed by another node today", home.getId());
                continue;
            }

            try {
                OffsetDateTime to = OffsetDateTime.now();
                OffsetDateTime from = to.minusDays(lookbackDays);
                List<RecommendationResponse> created = recommendationService.generateForSystem(home.getId(), from, to);

                if (!created.isEmpty()) {
                    log.info("Generated {} new automation recommendations for home [{}]", created.size(), home.getName());
                    notifyHomeOwner(home, created.size());
                } else {
                    log.debug("No new eligible recommendations generated for home [{}]", home.getName());
                }
            } catch (Exception ex) {
                log.error("Failed to generate recommendations for home {}", home.getId(), ex);
            }
        }
        log.info("Background scheduled recommendation generation completed.");
    }

    /**
     * Tự động tái huấn luyện mô hình ML định kỳ hàng tuần có kiểm định an toàn (Champion - Challenger).
     * Mặc định chạy lúc 03:00 sáng Chủ Nhật hàng tuần.
     */
    @Scheduled(cron = "${hesta.recommendation.auto-retrain.cron:0 0 3 * * SUN}", zone = "${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    public void runAutoRetrain() {
        if (!autoRetrainEnabled) {
            log.debug("Auto-retraining is disabled by configuration");
            return;
        }

        LocalDate today = LocalDate.now();
        if (!claim("REC_RETRAIN", SYSTEM_RETRAIN_CLAIM_ID, today)) {
            log.debug("Auto-retraining task for today already claimed by another node");
            return;
        }

        log.info("Evaluating auto-retraining criteria for ML habit models...");
        try {
            OffsetDateTime oneWeekAgo = OffsetDateTime.now().minusDays(7);
            long newEventsCount = behaviorEventRepository.countByOccurredAtAfter(oneWeekAgo);

            if (newEventsCount < minNewEvents) {
                log.info("Skipping ML auto-retraining: only {} events recorded in the last 7 days (minimum required: {})",
                        newEventsCount, minNewEvents);
                return;
            }

            log.info("Criteria satisfied ({} events >= {} required). Triggering ML model retraining...",
                    newEventsCount, minNewEvents);
            MlModelStatusResponse status = mlServiceClient.triggerRetraining(null);
            log.info("ML Auto-retraining finished. Status: {}, Version: {}, Accuracy: {}, Metrics: {}",
                    status.getStatus(), status.getVersion(), status.getAccuracy(), status.getMetrics());

        } catch (Exception ex) {
            log.error("Auto-retraining execution encountered an error", ex);
        }
    }

    private void notifyHomeOwner(Home home, int count) {
        if (notificationService == null) return;

        try {
            List<HomeMember> members = homeMemberRepository.findByHomeIdWithUser(home.getId());
            HomeMember targetMember = members.stream()
                    .filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getRole() == HomeRole.OWNER)
                    .findFirst()
                    .orElseGet(() -> members.stream()
                            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                            .findFirst()
                            .orElse(null));

            if (targetMember != null && targetMember.getUser() != null) {
                notificationService.create(new NotificationEvent(
                        targetMember.getUser().getId(),
                        home.getId(),
                        NotificationType.AUTOMATION,
                        "Gợi ý tự động hóa mới",
                        String.format("HESTA vừa nhận diện được %d thói quen sinh hoạt mới của gia đình bạn. Hãy xem và kích hoạt nhé!", count),
                        NotificationPriority.MEDIUM
                ));
                log.info("Sent recommendation notification to user {} for home {}",
                        targetMember.getUser().getId(), home.getId());
            }
        } catch (Exception ex) {
            log.warn("Could not dispatch notification for new recommendations in home {}: {}", home.getId(), ex.getMessage());
        }
    }

    private boolean claim(String type, UUID scheduleId, LocalDate date) {
        try {
            return jdbcTemplate.update(
                    "INSERT INTO scheduled_run_claims (schedule_type, schedule_id, local_date) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    type, scheduleId, date) == 1;
        } catch (Exception ex) {
            log.warn("Claim check failed for type {} id {}: {}", type, scheduleId, ex.getMessage());
            return true; // Fallback: if claim table has error, allow execution
        }
    }
}
