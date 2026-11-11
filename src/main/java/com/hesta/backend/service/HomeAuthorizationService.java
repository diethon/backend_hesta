package com.hesta.backend.service;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceGroup;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.MemberDeviceAccess;
import com.hesta.backend.entity.MemberRoomAccess;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceGroupRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.MemberDeviceAccessRepository;
import com.hesta.backend.repository.MemberRoomAccessRepository;
import com.hesta.backend.repository.SceneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central authorization service for Hesta Smart Home.
 * Evaluates membership status, granular room access, and device overrides.
 * Enforces fail-closed, deny-by-default security policies.
 */
@Service
@Slf4j
public class HomeAuthorizationService {

    private static final String REQUEST_ATTR_ACCESS_CONTEXT = "HESTA_MEMBER_ACCESS_CONTEXT_CACHE";

    private final HomeRepository homeRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final MemberRoomAccessRepository memberRoomAccessRepository;
    private final MemberDeviceAccessRepository memberDeviceAccessRepository;
    private final DeviceRepository deviceRepository;
    private final SceneRepository sceneRepository;
    private final DeviceGroupRepository deviceGroupRepository;

    @Autowired
    public HomeAuthorizationService(
            HomeRepository homeRepository,
            HomeMemberRepository homeMemberRepository,
            MemberRoomAccessRepository memberRoomAccessRepository,
            MemberDeviceAccessRepository memberDeviceAccessRepository,
            DeviceRepository deviceRepository,
            SceneRepository sceneRepository,
            DeviceGroupRepository deviceGroupRepository) {
        this.homeRepository = homeRepository;
        this.homeMemberRepository = homeMemberRepository;
        this.memberRoomAccessRepository = memberRoomAccessRepository;
        this.memberDeviceAccessRepository = memberDeviceAccessRepository;
        this.deviceRepository = deviceRepository;
        this.sceneRepository = sceneRepository;
        this.deviceGroupRepository = deviceGroupRepository;
    }

    /**
     * Backward-compatible constructor for existing tests and legacy callers.
     */
    public HomeAuthorizationService(HomeRepository homeRepository, HomeMemberRepository homeMemberRepository) {
        this(homeRepository, homeMemberRepository, null, null, null, null, null);
    }

    public Home requireAccess(UUID userId, UUID homeId) {
        return loadAuthorizedHome(userId, homeId).home();
    }

