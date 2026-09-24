package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.command.NotificationCreatedEvent;
import com.hesta.backend.dto.response.NotificationRealtimePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringJUnitConfig(NotificationRealtimeTransactionTest.TestConfig.class)
class NotificationRealtimeTransactionTest {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RealtimeEventPublisher realtimeEventPublisher;

    @BeforeEach
    void resetPublisher() {
        reset(realtimeEventPublisher);
    }

    @Test
    void notificationCreatedEvent_whenTransactionCommits_publishesRealtimeEvent() {
        NotificationCreatedEvent event = event();

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> applicationEventPublisher.publishEvent(event)
        );

        verify(realtimeEventPublisher).publish(org.mockito.ArgumentMatchers.argThat(realtimeEvent ->
                realtimeEvent.homeId().equals(event.payload().homeId())
                        && realtimeEvent.data().equals(event.payload())
        ));
    }

    @Test
    void notificationCreatedEvent_whenTransactionRollsBack_doesNotPublishRealtimeEvent() {
        NotificationCreatedEvent event = event();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(event);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(realtimeEventPublisher);
    }

    private NotificationCreatedEvent event() {
        UUID homeId = UUID.randomUUID();
        return new NotificationCreatedEvent(new NotificationRealtimePayload(
                UUID.randomUUID(), UUID.randomUUID(), homeId, false, OffsetDateTime.now()
        ));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        RealtimeEventPublisher realtimeEventPublisher() {
            return mock(RealtimeEventPublisher.class);
        }

        @Bean
        NotificationRealtimeListener notificationRealtimeListener(RealtimeEventPublisher publisher) {
            return new NotificationRealtimeListener(publisher);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource is needed; Spring transaction synchronization is the behavior under test.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Successful no-op commit.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Successful no-op rollback.
        }
    }
}
