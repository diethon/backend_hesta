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
        // Atomic bit-packed state: (second << 32) | (counter & 0xFFFFFFFFL)
        final AtomicLong state = new AtomicLong(0);
    }

    @Override
    public boolean checkRateLimit(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return true;
        }

        long sec = System.currentTimeMillis() / 1000;
        RateLimitTracker tracker = rateLimitMap.computeIfAbsent(nodeId, k -> new RateLimitTracker());

        while (true) {
            long current = tracker.state.get();
            long currentSec = current >>> 32;
            int count = (int) current;

            long newSec;
            int newCount;
            if (currentSec != sec) {
                newSec = sec;
                newCount = 1;
            } else {
                newSec = sec;
                newCount = count + 1;
            }

            if (newCount > MAX_RATE_PER_SECOND) {
                log.warn("Rate limit exceeded for node [{}] with {} msg/s (limit: {})", nodeId, newCount, MAX_RATE_PER_SECOND);
                return false;
            }

            long next = (newSec << 32) | (newCount & 0xFFFFFFFFL);
            if (tracker.state.compareAndSet(current, next)) {
                return true;
            }
        }
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

        synchronized (dlqBuffer) {
            while (dlqBuffer.size() >= MAX_DLQ_CAPACITY) {
                dlqBuffer.pollFirst();
            }
            dlqBuffer.addLast(dl);
        }
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
                try {
                    // Try to re-dispatch to mqttInputChannel
                    if (applicationContext != null) {
                        org.springframework.messaging.MessageChannel inputChannel = 
                                applicationContext.getBean("mqttInputChannel", org.springframework.messaging.MessageChannel.class);
                        if (inputChannel != null) {
                            Message<String> message = MessageBuilder.withPayload(dl.getPayload())
                                    .setHeader(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC, dl.getTopic())
                                    .setHeader("X-Replayed-From-DLQ", dl.getId())
                                    .build();
                            boolean sent = inputChannel.send(message);
                            if (sent) {
                                dl.setReplayed(true);
                                dl.setReplayedAt(OffsetDateTime.now());
                                log.info("Replayed DLQ message [{}] to topic [{}]", id, dl.getTopic());
                            } else {
                                log.warn("Failed to send DLQ message [{}] to channel", id);
                            }
                        }
                    }
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
                MqttDeadLetterDto res = replay(dl.getId());
                if (res != null && res.isReplayed()) {
                    count++;
                }
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
