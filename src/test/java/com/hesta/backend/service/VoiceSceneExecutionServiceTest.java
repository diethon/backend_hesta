package com.hesta.backend.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.response.VoiceSceneExecutionResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.SceneExecutionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.execution.CommandBatchRunner;
import com.hesta.backend.service.execution.CommandBatchRunner.PlannedCommand;
import com.hesta.backend.service.impl.VoiceSceneExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoiceSceneExecutionServiceTest {

    @Mock private SceneRepository sceneRepository;
    @Mock private SceneExecutionRepository executionRepository;
    @Mock private HomeAuthorizationService homeAuthorizationService;
    @Mock private HomeMemberRepository homeMemberRepository;
    @Mock private CommandBatchRunner commandBatchRunner;
    @Mock private BehaviorEventRecorder behaviorEventRecorder;

    @InjectMocks
    private VoiceSceneExecutionService service;

    private UUID homeId;
    private UUID userId;
    private Home home;
    private Device light;
    private Device ac;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        userId = UUID.randomUUID();
        home = Home.builder().id(homeId).name("Test Home").build();
        light = Device.builder()
                .id(UUID.randomUUID())
                .name("Đèn phòng khách")
                .deviceType("LIGHT")
                .status(DeviceStatus.ONLINE)
                .currentState(Map.of("power", "OFF"))
                .build();
        ac = Device.builder()
                .id(UUID.randomUUID())
                .name("Điều hòa")
                .deviceType("AIR_CONDITIONER")
                .status(DeviceStatus.ONLINE)
                .currentState(Map.of("power", "OFF"))
                .build();
    }

    @Test
    void executeFromVoice_skipsDevicesAlreadyInTargetState() {
        light.setCurrentState(Map.of("power", "ON"));
        Scene scene = Scene.builder()
                .id(UUID.randomUUID())
                .home(home)
                .name("Chào buổi sáng")
                .enabled(true)
                .actions(List.of(
                        SceneAction.builder().targetDevice(light).action("TURN_ON").order(0).build()
                ))
                .build();

        when(sceneRepository.findByIdAndHomeId(scene.getId(), homeId)).thenReturn(Optional.of(scene));

        VoiceSceneExecutionResult res = service.executeFromVoice(userId, homeId, scene.getId());

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getStatus()).isEqualTo("ALREADY_IN_STATE");
        assertThat(res.getAlreadyInStateCount()).isEqualTo(1);
        assertThat(res.getExecutedCount()).isZero();
        verify(commandBatchRunner, never()).run(any(), any());
    }

    @Test
    void executeFromVoice_resolvesIntraSceneConflictWithLastActionWins() {
        Scene scene = Scene.builder()
                .id(UUID.randomUUID())
                .home(home)
                .name("Kịch bản trùng")
                .enabled(true)
                .actions(List.of(
                        SceneAction.builder().targetDevice(light).action("TURN_ON").order(0).build(),
                        SceneAction.builder().targetDevice(light).action("TURN_OFF").order(1).build()
                ))
                .build();
        // Đèn đang bật để lệnh TURN_OFF thực sự được gửi đi
        light.setCurrentState(Map.of("power", "ON"));

        when(sceneRepository.findByIdAndHomeId(scene.getId(), homeId)).thenReturn(Optional.of(scene));
        when(commandBatchRunner.run(any(), eq(StateChangeSource.VOICE))).thenAnswer(inv -> {
            List<PlannedCommand> cmds = inv.getArgument(0);
            for (PlannedCommand c : cmds) {
                ObjectNode d = c.detail();
                d.put("success", true);
                d.put("status", "SUCCESS");
            }
            return cmds.size();
        });

        VoiceSceneExecutionResult res = service.executeFromVoice(userId, homeId, scene.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlannedCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandBatchRunner).run(captor.capture(), eq(StateChangeSource.VOICE));
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).action()).isEqualTo("TURN_OFF");
        assertThat(res.getConflictResolvedCount()).isEqualTo(1);
        assertThat(res.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void executeFromVoice_tracksCrossSceneOverrideWhenNewSceneFlipsDevice() {
        Scene morning = Scene.builder()
                .id(UUID.randomUUID())
                .home(home)
                .name("Chào buổi sáng")
                .enabled(true)
                .actions(List.of(SceneAction.builder().targetDevice(light).action("TURN_ON").order(0).build()))
                .build();
        Scene sleep = Scene.builder()
                .id(UUID.randomUUID())
                .home(home)
                .name("Đi ngủ")
                .enabled(true)
                .actions(List.of(SceneAction.builder().targetDevice(light).action("TURN_OFF").order(0).build()))
                .build();

        when(sceneRepository.findByIdAndHomeId(morning.getId(), homeId)).thenReturn(Optional.of(morning));
        when(sceneRepository.findByIdAndHomeId(sleep.getId(), homeId)).thenReturn(Optional.of(sleep));
        when(commandBatchRunner.run(any(), eq(StateChangeSource.VOICE))).thenAnswer(inv -> {
            List<PlannedCommand> cmds = inv.getArgument(0);
            for (PlannedCommand c : cmds) {
                c.detail().put("success", true);
            }
            return cmds.size();
        });

        // 1. Chạy Chào buổi sáng (bật đèn)
        VoiceSceneExecutionResult r1 = service.executeFromVoice(userId, homeId, morning.getId());
        assertThat(r1.isSuccess()).isTrue();

        // Cập nhật trạng thái đèn đang bật
        light.setCurrentState(Map.of("power", "ON"));

        // 2. Chạy Đi ngủ (tắt đèn) -> phải ưu tiên Đi ngủ và ghi nhận ghi đè Chào buổi sáng
        VoiceSceneExecutionResult r2 = service.executeFromVoice(userId, homeId, sleep.getId());
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.getOverriddenSceneName()).isEqualTo("Chào buổi sáng");
        assertThat(r2.getOverriddenCount()).isEqualTo(1);
    }

    @Test
    void deactivateFromVoice_usesSafeInverseAndDoesNotTurnOnPreviouslyOffDevices() {
        ac.setCurrentState(Map.of("power", "ON"));
        light.setCurrentState(Map.of("power", "OFF"));

        Scene nap = Scene.builder()
                .id(UUID.randomUUID())
                .home(home)
                .name("Nghỉ trưa")
                .enabled(true)
                .actions(List.of(
                        SceneAction.builder().targetDevice(ac).action("TURN_ON").order(0).build(),
                        SceneAction.builder().targetDevice(light).action("TURN_OFF").order(1).build()
                ))
                .build();

        when(sceneRepository.findByIdAndHomeId(nap.getId(), homeId)).thenReturn(Optional.of(nap));
        when(commandBatchRunner.run(any(), eq(StateChangeSource.VOICE))).thenAnswer(inv -> {
            List<PlannedCommand> cmds = inv.getArgument(0);
            for (PlannedCommand c : cmds) {
                c.detail().put("success", true);
            }
            return cmds.size();
        });

        VoiceSceneExecutionResult res = service.deactivateFromVoice(userId, homeId, nap.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlannedCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandBatchRunner).run(captor.capture(), eq(StateChangeSource.VOICE));
        // Chỉ tắt điều hòa (được bật bởi kịch bản), KHÔNG tự bật lại đèn (vốn bị tắt trong kịch bản)
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).device().getId()).isEqualTo(ac.getId());
        assertThat(captor.getValue().get(0).action()).isEqualTo("TURN_OFF");
        assertThat(res.getSkippedCount()).isEqualTo(1);
        assertThat(res.isSuccess()).isTrue();
    }

    @Test
    void executeFromVoice_customMemberWithoutScenePermission_throwsUnauthorized() {
        Scene scene = Scene.builder()
                .id(UUID.randomUUID())
                .home(home)
                .name("Đi ngủ")
                .enabled(true)
                .build();

        when(sceneRepository.findByIdAndHomeId(scene.getId(), homeId)).thenReturn(Optional.of(scene));
        when(homeAuthorizationService.loadAccessContext(homeId, userId))
                .thenReturn(new MemberAccessContext(
                        UUID.randomUUID(),
                        homeId,
                        userId,
                        com.hesta.backend.enums.HomeRole.MEMBER,
                        com.hesta.backend.enums.MemberStatus.ACTIVE,
                        AccessScope.CUSTOM,
                        Map.of(),
                        Map.of()
                ));
        when(homeAuthorizationService.canRunScene(userId, scene.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.executeFromVoice(userId, homeId, scene.getId()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
