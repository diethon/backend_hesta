package com.hesta.backend.service;

import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.dto.response.ScheduleResponse;

import java.util.List;
import java.util.UUID;

public interface ScheduleService {
    List<ScheduleResponse> sceneSchedules(UUID userId, UUID homeId, UUID sceneId);
    ScheduleResponse saveSceneSchedule(UUID userId, UUID homeId, UUID sceneId, UUID scheduleId, ScheduleRequest request);
    void deleteSceneSchedule(UUID userId, UUID homeId, UUID sceneId, UUID scheduleId);
    List<ScheduleResponse> ruleSchedules(UUID userId, UUID homeId, UUID ruleId);
    ScheduleResponse saveRuleSchedule(UUID userId, UUID homeId, UUID ruleId, UUID scheduleId, ScheduleRequest request);
    void deleteRuleSchedule(UUID userId, UUID homeId, UUID ruleId, UUID scheduleId);
}
