package com.hesta.backend.service;

import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Access context holding membership state, room levels, and device overrides
 * for a specific (homeId, userId) pair. Memoized per request.
 */
public record MemberAccessContext(
        UUID homeId,
        UUID userId,
        UUID memberId,
        HomeRole role,
        MemberStatus status,
        AccessScope scope,
        Map<UUID, AccessLevel> roomLevels,
        Map<UUID, AccessLevel> deviceOverrides
) {
    public MemberAccessContext {
        if (roomLevels == null) {
            roomLevels = Collections.emptyMap();
        }
        if (deviceOverrides == null) {
            deviceOverrides = Collections.emptyMap();
        }
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public boolean isOwner() {
        return isActive() && role == HomeRole.OWNER;
    }
}
