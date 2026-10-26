package com.hesta.backend.service;

import com.hesta.backend.diagnostics.MqttDeadLetterDto;
import com.hesta.backend.diagnostics.MqttDlqService;
import com.hesta.backend.diagnostics.MqttDlqServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MqttDlqServiceTest {

    private MqttDlqServiceImpl dlqService;

    @BeforeEach
    void setUp() {
        dlqService = new MqttDlqServiceImpl();
        ApplicationContext mockContext = mock(ApplicationContext.class);
        ReflectionTestUtils.setField(dlqService, "applicationContext", mockContext);
    }

    @Test
    @DisplayName("Should record dead letter and retrieve unhandled items")
    void testRecordDeadLetter() {
        MqttDeadLetterDto dl = dlqService.recordDeadLetter("hesta/nodes/node-1/bad", "corrupted", "MALFORMED_JSON", "node-1");

        assertNotNull(dl.getId());
        assertEquals("node-1", dl.getNodeId());
        assertEquals("MALFORMED_JSON", dl.getFailureReason());
        assertFalse(dl.isReplayed());

        List<MqttDeadLetterDto> unhandled = dlqService.getDeadLetters(10, true);
        assertEquals(1, unhandled.size());
        assertEquals(dl.getId(), unhandled.get(0).getId());
    }

    @Test
    @DisplayName("Should enforce token bucket rate limit of 50 msgs/s per node")
    void testRateLimiter() {
        String nodeId = "flood-node";
        for (int i = 0; i < 50; i++) {
            assertTrue(dlqService.checkRateLimit(nodeId), "Messages under 50/s should be allowed");
        }

        // 51st message in the same second should be rejected
        assertFalse(dlqService.checkRateLimit(nodeId), "51st message in same second must be rate-limited");
    }

    @Test
    @DisplayName("Should clear all DLQ entries cleanly")
    void testClearDlq() {
        dlqService.recordDeadLetter("topic/1", "p1", "ERR1", "node-1");
        dlqService.recordDeadLetter("topic/2", "p2", "ERR2", "node-2");

        assertEquals(2, dlqService.getDeadLetterCount());

        dlqService.clearDlq();
        assertEquals(0, dlqService.getDeadLetterCount());
    }
}
