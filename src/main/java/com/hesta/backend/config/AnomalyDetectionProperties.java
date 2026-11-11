package com.hesta.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for Tier-1 Anomaly Detection.
 * NOTE: These are baseline safe defaults for uncalibrated MQ-x and DHT sensors.
 * Must be calibrated against real hardware telemetry in production, NOT international standards.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "hesta.anomaly")
public class AnomalyDetectionProperties {

    private boolean enabled = true;

    private Map<String, MetricThresholdConfig> metrics = new HashMap<>();

    /** Offline monitoring parameters (Phase 1.5) */
    private Duration offlineThreshold = Duration.ofMinutes(5);
    private Duration offlineCheckInterval = Duration.ofSeconds(60);
    private Duration offlineGracePeriod = Duration.ofMinutes(5);
    private Duration offlineMaxAge = Duration.ofHours(24);
    private List<String> offlineMonitoredTypes = List.of(
            "GAS_SENSOR",
            "SMOKE_SENSOR",
            "FIRE_SENSOR",
            "TEMP_SENSOR",
            "TEMP_HUMID_SENSOR",
            "ENVIRONMENT_SENSOR"
    );

    public AnomalyDetectionProperties() {
        initDefaultMetrics();
    }

    private void initDefaultMetrics() {
        // gas_ppm: MQ-2/MQ-5/MQ-6
        MetricThresholdConfig gas = new MetricThresholdConfig();
        gas.setEnabled(true);
        gas.setWarn(400.0);
        gas.setCritical(1000.0);
        gas.setValidMin(0.0);
        gas.setValidMax(10000.0);
        gas.setUnit("ppm");
        gas.setConfirmReadingsHigh(2);
        gas.setConfirmReadingsCritical(1);
        gas.setClearMargin(0.10);
        gas.setClearReadings(3);
        gas.setNotificationCooldown(Duration.ofMinutes(5));
        gas.setManualResolveSuppress(Duration.ofMinutes(10));
        metrics.put("gas_ppm", gas);

        // smoke: MQ-2/MQ-135
        MetricThresholdConfig smoke = new MetricThresholdConfig();
        smoke.setEnabled(true);
        smoke.setWarn(300.0);
        smoke.setCritical(1000.0);
        smoke.setValidMin(0.0);
        smoke.setValidMax(10000.0);
        smoke.setUnit("ppm");
        smoke.setConfirmReadingsHigh(2);
        smoke.setConfirmReadingsCritical(1);
        smoke.setClearMargin(0.10);
        smoke.setClearReadings(3);
        smoke.setNotificationCooldown(Duration.ofMinutes(5));
        smoke.setManualResolveSuppress(Duration.ofMinutes(10));
        metrics.put("smoke", smoke);

        // temperature: DHT11/DHT22/Sensirion
        MetricThresholdConfig temp = new MetricThresholdConfig();
        temp.setEnabled(true);
        temp.setWarn(50.0);
        temp.setCritical(65.0);
        temp.setValidMin(-40.0);
        temp.setValidMax(85.0);
        temp.setUnit("°C");
        temp.setConfirmReadingsHigh(2);
        temp.setConfirmReadingsCritical(1);
        temp.setClearMargin(0.10);
        temp.setClearReadings(3);
        temp.setNotificationCooldown(Duration.ofMinutes(5));
        temp.setManualResolveSuppress(Duration.ofMinutes(10));
        metrics.put("temperature", temp);
    }

    public MetricThresholdConfig getConfig(String metric) {
        if (metric == null) return null;
        String m = metric.trim().toLowerCase();
        if (m.equals("gas") || m.equals("gas_ppm")) return metrics.get("gas_ppm");
        if (m.equals("smoke")) return metrics.get("smoke");
        if (m.equals("temperature") || m.equals("temp")) return metrics.get("temperature");
        return metrics.get(m);
    }

    @Data
    public static class MetricThresholdConfig {
        private boolean enabled = true;
        private Double warn;
        private Double critical;
        private Double validMin;
        private Double validMax;
        private String unit = "";
        private int confirmReadingsHigh = 2;
        private int confirmReadingsCritical = 1;
        private double clearMargin = 0.10; // 10% below warn
        private int clearReadings = 3;
        private Duration notificationCooldown = Duration.ofMinutes(5);
        private Duration manualResolveSuppress = Duration.ofMinutes(10);
    }
}
