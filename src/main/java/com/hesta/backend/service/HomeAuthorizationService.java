package com.hesta.backend.service;

import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HomeAuthorizationService {

    private final HomeRepository homeRepository;
    private final HomeMemberRepository homeMemberRepository;

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

    private record AuthorizedHome(Home home, HomeMember membership) {
    }
}
