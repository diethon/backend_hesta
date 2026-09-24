package com.hesta.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserPreference {

    @Id
    @Column(name = "user_id")
    UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    User user;

    @Column(name = "preferred_temperature", precision = 4, scale = 1)
    BigDecimal preferredTemperature;

    @Column(name = "preferred_brightness")
    Short preferredBrightness;

    @Column(name = "default_room_id")
    UUID defaultRoomId;

    @Column(name = "theme", length = 20)
    @Builder.Default
    String theme = "system";

    @Column(name = "language", length = 10)
    @Builder.Default
    String language = "vi";

    @Column(name = "voice_feedback_enabled", nullable = false)
    @Builder.Default
    boolean voiceFeedbackEnabled = true;

    @Embedded
    @Builder.Default
    NotificationPreference notificationPreference = NotificationPreference.defaults();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}
