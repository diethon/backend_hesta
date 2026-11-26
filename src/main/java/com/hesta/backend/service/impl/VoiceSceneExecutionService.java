package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.response.VoiceSceneExecutionResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.SceneExecution;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.ExecutionStatus;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.SceneExecutionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.service.SceneExecutionService;
import com.hesta.backend.service.execution.CommandBatchRunner;
import com.hesta.backend.service.execution.CommandBatchRunner.PlannedCommand;
import com.hesta.backend.service.execution.DeviceActionValues;
import com.hesta.backend.service.execution.DeviceActionValues.DeviceCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thực thi và tắt Kịch bản (Scene) từ Voice AI với cơ chế xử lý xung đột tối ưu:
 * 1. Intra-scene Conflict Resolution (Last-Action-Wins theo order cho cùng 1 thiết bị).
 * 2. Idempotent / Already-In-State Check (bỏ qua thiết bị đã ở đúng trạng thái mục tiêu).
 * 3. Cross-scene Priority Tracking (Last-Intent-Wins khi kích hoạt kịch bản mới đè kịch bản đang chạy).
 * 4. Safe-Inverse Deactivation (tắt an toàn các thiết bị được bật/mở bởi kịch bản, không tự ý bật thiết bị đã tắt).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceSceneExecutionService {

    private static final Set<String> ACTIVE_TURN_ON_ACTIONS = Set.of(
            "TURN_ON", "POWER_ON", "SET_BRIGHTNESS", "SET_COLOR", "SET_RGB",
            "SET_TEMPERATURE", "SET_SPEED", "SET_FAN", "SET_MODE", "SET_SWING"
    );

    private final SceneRepository sceneRepository;
    private final SceneExecutionRepository executionRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final HomeMemberRepository homeMemberRepository;
    private final CommandBatchRunner commandBatchRunner;
    private final BehaviorEventRecorder behaviorEventRecorder;

    @Autowired(required = false)
    private SceneExecutionService sceneExecutionService;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    private record ActiveSceneState(
            UUID sceneId,
            String sceneName,
            OffsetDateTime activatedAt,
            Map<UUID, String> deviceEffectiveActions
    ) {}

    /** Theo dõi kịch bản gần nhất đang hoạt động cho từng nhà để phát hiện ghi đè cross-scene. */
    private final Map<UUID, ActiveSceneState> activeScenesByHome = new ConcurrentHashMap<>();

    public VoiceSceneExecutionResult executeFromVoice(UUID userId, UUID homeId, UUID sceneId) {
        OffsetDateTime startedAt = OffsetDateTime.now();

        ScenePlan plan = inTx(() -> {
            Scene scene = loadAndAuthorize(userId, homeId, sceneId);
            if (!scene.isEnabled()) {
                throw new AppException(ErrorCode.SCENE_DISABLED);
            }
            return buildActivatePlan(scene);
        });

        int succeeded = plan.commands().isEmpty()
                ? 0
                : commandBatchRunner.run(plan.commands(), StateChangeSource.VOICE);

        // Phân loại lỗi sau khi chạy batch (offline vs failed)
        int offlineCount = 0;
        int failedCount = 0;
        for (PlannedCommand cmd : plan.commands()) {
            ObjectNode d = cmd.detail();
            if (!d.path("success").asBoolean(false)) {
                String errCode = d.path("errorCode").asText("");
                if ("DEVICE_OFFLINE".equalsIgnoreCase(errCode) || "GATEWAY_DISCONNECTED".equalsIgnoreCase(errCode)) {
                    offlineCount++;
                } else {
                    failedCount++;
                }
            }
        }

        // Ghi nhận active scene khi có ít nhất 1 thiết bị thực thi thành công hoặc đã ở đúng trạng thái
        if (succeeded > 0 || (plan.commands().isEmpty() && plan.alreadyInStateCount() > 0)) {
            activeScenesByHome.put(plan.homeId(), new ActiveSceneState(
                    plan.sceneId(),
                    plan.sceneName(),
                    OffsetDateTime.now(),
                    plan.deviceEffectiveActions()
            ));
        }

        String status = resolveStatus(
                plan.totalDevices(),
                plan.commands().size(),
                succeeded,
                plan.alreadyInStateCount(),
                offlineCount,
                failedCount
        );
        boolean success = "SUCCESS".equals(status) || "ALREADY_IN_STATE".equals(status) || "PARTIAL".equals(status);
        String errorCode = resolveErrorCode(status, offlineCount, failedCount);

        // Lưu lịch sử SceneExecution với triggerSource = VOICE
        saveExecutionHistory(plan.homeId(), plan.sceneId(), userId, "VOICE", startedAt, status, plan.details());

        return VoiceSceneExecutionResult.builder()
                .sceneId(plan.sceneId())
                .sceneName(plan.sceneName())
                .mode("ACTIVATE")
                .status(status)
                .success(success)
                .totalDevices(plan.totalDevices())
                .executedCount(succeeded)
                .alreadyInStateCount(plan.alreadyInStateCount())
                .conflictResolvedCount(plan.conflictResolvedCount())
                .overriddenCount(plan.overriddenCount())
                .overriddenSceneName(plan.overriddenSceneName())
                .offlineCount(offlineCount)
                .failedCount(failedCount)
                .skippedCount(plan.skippedCount())
                .errorCode(errorCode)
                .resultDetail(plan.details())
                .build();
    }

    public VoiceSceneExecutionResult deactivateFromVoice(UUID userId, UUID homeId, UUID sceneId) {
        OffsetDateTime startedAt = OffsetDateTime.now();

        ScenePlan plan = inTx(() -> {
            Scene scene = loadAndAuthorize(userId, homeId, sceneId);
            return buildDeactivatePlan(scene);
        });

        // Hủy hẹn giờ run-later nếu có
        if (sceneExecutionService != null) {
            try {
                sceneExecutionService.cancelRunLater(sceneId);
            } catch (Exception e) {
                log.debug("Could not cancel pending runLater for scene {}: {}", sceneId, e.getMessage());
            }
        }

        int succeeded = plan.commands().isEmpty()
                ? 0
                : commandBatchRunner.run(plan.commands(), StateChangeSource.VOICE);

        int offlineCount = 0;
        int failedCount = 0;
        for (PlannedCommand cmd : plan.commands()) {
            ObjectNode d = cmd.detail();
            if (!d.path("success").asBoolean(false)) {
                String errCode = d.path("errorCode").asText("");
                if ("DEVICE_OFFLINE".equalsIgnoreCase(errCode) || "GATEWAY_DISCONNECTED".equalsIgnoreCase(errCode)) {
                    offlineCount++;
                } else {
                    failedCount++;
                }
            }
        }

        // Gỡ trạng thái active nếu kịch bản này đang là active scene của home
        activeScenesByHome.computeIfPresent(plan.homeId(), (k, curr) ->
                curr.sceneId().equals(sceneId) ? null : curr
        );

        String status = resolveStatus(
                plan.totalDevices(),
                plan.commands().size(),
                succeeded,
                plan.alreadyInStateCount(),
                offlineCount,
                failedCount
        );
        boolean success = "SUCCESS".equals(status) || "ALREADY_IN_STATE".equals(status)
                || "PARTIAL".equals(status) || "SKIPPED".equals(status);
        String errorCode = resolveErrorCode(status, offlineCount, failedCount);

        saveExecutionHistory(plan.homeId(), plan.sceneId(), userId, "VOICE_DEACTIVATE", startedAt, status, plan.details());

        return VoiceSceneExecutionResult.builder()
                .sceneId(plan.sceneId())
                .sceneName(plan.sceneName())
                .mode("DEACTIVATE")
                .status(status)
                .success(success)
                .totalDevices(plan.totalDevices())
                .executedCount(succeeded)
                .alreadyInStateCount(plan.alreadyInStateCount())
                .conflictResolvedCount(plan.conflictResolvedCount())
                .overriddenCount(0)
                .overriddenSceneName(null)
                .offlineCount(offlineCount)
                .failedCount(failedCount)
                .skippedCount(plan.skippedCount())
                .errorCode(errorCode)
                .resultDetail(plan.details())
                .build();
    }

    // ------------------------------------------------------------------
    // Authorization & Loading
    // ------------------------------------------------------------------

    private Scene loadAndAuthorize(UUID userId, UUID homeId, UUID sceneId) {
        Scene scene = homeId != null
                ? sceneRepository.findByIdAndHomeId(sceneId, homeId)
                        .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND))
                : sceneRepository.findById(sceneId)
                        .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND));

        UUID resolvedHomeId = scene.getHome().getId();
        if (userId != null) {
            homeAuthorizationService.requireAccess(userId, resolvedHomeId);
            MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(resolvedHomeId, userId);
            if (ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM) {
                if (!homeAuthorizationService.canRunScene(userId, sceneId)) {
                    throw new AppException(ErrorCode.UNAUTHORIZED);
                }
            }
        } else {
            boolean hasCustomMembers = homeMemberRepository.findByHomeId(resolvedHomeId)
                    .stream()
                    .anyMatch(m -> m.getStatus() == MemberStatus.ACTIVE
                            && m.getRole() != HomeRole.OWNER
                            && m.getAccessScope() == AccessScope.CUSTOM);
            if (hasCustomMembers) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }
        return scene;
    }

    // ------------------------------------------------------------------
    // Planning + Conflict Resolution
    // ------------------------------------------------------------------

    private record ScenePlan(
            UUID homeId,
            UUID sceneId,
            String sceneName,
            int totalDevices,
            int alreadyInStateCount,
            int conflictResolvedCount,
            int overriddenCount,
            String overriddenSceneName,
            int skippedCount,
            Map<UUID, String> deviceEffectiveActions,
            ArrayNode details,
            List<PlannedCommand> commands
    ) {}

    private ScenePlan buildActivatePlan(Scene scene) {
        UUID homeId = scene.getHome().getId();
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        List<PlannedCommand> commands = new ArrayList<>();

        List<SceneAction> sortedActions = scene.getActions().stream()
                .sorted(Comparator.comparingInt(SceneAction::getOrder))
                .toList();

        // Bước 1: Giải quyết xung đột nội bộ (Intra-scene Conflict Resolution — Last-Action-Wins)
        // Gom các lệnh đã mở rộng theo từng thiết bị
        Map<UUID, Device> deviceMap = new LinkedHashMap<>();
        Map<UUID, List<DeviceCommand>> rawByDevice = new LinkedHashMap<>();

        for (SceneAction action : sortedActions) {
            Device dev = action.getTargetDevice();
            if (dev == null || dev.isDeleted()) continue;
            deviceMap.putIfAbsent(dev.getId(), dev);
            rawByDevice.computeIfAbsent(dev.getId(), ignored -> new ArrayList<>())
                    .addAll(toCommands(action));
        }

        int conflictResolvedCount = 0;
        int alreadyInStateCount = 0;
        int overriddenCount = 0;
        Map<UUID, String> effectiveActions = new LinkedHashMap<>();

        ActiveSceneState prevActive = activeScenesByHome.get(homeId);
        String overriddenSceneName = null;

        for (Map.Entry<UUID, List<DeviceCommand>> entry : rawByDevice.entrySet()) {
            UUID devId = entry.getKey();
            Device device = deviceMap.get(devId);
            List<DeviceCommand> devCmds = entry.getValue();

            // Khử xung đột nội bộ trên cùng 1 thiết bị
            List<DeviceCommand> resolved = resolveIntraDeviceConflicts(devId, devCmds, details);
            if (resolved.size() < devCmds.size()) {
                conflictResolvedCount += (devCmds.size() - resolved.size());
            }

            // Xác định ý định cuối cùng của thiết bị để track cross-scene override
            String finalSemantic = resolveFinalSemanticAction(resolved);
            effectiveActions.put(devId, finalSemantic);

            if (prevActive != null && !prevActive.sceneId().equals(scene.getId())) {
                String prevSemantic = prevActive.deviceEffectiveActions().get(devId);
                if (prevSemantic != null && isOppositeSemantic(prevSemantic, finalSemantic)) {
                    overriddenCount++;
                    overriddenSceneName = prevActive.sceneName();
                }
            }

            // Bước 2: Kiểm tra trùng trạng thái hiện tại (Idempotent Check)
            boolean allMatched = !resolved.isEmpty() && device.getStatus() == DeviceStatus.ONLINE;
            if (allMatched) {
                for (DeviceCommand cmd : resolved) {
                    if (!isDeviceAlreadyInTargetState(device, cmd)) {
                        allMatched = false;
                        break;
                    }
                }
            }

            if (allMatched) {
                alreadyInStateCount++;
                for (DeviceCommand cmd : resolved) {
                    ObjectNode row = CommandBatchRunner.detail(devId, cmd.action());
                    row.put("success", true);
                    row.put("status", "ALREADY_IN_STATE");
                    row.put("deviceName", device.getName());
                    details.add(row);
                }
                continue;
            }

            // Lên lịch gửi lệnh thực tế
            for (DeviceCommand cmd : resolved) {
                ObjectNode row = CommandBatchRunner.detail(devId, cmd.action());
                row.put("deviceName", device.getName());
                details.add(row);
                commands.add(new PlannedCommand(device, cmd.action(), cmd.parameters(), row));
            }
        }

        return new ScenePlan(
                homeId,
                scene.getId(),
                scene.getName(),
                deviceMap.size(),
                alreadyInStateCount,
                conflictResolvedCount,
                overriddenCount,
                overriddenSceneName,
                0,
                effectiveActions,
                details,
                commands
        );
    }

    private ScenePlan buildDeactivatePlan(Scene scene) {
        UUID homeId = scene.getHome().getId();
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        List<PlannedCommand> commands = new ArrayList<>();

        List<SceneAction> sortedActions = scene.getActions().stream()
                .sorted(Comparator.comparingInt(SceneAction::getOrder))
                .toList();

        Map<UUID, Device> deviceMap = new LinkedHashMap<>();
        Map<UUID, List<DeviceCommand>> rawByDevice = new LinkedHashMap<>();

        for (SceneAction action : sortedActions) {
            Device dev = action.getTargetDevice();
            if (dev == null || dev.isDeleted()) continue;
            deviceMap.putIfAbsent(dev.getId(), dev);
            rawByDevice.computeIfAbsent(dev.getId(), ignored -> new ArrayList<>())
                    .addAll(toCommands(action));
        }

        int alreadyInStateCount = 0;
        int skippedCount = 0;
        int targetDeviceCount = 0;

        for (Map.Entry<UUID, List<DeviceCommand>> entry : rawByDevice.entrySet()) {
            UUID devId = entry.getKey();
            Device device = deviceMap.get(devId);
            List<DeviceCommand> resolved = resolveIntraDeviceConflicts(devId, entry.getValue(), JsonNodeFactory.instance.arrayNode());
            String finalSemantic = resolveFinalSemanticAction(resolved);

            // Safe Inverse: chỉ tắt thiết bị được kịch bản BẬT, hoặc đóng rèm được kịch bản MỞ.
            // Không tự ý bật lại thiết bị vốn bị tắt để đảm bảo an toàn điện & an ninh!
            DeviceCommand inverseCmd = computeSafeInverse(device, finalSemantic);
            if (inverseCmd == null) {
                skippedCount++;
                ObjectNode row = CommandBatchRunner.detail(devId, "NO_OP");
                row.put("deviceName", device.getName());
                CommandBatchRunner.skip(row, "SKIPPED_SAFE_INVERSE");
                details.add(row);
                continue;
            }

            targetDeviceCount++;
            if (device.getStatus() == DeviceStatus.ONLINE && isDeviceAlreadyInTargetState(device, inverseCmd)) {
                alreadyInStateCount++;
                ObjectNode row = CommandBatchRunner.detail(devId, inverseCmd.action());
                row.put("success", true);
                row.put("status", "ALREADY_IN_STATE");
                row.put("deviceName", device.getName());
                details.add(row);
                continue;
            }

            ObjectNode row = CommandBatchRunner.detail(devId, inverseCmd.action());
            row.put("deviceName", device.getName());
            details.add(row);
            commands.add(new PlannedCommand(device, inverseCmd.action(), inverseCmd.parameters(), row));
        }

        return new ScenePlan(
                homeId,
                scene.getId(),
                scene.getName(),
                targetDeviceCount,
                alreadyInStateCount,
                0,
                0,
                null,
                skippedCount,
                Map.of(),
                details,
                commands
        );
    }

    /**
     * Khử xung đột nội bộ trên cùng 1 thiết bị theo nguyên tắc Last-Action-Wins:
     * - Nếu có lệnh TURN_OFF đứng sau cùng → bỏ hết các lệnh bật/chỉnh sáng/nhiệt đứng trước.
     * - Nếu có nhiều lệnh cùng loại (VD: 2 lệnh SET_TEMPERATURE, 2 lệnh TURN_ON/TURN_OFF, 2 lệnh OPEN/CLOSE)
     *   → chỉ giữ lệnh sau cùng, đánh dấu lệnh trước là SKIPPED_CONFLICT.
     */
    private List<DeviceCommand> resolveIntraDeviceConflicts(
            UUID deviceId,
            List<DeviceCommand> cmds,
            ArrayNode details
    ) {
        if (cmds.size() <= 1) return cmds;

        // Xác định chỉ số lệnh TURN_OFF cuối cùng (nếu đứng sau mọi lệnh bật/chỉnh thì toàn bộ lệnh trước bị hủy)
        int lastOffIdx = -1;
        for (int i = 0; i < cmds.size(); i++) {
            String norm = DeviceActionValues.normalize(cmds.get(i).action());
            if ("TURN_OFF".equals(norm) || "POWER_OFF".equals(norm)
                    || ("SET_POWER".equals(norm) && !DeviceActionValues.turnsOn(norm, cmds.get(i).parameters()))) {
                lastOffIdx = i;
            }
        }

        if (lastOffIdx == cmds.size() - 1) {
            for (int i = 0; i < lastOffIdx; i++) {
                details.add(CommandBatchRunner.skip(
                        CommandBatchRunner.detail(deviceId, cmds.get(i).action()),
                        "SKIPPED_CONFLICT"
                ));
            }
            return List.of(cmds.get(lastOffIdx));
        }

        // Khử trùng/xung đột theo nhóm thuộc tính (POWER, GATE, BRIGHTNESS, TEMPERATURE, COLOR, SPEED, MODE)
        Map<String, Integer> lastIndexByGroup = new LinkedHashMap<>();
        for (int i = 0; i < cmds.size(); i++) {
            lastIndexByGroup.put(conflictGroupOf(cmds.get(i).action()), i);
        }

        List<DeviceCommand> kept = new ArrayList<>();
        for (int i = 0; i < cmds.size(); i++) {
            String group = conflictGroupOf(cmds.get(i).action());
            if (lastIndexByGroup.get(group) == i) {
                kept.add(cmds.get(i));
            } else {
                details.add(CommandBatchRunner.skip(
                        CommandBatchRunner.detail(deviceId, cmds.get(i).action()),
                        "SKIPPED_CONFLICT"
                ));
            }
        }
        return kept;
    }

    private static String conflictGroupOf(String action) {
        String norm = DeviceActionValues.normalize(action);
        if (DeviceActionValues.POWER_ACTIONS.contains(norm)) return "POWER";
        if (DeviceActionValues.GATE_ACTIONS.contains(norm)) return "GATE";
        if ("SET_COLOR".equals(norm) || "SET_RGB".equals(norm)) return "COLOR";
        if ("SET_SPEED".equals(norm) || "SET_FAN".equals(norm)) return "SPEED";
        return norm;
    }

    private static String resolveFinalSemanticAction(List<DeviceCommand> resolved) {
        if (resolved.isEmpty()) return "NONE";
        String semantic = "OTHER";
        for (DeviceCommand cmd : resolved) {
            String norm = DeviceActionValues.normalize(cmd.action());
            if ("TURN_OFF".equals(norm) || "POWER_OFF".equals(norm)
                    || ("SET_POWER".equals(norm) && !DeviceActionValues.turnsOn(norm, cmd.parameters()))) {
                semantic = "OFF";
            } else if (ACTIVE_TURN_ON_ACTIONS.contains(norm)
                    || ("SET_POWER".equals(norm) && DeviceActionValues.turnsOn(norm, cmd.parameters()))) {
                semantic = "ON";
            } else if ("OPEN".equals(norm) || "GATE_OPEN".equals(norm)) {
                semantic = "OPEN";
            } else if ("CLOSE".equals(norm) || "GATE_CLOSE".equals(norm)) {
                semantic = "CLOSE";
            }
        }
        return semantic;
    }

    private static boolean isOppositeSemantic(String prev, String next) {
        return ("ON".equals(prev) && "OFF".equals(next))
                || ("OFF".equals(prev) && "ON".equals(next))
                || ("OPEN".equals(prev) && "CLOSE".equals(next))
                || ("CLOSE".equals(prev) && "OPEN".equals(next));
    }

    private static DeviceCommand computeSafeInverse(Device device, String semantic) {
        if ("ON".equals(semantic)) {
            return new DeviceCommand("TURN_OFF", Map.of());
        }
        if ("OPEN".equals(semantic)) {
            String type = device.getDeviceType() == null ? "" : device.getDeviceType().toLowerCase(Locale.ROOT);
            // Đảo ngược an toàn cho rèm & cổng khi kịch bản chủ đích mở
            if (type.contains("curtain") || type.contains("gate") || type.contains("rolling")) {
                return new DeviceCommand("CLOSE", Map.of());
            }
        }
        return null;
    }

    /**
     * Kiểm tra thiết bị hiện tại đã ở đúng trạng thái mục tiêu chưa để bỏ qua gói tin MQTT thừa.
     */
    private static boolean isDeviceAlreadyInTargetState(Device device, DeviceCommand cmd) {
        Map<String, Object> state = device.getCurrentState();
        if (state == null || state.isEmpty()) return false;

        String norm = DeviceActionValues.normalize(cmd.action());
        Object powerVal = state.containsKey("power") ? state.get("power") : state.get("on");

        if ("TURN_ON".equals(norm) || "POWER_ON".equals(norm)) {
            return powerVal != null && DeviceActionValues.isOn(powerVal);
        }
        if ("TURN_OFF".equals(norm) || "POWER_OFF".equals(norm)) {
            return powerVal != null && !DeviceActionValues.isOn(powerVal);
        }
        if ("SET_POWER".equals(norm)) {
            boolean wantOn = DeviceActionValues.turnsOn(norm, cmd.parameters());
            return powerVal != null && DeviceActionValues.isOn(powerVal) == wantOn;
        }
        if ("SET_BRIGHTNESS".equals(norm)) {
            if (powerVal != null && !DeviceActionValues.isOn(powerVal)) return false;
            Object cur = state.get("brightness") != null ? state.get("brightness") : state.get("level");
            Object want = cmd.parameters().get("brightness");
            return numbersEqual(cur, want);
        }
        if ("SET_TEMPERATURE".equals(norm)) {
            if (powerVal != null && !DeviceActionValues.isOn(powerVal)) return false;
            Object cur = state.get("temperature") != null ? state.get("temperature") : state.get("temp");
            Object want = cmd.parameters().get("temperature");
            return numbersEqual(cur, want);
        }
        if ("OPEN".equals(norm) || "GATE_OPEN".equals(norm)) {
            Object curState = state.get("state") != null ? state.get("state") : state.get("gate");
            if (curState != null && ("OPEN".equalsIgnoreCase(curState.toString()) || "OPENED".equalsIgnoreCase(curState.toString()))) {
                return true;
            }
            Object pos = state.get("position");
            return numbersEqual(pos, 100);
        }
        if ("CLOSE".equals(norm) || "GATE_CLOSE".equals(norm)) {
            Object curState = state.get("state") != null ? state.get("state") : state.get("gate");
            if (curState != null && ("CLOSE".equalsIgnoreCase(curState.toString()) || "CLOSED".equalsIgnoreCase(curState.toString()))) {
                return true;
            }
            Object pos = state.get("position");
            return numbersEqual(pos, 0);
        }
        return false;
    }

    private static boolean numbersEqual(Object a, Object b) {
        if (a == null || b == null) return false;
        try {
            return Math.abs(Double.parseDouble(a.toString()) - Double.parseDouble(b.toString())) < 0.01;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String resolveStatus(
            int totalDevices,
            int attemptedCommands,
            int succeededCommands,
            int alreadyInStateCount,
            int offlineCount,
            int failedCount
    ) {
        if (totalDevices == 0) return "SKIPPED";
        if (attemptedCommands == 0 && alreadyInStateCount > 0) return "ALREADY_IN_STATE";
        if (attemptedCommands == 0) return "SKIPPED";
        if (offlineCount == 0 && failedCount == 0 && succeededCommands == attemptedCommands) return "SUCCESS";
        if (succeededCommands > 0 || alreadyInStateCount > 0) return "PARTIAL";
        return "FAILED";
    }

    private static String resolveErrorCode(String status, int offlineCount, int failedCount) {
        if ("SUCCESS".equals(status) || "ALREADY_IN_STATE".equals(status) || "SKIPPED".equals(status)) {
            return null;
        }
        if (offlineCount > 0 && failedCount == 0) return "DEVICE_OFFLINE";
        return "FAILED";
    }

    private void saveExecutionHistory(
            UUID homeId,
            UUID sceneId,
            UUID userId,
            String trigger,
            OffsetDateTime startedAt,
            String status,
            ArrayNode details
    ) {
        try {
            ExecutionStatus execStatus = switch (status) {
                case "SUCCESS", "ALREADY_IN_STATE" -> ExecutionStatus.SUCCESS;
                case "PARTIAL" -> ExecutionStatus.PARTIAL;
                case "SKIPPED" -> ExecutionStatus.SKIPPED;
                default -> ExecutionStatus.FAILED;
            };
            Scene savedScene = inTx(() -> {
                Scene scene = sceneRepository.findByIdAndHomeId(sceneId, homeId).orElse(null);
                if (scene == null) return null;
                SceneExecution exec = SceneExecution.builder()
                        .scene(scene)
                        .triggerSource(trigger)
                        .status(execStatus)
                        .startedAt(startedAt)
                        .completedAt(OffsetDateTime.now())
                        .resultDetail(details)
                        .build();
                executionRepository.save(exec);
                return scene;
            });
            if (savedScene != null) {
                behaviorEventRecorder.recordExecution(savedScene.getHome(), userId, "SCENE_EXECUTION", sceneId, execStatus.name());
            }
        } catch (Exception e) {
            log.warn("Could not save voice scene execution history for scene {}: {}", sceneId, e.getMessage());
        }
    }

    private List<DeviceCommand> toCommands(SceneAction action) {
        Map<String, Object> parameters = DeviceActionValues.toParameters(action.getAction(), action.getValue());
        return "SET_STATE".equalsIgnoreCase(action.getAction())
                ? DeviceActionValues.expandSetState(parameters)
                : List.of(new DeviceCommand(action.getAction(), parameters));
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return transactionManager == null
                ? work.get()
                : new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