    public Home requireSceneManagement(UUID userId, UUID homeId) {
        AuthorizedHome authorizedHome = loadAuthorizedHome(userId, homeId);
        if (authorizedHome.membership().getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return authorizedHome.home();
    }

    public Home requireLayoutManagement(UUID userId, UUID homeId) {
        return requireSceneManagement(userId, homeId);
    }

    public Home requireOwner(UUID userId, UUID homeId) {
        return requireSceneManagement(userId, homeId);
    }

    @Transactional(readOnly = true)
    public MemberAccessContext loadAccessContext(UUID homeId, UUID userId) {
        if (userId == null || homeId == null) {
            return new MemberAccessContext(homeId, userId, null, null, null, AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap());
        }

        String cacheKey = homeId + ":" + userId;
        Map<String, MemberAccessContext> cache = getRequestCache();
        if (cache != null && cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        Optional<HomeMember> membershipOpt = homeMemberRepository.findByHomeIdAndUserId(homeId, userId);
        if (membershipOpt.isEmpty()) {
            MemberAccessContext unauthCtx = new MemberAccessContext(
                    homeId, userId, null, null, null, null, Collections.emptyMap(), Collections.emptyMap()
            );
            if (cache != null) cache.put(cacheKey, unauthCtx);
            return unauthCtx;
        }

        HomeMember membership = membershipOpt.get();
        if (membership.getStatus() != MemberStatus.ACTIVE) {
            MemberAccessContext inactiveCtx = new MemberAccessContext(
                    homeId, userId, membership.getId(), membership.getRole(), membership.getStatus(),
                    membership.getAccessScope(), Collections.emptyMap(), Collections.emptyMap()
            );
            if (cache != null) cache.put(cacheKey, inactiveCtx);
            return inactiveCtx;
        }

        // Owner or scope ALL: bypasses room/device specific mappings
        if (membership.getRole() == HomeRole.OWNER || membership.getAccessScope() == AccessScope.ALL) {
            MemberAccessContext fullCtx = new MemberAccessContext(
                    homeId, userId, membership.getId(), membership.getRole(), membership.getStatus(),
                    membership.getAccessScope() != null ? membership.getAccessScope() : AccessScope.ALL,
                    Collections.emptyMap(), Collections.emptyMap()
            );
            if (cache != null) cache.put(cacheKey, fullCtx);
            return fullCtx;
        }

        // CUSTOM scope: fetch room levels and device overrides
        List<MemberRoomAccess> roomAccessList = memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId);
        Map<UUID, AccessLevel> roomLevels = roomAccessList.stream()
                .collect(Collectors.toMap(ra -> ra.getRoom().getId(), MemberRoomAccess::getAccessLevel, (k1, k2) -> k2));

        List<MemberDeviceAccess> deviceAccessList = memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId);
        Map<UUID, AccessLevel> deviceOverrides = deviceAccessList.stream()
                .collect(Collectors.toMap(da -> da.getDevice().getId(), MemberDeviceAccess::getAccessLevel, (k1, k2) -> k2));

        MemberAccessContext customCtx = new MemberAccessContext(
                homeId, userId, membership.getId(), membership.getRole(), membership.getStatus(),
                AccessScope.CUSTOM, roomLevels, deviceOverrides
        );
        if (cache != null) cache.put(cacheKey, customCtx);
        return customCtx;
    }

    public AccessLevel effectiveLevel(MemberAccessContext context, Device device) {
        if (context == null || !context.isActive() || device == null || device.isDeleted()) {
            return AccessLevel.NONE;
        }

        if (context.isOwner() || context.scope() == AccessScope.ALL) {
            return AccessLevel.CONTROL;
        }

        // 1. Explicit device override takes highest precedence
        if (context.deviceOverrides().containsKey(device.getId())) {
            return context.deviceOverrides().get(device.getId());
        }

        // 2. Room level inheritance
        if (device.getRoom() != null && device.getRoom().getId() != null) {
            return context.roomLevels().getOrDefault(device.getRoom().getId(), AccessLevel.NONE);
        }

        // 3. Unassigned device without override defaults to NONE
        return AccessLevel.NONE;
    }

