package com.hesta.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    User user;

    @Column(name = "device_id", nullable = false, length = 255)
    String deviceId;

    @Column(name = "device_type", length = 50)
    String deviceType;

    @Column(name = "token_hash", nullable = false, length = 255)
    String tokenHash;

    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    boolean isRevoked = false;

    @Column(name = "expires_at", nullable = false)
    OffsetDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    OffsetDateTime createdAt;
}
