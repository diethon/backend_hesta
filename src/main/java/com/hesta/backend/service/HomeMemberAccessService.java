package com.hesta.backend.service;

import com.hesta.backend.dto.access.MemberAccessDetailResponse;
import com.hesta.backend.dto.access.UpdateMemberAccessRequest;

import java.util.UUID;

public interface HomeMemberAccessService {
    MemberAccessDetailResponse getMemberAccess(UUID callerUserId, UUID homeId, UUID targetUserId);
    MemberAccessDetailResponse updateMemberAccess(UUID callerUserId, UUID homeId, UUID targetUserId, UpdateMemberAccessRequest request);
}