    @Transactional(readOnly = true)
    public Device requireDeviceAccess(UUID userId, UUID deviceId, AccessLevel minLevel) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (deviceId == null) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }

        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        if (device.isDeleted()) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }

        Home home = device.getHome();
        if (home == null) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }

        MemberAccessContext context = loadAccessContext(home.getId(), userId);
        if (!context.isActive()) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        AccessLevel level = effectiveLevel(context, device);

        // Security Posture: Level NONE -> 404 (DEVICE_NOT_FOUND), fail-closed
        if (level == AccessLevel.NONE) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }

        // Insufficient level (e.g. VIEW trying to execute CONTROL action) -> 403 (UNAUTHORIZED)
        if (minLevel != null && level.ordinal() < minLevel.ordinal()) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return device;
    }

    @Transactional(readOnly = true)
    public boolean hasDeviceAccess(UUID userId, UUID deviceId, AccessLevel minLevel) {
        if (userId == null || deviceId == null) return false;
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty() || devOpt.get().isDeleted()) return false;
        Device dev = devOpt.get();
        if (dev.getHome() == null) return false;
        MemberAccessContext ctx = loadAccessContext(dev.getHome().getId(), userId);
        if (!ctx.isActive()) return false;
        AccessLevel level = effectiveLevel(ctx, dev);
        if (level == AccessLevel.NONE) return false;
        return minLevel == null || level.ordinal() >= minLevel.ordinal();
    }

    @Transactional(readOnly = true)
    public Set<UUID> getVisibleDeviceIds(UUID homeId, UUID userId, AccessLevel minLevel) {
        MemberAccessContext context = loadAccessContext(homeId, userId);
        if (!context.isActive()) {
            return Collections.emptySet();
        }

        List<Device> allDevices = deviceRepository.findByHomeIdWithRoomAndNode(homeId);
        if (allDevices.isEmpty()) {
            return Collections.emptySet();
        }

        AccessLevel threshold = minLevel != null ? minLevel : AccessLevel.VIEW;

        return allDevices.stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> effectiveLevel(context, d).ordinal() >= threshold.ordinal())
                .map(Device::getId)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public boolean canRunScene(UUID userId, UUID sceneId) {
        if (userId == null || sceneId == null) return false;

        Optional<Scene> sceneOpt = sceneRepository.findById(sceneId);
        if (sceneOpt.isEmpty()) return false;

        Scene scene = sceneOpt.get();
        if (scene.getHome() == null) return false;

        MemberAccessContext context = loadAccessContext(scene.getHome().getId(), userId);
        if (!context.isActive()) return false;
        if (context.isOwner() || context.scope() == AccessScope.ALL) return true;

        List<SceneAction> actions = scene.getActions();
        if (actions == null || actions.isEmpty()) {
            // A scene with no actions / devices is hidden/denied for CUSTOM
            return false;
        }

        List<Device> liveDevices = actions.stream()
                .map(SceneAction::getTargetDevice)
                .filter(Objects::nonNull)
                .filter(d -> !d.isDeleted())
                .toList();

        // Product Decision 2: A scene with no live devices is hidden from CUSTOM members
        if (liveDevices.isEmpty()) {
            return false;
        }

        // Must have CONTROL on 100% of live devices
        return liveDevices.stream().allMatch(d -> effectiveLevel(context, d) == AccessLevel.CONTROL);
    }

    @Transactional(readOnly = true)
    public boolean canControlGroup(UUID userId, UUID groupId) {
        if (userId == null || groupId == null) return false;

        Optional<DeviceGroup> groupOpt = deviceGroupRepository.findById(groupId);
        if (groupOpt.isEmpty()) return false;

        DeviceGroup group = groupOpt.get();
        if (group.getHome() == null) return false;

        MemberAccessContext context = loadAccessContext(group.getHome().getId(), userId);
        if (!context.isActive()) return false;
        if (context.isOwner() || context.scope() == AccessScope.ALL) return true;

        List<Device> devices = group.getDevices();
        if (devices == null || devices.isEmpty()) return false;

        List<Device> liveDevices = devices.stream()
                .filter(d -> !d.isDeleted())
                .toList();

        if (liveDevices.isEmpty()) return false;

        return liveDevices.stream().allMatch(d -> effectiveLevel(context, d) == AccessLevel.CONTROL);
    }

    private AuthorizedHome loadAuthorizedHome(UUID userId, UUID homeId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (homeId == null) {
            throw new AppException(ErrorCode.HOME_NOT_FOUND);
        }
        Home home = homeRepository.findById(homeId)
                .orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));
        HomeMember membership = homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (membership.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return new AuthorizedHome(home, membership);
    }

    @SuppressWarnings("unchecked")
    private Map<String, MemberAccessContext> getRequestCache() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        Object cacheObj = attrs.getAttribute(REQUEST_ATTR_ACCESS_CONTEXT, RequestAttributes.SCOPE_REQUEST);
        if (cacheObj instanceof Map) {
            return (Map<String, MemberAccessContext>) cacheObj;
        }
        Map<String, MemberAccessContext> newCache = new ConcurrentHashMap<>();
        attrs.setAttribute(REQUEST_ATTR_ACCESS_CONTEXT, newCache, RequestAttributes.SCOPE_REQUEST);
        return newCache;
    }

    private record AuthorizedHome(Home home, HomeMember membership) {
    }
}
