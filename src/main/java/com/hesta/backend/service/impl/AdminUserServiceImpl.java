package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.AdminCreateUserRequest;
import com.hesta.backend.dto.request.AdminUpdateUserRequest;
import com.hesta.backend.dto.response.AdminUserResponse;
import com.hesta.backend.dto.response.AdminUserStatsResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.entity.NotificationPreference;
import com.hesta.backend.entity.User;
import com.hesta.backend.entity.UserPreference;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.UserPreferenceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AdminUserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AdminUserServiceImpl implements AdminUserService {

    UserRepository userRepository;
    UserPreferenceRepository userPreferenceRepository;
    PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> getUsers(
            String search,
            PlatformRole role,
            AccountStatus status,
            AuthProvider provider,
            Pageable pageable) {

        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                Expression<String> phoneExpr = cb.coalesce(root.get("phoneNumber"), "");
                Predicate nameLike = cb.like(cb.lower(root.get("fullName")), pattern);
                Predicate emailLike = cb.like(cb.lower(root.get("email")), pattern);
                Predicate phoneLike = cb.like(cb.lower(phoneExpr), pattern);
                predicates.add(cb.or(nameLike, emailLike, phoneLike));
            }

            if (role != null) {
                predicates.add(cb.equal(root.get("platformRole"), role));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (provider != null) {
                predicates.add(cb.equal(root.get("provider"), provider));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<User> page = userRepository.findAll(spec, pageable);
        Page<AdminUserResponse> responsePage = page.map(this::mapToAdminUserResponse);
        return PageResponse.from(responsePage);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserStatsResponse getUserStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus(AccountStatus.ACTIVE);
        long lockedUsers = userRepository.countByStatus(AccountStatus.LOCKED);
        long disabledUsers = userRepository.countByStatus(AccountStatus.DISABLED);

        long adminUsers = userRepository.countByPlatformRole(PlatformRole.ADMIN);
        long standardUsers = userRepository.countByPlatformRole(PlatformRole.USER);

        long localUsers = userRepository.countByProvider(AuthProvider.LOCAL);
        long googleUsers = userRepository.countByProvider(AuthProvider.GOOGLE);

        // Generate Registration Trend for the last 6 months
        List<OffsetDateTime> createdDates = userRepository.findAllCreatedDates();
        List<AdminUserStatsResponse.RegistrationTrendPoint> trend = computeMonthlyTrend(createdDates);

        return AdminUserStatsResponse.builder()
                .totalUsers(totalUsers)
                .activeUsers(activeUsers)
                .lockedUsers(lockedUsers)
                .disabledUsers(disabledUsers)
                .adminUsers(adminUsers)
                .standardUsers(standardUsers)
                .localUsers(localUsers)
                .googleUsers(googleUsers)
                .registrationTrend(trend)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return mapToAdminUserResponse(user);
    }

    @Override
    @Transactional
    public AdminUserResponse createUser(AdminCreateUserRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null)
                .provider(AuthProvider.LOCAL)
                .platformRole(request.getPlatformRole() != null ? request.getPlatformRole() : PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts((short) 0)
                .build();

        User savedUser = userRepository.save(user);

        UserPreference userPreference = UserPreference.builder()
                .user(savedUser)
                .preferredTemperature(new BigDecimal("25.0"))
                .preferredBrightness((short) 80)
                .theme("system")
                .language("vi")
                .voiceFeedbackEnabled(true)
                .notificationPreference(NotificationPreference.defaults())
                .build();

        userPreferenceRepository.save(userPreference);

        return mapToAdminUserResponse(savedUser);
    }

    @Override
    @Transactional
    public AdminUserResponse updateUser(UUID id, AdminUpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setFullName(request.getFullName().trim());
        user.setPhoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null);
        user.setPlatformRole(request.getPlatformRole());

        User updated = userRepository.save(user);
        return mapToAdminUserResponse(updated);
    }

    @Override
    @Transactional
    public AdminUserResponse updateUserStatus(UUID id, AccountStatus status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setStatus(status);
        if (status == AccountStatus.ACTIVE) {
            user.setFailedLoginAttempts((short) 0);
            user.setLockedUntil(null);
        }

        User updated = userRepository.save(user);
        return mapToAdminUserResponse(updated);
    }

    @Override
    @Transactional
    public AdminUserResponse unlockUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setStatus(AccountStatus.ACTIVE);
        user.setFailedLoginAttempts((short) 0);
        user.setLockedUntil(null);

        User updated = userRepository.save(user);
        return mapToAdminUserResponse(updated);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private AdminUserResponse mapToAdminUserResponse(User user) {
        int homeCount = (user.getHomeMembers() != null) ? user.getHomeMembers().size() : 0;

        return AdminUserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl())
                .provider(user.getProvider())
                .platformRole(user.getPlatformRole())
                .status(user.getStatus())
                .failedLoginAttempts(user.getFailedLoginAttempts())
                .lockedUntil(user.getLockedUntil())
                .lastActiveAt(user.getLastActiveAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .homeCount(homeCount)
                .build();
    }

    private List<AdminUserStatsResponse.RegistrationTrendPoint> computeMonthlyTrend(List<OffsetDateTime> createdDates) {
        OffsetDateTime now = OffsetDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/yyyy");

        // Prepare last 6 months in order
        Map<String, Long> monthCounts = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) {
            OffsetDateTime month = now.minusMonths(i);
            String label = "T" + month.format(formatter);
            monthCounts.put(label, 0L);
        }

        if (createdDates != null) {
            for (OffsetDateTime date : createdDates) {
                if (date != null) {
                    String label = "T" + date.format(formatter);
                    if (monthCounts.containsKey(label)) {
                        monthCounts.put(label, monthCounts.get(label) + 1);
                    }
                }
            }
        }

        return monthCounts.entrySet().stream()
                .map(e -> AdminUserStatsResponse.RegistrationTrendPoint.builder()
                        .label(e.getKey())
                        .count(e.getValue())
                        .build())
                .collect(Collectors.toList());
    }
}
