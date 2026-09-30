package com.hesta.backend.config.mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MQTT_SMOKE_TEST", matches = "true")
class MqttSmokeTest {

    @Autowired
    private MqttGateway mqttGateway;

    @Test
    void testPublishRoundTrip() throws InterruptedException {
        // Publish to a test state topic that the MqttMessageReceiver is listening to
        String testTopic = "hesta/nodes/test_node/devices/test_device/state";
        String testPayload = "{\"power\":\"ON\"}";
        
        mqttGateway.sendToMqtt(testTopic, 1, testPayload);
        
        // Wait a bit to see the log in console
        Thread.sleep(1000);
    }
}
