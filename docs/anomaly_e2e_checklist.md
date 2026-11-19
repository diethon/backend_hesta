# HESTA Tier-1 Anomaly Detection: End-to-End Testing Checklist

Tài liệu checklist hướng dẫn tự chạy kiểm thử tích hợp đầu-cuối (End-to-End) trên môi trường Database Development / Staging cho tính năng **Tier-1 Rule-Based Anomaly Detection & Sensor Offline Monitoring**.

---

## 1. Chuẩn Bị Biến Môi Trường & Migration

### Khai báo các biến kiểm thử (Terminal / Bash / PowerShell)
```bash
# Thay thế các giá trị bên dưới theo môi trường của bạn
export BACKEND_URL="http://localhost:8080"
export MQTT_HOST="localhost"
export TOKEN="<YOUR_JWT_ACCESS_TOKEN>"
export HOME_ID="<YOUR_HOME_UUID>"
export DEVICE_ID="<YOUR_GAS_SENSOR_UUID>"
export NODE_CODE="NODE_KITCHEN"
```

### Bước 1: Áp dụng Migration lên DB Dev (An toàn)
```bash
cd backend_hesta

# 1. Chạy dry-run để kiểm tra các câu lệnh SQL trước khi thực thi
supabase db push --dry-run

# 2. Áp dụng migration lên database dev
supabase db push
```

> [!NOTE]
> Nếu chạy qua Supabase SQL Editor / psql, thực thi file:
> `backend_hesta/supabase/migrations/20261111170000_extend_device_anomalies_schema.sql`

### Bước 2: Kiểm tra cấu trúc bảng sau khi Migration
Chạy câu SQL sau trong database client:
```sql
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'device_anomalies'
ORDER BY ordinal_position;
```
*Kỳ vọng:* Thấy đầy đủ các cột mới: `metric`, `source`, `score`, `reason`, `reading_id`, `last_seen_at`, `occurrence_count`, `acknowledged_at`, `acknowledged_by`, `resolved_at`, `resolved_by`, `false_positive`, `resolution_note`.

Kiểm tra Partial Unique Index:
```sql
SELECT indexname, indexdef 
FROM pg_indexes 
WHERE tablename = 'device_anomalies';
```
*Kỳ vọng:* Có `idx_device_anomalies_active_unique` với điều kiện `WHERE status IN ('OPEN', 'ACKNOWLEDGED')`.

---

## 2. Kịch Bản 1: Luồng Gas Leakage (Bình thường → Cảnh báo Warn → Nguy hiểm Critical → Hysteresis Auto-Resolve)

### Bước 1.1: Gửi telemetry mức bình thường (`gas_ppm = 250` < 400)
```bash
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":250.0,"unit":"ppm"}'
```
**Kiểm tra SQL:**
```sql
-- Xác nhận telemetry đã được lưu
SELECT id, metric_type, value, recorded_at FROM sensor_readings WHERE device_id = '$DEVICE_ID' ORDER BY recorded_at DESC LIMIT 1;
-- Xác nhận CHƯA CÓ anomaly nào được tạo
SELECT count(*) FROM device_anomalies WHERE device_id = '$DEVICE_ID' AND status IN ('OPEN', 'ACKNOWLEDGED');
```
*Kỳ vọng:* `sensor_readings` có 1 dòng (250 ppm), `device_anomalies` có 0 dòng active.

---

### Bước 1.2: Vượt ngưỡng cảnh báo Warn (`gas_ppm = 500` > 400)
Cấu hình yêu cầu 2 lần đọc liên tiếp (`confirmReadingsHigh = 2`) để chống nhiễu ADC.

**Lần đọc 1 (Streak 1/2 - Chưa cảnh báo):**
```bash
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":500.0,"unit":"ppm"}'
```
*Kiểm tra SQL:* `device_anomalies` vẫn 0 dòng active.

