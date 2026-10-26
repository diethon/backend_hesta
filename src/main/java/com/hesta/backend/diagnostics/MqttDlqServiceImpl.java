package com.hesta.backend.diagnostics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MqttDlqServiceImpl implements MqttDlqService {

    private static final int MAX_DLQ_CAPACITY = 500;
    private static final int MAX_RATE_PER_SECOND = 50;

    private final ConcurrentLinkedDeque<MqttDeadLetterDto> dlqBuffer = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, RateLimitTracker> rateLimitMap = new ConcurrentHashMap<>();

    @Autowired
    private ApplicationContext applicationContext;

    private static class RateLimitTracker {
        final AtomicLong currentSecond = new AtomicLong(0);
        final AtomicInteger counter = new AtomicInteger(0);
    }

    @Override
    public boolean checkRateLimit(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return true;
        }

        long sec = System.currentTimeMillis() / 1000;
        RateLimitTracker tracker = rateLimitMap.computeIfAbsent(nodeId, k -> new RateLimitTracker());

        long prevSec = tracker.currentSecond.get();
        if (prevSec != sec) {
            tracker.currentSecond.set(sec);
            tracker.counter.set(1);
            return true;
        }

        int currentCount = tracker.counter.incrementAndGet();
        if (currentCount > MAX_RATE_PER_SECOND) {
            log.warn("Rate limit exceeded for node [{}] with {} msg/s (limit: {})", nodeId, currentCount, MAX_RATE_PER_SECOND);
            return false;
        }

        return true;
    }

    @Override
    public MqttDeadLetterDto recordDeadLetter(String topic, String payload, String reason, String nodeId) {
        String id = UUID.randomUUID().toString();
        MqttDeadLetterDto dl = MqttDeadLetterDto.builder()
                .id(id)
                .nodeId(nodeId)
                .topic(topic != null ? topic : "")
                .payload(payload != null ? payload : "")
                .failureReason(reason != null ? reason : "UNKNOWN_ERROR")
                .receivedAt(OffsetDateTime.now())
                .replayed(false)
                .replayedAt(null)
                .build();

        while (dlqBuffer.size() >= MAX_DLQ_CAPACITY) {
            dlqBuffer.pollFirst();
        }

        dlqBuffer.addLast(dl);
        log.warn("Recorded MQTT Dead-Letter [{}]. Topic: [{}], Reason: [{}]", id, topic, reason);
        return dl;
    }

    @Override
    public List<MqttDeadLetterDto> getDeadLetters(int limit, Boolean unhandledOnly) {
        int max = limit <= 0 ? 50 : Math.min(limit, MAX_DLQ_CAPACITY);
        return dlqBuffer.stream()
                .filter(dl -> unhandledOnly == null || !unhandledOnly || !dl.isReplayed())
                .sorted((a, b) -> b.getReceivedAt().compareTo(a.getReceivedAt()))
                .limit(max)
                .collect(Collectors.toList());
    }

    @Override
    public MqttDeadLetterDto replay(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }

        for (MqttDeadLetterDto dl : dlqBuffer) {
            if (dl.getId().equals(id)) {
                dl.setReplayed(true);
                dl.setReplayedAt(OffsetDateTime.now());

                try {
                    // Try to re-dispatch to mqttInputChannel
                    org.springframework.messaging.MessageChannel inputChannel = 
                            applicationContext.getBean("mqttInputChannel", org.springframework.messaging.MessageChannel.class);
                    Message<String> message = MessageBuilder.withPayload(dl.getPayload())
                            .setHeader(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC, dl.getTopic())
                            .setHeader("X-Replayed-From-DLQ", dl.getId())
                            .build();
                    inputChannel.send(message);
                    log.info("Replayed DLQ message [{}] to topic [{}]", id, dl.getTopic());
                } catch (Exception e) {
                    log.error("Failed to replay DLQ message [{}]: {}", id, e.getMessage());
                }

                return dl;
            }
        }
        return null;
    }

    @Override
    public int replayAll() {
        int count = 0;
        for (MqttDeadLetterDto dl : dlqBuffer) {
            if (!dl.isReplayed()) {
                replay(dl.getId());
                count++;
            }
        }
        return count;
    }

    @Override
    public void clearDlq() {
        dlqBuffer.clear();
        log.info("Cleared all MQTT Dead-Letter Queue items");
    }

    @Override
    public long getDeadLetterCount() {
        return dlqBuffer.stream().filter(dl -> !dl.isReplayed()).count();
    }
}
