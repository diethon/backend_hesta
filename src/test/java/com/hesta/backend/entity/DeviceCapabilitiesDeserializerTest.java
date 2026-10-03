package com.hesta.backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceCapabilitiesDeserializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserialize_DirectClass_EmptyArray_ReturnsEmptyDeviceCapabilities() throws Exception {
        DeviceCapabilities caps = objectMapper.readValue("[]", DeviceCapabilities.class);
        assertNotNull(caps);
        assertTrue(caps.isEmpty());
    }

    @Test
    void deserialize_DirectClass_LegacyStringArray_ReturnsDefaultGroupedMap() throws Exception {
        DeviceCapabilities caps = objectMapper.readValue("[\"POWER_ON\", \"TURN_OFF\"]", DeviceCapabilities.class);
        assertNotNull(caps);
        assertTrue(caps.containsKey("DEFAULT"));
        assertEquals(List.of("POWER_ON", "TURN_OFF"), caps.get("DEFAULT"));
    }

    @Test
    void deserialize_DirectClass_JsonObject_ReturnsParsedMap() throws Exception {
        DeviceCapabilities caps = objectMapper.readValue("{\"POWER\": [\"TURN_ON\", \"TURN_OFF\"], \"COLOR\": [\"SET_COLOR\"]}", DeviceCapabilities.class);
        assertNotNull(caps);
        assertEquals(List.of("TURN_ON", "TURN_OFF"), caps.get("POWER"));
        assertEquals(List.of("SET_COLOR"), caps.get("COLOR"));
    }

    @Test
    void deserialize_DeviceWithLegacyArray_SuccessfullyDeserializes() throws Exception {
        String deviceJson = """
                {
                    "name": "Test Lamp",
                    "deviceType": "LIGHT",
                    "status": "ONLINE",
                    "capabilities": ["TURN_ON", "TURN_OFF"]
                }
                """;

        Device device = objectMapper.readValue(deviceJson, Device.class);
        assertNotNull(device);
        assertNotNull(device.getCapabilities());
        assertTrue(device.supportsAction("TURN_ON"));
        assertTrue(device.supportsAction("TURN_OFF"));
        assertFalse(device.supportsAction("SET_COLOR"));
    }

    @Test
    void deserialize_DeviceWithEmptyArray_SuccessfullyDeserializes() throws Exception {
        String deviceJson = """
                {
                    "name": "Test Lamp",
                    "deviceType": "LIGHT",
                    "status": "ONLINE",
                    "capabilities": []
                }
                """;

        Device device = objectMapper.readValue(deviceJson, Device.class);
        assertNotNull(device);
        assertNotNull(device.getCapabilities());
        assertTrue(device.getCapabilities().isEmpty());
    }
}