**Lần đọc 2 sau 5 giây (Streak 2/2 - Kích hoạt cảnh báo HIGH):**
```bash
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":520.0,"unit":"ppm"}'
```
**Kiểm tra SQL:**
```sql
-- Kiểm tra bản ghi Anomaly
SELECT id, anomaly_type, severity, status, current_value, expected_range, occurrence_count, detected_at
FROM device_anomalies 
WHERE device_id = '$DEVICE_ID' AND status = 'OPEN';

-- Kiểm tra Notification được tạo và gắn source_anomaly_id
SELECT id, recipient_id, title, message, priority_level, is_read, source_anomaly_id
FROM notifications 
WHERE source_anomaly_id IS NOT NULL 
ORDER BY created_at DESC LIMIT 3;
```
*Kỳ vọng:*
1. `device_anomalies` sinh 1 dòng: `severity = 'HIGH'`, `status = 'OPEN'`, `occurrence_count = 1`.
2. `notifications` sinh bản ghi với `priority_level = 'HIGH'`, `source_anomaly_id` trỏ đúng vào anomaly trên, và STOMP WebSocket đẩy thông báo realtime tới người dùng.

---

### Bước 1.3: Leo thang mức nguy hiểm Critical (`gas_ppm = 1200` >= 1000)
Gửi 1 gói tin vượt ngưỡng Critical:
```bash
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":1200.0,"unit":"ppm"}'
```
**Kiểm tra SQL:**
```sql
SELECT id, anomaly_type, severity, status, current_value, occurrence_count
FROM device_anomalies 
WHERE device_id = '$DEVICE_ID';
```
*Kỳ vọng:*
1. **Không tạo thêm dòng mới** (nhờ Unique Index), cập nhật trực tiếp trên dòng Anomaly hiện tại.
2. `severity` chuyển từ `HIGH` -> `CRITICAL`.
3. `status` reset về `OPEN` (nếu trước đó đã ACKNOWLEDGED).
4. `occurrence_count` tăng lên 2.
5. `notifications` nhận thêm 1 thông báo mức khẩn cấp mới ("CỰC KỲ NGUY HIỂM...").

---

### Bước 1.4: Tự động khôi phục qua cơ chế Hysteresis (An toàn <= 360 ppm qua 3 lần đọc)
Ngưỡng giải phóng: $400 \times (1 - 0.10) = 360\text{ ppm}$. Cần 3 lần đọc liên tiếp <= 360 ppm.

```bash
# Lần đọc 1: 300 ppm (clear streak 1/3)
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":300.0,"unit":"ppm"}'
sleep 3

# Lần đọc 2: 280 ppm (clear streak 2/3)
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":280.0,"unit":"ppm"}'
sleep 3

# Lần đọc 3: 250 ppm (clear streak 3/3 -> AUTO RESOLVE)
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":250.0,"unit":"ppm"}'
```
**Kiểm tra SQL:**
```sql
SELECT id, anomaly_type, severity, status, resolved_at, resolution_note 
FROM device_anomalies 
WHERE device_id = '$DEVICE_ID';
```
*Kỳ vọng:* `status` tự động chuyển thành `RESOLVED`, `resolved_at` được gán timestamp hiện tại, `resolution_note` ghi rõ lý do "Tự động phục hồi theo cơ chế Hysteresis...".

---

## 3. Kịch Bản 2: Giám Sát Cảm Biến Offline & Khôi Phục (Phase 1.5)

### Bước 2.1: Mô phỏng cảm biến mất kết nối quá 5 phút
Cập nhật giả lập `last_seen` của cảm biến lùi về 10 phút trước:
```sql
UPDATE devices 
SET last_seen = NOW() - INTERVAL '10 minutes' 
WHERE id = '$DEVICE_ID';
```
Chờ tối đa 60 giây (theo chu kỳ `@Scheduled` của `SensorOfflineMonitorScheduler`).

