package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.GenerateBehaviorDataRequest;
import com.hesta.backend.dto.response.BehaviorDatasetResponse;
import com.hesta.backend.dto.response.BehaviorPatternResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.BehaviorService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BehaviorServiceImpl implements BehaviorService {
    private final HomeAuthorizationService homeAuthorizationService;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final BehaviorEventRepository eventRepository;

    @Override
    @Transactional
    public BehaviorDatasetResponse generate(UUID userId, UUID homeId, GenerateBehaviorDataRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(userId, homeId);
        if (request == null || request.getStartDate() == null || request.getDays() < 2 || request.getDays() > 365) {
            throw new AppException(ErrorCode.BEHAVIOR_DATASET_INVALID);
        }
        List<Device> devices = deviceRepository.findAllByHomeIdOrderByNameAsc(homeId);
        if (devices.isEmpty()) throw new AppException(ErrorCode.BEHAVIOR_DEVICE_REQUIRED);
        User user = userRepository.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        String datasetKey = "sim-%s-%d-%d".formatted(request.getStartDate(), request.getDays(), request.getSeed());
        eventRepository.deleteGeneratedDataset(homeId, datasetKey);

        Random random = new Random(request.getSeed());
        List<BehaviorEvent> generated = new ArrayList<>();
        for (int day = 0; day < request.getDays(); day++) {
            LocalDate date = request.getStartDate().plusDays(day);
            Device morningDevice = devices.getFirst();
            Device bedtimeDevice = devices.getLast();
            generated.add(event(home, user, morningDevice, datasetKey, "TURN_ON",
                    date.atTime(6, 45).plusMinutes(random.nextInt(31) - 15)));
            generated.add(event(home, user, bedtimeDevice, datasetKey, "TURN_OFF",
                    date.atTime(22, 30).plusMinutes(random.nextInt(41) - 20)));
            if (random.nextBoolean()) {
                Device extra = devices.get(random.nextInt(devices.size()));
                generated.add(event(home, user, extra, datasetKey, "TURN_ON",
                        date.atTime(12, 0).plusMinutes(random.nextInt(181) - 90)));
            }
        }
        eventRepository.saveAll(generated);
        return BehaviorDatasetResponse.builder().datasetKey(datasetKey).startDate(request.getStartDate())
                .days(request.getDays()).seed(request.getSeed()).generatedEvents(generated.size()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BehaviorPatternResponse> detectPatterns(UUID userId, UUID homeId,
                                                        OffsetDateTime from, OffsetDateTime to) {
        homeAuthorizationService.requireAccess(userId, homeId);
        if (from == null || to == null || !from.isBefore(to)) throw new AppException(ErrorCode.BEHAVIOR_DATASET_INVALID);
        List<BehaviorEvent> events = eventRepository.findAllByHomeIdAndOccurredAtBetweenOrderByOccurredAtAsc(homeId, from, to);
        long totalDays = Math.max(1, Duration.between(from, to).toDays() + 1);
        Map<PatternKey, List<BehaviorEvent>> groups = events.stream()
                .filter(event -> event.getDevice() != null && event.getAction() != null)
                .filter(event -> window(event.getOccurredAt().toLocalTime()) != null)
                .collect(Collectors.groupingBy(event -> new PatternKey(event.getDevice().getId(), event.getAction(),
                        window(event.getOccurredAt().toLocalTime()))));

        return groups.entrySet().stream().map(entry -> pattern(entry.getKey(), entry.getValue(), totalDays))
                .filter(pattern -> pattern.getOccurrences() >= 2 && pattern.getConfidence() >= 0.5)
                .sorted(Comparator.comparingDouble(BehaviorPatternResponse::getConfidence).reversed()).toList();
    }

    private BehaviorEvent event(Home home, User user, Device device, String datasetKey, String action,
                                LocalDateTime occurredAt) {
        boolean on = "TURN_ON".equals(action);
        return BehaviorEvent.builder().home(home).user(user).device(device).room(device.getRoom())
                .eventType("DEVICE_ACTION").action(action).eventSource("SIMULATED").datasetKey(datasetKey)
                .previousState(Map.of("power", on ? "OFF" : "ON"))
                .currentState(Map.of("power", on ? "ON" : "OFF"))
                .occurredAt(occurredAt.atOffset(ZoneOffset.ofHours(7))).build();
    }

    private String window(LocalTime time) {
        if (!time.isBefore(LocalTime.of(5, 0)) && time.isBefore(LocalTime.of(10, 0))) return "MORNING";
        if (!time.isBefore(LocalTime.of(20, 0))) return "BEDTIME";
        return null;
    }

    private BehaviorPatternResponse pattern(PatternKey key, List<BehaviorEvent> events, long totalDays) {
        BehaviorEvent sample = events.getFirst();
        long distinctDays = events.stream().map(event -> event.getOccurredAt().toLocalDate()).distinct().count();
        long averageSecond = Math.round(events.stream().mapToLong(event -> event.getOccurredAt().toLocalTime().toSecondOfDay())
                .average().orElse(0));
        return BehaviorPatternResponse.builder().patternType(key.window()).deviceId(key.deviceId())
                .deviceName(sample.getDevice().getName())
                .roomId(sample.getRoom() == null ? null : sample.getRoom().getId())
                .roomName(sample.getRoom() == null ? null : sample.getRoom().getName())
                .action(key.action()).averageTime(LocalTime.ofSecondOfDay(averageSecond))
                .occurrences(events.size()).confidence(Math.min(1d, (double) distinctDays / totalDays)).build();
    }

    private record PatternKey(UUID deviceId, String action, String window) {}
}
