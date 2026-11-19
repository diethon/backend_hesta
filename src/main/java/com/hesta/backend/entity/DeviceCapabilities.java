package com.hesta.backend.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonDeserialize(using = DeviceCapabilitiesDeserializer.class)
public class DeviceCapabilities extends HashMap<String, List<String>> {

    public DeviceCapabilities() {
        super();
    }

    public DeviceCapabilities(Map<String, List<String>> map) {
        super(map != null ? map : Map.of());
    }
}
