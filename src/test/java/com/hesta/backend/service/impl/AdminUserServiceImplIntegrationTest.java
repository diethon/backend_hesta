package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.AdminUserResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AdminUserServiceImplIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.save(User.builder()
                .fullName("Super Admin")
                .email("superadmin-" + UUID.randomUUID() + "@hesta.vn")
                .passwordHash("hashed")
                .platformRole(PlatformRole.ADMIN)
                .status(AccountStatus.ACTIVE)
                .provider(AuthProvider.LOCAL)
                .phoneNumber("0901234567")
                .build());

        userRepository.save(User.builder()
                .fullName("Nguyen Van A")
                .email("user-a-" + UUID.randomUUID() + "@gmail.com")
                .passwordHash("hashed")
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.LOCKED)
                .provider(AuthProvider.GOOGLE)
                .build());
    }

    @Test
    @DisplayName("getUsers with all null filters executes without named parameter error")
    void testGetUsersWithAllNullFilters() {
        PageResponse<AdminUserResponse> response = adminUserService.getUsers(
                null, null, null, null, PageRequest.of(0, 10)
        );

        assertThat(response).isNotNull();
        assertThat(response.content()).isNotEmpty();
        assertThat(response.totalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("getUsers with keyword search and role filter works")
    void testGetUsersWithKeywordAndRole() {
        PageResponse<AdminUserResponse> response = adminUserService.getUsers(
                "Super", PlatformRole.ADMIN, AccountStatus.ACTIVE, AuthProvider.LOCAL, PageRequest.of(0, 10)
        );

        assertThat(response).isNotNull();
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().getFullName()).isEqualTo("Super Admin");
    }

    @Test
    @DisplayName("getUserStats executes successfully")
    void testGetUserStats() {
        var stats = adminUserService.getUserStats();
        assertThat(stats).isNotNull();
        assertThat(stats.getTotalUsers()).isGreaterThanOrEqualTo(2);
        assertThat(stats.getActiveUsers()).isGreaterThanOrEqualTo(1);
    }
}
