# Báo Cáo Kiểm Thử End-to-End (E2E) Tính Năng Tier-1 Anomaly Detection

## 1. Môi trường kiểm thử

- **Hệ điều hành**: Windows 11 (Isolated Local Environment)
- **Docker Engine**: Server Version `29.7.2`
- **PostgreSQL**: `public.ecr.aws/supabase/postgres:17.11.0.018` (Supabase Local Stack trên cổng 54322)
- **MQTT Broker**: `eclipse-mosquitto:2.0.18` (Local container trên cổng 1883)
- **Java / Runtime**: JDK `21.0.12`, Spring Boot `3.4.3`
- **Nhánh Git**: `feature/anomaly-rule-engine`
- **Datasource URL**: `jdbc:postgresql://127.0.0.1:54322/postgres` (Cam kết 100% localhost, không kết nối remote/staging)
- **Các commit liên quan trong đợt kiểm thử**:
  - `656350c`: `fix(telemetry): normalize temp metric to temperature when extracting from flat map`
  - `57cee0a`: `fix(migrations): drop dependent trigger before dropping devices.home_id`
  - `9b0f842`: `fix(db): restore devices.last_seen column dropped by iot_device_refactor` (migration `20261111171000_add_device_last_seen.sql`)
  - `d26f540`: `fix(db): repair functions referencing dropped devices.home_id` (migration `20261111172000_fix_devices_home_id_references.sql`)
  - `87e8522`: `fix(notification): avoid LazyInitializationException in AnomalyNotificationListener`
  - `1897c16`: `fix(mqtt): remove class-level @Transactional from MqttMessageReceiver to prevent nested REQUIRES_NEW deadlock`

---

## 2. Bảng kết quả 14 kịch bản kiểm thử E2E

Tất cả 14 kịch bản đã được chạy tự động qua script kiểm thử E2E kết hợp MQTT Publisher (`mosquitto_pub`), REST API Client (JWT Bearer Token của OWNER và CUSTOM), và truy vấn trực tiếp vào PostgreSQL:

| STT | Tên kịch bản | Đầu vào (MQTT / REST API) | Kết quả mong đợi | Kết quả thực tế | Đánh giá |
|:---:|:---|:---|:---|:---|:---:|
| **1** | Telemetry hợp lệ không sinh anomaly | `gas_ppm = 200` ppm qua topic `/telemetry` | `SensorReading = 1`, `DeviceAnomaly = 0` | `SensorReading = 1`, `DeviceAnomaly = 0` | **PASS** |
| **2** | Vượt ngưỡng WARN sinh anomaly HIGH | `gas_ppm = 450` ppm (2 lần liên tiếp, ngưỡng warn=400) | `status = OPEN`, `severity = HIGH`, `type = GAS_LEAKAGE` | `status = OPEN`, `severity = HIGH` (type=GAS_LEAKAGE, count=1) | **PASS** |
| **3** | Vượt ngưỡng CRITICAL nâng severity | `gas_ppm = 1200` ppm (ngưỡng critical=1000) | `status = OPEN`, `severity = CRITICAL` | `status = OPEN`, `severity = CRITICAL` (`current_value = 1200.0`) | **PASS** |
| **4** | Giá trị an toàn tự động RESOLVED theo Hysteresis | `gas_ppm = 150` ppm (3 lần liên tiếp <= ngưỡng clear 360 ppm) | `status = RESOLVED`, `resolved_at != null`, note Hysteresis | `status = RESOLVED`, `note` = "Tự động phục hồi theo cơ chế Hysteresis: giá trị an toàn (150.0 <= 360.0) duy trì qua 3 lần đọc liên tiếp" | **PASS** |
| **5** | Deduplication trong cửa sổ dedup | Gửi liên tiếp 3 gói gas vượt ngưỡng khi đã có sự cố OPEN | Duy nhất 1 anomaly OPEN, `occurrence_count >= 2` | `open_count = 1`, `occurrence_count = 3` | **PASS** |
| **6** | Nhiều metric trong 1 gói flat map | `{"temperature": 55.0, "humidity": 80.0}` | Temperature sinh anomaly HIGH, humidity không sinh anomaly | `temp = OPEN\|HIGH\|temperature`, `humidity = 0` | **PASS** |
| **7** | Thiết bị ngoài danh sách theo dõi offline | `MOTION_SENSOR` có `last_seen = now() - 10m` | Không sinh anomaly `SENSOR_OFFLINE` | `SENSOR_OFFLINE count = 0` | **PASS** |
| **8** | Cảm biến trong danh sách theo dõi offline | `TEMP_SENSOR` có `last_seen = now() - 10m` (ngưỡng 5m) | `status = OPEN`, `severity = MEDIUM`, `type = SENSOR_OFFLINE` | `status = OPEN`, `severity = MEDIUM`, `expected_range = last_seen <= 5m` | **PASS** |
| **9** | Khôi phục kết nối & chuẩn hóa flat map `temp` | 1. Flat map `{"temp": 55}`; 2. Standard `{"metricType": "temperature", "value": 24.5}` | Metric lưu là `temperature`, `SENSOR_OFFLINE` tự động RESOLVED, `HIGH_TEMPERATURE` tự động RESOLVED | `reading = temperature\|55.000`, `Offline = RESOLVED`, `TempAnom = RESOLVED` | **PASS** |
| **10** | Phân quyền thông báo theo Access Scope | Sự cố trên `GAS_SENSOR` (USER1 là OWNER, USER2 là CUSTOM có quyền NONE) | USER1 nhận thông báo (>0), USER2 không nhận thông báo (=0) | `USER1_notifs = 3`, `USER2_notifs = 0` | **PASS** |
| **11** | REST API lọc danh sách sự cố theo quyền thiết bị | `GET /api/v1/homes/{homeId}/anomalies` với Bearer Token OWNER vs CUSTOM | OWNER thấy sự cố Gas Sensor, CUSTOM không thấy sự cố Gas Sensor | `Owner total = 4 (sees_gas = True)`, `Custom total = 2 (sees_gas = False)` | **PASS** |
| **12** | REST API xác nhận sự cố (Acknowledge) | `PUT /api/v1/homes/{homeId}/anomalies/{id}/acknowledge` (Token OWNER) | HTTP 200, `status = ACKNOWLEDGED`, `acknowledged_by = USER1_ID` | HTTP 200, `status = ACKNOWLEDGED`, `acknowledged_by = 65d33621-3665-4807-adb6-5a34386abdc4` | **PASS** |
| **13** | REST API giải quyết thủ công (Resolve) | `PUT /api/v1/homes/{homeId}/anomalies/{id}/resolve` kèm ghi chú | HTTP 200, `status = RESOLVED`, lưu đúng `resolution_note` và `resolved_by` | HTTP 200, `status = RESOLVED`, `note` = "Đã kiểm tra van gas và xử lý rò rỉ", `resolved_by = USER1_ID` | **PASS** |
| **14** | Tính lũy đẳng (Idempotency) khi Resolve | Gọi lại `PUT /resolve` lần 2 trên sự cố đã RESOLVED | HTTP 200, không lỗi 500, bảo toàn dữ liệu ban đầu | HTTP 200, `status = RESOLVED`, note ban đầu được bảo toàn | **PASS** |

