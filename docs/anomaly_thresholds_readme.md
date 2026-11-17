# HESTA Tier-1 Anomaly Detection Engine: Thresholds & Operational Guide

Tài liệu hướng dẫn vận hành, cấu hình ngưỡng và quy trình hiệu chuẩn phần cứng cảm biến cho hệ thống **Tier-1 Anomaly Detection (Rule-Based)** của HESTA Smart Home.

---

## 1. CẢNH BÁO QUAN TRỌNG VỀ HIỆU CHUẨN CẢM BIẾN (HARDWARE CALIBRATION WARNING)

> [!WARNING]
> **Các giá trị ngưỡng mặc định (`warn`, `critical`) trong cấu hình hiện tại chỉ là các mốc an toàn cơ sở (safe baseline defaults) áp dụng cho các cảm biến phổ thông chưa qua hiệu chuẩn phòng thí nghiệm (MQ-2, MQ-5, DHT11/22).**
> 
> **TUYỆT ĐỐI KHÔNG** sử dụng tiêu chuẩn khí thải công nghiệp quốc tế (như OSHA, WHO, EPA) để gán trực tiếp vào đầu ra raw ppm của MQ-x mà chưa qua hiệu chuẩn điện trở thực tế ($R_0$). Cảm biến điện trở oxit bán dẫn (MOS) bị ảnh hưởng rất mạnh bởi:
> - Độ ẩm và nhiệt độ môi trường xung quanh.
> - Điện áp cấp nguồn $V_{cc}$ và độ sụt áp dây dẫn của kit ESP32.
> - Quá trình lão hóa của dây sấy (pre-heating period: cần ít nhất 24-48 giờ sấy nhiệt trước khi đo ổn định).

### Quy trình hiệu chuẩn cảm biến khí gas/khói (MQ-2, MQ-5, MQ-6) trên thực tế:
1. **Đo điện trở trong không khí sạch ($R_0$):**
   - Đặt cảm biến trong phòng kín không có mùi gas, chạy liên tục 24h.
   - Tính toán điện trở cảm biến $R_s = \frac{V_{cc} - V_{out}}{V_{out}} \times R_L$.
   - Xác định $R_0 = \frac{R_s}{CleanAirRatio}$ (đối với MQ-2, tỉ số trong không khí sạch khoảng $9.83$).
2. **Hiệu chỉnh trong firmware ESP32:**
   - Nạp giá trị $R_0$ thực nghiệm vào code ESP32 trước khi tính toán ra ppm:
     $$ppm = a \times \left(\frac{R_s}{R_0}\right)^b$$
   - Khi đó, giá trị `gas_ppm` gửi lên MQTT mới phản ánh nồng độ tương đối chính xác.
3. **Cân chỉnh ngưỡng trên HESTA Backend:**
   - Sau khi firmware tính toán ổn định, ghi nhận giá trị nền trung bình (baseline) trong 48h.
   - Thiết lập `warn = baseline * 2.5` và `critical = baseline * 5.0`.

---

## 2. Danh Mục Tham Số Cấu Hình (`application.properties`)

