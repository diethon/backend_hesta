package com.hesta.backend.security.policy;

/**
 * Access policy declarations for Spring MVC controller endpoints.
 * Part of the deny-by-default security posture (Correction C11).
 */
public enum AccessPolicyType {
    /** Public endpoint, no authentication required. */
    PUBLIC,

    /** Any authenticated user. */
    AUTHENTICATED,

    /** Platform administrator with ROLE_ADMIN. */
    PLATFORM_ADMIN,

    /** Any active member of the specified home. */
    HOME_ACCESS,

    /** Active owner of the specified home. */
    HOME_OWNER,

    /** Granular device-level check (NONE -> 404, VIEW/CONTROL -> 403 on write). */
    DEVICE_LEVEL,

    /** Scene execution check (requires CONTROL on all live devices). */
    SCENE_LEVEL,

    /** Internal service-to-service communication (e.g. X-Internal-Api-Key). */
    INTERNAL_SERVICE
}
