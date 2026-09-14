package com.hesta.backend.config.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MqttMessageReceiver {

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {
        String topic = message.getHeaders().get(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC).toString();
        String payload = message.getPayload().toString();
        log.info("Received MQTT message. Topic: [{}], Payload: [{}]", topic, payload);
        
        // TODO: Phân loại topic và gọi các service xử lý tương ứng
    }
}