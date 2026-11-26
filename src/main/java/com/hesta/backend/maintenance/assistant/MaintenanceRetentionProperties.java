package com.hesta.backend.maintenance.assistant;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hesta.assistant.retention")
@Getter
@Setter
public class MaintenanceRetentionProperties {

    /** Số ngày giữ lịch sử tin nhắn thô của hội thoại đã kết thúc (mặc định 90 ngày). */
    private int messagesDays = 90;

    /** Số ngày giữ ảnh mồ côi chưa gắn vào ticket trước khi xóa DB + Cloudinary (mặc định 1 ngày). */
    private int orphanImagesDays = 1;
}
