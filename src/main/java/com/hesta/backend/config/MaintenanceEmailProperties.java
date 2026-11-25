package com.hesta.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "hesta.maintenance.email")
@Getter
@Setter
public class MaintenanceEmailProperties {

    /** Mặc định tắt để an toàn ở môi trường dev/staging. */
    private boolean enabled = false;

    /** Danh sách email admin nhận thông báo khi có ticket mới. */
    private List<String> adminRecipients = new ArrayList<>();

    private int maxRetries = 2;
    private long retryBackoffMs = 500L;
    private int maxPerTicketPerHour = 4;
}