**Kiểm tra SQL:**
```sql
-- Kiểm tra anomaly SENSOR_OFFLINE
SELECT id, anomaly_type, severity, status, current_value, reason, detected_at 
FROM device_anomalies 
WHERE device_id = '$DEVICE_ID' AND anomaly_type = 'SENSOR_OFFLINE' AND status = 'OPEN';

-- Kiểm tra Device.status KHÔNG bị thay đổi (vẫn giữ nguyên ONLINE hoặc trạng thái cũ)
SELECT id, name, status, last_seen FROM devices WHERE id = '$DEVICE_ID';
```
*Kỳ vọng:*
1. Sinh 1 anomaly `SENSOR_OFFLINE`, `severity = 'MEDIUM'`, `status = 'OPEN'`.
2. Cột `devices.status` **TUYỆT ĐỐI KHÔNG BỊ ĐỔI** (tuân thủ nghiêm ngặt quy tắc kiến trúc).
3. 1 thông báo được gửi đến người dùng cảnh báo cảm biến mất kết nối.

### Bước 2.2: Cảm biến gửi tín hiệu trở lại → Tự động RESOLVED
Cảm biến online và gửi một gói telemetry bình thường:
```bash
mosquitto_pub -h $MQTT_HOST -p 1883 -t "hesta/nodes/$NODE_CODE/devices/$DEVICE_ID/telemetry" \
  -m '{"nodeId":"'$NODE_CODE'","deviceId":"'$DEVICE_ID'","metricType":"gas_ppm","value":200.0,"unit":"ppm"}'
```
Chờ tối đa 60 giây cho lượt quét scheduler tiếp theo.

**Kiểm tra SQL:**
```sql
SELECT id, anomaly_type, status, resolved_at, resolution_note 
FROM device_anomalies 
WHERE device_id = '$DEVICE_ID' AND anomaly_type = 'SENSOR_OFFLINE';
```
*Kỳ vọng:* `status` tự động chuyển sang `RESOLVED`, `resolution_note` ghi nhận "Tự động khôi phục: cảm biến đã gửi lại dữ liệu...".

---

## 4. Kịch Bản 3: Gọi 3 REST API Quản Lý Anomaly Bằng `curl`

### 3.1: API Lấy danh sách bất thường (Phân trang DB)
```bash
curl -s -X GET "$BACKEND_URL/api/v1/homes/$HOME_ID/anomalies?status=OPEN&page=0&size=10" \
  -H "Authorization: Bearer $TOKEN" | jq .
```
*Kỳ vọng:* Trả về JSON chuẩn `ApiResponse<Page<DeviceAnomalyResponse>>` với `code: 1000`, danh sách các anomaly `OPEN`, tổng số phần tử (`totalElements`), phân trang thực hiện 100% tại database.

### 3.2: API Xác nhận sự cố (Acknowledge) - Idempotent
```bash
export ANOMALY_ID="<ID_ANOMALY_DANG_OPEN>"

curl -s -X PUT "$BACKEND_URL/api/v1/homes/$HOME_ID/anomalies/$ANOMALY_ID/acknowledge" \
  -H "Authorization: Bearer $TOKEN" | jq .
```
*Kỳ vọng:*
- Lần 1: Trả về HTTP 200, message `"Đã xác nhận sự cố bất thường"`, `status = "ACKNOWLEDGED"`, gán `acknowledgedAt` và `acknowledgedBy`.
- Lần 2 (Gọi lại): Vẫn trả về HTTP 200 với trạng thái hiện tại, không thay đổi `acknowledgedAt` (Idempotent).

### 3.3: API Đóng xử lý sự cố (Resolve) - Idempotent & Kích hoạt Suppress Cooldown
```bash
curl -s -X PUT "$BACKEND_URL/api/v1/homes/$HOME_ID/anomalies/$ANOMALY_ID/resolve" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "falsePositive": false,
    "note": "Đã kiểm tra đầu dò, phòng bếp thông thoáng an toàn"
  }' | jq .
```
*Kỳ vọng:*
1. HTTP 200, `status = "RESOLVED"`, lưu ghi chú `note` và `resolvedBy`.
2. Kích hoạt cửa sổ tạm hoãn cảnh báo 10-15 phút (`manual-resolve-suppress`).
3. Nếu gửi lại gói gas_ppm 600 ppm ngay sau đó, engine sẽ chặn không tạo anomaly mới trong suốt thời gian cooldown.
