package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceEnergyBreakdown;
import com.hesta.backend.dto.response.EnergyAnalyticsResponse;

import java.util.UUID;

public interface EnergyAnalyticsService {

    /**
     * Lấy toàn bộ phân tích năng lượng của ngôi nhà: Tổng quan, Biểu đồ mốc thời gian, và Phân bổ từng thiết bị.
     * @param userId Người dùng yêu cầu (kiểm tra quyền OWNER / MEMBER)
     * @param homeId ID ngôi nhà
     * @param period "24H", "7D", "30D", hoặc "MONTHLY"
     */
    EnergyAnalyticsResponse getHomeEnergyAnalytics(UUID userId, UUID homeId, String period);

    /**
     * Lấy thông số tiêu thụ điện riêng lẻ của một thiết bị.
     */
    DeviceEnergyBreakdown getDeviceEnergy(UUID userId, UUID deviceId, String period);

    /**
     * Tính toán số tiền điện VNĐ theo biểu giá bậc thang 6 mức của EVN.
     */
    long calculateEvnTariffCost(double kwh);

    /**
     * Xác định bậc giá điện EVN hiện tại (từ bậc 1 đến bậc 6).
     */
    int determineEvnTier(double kwh);
}
