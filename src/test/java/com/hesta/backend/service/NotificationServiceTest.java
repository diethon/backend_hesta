package com.hesta.backend.service;

import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Notification;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.NotificationRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationServiceImpl.class)
class NotificationServiceTest {
    @Autowired private UserRepository users;
    @Autowired private HomeRepository homes;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationService service;

    private User recipient;
    private User other;
    private Home home;
    private Home otherHome;

    @BeforeEach
    void setUp() {
        recipient = users.save(User.builder().fullName("Recipient").email("recipient@example.com")
                .passwordHash("hash").provider(AuthProvider.LOCAL).build());
        other = users.save(User.builder().fullName("Other").email("other@example.com")
                .passwordHash("hash").provider(AuthProvider.LOCAL).build());
        home = homes.save(Home.builder().name("Home A").createdBy(recipient).build());
        otherHome = homes.save(Home.builder().name("Home B").createdBy(recipient).build());
    }

    @Test
    void list_filtersByRecipientHomeReadStateTypeAndPriority() {
        Notification target = save(recipient, home, NotificationType.SECURITY, NotificationPriority.HIGH, false);
        save(recipient, home, NotificationType.SYSTEM, NotificationPriority.LOW, true);
        save(recipient, otherHome, NotificationType.SECURITY, NotificationPriority.HIGH, false);
        save(other, home, NotificationType.SECURITY, NotificationPriority.HIGH, false);

        var result = service.list(recipient.getId(), home.getId(), false,
                NotificationType.SECURITY, NotificationPriority.HIGH, 0, 1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().id()).isEqualTo(target.getId());
        assertThat(result.content().getFirst().userId()).isEqualTo(recipient.getId());
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.last()).isTrue();
        assertThat(service.list(recipient.getId(), null, null, null, null, 0, 2).totalElements())
                .isEqualTo(3);
        assertThatThrownBy(() -> service.list(recipient.getId(), null, null, null, null, -1, 20))
                .isInstanceOf(AppException.class);
    }

    @Test
    void readOperations_onlyChangeRecipientsNotificationsAndRespectHome() {
        Notification first = save(recipient, home, NotificationType.DEVICE, NotificationPriority.MEDIUM, false);
        Notification second = save(recipient, home, NotificationType.DEVICE, NotificationPriority.MEDIUM, false);
        Notification anotherHome = save(recipient, otherHome, NotificationType.SYSTEM, NotificationPriority.LOW, false);
        Notification anotherUser = save(other, home, NotificationType.DEVICE, NotificationPriority.MEDIUM, false);

        assertThatThrownBy(() -> service.get(other.getId(), first.getId()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.markRead(other.getId(), first.getId()))
                .isInstanceOf(AppException.class);
        assertThat(service.markRead(recipient.getId(), first.getId()).isRead()).isTrue();
        assertThat(service.markAllRead(recipient.getId(), home.getId()).updatedCount()).isEqualTo(1);
        assertThat(service.markAllRead(recipient.getId(), home.getId()).updatedCount()).isZero();
        assertThat(notifications.findById(second.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notifications.findById(anotherHome.getId()).orElseThrow().isRead()).isFalse();
        assertThat(notifications.findById(anotherUser.getId()).orElseThrow().isRead()).isFalse();
        assertThat(service.markAllRead(recipient.getId(), null).updatedCount()).isEqualTo(1);
    }

    private Notification save(User user, Home notificationHome, NotificationType type,
                              NotificationPriority priority, boolean read) {
        return notifications.saveAndFlush(Notification.builder().recipient(user).home(notificationHome)
                .type(type).title("Alert").message("Something happened").priority(priority)
                .read(read).build());
    }
}
