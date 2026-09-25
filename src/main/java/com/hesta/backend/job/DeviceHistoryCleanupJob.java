package com.hesta.backend.job;

import com.hesta.backend.repository.DeviceStateHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceHistoryCleanupJob {

    private final DeviceStateHistoryRepository deviceStateHistoryRepository;

    @Value("${hesta.history.retention.days:30}")
    private int retentionDays;

    // Chạy vào lúc 2:00 AM mỗi ngày
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldHistory() {
        log.info("Starting scheduled cleanup for DeviceStateHistory older than {} days...", retentionDays);
        try {
            OffsetDateTime cutoffDate = OffsetDateTime.now().minusDays(retentionDays);
            int deletedCount = deviceStateHistoryRepository.deleteOlderThan(cutoffDate);
            log.info("Successfully deleted {} old DeviceStateHistory records.", deletedCount);
        } catch (Exception e) {
            log.error("Failed to execute DeviceStateHistory cleanup job.", e);
        }
    }
}
