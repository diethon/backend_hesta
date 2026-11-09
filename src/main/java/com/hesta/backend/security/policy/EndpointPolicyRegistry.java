package com.hesta.backend.security.policy;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Registry holding explicit baseline authorization policies for all endpoints.
 * Provides fallback declarations so that existing endpoints satisfy the deny-by-default scanner
 * without requiring disruptive modifications to controller source code in Phase 1.
 */
public final class EndpointPolicyRegistry {

    private static final Map<String, AccessPolicyType> CLASS_POLICIES = new HashMap<>();
    private static final Map<String, AccessPolicyType> METHOD_POLICIES = new HashMap<>();
    private static final Set<String> PUBLIC_ALLOWLIST = new HashSet<>();

    static {
        // Public allowlist (no auth required)
        PUBLIC_ALLOWLIST.add("com.hesta.backend.controller.AuthController");
        PUBLIC_ALLOWLIST.add("com.hesta.backend.controller.MockSensorController");

        // Admin controllers
        CLASS_POLICIES.put("AdminController", AccessPolicyType.PLATFORM_ADMIN);
        CLASS_POLICIES.put("AdminDeviceCatalogController", AccessPolicyType.PLATFORM_ADMIN);
        CLASS_POLICIES.put("AdminDeviceController", AccessPolicyType.PLATFORM_ADMIN);
        CLASS_POLICIES.put("AdminDiagnosticsController", AccessPolicyType.PLATFORM_ADMIN);
        CLASS_POLICIES.put("AdminOtaController", AccessPolicyType.PLATFORM_ADMIN);
        CLASS_POLICIES.put("AdminUserController", AccessPolicyType.PLATFORM_ADMIN);

        // Internal service controller
        CLASS_POLICIES.put("InternalDeviceController", AccessPolicyType.INTERNAL_SERVICE);

        // User / Profile
        CLASS_POLICIES.put("UserController", AccessPolicyType.AUTHENTICATED);
        CLASS_POLICIES.put("NotificationController", AccessPolicyType.AUTHENTICATED);

        // Home controllers
        CLASS_POLICIES.put("HomeController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("RoomController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("DeviceController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("AirConditionerController", AccessPolicyType.DEVICE_LEVEL);
        CLASS_POLICIES.put("GateController", AccessPolicyType.DEVICE_LEVEL);
        CLASS_POLICIES.put("DeviceGroupController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("DeviceOnboardingController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("SceneController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("AutomationRuleController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("EnergyAnalyticsController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("TwinLayoutController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("TwinSnapshotController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("RecommendationController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("ParentalGuardianController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("SensorReadingIngestionController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("BehaviorController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("EdgeNodeController", AccessPolicyType.HOME_ACCESS);
        CLASS_POLICIES.put("EnergyController", AccessPolicyType.HOME_ACCESS);

        // Specific method overrides (Owner only or specific level)
        METHOD_POLICIES.put("EnergyController.updateDemandResponseSettings", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("HomeController.updateMemberRole", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("HomeController.removeMember", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("RoomController.createRoom", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("RoomController.updateRoom", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("RoomController.deleteRoom", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("TwinLayoutController.saveLayout", AccessPolicyType.HOME_OWNER);
        METHOD_POLICIES.put("SceneController.executeScene", AccessPolicyType.SCENE_LEVEL);
        METHOD_POLICIES.put("DeviceController.executeAction", AccessPolicyType.DEVICE_LEVEL);
        METHOD_POLICIES.put("DeviceController.setPowerState", AccessPolicyType.DEVICE_LEVEL);
    }

    private EndpointPolicyRegistry() {
    }

    public static Optional<AccessPolicyType> resolvePolicy(Class<?> controllerClass, Method method) {
        // 1. Method-level annotation
        if (method.isAnnotationPresent(HomeAccessPolicy.class)) {
            return Optional.of(method.getAnnotation(HomeAccessPolicy.class).value());
        }

        // 2. Class-level annotation
        if (controllerClass.isAnnotationPresent(HomeAccessPolicy.class)) {
            return Optional.of(controllerClass.getAnnotation(HomeAccessPolicy.class).value());
        }

        // 3. Method-level registry override
        String methodKey = controllerClass.getSimpleName() + "." + method.getName();
        if (METHOD_POLICIES.containsKey(methodKey)) {
            return Optional.of(METHOD_POLICIES.get(methodKey));
        }

        // 4. Class-level registry
        if (CLASS_POLICIES.containsKey(controllerClass.getSimpleName())) {
            return Optional.of(CLASS_POLICIES.get(controllerClass.getSimpleName()));
        }

        // 5. Public allowlist
        if (PUBLIC_ALLOWLIST.contains(controllerClass.getName())) {
            return Optional.of(AccessPolicyType.PUBLIC);
        }

        return Optional.empty();
    }

    public static boolean isPublicAllowlisted(Class<?> controllerClass) {
        return PUBLIC_ALLOWLIST.contains(controllerClass.getName());
    }
}