```properties
# Bật/tắt toàn bộ engine
hesta.anomaly.enabled=true

# --- Cảm biến Gas (gas_ppm: MQ-2, MQ-5, MQ-6) ---
hesta.anomaly.metrics.gas_ppm.enabled=true
hesta.anomaly.metrics.gas_ppm.warn=400.0
hesta.anomaly.metrics.gas_ppm.critical=1000.0
hesta.anomaly.metrics.gas_ppm.valid-min=0.0
hesta.anomaly.metrics.gas_ppm.valid-max=10000.0
hesta.anomaly.metrics.gas_ppm.unit=ppm
hesta.anomaly.metrics.gas_ppm.confirm-readings-high=2
hesta.anomaly.metrics.gas_ppm.confirm-readings-critical=1
hesta.anomaly.metrics.gas_ppm.clear-margin=0.10
hesta.anomaly.metrics.gas_ppm.clear-readings=3
hesta.anomaly.metrics.gas_ppm.notification-cooldown=5m
hesta.anomaly.metrics.gas_ppm.manual-resolve-suppress=10m

# --- Cảm biến Khói (smoke: MQ-2 ppm) ---
hesta.anomaly.metrics.smoke.enabled=true
hesta.anomaly.metrics.smoke.warn=300.0
hesta.anomaly.metrics.smoke.critical=1000.0
hesta.anomaly.metrics.smoke.valid-min=0.0
hesta.anomaly.metrics.smoke.valid-max=10000.0
hesta.anomaly.metrics.smoke.unit=ppm
hesta.anomaly.metrics.smoke.confirm-readings-high=2
hesta.anomaly.metrics.smoke.confirm-readings-critical=1
hesta.anomaly.metrics.smoke.clear-margin=0.10
hesta.anomaly.metrics.smoke.clear-readings=3
hesta.anomaly.metrics.smoke.notification-cooldown=5m
hesta.anomaly.metrics.smoke.manual-resolve-suppress=10m

# --- Cảm biến Nhiệt độ quá cao (temperature: °C) ---
hesta.anomaly.metrics.temperature.enabled=true
hesta.anomaly.metrics.temperature.warn=50.0
hesta.anomaly.metrics.temperature.critical=65.0
hesta.anomaly.metrics.temperature.valid-min=-20.0
hesta.anomaly.metrics.temperature.valid-max=100.0
hesta.anomaly.metrics.temperature.unit=°C
hesta.anomaly.metrics.temperature.confirm-readings-high=2
hesta.anomaly.metrics.temperature.confirm-readings-critical=1
hesta.anomaly.metrics.temperature.clear-margin=0.05
hesta.anomaly.metrics.temperature.clear-readings=3
hesta.anomaly.metrics.temperature.notification-cooldown=10m
hesta.anomaly.metrics.temperature.manual-resolve-suppress=15m

# --- Giám sát Cảm biến Offline (Phase 1.5) ---
hesta.anomaly.offline-threshold=5m
hesta.anomaly.offline-check-interval=60s
hesta.anomaly.offline-grace-period=5m
hesta.anomaly.offline-max-age=24h
```

---

## 3. Vòng Đời & Cơ Chế Hoạt Động (State Machine & Mechanics)

```
        [Readings vượt Warn/Critical Streak]
                       │
                       ▼
                 ┌───────────┐
                 │   OPEN    │ ◄─── Escalation (HIGH -> CRITICAL)
                 └─────┬─────┘
                       │
       ┌───────────────┴───────────────┐
       ▼                               ▼
[Acknowledge API]             [Hysteresis Auto-Clear
       │                       hoặc Resolve API]
       ▼                               │
┌──────────────┐                       │
│ ACKNOWLEDGED │                       │
└──────┬───────┘                       │
       │                               │
       └───────────────┬───────────────┘
                       │
                       ▼
                 ┌───────────┐
                 │ RESOLVED  │ (Kích hoạt Cooldown Suppress Window)
                 └───────────┘
```

1. **Confirmation Streak (Chống nhiễu ADC):**
   - Vượt ngưỡng cảnh báo `warn` cần $N$ lần đọc liên tiếp (`confirmReadingsHigh = 2`).
   - Vượt ngưỡng nguy hiểm `critical` kích hoạt ngay lập tức (`confirmReadingsCritical = 1`).
2. **Hysteresis Auto-Clear:**
   - Để tự động chuyển sang `RESOLVED`, giá trị đo phải nằm sâu dưới ngưỡng cảnh báo theo biên độ $clearMargin$ (ví dụ: $400 \times (1 - 0.10) = 360$ ppm) liên tục qua 3 lần đọc (`clearReadings = 3`). Ngăn ngừa việc trạng thái bật/tắt liên tục khi giá trị dao động quanh ngưỡng.
