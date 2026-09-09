package com.hesta.backend.entity;

import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "full_name", nullable = false, length = 150)
    String fullName;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    String email;

    @Column(name = "password_hash")
    String passwordHash;

    @Column(name = "phone_number", length = 20)
    String phoneNumber;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    @Builder.Default
    AuthProvider provider = AuthProvider.LOCAL;

    @Column(name = "google_uid", unique = true, length = 255)
    String googleUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, length = 20)
    @Builder.Default
    PlatformRole platformRole = PlatformRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    short failedLoginAttempts = 0;

    @Column(name = "locked_until")
    OffsetDateTime lockedUntil;

    @Column(name = "last_active_at")
    OffsetDateTime lastActiveAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<HomeMember> homeMembers = new ArrayList<>();
}
