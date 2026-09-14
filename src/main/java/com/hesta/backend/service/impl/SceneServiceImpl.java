package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.hesta.backend.dto.request.CreateSceneRequest;
import com.hesta.backend.dto.request.ReorderSceneActionsRequest;
import com.hesta.backend.dto.request.SceneActionRequest;
import com.hesta.backend.dto.request.UpdateSceneRequest;
import com.hesta.backend.dto.response.SceneActionResponse;
import com.hesta.backend.dto.response.SceneResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.enums.SceneActionType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SceneActionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SceneServiceImpl implements SceneService {

    private static final int MAX_SCENE_NAME_LENGTH = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_ACTION_ORDER = Short.MAX_VALUE;

    private final HomeAuthorizationService homeAuthorizationService;
    private final SceneRepository sceneRepository;
    private final SceneActionRepository sceneActionRepository;
    private final DeviceRepository deviceRepository;

    @Override
    @Transactional
    public SceneResponse createScene(UUID authenticatedUserId, UUID homeId, CreateSceneRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(authenticatedUserId, homeId);
        validateSceneRequest(request == null ? null : request.getName(),
                request == null ? null : request.getDescription(),
                request == null ? null : request.getEnabled());
        String name = request.getName().trim();
        rejectDuplicateName(homeId, name, null);

        Scene scene = Scene.builder()
                .home(home)
                .name(name)
                .description(normalizeDescription(request.getDescription()))
                .enabled(request.getEnabled())
                .build();
        scene.getActions().addAll(buildActions(scene, home, actionsOrEmpty(request.getActions())));

        return toResponse(sceneRepository.saveAndFlush(scene));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneResponse> getScenesForHome(UUID authenticatedUserId, UUID homeId) {
        homeAuthorizationService.requireAccess(authenticatedUserId, homeId);
        return sceneRepository.findAllByHomeIdOrderByNameAsc(homeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SceneResponse getScene(UUID authenticatedUserId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireAccess(authenticatedUserId, homeId);
        return toResponse(findSceneInHome(sceneId, homeId));
    }

    @Override
    @Transactional
    public SceneResponse updateScene(
            UUID authenticatedUserId,
            UUID homeId,
            UUID sceneId,
            UpdateSceneRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(authenticatedUserId, homeId);
        Scene scene = findSceneInHome(sceneId, homeId);
        validateSceneRequest(request == null ? null : request.getName(),
                request == null ? null : request.getDescription(),
                request == null ? null : request.getEnabled());
        String name = request.getName().trim();
        rejectDuplicateName(homeId, name, sceneId);

        List<SceneAction> replacementActions = null;
        if (request.getActions() != null) {
            replacementActions = buildActions(scene, home, request.getActions());
        }

        scene.setName(name);
        scene.setDescription(normalizeDescription(request.getDescription()));
        scene.setEnabled(request.getEnabled());
        if (replacementActions != null) {
            deferOrderConstraintIfNeeded(scene.getActions().size() + replacementActions.size());
            scene.getActions().clear();
            scene.getActions().addAll(replacementActions);
        }

        return toResponse(sceneRepository.saveAndFlush(scene));
    }

    @Override
    @Transactional
    public void deleteScene(UUID authenticatedUserId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireSceneManagement(authenticatedUserId, homeId);
        sceneRepository.delete(findSceneInHome(sceneId, homeId));
    }

    @Override
    @Transactional
    public SceneActionResponse addAction(
            UUID authenticatedUserId,
            UUID homeId,
            UUID sceneId,
            SceneActionRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(authenticatedUserId, homeId);
        Scene scene = findSceneInHome(sceneId, homeId);
        if (request == null || request.getOrder() == null
                || request.getOrder() < 0 || request.getOrder() > scene.getActions().size()
                || scene.getActions().size() > MAX_ACTION_ORDER) {
            throw new AppException(ErrorCode.SCENE_ACTION_ORDER_INVALID);
        }

        SceneAction newAction = buildAction(scene, home, request);
        deferOrderConstraintIfNeeded(scene.getActions().size() + 1);
        for (SceneAction existingAction : scene.getActions()) {
            if (existingAction.getOrder() >= request.getOrder()) {
                existingAction.setOrder(existingAction.getOrder() + 1);
            }
        }
        scene.getActions().add(newAction);
        sceneRepository.saveAndFlush(scene);
        return toActionResponse(newAction);
    }

    @Override
    @Transactional
    public void removeAction(UUID authenticatedUserId, UUID homeId, UUID sceneId, UUID sceneActionId) {
        homeAuthorizationService.requireSceneManagement(authenticatedUserId, homeId);
        Scene scene = findSceneInHome(sceneId, homeId);
        SceneAction action = scene.getActions().stream()
                .filter(candidate -> candidate.getId().equals(sceneActionId))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_ACTION_NOT_FOUND));

        deferOrderConstraintIfNeeded(scene.getActions().size());
        int removedOrder = action.getOrder();
        scene.getActions().remove(action);
        scene.getActions().stream()
                .filter(candidate -> candidate.getOrder() > removedOrder)
                .forEach(candidate -> candidate.setOrder(candidate.getOrder() - 1));
        sceneRepository.saveAndFlush(scene);
    }

    @Override
    @Transactional
    public SceneResponse reorderActions(
            UUID authenticatedUserId,
            UUID homeId,
            UUID sceneId,
            ReorderSceneActionsRequest request) {
        homeAuthorizationService.requireSceneManagement(authenticatedUserId, homeId);
        Scene scene = findSceneInHome(sceneId, homeId);
        List<UUID> requestedIds = request == null ? null : request.getActionIds();
        validateReorderPayload(scene.getActions(), requestedIds);

        Map<UUID, SceneAction> actionsById = new HashMap<>();
        scene.getActions().forEach(action -> actionsById.put(action.getId(), action));
        deferOrderConstraintIfNeeded(scene.getActions().size());
        for (int order = 0; order < requestedIds.size(); order++) {
            actionsById.get(requestedIds.get(order)).setOrder(order);
        }
        scene.getActions().sort(Comparator.comparingInt(SceneAction::getOrder));

        return toResponse(sceneRepository.saveAndFlush(scene));
    }

    private Scene findSceneInHome(UUID sceneId, UUID homeId) {
        if (sceneId == null) {
            throw new AppException(ErrorCode.SCENE_NOT_FOUND);
        }
        return sceneRepository.findByIdAndHomeId(sceneId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND));
    }

    private List<SceneAction> buildActions(Scene scene, Home home, List<SceneActionRequest> requests) {
        validateActionOrders(requests);
        Map<UUID, Device> devices = loadDevices(requests);
        List<SceneAction> actions = new ArrayList<>();
        requests.stream()
                .sorted(Comparator.comparingInt(SceneActionRequest::getOrder))
                .forEach(request -> actions.add(buildAction(scene, home, request, devices.get(request.getTargetDeviceId()))));
        return actions;
    }

    private SceneAction buildAction(Scene scene, Home home, SceneActionRequest request) {
        if (request == null || request.getTargetDeviceId() == null) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }
        Device device = deviceRepository.findById(request.getTargetDeviceId())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        return buildAction(scene, home, request, device);
    }

    private SceneAction buildAction(Scene scene, Home home, SceneActionRequest request, Device device) {
        if (device == null) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }
        if (!home.getId().equals(device.getHome().getId())) {
            throw new AppException(ErrorCode.SCENE_DEVICE_HOME_MISMATCH);
        }
        SceneActionType actionType = validateActionValue(request.getAction(), request.getValue());
        return SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action(actionType.name())
                .value(normalizeValue(request.getValue()))
                .order(request.getOrder())
                .build();
    }

    private Map<UUID, Device> loadDevices(List<SceneActionRequest> requests) {
        Set<UUID> deviceIds = new HashSet<>();
        for (SceneActionRequest request : requests) {
            if (request == null || request.getTargetDeviceId() == null) {
                throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
            }
            deviceIds.add(request.getTargetDeviceId());
        }
        Map<UUID, Device> devices = new HashMap<>();
        deviceRepository.findAllById(deviceIds).forEach(device -> devices.put(device.getId(), device));
        if (devices.size() != deviceIds.size()) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }
        return devices;
    }

    private void validateActionOrders(List<SceneActionRequest> requests) {
        if (requests.size() > MAX_ACTION_ORDER + 1) {
            throw new AppException(ErrorCode.SCENE_ACTIONS_INVALID);
        }
        Set<Integer> orders = new HashSet<>();
        for (SceneActionRequest request : requests) {
            if (request == null || request.getOrder() == null
                    || request.getOrder() < 0 || request.getOrder() >= requests.size()
                    || !orders.add(request.getOrder())) {
                throw new AppException(ErrorCode.SCENE_ACTIONS_INVALID);
            }
        }
    }

    private SceneActionType validateActionValue(String action, JsonNode value) {
        SceneActionType actionType = SceneActionType.from(action)
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_ACTION_INVALID));
        boolean valid = switch (actionType) {
            case TURN_ON, TURN_OFF -> value == null || value.isNull();
            case SET_BRIGHTNESS, SET_SPEED -> value != null
                    && value.isIntegralNumber()
                    && value.intValue() >= 0
                    && value.intValue() <= 100;
            case SET_TEMPERATURE -> value != null && value.isNumber();
            case SET_STATE -> value != null && value.isObject() && !value.isEmpty();
        };
        if (!valid) {
            throw new AppException(ErrorCode.SCENE_ACTION_VALUE_INVALID);
        }
        return actionType;
    }

    private JsonNode normalizeValue(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }

    private void validateReorderPayload(List<SceneAction> actions, List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.size() != actions.size()
                || new HashSet<>(requestedIds).size() != requestedIds.size()) {
            throw new AppException(ErrorCode.SCENE_REORDER_INVALID);
        }
        Set<UUID> existingIds = new HashSet<>();
        actions.forEach(action -> existingIds.add(action.getId()));
        if (!existingIds.equals(new HashSet<>(requestedIds))) {
            throw new AppException(ErrorCode.SCENE_REORDER_INVALID);
        }
    }

    private void validateSceneRequest(String name, String description, Boolean enabled) {
        if (name == null || name.isBlank() || name.trim().length() > MAX_SCENE_NAME_LENGTH) {
            throw new AppException(ErrorCode.SCENE_NAME_INVALID);
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new AppException(ErrorCode.SCENE_DESCRIPTION_INVALID);
        }
        if (enabled == null) {
            throw new AppException(ErrorCode.SCENE_ENABLED_REQUIRED);
        }
    }

    private void rejectDuplicateName(UUID homeId, String name, UUID sceneId) {
        boolean exists = sceneId == null
                ? sceneRepository.existsByHomeIdAndName(homeId, name)
                : sceneRepository.existsByHomeIdAndNameAndIdNot(homeId, name, sceneId);
        if (exists) {
            throw new AppException(ErrorCode.SCENE_NAME_ALREADY_EXISTS);
        }
    }

    private void deferOrderConstraintIfNeeded(int actionCount) {
        if (actionCount > 1) {
            sceneActionRepository.deferOrderConstraint();
        }
    }

    private List<SceneActionRequest> actionsOrEmpty(List<SceneActionRequest> actions) {
        return actions == null ? List.of() : actions;
    }

    private String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }

    private SceneResponse toResponse(Scene scene) {
        List<SceneActionResponse> actions = scene.getActions().stream()
                .sorted(Comparator.comparingInt(SceneAction::getOrder))
                .map(this::toActionResponse)
                .toList();
        return SceneResponse.builder()
                .id(scene.getId())
                .homeId(scene.getHome().getId())
                .name(scene.getName())
                .description(scene.getDescription())
                .enabled(scene.isEnabled())
                .actions(actions)
                .createdAt(scene.getCreatedAt())
                .updatedAt(scene.getUpdatedAt())
                .build();
    }

    private SceneActionResponse toActionResponse(SceneAction action) {
        return SceneActionResponse.builder()
                .id(action.getId())
                .targetDeviceId(action.getTargetDevice().getId())
                .targetDeviceName(action.getTargetDevice().getName())
                .action(action.getAction())
                .value(action.getValue())
                .order(action.getOrder())
                .createdAt(action.getCreatedAt())
                .updatedAt(action.getUpdatedAt())
                .build();
    }
}
