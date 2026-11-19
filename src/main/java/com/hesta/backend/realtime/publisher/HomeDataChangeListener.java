package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.response.HomeDataChangedPayload;
import com.hesta.backend.entity.AutomationExecution;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.AutomationSchedule;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.SceneExecution;
import com.hesta.backend.entity.SceneSchedule;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JPA listener on scene and automation entities: every write path (CRUD, schedules, run history, engine updates)
 * tells the home's open screens to refresh, without each service having to remember to do it.
 * One event per (type, home) per transaction, sent only after commit.
 */
@Slf4j
@Component
public class HomeDataChangeListener {
    private static final Object PENDING_KEY = HomeDataChangeListener.class.getName() + ".pending";

    // Provider: Hibernate may build this listener before the realtime beans exist.
    private final ObjectProvider<RealtimeEventPublisher> publisher;

    public HomeDataChangeListener(ObjectProvider<RealtimeEventPublisher> publisher) {
        this.publisher = publisher;
    }

    record Change(RealtimeEventType type, UUID homeId) { }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        Change change;
        try {
            change = toChange(entity);
        } catch (RuntimeException ex) {
            // Reading a lazy parent during flush can fail; a missed refresh is better than a failed write.
            log.debug("Skip realtime change for {}: {}", entity.getClass().getSimpleName(), ex.getMessage());
            return;
        }
        if (change == null) return;
        String name = entity.getClass().getSimpleName();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(change, name);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Change, String> pending = (Map<Change, String>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            Map<Change, String> batch = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, batch);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    batch.forEach(HomeDataChangeListener.this::publish);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                }
            });
            pending = batch;
        }
        pending.putIfAbsent(change, name);
    }

    static Change toChange(Object entity) {
        if (entity instanceof Scene scene) return scene(scene);
        if (entity instanceof SceneAction action) return scene(action.getScene());
        if (entity instanceof SceneExecution execution) return scene(execution.getScene());
        if (entity instanceof SceneSchedule schedule) return scene(schedule.getScene());
        if (entity instanceof AutomationRule rule) return rule(rule);
        if (entity instanceof AutomationExecution execution) return rule(execution.getRule());
        if (entity instanceof AutomationSchedule schedule) return rule(schedule.getRule());
        return null;
    }

    private static Change scene(Scene scene) {
        UUID homeId = homeId(scene == null ? null : scene.getHome());
        return homeId == null ? null : new Change(RealtimeEventType.SCENE_CHANGED, homeId);
    }

    private static Change rule(AutomationRule rule) {
        UUID homeId = homeId(rule == null ? null : rule.getHome());
        return homeId == null ? null : new Change(RealtimeEventType.AUTOMATION_RULE_CHANGED, homeId);
    }

    private static UUID homeId(Home home) {
        return home == null ? null : home.getId();
    }

    private void publish(Change change, String entityName) {
        RealtimeEventPublisher target = publisher.getIfAvailable();
        if (target == null) return;
        try {
            target.publish(RealtimeEvent.create(change.type(), change.homeId(), new HomeDataChangedPayload(entityName)));
        } catch (RuntimeException ex) {
            log.warn("Could not publish {} for home {}: {}", change.type(), change.homeId(), ex.getMessage());
        }
    }
}
