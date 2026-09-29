package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.CreateSceneRequest;
import com.hesta.backend.dto.request.ReorderSceneActionsRequest;
import com.hesta.backend.dto.request.SceneActionRequest;
import com.hesta.backend.dto.response.SceneActionResponse;
import com.hesta.backend.dto.response.SceneResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SceneActionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.impl.SceneServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneServiceTest {

    @Mock
    private HomeAuthorizationService homeAuthorizationService;
    @Mock
    private SceneRepository sceneRepository;
    @Mock
    private SceneActionRepository sceneActionRepository;
    @Mock
    private DeviceRepository deviceRepository;

    @InjectMocks
    private SceneServiceImpl sceneService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID userId;
    private UUID homeId;
    private UUID sceneId;
    private UUID deviceId;
    private Home home;
    private Device device;
    private Scene scene;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        sceneId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        home = Home.builder().id(homeId).name("Home").build();
        device = Device.builder().id(deviceId).name("Lamp").room(Room.builder().id(UUID.randomUUID()).home(home).name("Test Room").build()).deviceType("LIGHT").build();
        scene = Scene.builder()
                .id(sceneId)
                
                .name("Evening")
                .enabled(true)
                .actions(new ArrayList<>())
                .build();
    }

    @Test
    void ownerCreatesSceneWithSameHomeActionAtomically() {
        CreateSceneRequest request = CreateSceneRequest.builder()
                .name(" Sleep mode ")
                .description("Turn everything off")
                .enabled(true)
                .actions(List.of(actionRequest(deviceId, "TURN_OFF", null, 0)))
                .build();
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(deviceRepository.findAllById(any())).thenReturn(List.of(device));
        when(sceneRepository.saveAndFlush(any(Scene.class))).thenAnswer(invocation -> {
            Scene saved = invocation.getArgument(0);
            saved.setId(sceneId);
            saved.getActions().getFirst().setId(UUID.randomUUID());
            return saved;
        });

        SceneResponse response = sceneService.createScene(userId, homeId, request);

        assertThat(response.getName()).isEqualTo("Sleep mode");
        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().getFirst().getAction()).isEqualTo("TURN_OFF");
        assertThat(response.getActions().getFirst().getValue()).isNull();
    }

    @Test
    void detailReturnsActionsInAscendingOrder() {
        SceneAction second = persistedAction(UUID.randomUUID(), "SET_BRIGHTNESS", 1);
        SceneAction first = persistedAction(UUID.randomUUID(), "TURN_ON", 0);
        scene.setActions(new ArrayList<>(List.of(second, first)));
        when(homeAuthorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));

        SceneResponse response = sceneService.getScene(userId, homeId, sceneId);

        assertThat(response.getActions()).extracting(SceneActionResponse::getOrder).containsExactly(0, 1);
    }

    @Test
    void rejectsDeviceFromAnotherHomeUsingPersistedDevice() {
        Home anotherHome = Home.builder().id(UUID.randomUUID()).name("Other").build();
        Device foreignDevice = Device.builder()
                .id(deviceId)
                
                .name("Foreign lamp").room(Room.builder().id(UUID.randomUUID()).home(anotherHome).name("Foreign Room").build())
                .deviceType("LIGHT")
                .build();
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(foreignDevice));

        assertError(() -> sceneService.addAction(
                userId, homeId, sceneId, actionRequest(deviceId, "TURN_ON", null, 0)),
                ErrorCode.SCENE_DEVICE_HOME_MISMATCH);
        verify(sceneRepository, never()).saveAndFlush(any());
    }

    @Test
    void acceptsValidActionValueAndRejectsUnknownAction() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(sceneRepository.saveAndFlush(scene)).thenReturn(scene);

        SceneActionResponse response = sceneService.addAction(userId, homeId, sceneId,
                actionRequest(deviceId, "SET_BRIGHTNESS", objectMapper.valueToTree(75), 0));
        assertThat(response.getAction()).isEqualTo("SET_BRIGHTNESS");
        assertThat(response.getValue().intValue()).isEqualTo(75);

        Scene emptyScene = Scene.builder().id(sceneId).name("Empty").actions(new ArrayList<>()).build();
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(emptyScene));
        assertError(() -> sceneService.addAction(userId, homeId, sceneId,
                actionRequest(deviceId, "OPEN_PORTAL", null, 0)), ErrorCode.SCENE_ACTION_INVALID);
    }

    @Test
    void rejectsInvalidActionValueCombination() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertError(() -> sceneService.addAction(userId, homeId, sceneId,
                actionRequest(deviceId, "SET_BRIGHTNESS", objectMapper.valueToTree(150), 0)),
                ErrorCode.SCENE_ACTION_VALUE_INVALID);
        assertError(() -> sceneService.addAction(userId, homeId, sceneId,
                actionRequest(deviceId, "TURN_ON", objectMapper.valueToTree(true), 0)),
                ErrorCode.SCENE_ACTION_VALUE_INVALID);
    }

    @Test
    void addingActionAtPositionShiftsExistingActions() {
        SceneAction existing = persistedAction(UUID.randomUUID(), "TURN_OFF", 0);
        scene.getActions().add(existing);
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(sceneRepository.saveAndFlush(scene)).thenReturn(scene);

        sceneService.addAction(userId, homeId, sceneId, actionRequest(deviceId, "TURN_ON", null, 0));

        assertThat(existing.getOrder()).isEqualTo(1);
        assertThat(scene.getActions()).extracting(SceneAction::getOrder).containsExactlyInAnyOrder(0, 1);
        verify(sceneActionRepository).deferOrderConstraint();
    }

    @Test
    void removingActionCompactsRemainingOrder() {
        SceneAction first = persistedAction(UUID.randomUUID(), "TURN_ON", 0);
        SceneAction removed = persistedAction(UUID.randomUUID(), "SET_BRIGHTNESS", 1);
        SceneAction last = persistedAction(UUID.randomUUID(), "TURN_OFF", 2);
        scene.setActions(new ArrayList<>(List.of(first, removed, last)));
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(sceneRepository.saveAndFlush(scene)).thenReturn(scene);

        sceneService.removeAction(userId, homeId, sceneId, removed.getId());

        assertThat(scene.getActions()).extracting(SceneAction::getId).containsExactly(first.getId(), last.getId());
        assertThat(scene.getActions()).extracting(SceneAction::getOrder).containsExactly(0, 1);
    }

    @Test
    void reorderIsCompleteDeterministicAndAtomic() {
        SceneAction first = persistedAction(UUID.randomUUID(), "TURN_ON", 0);
        SceneAction second = persistedAction(UUID.randomUUID(), "SET_BRIGHTNESS", 1);
        SceneAction third = persistedAction(UUID.randomUUID(), "TURN_OFF", 2);
        scene.setActions(new ArrayList<>(List.of(first, second, third)));
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(sceneRepository.saveAndFlush(scene)).thenReturn(scene);
        ReorderSceneActionsRequest request = ReorderSceneActionsRequest.builder()
                .actionIds(List.of(third.getId(), first.getId(), second.getId()))
                .build();

        SceneResponse response = sceneService.reorderActions(userId, homeId, sceneId, request);

        assertThat(response.getActions()).extracting(SceneActionResponse::getId)
                .containsExactly(third.getId(), first.getId(), second.getId());
        assertThat(response.getActions()).extracting(SceneActionResponse::getOrder).containsExactly(0, 1, 2);
        verify(sceneActionRepository).deferOrderConstraint();
        verify(sceneRepository).saveAndFlush(scene);
    }

    @Test
    void invalidReorderDoesNotPersistPartialChanges() {
        SceneAction first = persistedAction(UUID.randomUUID(), "TURN_ON", 0);
        SceneAction second = persistedAction(UUID.randomUUID(), "TURN_OFF", 1);
        scene.setActions(new ArrayList<>(List.of(first, second)));
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        ReorderSceneActionsRequest request = ReorderSceneActionsRequest.builder()
                .actionIds(List.of(first.getId(), first.getId()))
                .build();

        assertError(() -> sceneService.reorderActions(userId, homeId, sceneId, request),
                ErrorCode.SCENE_REORDER_INVALID);
        assertThat(scene.getActions()).extracting(SceneAction::getOrder).containsExactly(0, 1);
        verify(sceneRepository, never()).saveAndFlush(any());
    }

    @Test
    void authorizationFailureStopsMutationBeforeSceneLookup() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId))
                .thenThrow(new AppException(ErrorCode.UNAUTHORIZED));

        assertError(() -> sceneService.deleteScene(userId, homeId, sceneId), ErrorCode.UNAUTHORIZED);

        verify(sceneRepository, never()).findByIdAndHomeId(any(), any());
    }

    private SceneActionRequest actionRequest(
            UUID targetDeviceId,
            String action,
            com.fasterxml.jackson.databind.JsonNode value,
            int order) {
        return SceneActionRequest.builder()
                .targetDeviceId(targetDeviceId)
                .action(action)
                .value(value)
                .order(order)
                .build();
    }

    private SceneAction persistedAction(UUID id, String action, int order) {
        return SceneAction.builder()
                .id(id)
                .scene(scene)
                .targetDevice(device)
                .action(action)
                .order(order)
                .build();
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, ErrorCode errorCode) {
        assertThatThrownBy(operation)
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}