---

## 3. Phát hiện từ rà soát & Các giải pháp kỹ thuật đã thực hiện

### 3.1. Chuẩn hóa metric `temp` -> `temperature` khi trích xuất từ flat map (Commit `656350c`)
- **Vấn đề**: Trong `MqttMessageReceiver.extractAndProcessSafetyMetricsFromFlatMap()`, gói flat map chứa khóa `"temp"` được gửi vào `processTelemetry` với `metricType = "temp"`. Điều này làm lệch chuẩn dữ liệu so với kênh `/telemetry` chuẩn (vốn luôn dùng `"temperature"`), khiến cơ chế tra cứu ngưỡng và suy diễn anomaly bị phân mảnh giữa 2 metric khác nhau.
- **Giải pháp**: Chuẩn hóa `"temp"` thành `"temperature"` ngay tại `extractSingleSafetyMetric()`. Đã kiểm chứng trong Kịch bản 9: SensorReading được lưu với metric chuẩn `"temperature"` và tự động khôi phục kết nối.

### 3.2. Khắc phục lỗi schema migration & Trigger tham chiếu cột bị drop
1. **Khôi phục cột `devices.last_seen` (Commit `9b0f842`)**:
   - `20260925142119_iot_device_refactor.sql` từng drop cột `last_seen`. Khi khởi động với `hibernate.ddl-auto=validate`, Hibernate báo lỗi `missing column [last_seen] in table [devices]`.
   - Đã tạo migration mới `20261111171000_add_device_last_seen.sql` để khôi phục cột nullable, phục vụ tính năng phát hiện cảm biến mất kết nối (`SENSOR_OFFLINE`) và Twin Health.
2. **Sửa các trigger function tham chiếu `devices.home_id` (Commit `d26f540`)**:
   - Sau refactor, `devices.home_id` bị drop nhưng các trigger function lịch sử (`enforce_behavior_location_same_home`, `enforce_automation_device_same_home`, `enforce_scene_action_same_home`, `prevent_scene_home_mismatch`) vẫn query `devices.home_id`, gây lỗi `UnexpectedRollbackException` khi telemetry kích hoạt behavior trigger.
   - Đã tạo migration mới `20261111172000_fix_devices_home_id_references.sql`, viết lại cả 4 function sử dụng `COALESCE(r.home_id, en.home_id)` để suy ra home của thiết bị qua phòng hoặc edge node mà không bị chặn nhầm thiết bị chưa gán phòng.

### 3.3. Sửa lỗi `LazyInitializationException` trong `AnomalyNotificationListener` (Commit `87e8522`)
- **Vấn đề**: `@EventListener` xử lý bất đồng bộ (`@Async`) trong `AnomalyNotificationListener` khi truy cập `device.getRoom().getName()` gặp `LazyInitializationException` do session Hibernate ban đầu đã đóng.
- **Giải pháp**: Thêm `@Transactional(readOnly = true)` vào phương thức listener và dùng `deviceRepository.findByIdWithNode(...)` để fetch eager room và edge node liên kết.

