package com.hesta.backend.enums;

/**
 * Access scope for a home member.
 * - ALL: Member has full room/device access across the entire home (default, legacy behavior).
 * - CUSTOM: Member access is governed granularly by room levels and device overrides.
 */
public enum AccessScope {
    ALL,
    CUSTOM
}

