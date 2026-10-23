package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.DeviceUptimeResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.DeviceProfilingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProfilingServiceImpl implements DeviceProfilingService {

    private final DeviceRepository deviceRepository;
    private final DeviceStateHistoryRepository historyRepository;

    @Override
    @Transactional(readOnly = true)
    public DeviceUptimeResponse getDeviceUptime(UUID deviceId, int days) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        int effectiveDays = days > 0 ? days : 7;
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(effectiveDays);

        // Lỗ hổng 4: Nếu thiết bị mới được thêm vào hệ thống chưa đủ 'effectiveDays', chỉ tính từ createdAt
        if (device.getCreatedAt() != null && device.getCreatedAt().isAfter(from)) {
            from = device.getCreatedAt();
        }

        long totalSeconds = Math.max(1L, Duration.between(from, to).getSeconds());
        double totalHours = Math.round((totalSeconds / 3600.0) * 100.0) / 100.0;

        // Đảm bảo truy vấn bao gồm từ đầu ngày hôm nay để dựng timeline 24h
        LocalDate today = to.toLocalDate();
        OffsetDateTime todayStart = today.atTime(0, 0).atOffset(to.getOffset());
        OffsetDateTime queryFrom = from.isBefore(todayStart) ? from : todayStart;

        List<DeviceStateHistory> allHistories = historyRepository
                .findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, queryFrom, to);

        // Lỗ hổng 3: Xác định trạng thái ban đầu tại mốc queryFrom qua bản ghi gần nhất trước đó
        boolean initialQueryState = resolveStateAt(deviceId, device, queryFrom, allHistories);

        // 1. Tính toán onHours / offHours cho khoảng [from, to]
        double onSeconds = 0.0;
        double offSeconds = 0.0;

        final OffsetDateTime effectiveFrom = from;
        List<DeviceStateHistory> windowHistories = allHistories.stream()
                .filter(h -> !h.getChangedAt().isBefore(effectiveFrom))
                .toList();

        boolean stateAtFrom = resolveStateAt(deviceId, device, from, allHistories);

        if (windowHistories.isEmpty()) {
            if (stateAtFrom) {
                onSeconds = totalSeconds;
            } else {
                offSeconds = totalSeconds;
            }
        } else {
            OffsetDateTime cursor = from;
            boolean currentState = stateAtFrom;

            for (DeviceStateHistory h : windowHistories) {
                OffsetDateTime changeTime = h.getChangedAt();
                if (changeTime.isAfter(cursor)) {
                    long sec = Duration.between(cursor, changeTime).getSeconds();
                    if (currentState) {
                        onSeconds += sec;
                    } else {
                        offSeconds += sec;
                    }
                    cursor = changeTime;
                }
                currentState = isStateOn(h.getNewState());
            }

            if (cursor.isBefore(to)) {
                long sec = Duration.between(cursor, to).getSeconds();
                if (currentState) {
                    onSeconds += sec;
                } else {
                    offSeconds += sec;
                }
            }
        }

        double onHours = Math.round((onSeconds / 3600.0) * 100.0) / 100.0;
        double offHours = Math.round((offSeconds / 3600.0) * 100.0) / 100.0;
        double uptimePercent = totalHours > 0 ? Math.round(((onHours / totalHours) * 100.0) * 10.0) / 10.0 : 0.0;

        // 2. Lỗ hổng 1: Dựng chính xác 24 Hourly Activity Buckets cho thanh UI hôm nay
        List<DeviceUptimeResponse.TimelinePoint> timelinePoints = build24HourTimeline(
                allHistories, todayStart, initialQueryState, to);

        return DeviceUptimeResponse.builder()
                .uptimePercent(uptimePercent)
                .totalHours(totalHours)
                .onHours(onHours)
                .offHours(offHours)
                .timelinePoints(timelinePoints)
                .build();
    }

    private boolean resolveStateAt(UUID deviceId, Device device, OffsetDateTime pointInTime, List<DeviceStateHistory> histories) {
        DeviceStateHistory lastBefore = null;
        for (DeviceStateHistory h : histories) {
            if (!h.getChangedAt().isAfter(pointInTime)) {
                lastBefore = h;
            } else {
                break;
            }
        }

        if (lastBefore != null && lastBefore.getNewState() != null && !lastBefore.getNewState().isEmpty()) {
            return isStateOn(lastBefore.getNewState());
        }

        var recentPast = historyRepository.findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(deviceId, pointInTime);
        if (recentPast.isPresent() && recentPast.get().getNewState() != null && !recentPast.get().getNewState().isEmpty()) {
            return isStateOn(recentPast.get().getNewState());
        }

        return isStateOn(device.getCurrentState());
    }

    private List<DeviceUptimeResponse.TimelinePoint> build24HourTimeline(
            List<DeviceStateHistory> histories,
            OffsetDateTime todayStart,
            boolean initialStateAtTodayStart,
            OffsetDateTime now) {

        List<DeviceUptimeResponse.TimelinePoint> points = new ArrayList<>(24);
        DateTimeFormatter dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

        for (int h = 0; h < 24; h++) {
            OffsetDateTime slotStart = todayStart.plusHours(h);
            OffsetDateTime slotEnd = slotStart.plusHours(1);

            if (slotStart.isAfter(now)) {
                points.add(DeviceUptimeResponse.TimelinePoint.builder()
                        .hour(h)
                        .timestamp(slotStart.format(dtf))
                        .state("OFF")
                        .durationSeconds(0L)
                        .build());
                continue;
            }

            OffsetDateTime effectiveSlotEnd = slotEnd.isBefore(now) ? slotEnd : now;
            long slotDurationSec = Math.max(0L, Duration.between(slotStart, effectiveSlotEnd).getSeconds());

            boolean stateAtSlotStart = initialStateAtTodayStart;
            for (DeviceStateHistory hRecord : histories) {
                if (!hRecord.getChangedAt().isAfter(slotStart)) {
                    stateAtSlotStart = isStateOn(hRecord.getNewState());
                } else {
                    break;
                }
            }

            List<DeviceStateHistory> slotEvents = new ArrayList<>();
            for (DeviceStateHistory hRecord : histories) {
                if (hRecord.getChangedAt().isAfter(slotStart) && !hRecord.getChangedAt().isAfter(effectiveSlotEnd)) {
                    slotEvents.add(hRecord);
                }
            }

            long onSecInSlot = 0L;
            if (slotEvents.isEmpty()) {
                if (stateAtSlotStart) {
                    onSecInSlot = slotDurationSec;
                }
            } else {
                OffsetDateTime cursor = slotStart;
                boolean currentSlotState = stateAtSlotStart;
                for (DeviceStateHistory ev : slotEvents) {
                    if (ev.getChangedAt().isAfter(cursor)) {
                        long diff = Duration.between(cursor, ev.getChangedAt()).getSeconds();
                        if (currentSlotState) {
                            onSecInSlot += diff;
                        }
                        cursor = ev.getChangedAt();
                    }
                    currentSlotState = isStateOn(ev.getNewState());
                }
                if (cursor.isBefore(effectiveSlotEnd)) {
                    long diff = Duration.between(cursor, effectiveSlotEnd).getSeconds();
                    if (currentSlotState) {
                        onSecInSlot += diff;
                    }
                }
            }

            points.add(DeviceUptimeResponse.TimelinePoint.builder()
                    .hour(h)
                    .timestamp(slotStart.format(dtf))
                    .state(onSecInSlot > 0 ? "ON" : "OFF")
                    .durationSeconds(onSecInSlot)
                    .build());
        }

        return points;
    }

    private boolean isStateOn(Map<String, Object> state) {
        if (state == null || state.isEmpty()) return false;
        Object power = state.get("power");
        if (power != null) {
            if (power instanceof Boolean b) return b;
            String pStr = power.toString().trim().toUpperCase();
            if ("ON".equals(pStr) || "TRUE".equals(pStr) || "1".equals(pStr)) return true;
        }
        Object status = state.get("status");
        if (status != null) {
            String sStr = status.toString().trim().toUpperCase();
            if ("ON".equals(sStr) || "OPEN".equals(sStr) || "ACTIVE".equals(sStr)) return true;
        }
        Object stateVal = state.get("state");
        if (stateVal != null) {
            if (stateVal instanceof Boolean b) return b;
            String sStr = stateVal.toString().trim().toUpperCase();
            if ("ON".equals(sStr) || "TRUE".equals(sStr) || "OPEN".equals(sStr)) return true;
        }
        return false;
    }
}
