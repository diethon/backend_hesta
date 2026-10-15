package com.hesta.backend.entity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DeviceCapabilitiesDeserializer extends JsonDeserializer<DeviceCapabilities> {

    @Override
    public DeviceCapabilities deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        DeviceCapabilities result = new DeviceCapabilities();
        if (node == null || node.isNull()) {
            return result;
        }

        if (node.isArray()) {
            // Legacy schema: ["POWER_ON", "TURN_ON"] or []
            List<String> actions = new ArrayList<>();
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    actions.add(element.asText());
                }
            }
            if (!actions.isEmpty()) {
                result.put("DEFAULT", actions);
            }
            return result;
        }

        if (node.isObject()) {
            // New schema: {"POWER": ["TURN_ON", "TURN_OFF"], "COLOR": ["SET_COLOR"]}
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                List<String> actions = new ArrayList<>();
                if (entry.getValue().isArray()) {
                    for (JsonNode actionNode : entry.getValue()) {
                        if (actionNode.isTextual()) {
                            actions.add(actionNode.asText());
                        }
                    }
                }
                result.put(entry.getKey(), actions);
            }
            return result;
        }

        return result;
    }
}