3. **Manual Resolution Suppress Window:**
   - Khi người dùng bấm "Xử lý" (`resolve`), hệ thống kích hoạt cửa sổ tạm hoãn cảnh báo 10-15 phút. Điều này cho phép người dùng có thời gian mở cửa thông gió hoặc ngắt nguồn mà không bị hệ thống spam thông báo lại ngay lập tức.
4. **Idempotent REST APIs:**
   - Các API `acknowledge` và `resolve` được thiết kế idempotent: gọi lặp lại khi đã ở trạng thái đích sẽ trả về kết quả hiện tại mà không ghi đè người xử lý (`resolved_by`) hay thời gian xử lý (`resolved_at`).

---

## 4. Hướng Dẫn Chạy Migration Trên Cơ Sở Dữ Liệu (Supabase CLI)

> [!IMPORTANT]
> Toàn bộ quá trình phát triển tuân thủ nguyên tắc an toàn: **CHỈ kết nối đọc (SELECT), KHÔNG chạy `supabase db push` tự động trên database môi trường dùng chung.**
> 
> **LƯU Ý VỀ KHỞI ĐỘNG ỨNG DỤNG:**
> Dự án HESTA Backend cấu hình `spring.jpa.hibernate.ddl-auto=validate`. Khi khởi động, Hibernate sẽ đối chiếu toàn bộ Entity (`DeviceAnomaly`, `Notification`, v.v.) với schema thực tế trong database. **Nếu chưa chạy migration, backend sẽ báo lỗi `SchemaManagementException` (thiếu cột hoặc chỉ mục) và từ chối khởi động.**

### Quy trình áp dụng migration an toàn:

#### Bước 1: Kiểm tra trước bằng cờ `--dry-run` (Dry Run)
Luôn kiểm tra các câu lệnh SQL sẽ được áp dụng mà không làm thay đổi dữ liệu:
```bash
cd backend_hesta
supabase link --project-ref <PROJECT_ID>
supabase db push --dry-run
```

#### Bước 2: Áp dụng và kiểm thử trước trên môi trường DB Dev / Local
Thực thi migration trên database phát triển riêng biệt:
```bash
supabase db push
# Chạy toàn bộ test suite để xác minh tính tương thích
mvn test -Dtest=AnomalyDetectionServiceTest,DeviceAnomalyManagementServiceImplTest
```

#### Bước 3: Sao lưu (Backup) cơ sở dữ liệu chính trước khi chạy Production
Trước khi áp dụng lên môi trường production:
```bash
supabase db dump -f backup_pre_anomaly_$(date +%Y%m%d).sql
```

#### Bước 4: Áp dụng migration lên database chính
- **Qua Supabase CLI:**
  ```bash
  supabase db push
  ```
- **Hoặc qua Supabase SQL Editor:** Mở và chạy file:
  `backend_hesta/supabase/migrations/20261111170000_extend_device_anomalies_schema.sql`
Mở và thực thi file sau trong Supabase SQL Editor:
`backend_hesta/supabase/migrations/20261111170000_extend_device_anomalies_schema.sql`

File migration chứa:
- Mở rộng các cột: `metric`, `source`, `score`, `reason`, `reading_id`, `last_seen_at`, `occurrence_count`, `acknowledged_at`, `acknowledged_by`, `resolved_at`, `resolved_by`, `false_positive`, `resolution_note`.
- Thêm ràng buộc `CHECK` cho `status`, `severity`, `source`.
- Partial unique index chống trùng bản ghi active:
  ```sql
  CREATE UNIQUE INDEX idx_device_anomalies_active_unique 
  ON device_anomalies (device_id, anomaly_type) 
  WHERE status IN ('OPEN', 'ACKNOWLEDGED');
  ```
- Composite index tối ưu hóa phân trang & tra cứu: `idx_device_anomalies_home_status_severity`.

---

## 5. Tài Liệu REST API Cho Frontend & Mobile

