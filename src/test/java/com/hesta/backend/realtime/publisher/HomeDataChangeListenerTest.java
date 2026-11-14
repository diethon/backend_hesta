package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.response.HomeDataChangedPayload;
import com.hesta.backend.entity.AutomationExecution;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HomeDataChangeListenerTest {
    private final RealtimeEventPublisher publisher = mock(RealtimeEventPublisher.class);
    private final HomeDataChangeListener listener;
    private final UUID homeId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    HomeDataChangeListenerTest() {
        ObjectProvider<RealtimeEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        listener = new HomeDataChangeListener(provider);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void mapsChildEntitiesToTheirHomeAndType() {
        Home home = Home.builder().id(homeId).build();
        Scene scene = Scene.builder().home(home).build();
        AutomationRule rule = AutomationRule.builder().home(home).build();

        assertThat(HomeDataChangeListener.toChange(SceneAction.builder().scene(scene).build()))
                .isEqualTo(new HomeDataChangeListener.Change(RealtimeEventType.SCENE_CHANGED, homeId));
        assertThat(HomeDataChangeListener.toChange(AutomationExecution.builder().rule(rule).build()))
                .isEqualTo(new HomeDataChangeListener.Change(RealtimeEventType.AUTOMATION_RULE_CHANGED, homeId));
        assertThat(HomeDataChangeListener.toChange(home)).isNull();
    }

    @Test
    void publishesOncePerTypeOnlyAfterCommit() {
        Home home = Home.builder().id(homeId).build();
        Scene scene = Scene.builder().home(home).build();
        TransactionSynchronizationManager.initSynchronization();

        listener.changed(scene);
        listener.changed(SceneAction.builder().scene(scene).build());
        listener.changed(AutomationRule.builder().home(home).build());
        verifyNoInteractions(publisher);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<RealtimeEvent> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues()).extracting(RealtimeEvent::type)
                .containsExactly(RealtimeEventType.SCENE_CHANGED, RealtimeEventType.AUTOMATION_RULE_CHANGED);
        assertThat(captor.getAllValues().get(0).data()).isEqualTo(new HomeDataChangedPayload("Scene"));
        assertThat(TransactionSynchronizationManager.hasResource(HomeDataChangeListener.class.getName() + ".pending")).isFalse();
    }

    @Test
    void rolledBackChangesAreNeverPublished() {
        TransactionSynchronizationManager.initSynchronization();
        listener.changed(Scene.builder().home(Home.builder().id(homeId).build()).build());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(publisher);
    }
}
