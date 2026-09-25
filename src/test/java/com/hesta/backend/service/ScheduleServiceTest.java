package com.hesta.backend.service;

import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneSchedule;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.AutomationRuleRepository;
import com.hesta.backend.repository.AutomationScheduleRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.repository.SceneScheduleRepository;
import com.hesta.backend.service.impl.ScheduleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {
    @Mock HomeAuthorizationService authorization;
    @Mock SceneRepository scenes;
    @Mock SceneScheduleRepository sceneSchedules;
    @Mock AutomationRuleRepository rules;
    @Mock AutomationScheduleRepository ruleSchedules;
    @InjectMocks ScheduleServiceImpl service;

    UUID userId = UUID.randomUUID();
    UUID homeId = UUID.randomUUID();
    UUID sceneId = UUID.randomUUID();

    @BeforeEach
    void zone() {
        ReflectionTestUtils.setField(service, "zoneName", "Asia/Ho_Chi_Minh");
    }

    @Test
    void savesWeeklyScheduleWithNextRun() {
        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).build();
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(sceneSchedules.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ScheduleRequest request = request(List.of((short) 1, (short) 3));

        var response = service.saveSceneSchedule(userId, homeId, sceneId, null, request);

        assertThat(response.getScheduledTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(response.getRepeatDays()).containsExactly((short) 1, (short) 3);
        assertThat(response.getNextRunAt()).isNotNull();
        verify(authorization).requireSceneManagement(userId, homeId);
    }

    @Test
    void rejectsDuplicateOrOutOfRangeWeekdays() {
        Scene scene = Scene.builder().id(sceneId).build();
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        assertThatThrownBy(() -> service.saveSceneSchedule(userId, homeId, sceneId, null,
                request(List.of((short) 1, (short) 1)))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.saveSceneSchedule(userId, homeId, sceneId, null,
                request(List.of((short) 8)))).isInstanceOf(AppException.class);
        verifyNoInteractions(sceneSchedules);
    }

    private ScheduleRequest request(List<Short> days) {
        ScheduleRequest request = new ScheduleRequest();
        request.setScheduledTime(LocalTime.of(7, 0));
        request.setRepeatDays(days);
        request.setActive(true);
        return request;
    }
}
