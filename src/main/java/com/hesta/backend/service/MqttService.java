package com.hesta.backend.service;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MqttService {

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.client.id}")
    private String clientId;

    @Value("${mqtt.topic.device.command}")
    private String deviceCommandTopic;

    private MqttClient mqttClient;

    @PostConstruct
    public void connect() {
        try {
            mqttClient = new MqttClient(
                    brokerUrl,
                    clientId + "-" + System.currentTimeMillis()
            );

            MqttConnectOptions options = new MqttConnectOptions();

            options.setAutomaticReconnect(true);
            options.setCleanSession(true);

            mqttClient.connect(options);

            log.info("[MQTT] Backend connected");

        } catch (MqttException e) {
            throw new RuntimeException(
                    "Cannot connect to MQTT broker",
                    e
            );
        }
    }

    public void publish(String topic, String payload) {

        try {
            if (!mqttClient.isConnected()) {
                throw new IllegalStateException(
                        "MQTT client is not connected"
                );
            }

            MqttMessage message =
                    new MqttMessage(payload.getBytes());

            message.setQos(1);
            message.setRetained(false);

            mqttClient.publish(topic, message);

            log.info("[MQTT] Published\nTopic: {}\nPayload: {}", topic, payload);

        } catch (MqttException e) {
            throw new RuntimeException(
                    "MQTT publish failed",
                    e
            );
        }
    }

    @PreDestroy
    public void disconnect() {

        try {

            if (mqttClient != null
                    && mqttClient.isConnected()) {

                mqttClient.disconnect();
                mqttClient.close();

            }

        } catch (MqttException e) {
            log.error("MQTT disconnect error", e);
        }
    }
}
