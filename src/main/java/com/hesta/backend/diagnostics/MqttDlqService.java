package com.hesta.backend.diagnostics;

import java.util.List;

public interface MqttDlqService {

    boolean checkRateLimit(String nodeId);

    MqttDeadLetterDto recordDeadLetter(String topic, String payload, String reason, String nodeId);

    List<MqttDeadLetterDto> getDeadLetters(int limit, Boolean unhandledOnly);

    MqttDeadLetterDto replay(String id);

    int replayAll();

    void clearDlq();

    long getDeadLetterCount();
}
