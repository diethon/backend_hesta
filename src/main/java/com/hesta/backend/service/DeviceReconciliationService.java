package com.hesta.backend.service;

import com.hesta.backend.config.mqtt.MqttGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * PHO-026: State Reconciliation
 * Tự động gửi lệnh yêu cầu toàn bộ thiết bị báo cáo trạng thái khi Server khởi động (để sửa lỗi lệch DB do mất mạng).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mqtt.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DeviceReconciliationService {

    private final MqttGateway mqttGateway;

    @org.springframework.beans.factory.annotation.Value("${mqtt.topic.prefix:hesta/nodes}")
    private String topicPrefix;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileStatesOnStartup() {
        log.info("[State Reconciliation] Bắt đầu đồng bộ trạng thái thiết bị...");
        
        // Gửi một bản tin broadcast tới tất cả các Node yêu cầu báo cáo trạng thái (Ping State).
        // Các thiết bị phần cứng thực tế (ESP32) sẽ cần lắng nghe topic này và tự động gửi lại bản tin /state.
        String topic = topicPrefix + "/broadcast/request_state";
        String payload = "{\"action\": \"REPORT_STATE\", \"timestamp\": " + System.currentTimeMillis() + "}";
        
        try {
            mqttGateway.sendToMqtt(topic, 1, payload);
            log.info("[State Reconciliation] Đã gửi lệnh broadcast tới {}", topic);
        } catch (Exception e) {
            log.error("[State Reconciliation] Lỗi khi gửi lệnh đồng bộ", e);
        }
    }

    /**
     * PHO-038: Runtime Broker Failover
     * Tự động phục hồi và kích hoạt State Reconciliation khi Broker Mosquitto kết nối lại mà không cần restart server.
     */
    private final java.util.concurrent.atomic.AtomicLong lastReconcileTimestamp = new java.util.concurrent.atomic.AtomicLong(0);

    @EventListener(org.springframework.integration.mqtt.event.MqttSubscribedEvent.class)
    public void onMqttSubscribed(org.springframework.integration.mqtt.event.MqttSubscribedEvent event) {
        long now = System.currentTimeMillis();
        long lastTime = lastReconcileTimestamp.get();
        // Rate-limit / debounce: Bỏ qua nếu lần reconcile trước đó chưa qua 15 giây để tránh Broadcast Storm
        if (now - lastTime < 15000) {
            log.debug("[PHO-038 Broker Failover Recovery] Bỏ qua sự kiện Subscribed trùng lặp do đang trong thời gian debounce.");
            return;
        }
        if (!lastReconcileTimestamp.compareAndSet(lastTime, now)) {
            log.debug("[PHO-038 Broker Failover Recovery] Race condition detected on debounce timestamp update, skipping.");
            return;
        }
        log.info("[PHO-038 Broker Failover Recovery] MQTT Broker connected/subscribed! Triggering automatic State Reconciliation...");
        reconcileStatesOnStartup();
    }

    @EventListener(org.springframework.integration.mqtt.event.MqttConnectionFailedEvent.class)
    public void onMqttConnectionFailed(org.springframework.integration.mqtt.event.MqttConnectionFailedEvent event) {
        log.warn("[PHO-038 Broker Failover] Mất kết nối tới Mosquitto Broker: {}", 
                event.getCause() != null ? event.getCause().getMessage() : "Network dropped");
    }
}
