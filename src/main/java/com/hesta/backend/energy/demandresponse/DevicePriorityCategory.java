package com.hesta.backend.energy.demandresponse;

public enum DevicePriorityCategory {
    SAFETY,     // Khẩn cấp, an toàn (báo cháy, rò gas, khóa cửa): Tuyệt đối không ngắt
    ESSENTIAL,  // Thiết yếu (tủ lạnh, camera, router): Không ngắt
    COMFORT,    // Tiện nghi (điều hòa, bình nước nóng, quạt): Tiết giảm khi quá tải nặng
    LUXURY      // Xa xỉ / Phụ trợ (đèn trang trí, ổ cắm phụ): Cắt ngay lập tức khi vượt ngưỡng
}