### 1. Danh sách bất thường (Phân trang ở tầng DB)
`GET /api/v1/homes/{homeId}/anomalies`
- **Query Params:**
  - `status`: `OPEN` | `ACKNOWLEDGED` | `RESOLVED` (tùy chọn)
  - `severity`: `LOW` | `MEDIUM` | `HIGH` | `CRITICAL` (tùy chọn)
  - `page`: số trang (bắt đầu từ 0)
  - `size`: số lượng bản ghi / trang (mặc định 20)
- **Quyền hạn:**
  - `OWNER` hoặc thành viên `ALL`: xem toàn bộ bất thường trong nhà.
  - Thành viên `CUSTOM`: hệ thống chỉ trả về bất thường của các thiết bị mà thành viên đó có quyền xem (`VIEW` hoặc `CONTROL`).

### 2. Xác nhận sự cố (Acknowledge)
`PUT /api/v1/homes/{homeId}/anomalies/{id}/acknowledge`
- **Quyền hạn:** Có quyền xem thiết bị (`VIEW`).
- **Phản hồi:** Trả về đối tượng bất thường với `status = ACKNOWLEDGED`, `acknowledgedAt`, `acknowledgedBy`. Idempotent nếu đã xác nhận.

### 3. Xử lý đóng sự cố (Resolve)
`PUT /api/v1/homes/{homeId}/anomalies/{id}/resolve`
- **Payload:**
  ```json
  {
    "falsePositive": false,
    "note": "Đã kiểm tra đầu van gas an toàn, thông gió phòng bếp"
  }
  ```
- **Quyền hạn:** Có quyền điều khiển thiết bị (`CONTROL`) hoặc chủ nhà (`OWNER`).
- **Phản hồi:** `status = RESOLVED`, `resolvedAt`, `resolvedBy`, ghi chú xử lý. Kích hoạt khoảng hoãn cảnh báo 10-15 phút.

---

## 6. Kiến Trúc Kênh STOMP WebSocket Realtime Cho Anomaly Notification

Hệ thống HESTA đã hợp nhất toàn bộ kênh đẩy sự kiện thời gian thực (realtime broker) về một kênh duy nhất theo từng ngôi nhà:

- **Subscribe Destination:** `/topic/homes/{homeId}/events`
- **Loại sự kiện:** `RealtimeEventType.NOTIFICATION_CREATED`
- **Phong bì dữ liệu (Envelope):** `RealtimeEvent<NotificationRealtimePayload>`
  ```json
  {
    "eventId": "uuid",
    "type": "NOTIFICATION_CREATED",
    "homeId": "uuid",
    "timestamp": "2026-11-12T16:00:00Z",
    "data": {
      "notificationId": "uuid",
      "recipientId": "uuid",
      "homeId": "uuid",
      "isRead": false,
      "createdAt": "2026-11-12T16:00:00Z"
    }
  }
  ```
- **Xác thực & Ủy quyền kênh:** `RealtimeWebSocketChannelInterceptor` chặn kết nối STOMP và chỉ cho phép người dùng đăng ký đúng `/topic/homes/{homeId}/events` nếu người dùng đó là thành viên hợp lệ của ngôi nhà (`subscriptionService.authorizeSubscription`). Mọi kênh khác (`/topic/user/...`, `/queue/...`, `/topic/home/...`) đều bị từ chối với lỗi `AccessDeniedException`.
- **Cơ chế nhận thông báo phía Frontend (`hesta_frontend`):**
  1. Frontend đăng ký lắng nghe `/topic/homes/{homeId}/events` qua `SharedRealtimeClient`.
  2. Khi có sự kiện `NOTIFICATION_CREATED`, `NotificationLifecycle` kiểm tra nếu `recipientId` khớp với người dùng đang đăng nhập, nó sẽ gọi `GET /notifications/{notificationId}` để tải chi tiết thông báo (đảm bảo tính bảo mật riêng tư, không phát tán nội dung nhạy cảm ra kênh chung).
  3. UI hiển thị badge thông báo và toast cảnh báo bất thường khẩn cấp ngay lập tức.