### 3.4. Phát hiện & Sửa lỗi Deadlock nghiêm trọng: Nested `REQUIRES_NEW` Transaction trong MQTT Inbound (Commit `1897c16`)
- **Hiện tượng**: Khi nhận gói tin MQTT telemetry đầu tiên, tiến trình Spring Boot ghi nhận `Saved SensorReading`, nhưng giao dịch không thể commit và luồng xử lý bị treo vĩnh viễn (hang), dẫn đến việc broker ngắt kết nối do Paho timeout.
- **Nguyên nhân gốc rễ (Root Cause)**:
  1. `MqttMessageReceiver` được đánh dấu `@Transactional` ở mức class. Do đó, toàn bộ phương thức `handleMessage()` chạy trong một Transaction A lớn.
  2. `telemetryService.processTelemetry()` cập nhật `Device` (`deviceRepository.save(device)`), giữ khóa dòng độc quyền (Exclusive Row Lock) trên hàng của bảng `devices`.
  3. Ngay sau đó, `processTelemetryAutomation()` gọi `automationEngine.process()`, kích hoạt `behaviorEventRecorder.recordSensor()`.
  4. Phương thức `recordSensor()` có chú thích `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Spring tạm ngưng Transaction A và mở Transaction B độc lập.
  5. Trong Transaction B, lệnh `INSERT INTO behavior_events` kích hoạt kiểm tra ràng buộc Foreign Key `behavior_events_device_id_fkey` tới bảng `devices`. PostgreSQL thực thi truy vấn nội bộ:
     `SELECT 1 FROM ONLY "public"."devices" x WHERE "id" = $1 FOR KEY SHARE OF x`
  6. Khóa `FOR KEY SHARE` này buộc phải đợi Transaction A (vừa cập nhật dòng đó) commit hoặc rollback.
  7. Tuy nhiên, Transaction A đang bị tạm ngưng để đợi Transaction B hoàn thành. Hai transaction rơi vào vòng lặp phụ thuộc lẫn nhau (**Self-Deadlock**), khiến luồng xử lý treo vô thời hạn.
- **Giải pháp**: Loại bỏ `@Transactional` ở cấp class trên `MqttMessageReceiver`. Các phương thức nghiệp vụ hạ tầng (`telemetryService.processTelemetry`, `deviceService.updateDeviceStateFromMqtt`) đã có sẵn `@Transactional` độc lập ở cấp service, đảm bảo mỗi bước xử lý commit và giải phóng khóa trước khi bước tự động hóa tiếp theo mở transaction mới.

### 3.5. Kiến nghị 1: Sử dụng `CHECK ... NOT VALID` trên các bảng dữ liệu lớn trong Production
- Khi bổ sung ràng buộc kiểm tra (Check Constraint) hoặc Foreign Key trên các bảng có khối lượng bản ghi lớn (như `sensor_readings`, `behavior_events`, `device_state_history`), việc sử dụng cú pháp `ALTER TABLE ... ADD CONSTRAINT ... CHECK (...)` mặc định sẽ quét toàn bộ bảng và giữ khóa `ACCESS EXCLUSIVE`.
- **Khuyến nghị**: Nên tách thành 2 bước trong migration:
  1. `ALTER TABLE ... ADD CONSTRAINT ... CHECK (...) NOT VALID;` (chỉ khóa catalog trong vài mili-giây, áp dụng kiểm tra cho các bản ghi mới).
  2. `ALTER TABLE ... VALIDATE CONSTRAINT ...;` (chạy quét nền xác thực dữ liệu cũ với khóa `SHARE UPDATE EXCLUSIVE`, không chặn đọc/ghi của người dùng).

### 3.6. Kiến nghị 2: Tối ưu N+1 Query trong phân quyền thông báo cho thành viên `CUSTOM`
- **Hiện trạng**: Trong `AnomalyNotificationListener`, khi duyệt qua danh sách thành viên trong nhà:
  ```java
  for (HomeMember member : members) {
      if (member.getAccessScope() == AccessScope.CUSTOM) {
          MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);
          AccessLevel level = homeAuthorizationService.effectiveLevel(ctx, device);
          ...
      }
  }
  ```
- **Rủi ro**: Với nhà có nhiều thành viên `CUSTOM`, việc gọi `loadAccessContext()` trong vòng lặp gây ra N+1 query tới `member_device_access` và `member_room_access`.
- **Khuyến nghị**: Trong Phase 2, bổ sung phương thức batch query:
  `memberDeviceAccessRepository.findEffectiveLevelsForDevice(homeId, deviceId)` để tải trước toàn bộ quyền của các thành viên trong 1 query duy nhất, giảm tải I/O cho cơ sở dữ liệu khi phát sinh sự cố an toàn trên diện rộng.
