# Hướng Dẫn Điều Khiển & Giám Sát Thiết Bị IoT Qua REST API (LED, Air Conditioner, Gate, Sensors)

Tài liệu này hướng dẫn chi tiết cách sử dụng các REST API của hệ thống **HESTA Smart Home** để điều khiển và giám sát các thiết bị IoT:
1. **Đèn LED / Thiết bị chiếu sáng (LED, Dimmer, RGB Light, Relay Switch)**
2. **Điều hòa không khí (Air Conditioner)**
3. **Cổng trượt tự động & Cửa cuốn (Sliding Gate, Rolling Door)**
4. **Cảm biến (Sensors: DHT22 Nhiệt - Ẩm, LDR Ánh sáng, PIR Chuyển động)**

---

## 1. Tổng Quan Kiến Trúc & Giao Thức

### Luồng điều khiển:
```text
Mobile App / Web Dashboard / Postman / .http
       │  (HTTP POST/GET qua REST API)
       ▼
  Spring Boot Backend (HESTA)
       │  (MQTT Message: /command)
       ▼
   MQTT Broker (EMQX / Mosquitto)
       │  (MQTT Subscribe: /command)
       ▼
 ESP32 / Edge Node
       │  (GPIO / L298N / Relay / IR Transmitter)
       ▼
 Thiết bị phần cứng (LED, AC, Cổng trượt)
       │  (MQTT Telemetry / State: /state)
       ▼
 Backend cập nhật `current_state` & phát WebSocket tới App
```

### Chuẩn dữ liệu phản hồi (Response Contract)
Tất cả các API tuân theo định dạng chuẩn `ApiResponse<T>`:
```json
{
  "code": 1000,
  "message": "Thông điệp thành công",
  "result": {  }
}
```
* **Mã thành công:** `1000`
* **Xác thực (Authentication):** Gửi kèm header `Authorization: Bearer <accessToken>` lấy từ API `/api/v1/auth/login`.

---

## 2. Đèn LED & Thiết Bị Chiếu Sáng (LED, RGB, Dimmer, Công Tắc)

Đèn LED được điều khiển thông qua **Generic Device Command API** hoặc điều khiển hàng loạt theo phòng.

### 2.1. Bật / Tắt Đèn (Power ON / OFF)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/command`
* **Headers:** `Content-Type: application/json`

**Bật đèn:**
```json
{
  "action": "TURN_ON"
}
```
*Hoặc:*
```json
{
  "action": "SET_POWER",
  "power": true
}
```

**Tắt đèn:**
```json
{
  "action": "TURN_OFF"
}
```
*Hoặc:*
```json
{
  "action": "SET_POWER",
  "power": false
}
```

---

### 2.2. Điều Chỉnh Độ Sáng (Dimming - Brightness)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/command`
* **Payload:**
```json
{
  "action": "SET_BRIGHTNESS",
  "brightness": 75
}
```
*(Giá trị `brightness` từ `0` đến `100`. Nếu `brightness > 0`, thiết bị tự động chuyển trạng thái `power: ON`)*.

---

### 2.3. Điều Chỉnh Màu Sắc RGB (RGB LED Light)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/command`
* **Payload:**
```json
{
  "action": "SET_RGB",
  "r": 255,
  "g": 128,
  "b": 0
}
```
*(Tham số `r`, `g`, `b` nhận giá trị từ `0` đến `255`)*.

---

### 2.4. Điều Khiển Hàng Loạt Thiết Bị Trong Phòng (Room Command)
* **Endpoint:** `POST /api/v1/rooms/{roomId}/command`
* **Payload (Ví dụ tắt tất cả đèn trong phòng):**
```json
{
  "action": "TURN_OFF"
}
```

---

## 3. Điều Hòa Không Khí (Air Conditioner)

HESTA cung cấp cụm API chuyên biệt tại tiền tố:
`/api/v1/devices/{deviceId}/air-conditioner`

### 3.1. Bật / Tắt Nguồn Điều Hòa (Set Power)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/power?power=true`
* **Query Params:**
  * `power` *(boolean, required)*: `true` (Bật) hoặc `false` (Tắt)
* **Response Result:**
```json
{
  "code": 1000,
  "message": "Cập nhật nguồn điều hòa thành công",
  "result": {
    "commandId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "success": true,
    "status": "SUCCESS",
    "acknowledgedState": { "power": true }
  }
}
```

---

### 3.2. Cài Đặt Nhiệt Độ Cụ Thể (Set Temperature)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/temperature?temperature=24`
* **Query Params:**
  * `temperature` *(int, required)*: Giới hạn thông thường `16` - `30` (°C)

---

### 3.3. Tăng / Giảm 1 Độ C (Temperature Plus / Minus)
* **Tăng 1 độ:** `POST /api/v1/devices/{deviceId}/air-conditioner/temperature-plus`
* **Giảm 1 độ:** `POST /api/v1/devices/{deviceId}/air-conditioner/temperature-minus`

---

### 3.4. Cài Đặt Tốc Độ Quạt (Set Fan Speed)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/fan?fan=AUTO`
* **Query Params:**
  * `fan` *(string, required)*: `AUTO`, `LOW`, `MED`, `HIGH`, `QUIET`, `TURBO`

---

### 3.5. Cài Đặt Chế Độ Hoạt Động (Set Mode)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/mode?mode=COOL`
* **Query Params:**
  * `mode` *(string, required)*: `COOL` (Làm mát), `DRY` (Hút ẩm), `FAN` (Chỉ quạt), `HEAT` (Sưởi ấm), `AUTO`

---

### 3.6. Cài Đặt Đảo Gió (Set Swing)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/swing?enabled=true`
* **Query Params:**
  * `enabled` *(boolean, required)*: `true` (Bật đảo gió), `false` (Tắt đảo gió)

---

### 3.7. Hẹn Giờ Bật / Tắt (Timer)
* **Cài đặt hẹn giờ:**
  * **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/timer?hour=2&halfHour=true`
  * **Query Params:**
    * `hour` *(int, required)*: Số giờ hẹn (0 - 24)
    * `halfHour` *(boolean, optional, default: false)*: `true` nếu muốn thêm 30 phút (ví dụ 2.5 giờ)
* **Hủy hẹn giờ:**
  * **Endpoint:** `POST /api/v1/devices/{deviceId}/air-conditioner/timer/cancel`

---

### 3.8. Lấy Trạng Thái Hiện Tại Của Điều Hòa
* **Endpoint:** `GET /api/v1/devices/{deviceId}/air-conditioner/state`
* **Response Result:**
```json
{
  "code": 1000,
  "message": "Lấy trạng thái điều hòa thành công",
  "result": {
    "success": true,
    "status": "SUCCESS",
    "acknowledgedState": {
      "power": true,
      "temperature": 24,
      "mode": "COOL",
      "fan": "AUTO",
      "swing": false,
      "timerEnabled": false
    }
  }
}
```

---

## 4. Cổng Tự Động & Cửa Cuốn (Gate & Rolling Door)

Hỗ trợ các thiết bị loại `GATE` (Cổng trượt tự động) và `ROLLING_DOOR` (Cửa cuốn).
Tiền tố API: `/api/v1/devices/{deviceId}/gate`

> [!IMPORTANT]
> **Quy ước về `current_state` đối với GATE và ROLLING_DOOR:**
> Để bảo toàn bộ nhớ và tối ưu lưu trữ, `current_state` của cổng trong Database **chỉ lưu duy nhất trường `state`** (ví dụ: `{"state": "OPEN"}` hoặc `{"state": "CLOSED"}` hoặc `{"state": "STOPPED"}`). Hệ thống tự động lọc bỏ các trường `deviceId`, `nodeId`, `limit_open`, `limit_close`.
>
> **Tối ưu Buffer ESP32:**
> Khi gọi lệnh mở/đóng/dừng cổng, Backend xuất payload MQTT siêu gọn: `{"action": "OPEN"}` (~17 bytes) để đảm bảo không bị tràn bộ nhớ đệm `PubSubClient` (128 bytes) trên ESP32.

### 4.1. Mở Cổng (Open Gate)
* **Endpoint Shortcut:** `POST /api/v1/devices/{deviceId}/gate/open`
* **Hoặc truyền động Node ID:** `POST /api/v1/devices/{deviceId}/gate/open?nodeId={nodeId}`
* **Response:**
```json
{
  "code": 1000,
  "message": "Gửi lệnh mở cổng thành công",
  "result": {
    "commandId": "e1f1d182-4f30-4e1b-90f9-2b0e5b7c8a11",
    "success": true,
    "status": "SUCCESS",
    "message": "Device acknowledged via state telemetry"
  }
}
```

---

### 4.2. Đóng Cổng (Close Gate)
* **Endpoint Shortcut:** `POST /api/v1/devices/{deviceId}/gate/close`
* **Hoặc truyền động Node ID:** `POST /api/v1/devices/{deviceId}/gate/close?nodeId={nodeId}`
* **Response:**
```json
{
  "code": 1000,
  "message": "Gửi lệnh đóng cổng thành công",
  "result": {
    "commandId": "a9a2a720-1e5b-433b-8532-3f1917f30321",
    "success": true,
    "status": "SUCCESS",
    "message": "Device acknowledged via state telemetry"
  }
}
```

---

### 4.3. Dừng Cổng Khẩn Cấp (Stop Gate)
* **Endpoint Shortcut:** `POST /api/v1/devices/{deviceId}/gate/stop`
* **Hoặc truyền động Node ID:** `POST /api/v1/devices/{deviceId}/gate/stop?nodeId={nodeId}`
* **Response:**
```json
{
  "code": 1000,
  "message": "Gửi lệnh dừng cổng thành công",
  "result": {
    "commandId": "6c367468-b3f4-41d5-a3ae-e3801264c731",
    "success": true,
    "status": "SUCCESS",
    "message": "Device acknowledged via state telemetry"
  }
}
```

---

### 4.4. Gửi Lệnh Cổng Qua Request Body (Gate Command)
* **Endpoint:** `POST /api/v1/devices/{deviceId}/gate/command`
* **Payload:**
```json
{
  "action": "OPEN",
  "nodeId": "8d1cdd82-b339-469e-be13-7e91070f7ae5"
}
```
*(Tham số `nodeId` là tùy chọn; nếu thiết bị đã được gán vào Edge Node trong DB, backend sẽ tự động lấy `nodeCode` từ thiết bị)*.

Các giá trị hợp lệ cho `action`:
* `"OPEN"` hoặc `"GATE_OPEN"`
* `"CLOSE"` hoặc `"GATE_CLOSE"`
* `"STOP"` hoặc `"GATE_STOP"`

---

### 4.5. Lấy Trạng Thái Cổng (Get Gate State)
* **Endpoint:** `GET /api/v1/devices/{deviceId}/gate/state`
* **Response Result:**
```json
{
  "code": 1000,
  "message": "Lấy trạng thái cổng thành công",
  "result": {
    "deviceId": "2d566d0a-f7d6-4fac-a0bf-18912da4ab28",
    "state": "CLOSED",
    "currentState": {
      "state": "CLOSED"
    }
  }
}
```

---

## 5. Cảm Biến Môi Trường & An Ninh (DHT22, LDR, PIR Sensors)

Hệ thống đang tích hợp bộ 3 cảm biến phổ biến kết nối qua Node ESP32:
1. **Cảm biến nhiệt độ & độ ẩm (DHT22)**: Giám sát nhiệt độ và độ ẩm phòng.
2. **Cảm biến ánh sáng (LDR)**: Phát hiện trạng thái sáng / tối.
3. **Cảm biến chuyển động (PIR)**: Phát hiện có người chuyển động trong khu vực quét.

Toàn bộ dữ liệu cảm biến được ESP32 gửi lên Backend định kỳ qua MQTT Telemetry, lưu trữ vào chuỗi thời gian `SensorReading`, cập nhật tức thời vào `currentState` của `Device` và đẩy thông báo Realtime qua WebSocket STOMP.

---

### 5.1. Bảng Thông Số Cấu Hình Node ESP32 & Thiết Bị Thực Tế

* **Node ID (ESP32):** `109441d8-b6bf-4a18-bdf8-9279b7a1548d`

| Cảm biến | Chân GPIO | Device ID | MQTT Telemetry Topic | `metricType` | `unit` | Giá trị (`value`) | Chu kỳ gửi |
| :--- | :---: | :--- | :--- | :---: | :---: | :---: | :---: |
| **LDR (Ánh sáng)** | `12` | `57c13343-f2cd-45ce-b65d-0bd843ae95d3` | `hesta/nodes/109441d8-b6bf-4a18-bdf8-9279b7a1548d/devices/57c13343-f2cd-45ce-b65d-0bd843ae95d3/telemetry` | `LIGHT_SENSOR` | `boolean` | `0` (Sáng) hoặc `1` (Tối) | 10 giây (`10000ms`) |
| **PIR (Chuyển động)** | `13` | `5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2` | `hesta/nodes/109441d8-b6bf-4a18-bdf8-9279b7a1548d/devices/5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2/telemetry` | `MOTION_SENSOR` | `boolean` | `1` (Có người) hoặc `0` (Không) | 5 giây (`5000ms`) |
| **DHT22 (Nhiệt độ)** | `14` | `0bb7dc6e-0556-413d-a507-66bc8225b4ba` | `hesta/nodes/109441d8-b6bf-4a18-bdf8-9279b7a1548d/devices/0bb7dc6e-0556-413d-a507-66bc8225b4ba/telemetry` | `temperature` | `C` | Ví dụ: `28.5` | 25 giây (`25000ms`) |
| **DHT22 (Độ ẩm)** | `14` | `0bb7dc6e-0556-413d-a507-66bc8225b4ba` | `hesta/nodes/109441d8-b6bf-4a18-bdf8-9279b7a1548d/devices/0bb7dc6e-0556-413d-a507-66bc8225b4ba/telemetry` | `humidity` | `%` | Ví dụ: `65.0` | 25 giây (`25000ms`) |

---

### 5.2. Lấy Trạng Thái & Giá Trị Tức Thời Của Từng Cảm Biến
Sử dụng API này để lấy thông số đo mới nhất của một cảm biến cụ thể thông qua `deviceId`.

* **Endpoint:** `GET /api/v1/devices/{deviceId}`
* **Headers:** `Authorization: Bearer <accessToken>`

#### Mẫu 1: Cảm biến Nhiệt độ & Độ ẩm DHT22 (`0bb7dc6e-0556-413d-a507-66bc8225b4ba`)
```http
GET /api/v1/devices/0bb7dc6e-0556-413d-a507-66bc8225b4ba
Authorization: Bearer <accessToken>
```
**Response Result:**
```json
{
  "code": 1000,
  "message": "Success",
  "result": {
    "id": "0bb7dc6e-0556-413d-a507-66bc8225b4ba",
    "homeId": "7d91cb61-7e88-4ca3-b67f-44e4bb3c4379",
    "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
    "roomName": "Phòng Khách",
    "nodeId": "109441d8-b6bf-4a18-bdf8-9279b7a1548d",
    "nodeName": "ESP32-NODE-01",
    "name": "Cảm Biến Nhiệt Ẩm DHT22",
    "deviceType": "TEMP_SENSOR",
    "status": "ONLINE",
    "currentState": {
      "temperature": 28.5,
      "humidity": 65.0
    },
    "createdAt": "2026-09-15T08:00:00Z",
    "updatedAt": "2026-10-03T00:30:15Z"
  }
}
```

#### Mẫu 2: Cảm biến Ánh sáng LDR (`57c13343-f2cd-45ce-b65d-0bd843ae95d3`)
```http
GET /api/v1/devices/57c13343-f2cd-45ce-b65d-0bd843ae95d3
Authorization: Bearer <accessToken>
```
**Response Result:**
```json
{
  "code": 1000,
  "message": "Success",
  "result": {
    "id": "57c13343-f2cd-45ce-b65d-0bd843ae95d3",
    "homeId": "7d91cb61-7e88-4ca3-b67f-44e4bb3c4379",
    "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
    "roomName": "Phòng Khách",
    "nodeId": "109441d8-b6bf-4a18-bdf8-9279b7a1548d",
    "name": "Cảm Biến Ánh Sáng LDR",
    "deviceType": "LIGHT_SENSOR",
    "status": "ONLINE",
    "currentState": {
      "LIGHT_SENSOR": 1.0
    }
  }
}
```

#### Mẫu 3: Cảm biến Chuyển động PIR (`5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2`)
```http
GET /api/v1/devices/5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2
Authorization: Bearer <accessToken>
```
**Response Result:**
```json
{
  "code": 1000,
  "message": "Success",
  "result": {
    "id": "5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2",
    "homeId": "7d91cb61-7e88-4ca3-b67f-44e4bb3c4379",
    "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
    "roomName": "Phòng Khách",
    "nodeId": "109441d8-b6bf-4a18-bdf8-9279b7a1548d",
    "name": "Cảm Biến Chuyển Động PIR",
    "deviceType": "MOTION_SENSOR",
    "status": "ONLINE",
    "currentState": {
      "MOTION_SENSOR": 1.0
    }
  }
}
```

---

### 5.3. Lấy Danh Sách Cảm Biến Theo Nhà & Phòng
Dùng để hiển thị toàn bộ thiết bị và chỉ số cảm biến theo phạm vi:

* **Tất cả thiết bị & cảm biến trong nhà:** `GET /api/v1/homes/{homeId}/devices`
* **Thiết bị & cảm biến theo từng phòng:** `GET /api/v1/rooms/{roomId}/devices`
* **Xử lý trên Client (Mobile/Web):** Lọc theo `deviceType` chứa đuôi `_SENSOR` (`TEMP_SENSOR`, `LIGHT_SENSOR`, `MOTION_SENSOR`) để hiển thị riêng khu vực giám sát cảm biến.

---

### 5.4. Lấy Toàn Bộ Dữ Liệu Cảm Biến Qua Digital Twin Snapshot (Khuyên Dùng)
> [!TIP]
> **API Tối Ưu Nhất Cho Web Dashboard & Mobile App:**
> API này trả về snapshot trạng thái của cả ngôi nhà, tự động gom nhóm cảm biến theo từng phòng (`rooms[].sensors[]`), kèm theo đánh giá sức khỏe thiết bị (`health`: `HEALTHY`, `DEGRADED`, `CRITICAL`).

* **Endpoint:** `GET /api/v1/homes/{homeId}/twin`
* **Response Mẫu Trích Đoạn:**
```json
{
  "code": 1000,
  "result": {
    "homeId": "7d91cb61-7e88-4ca3-b67f-44e4bb3c4379",
    "homeName": "Căn Hộ Green Valley",
    "rooms": [
      {
        "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
        "homeId": "7d91cb61-7e88-4ca3-b67f-44e4bb3c4379",
        "roomName": "Phòng Khách",
        "devices": [ ... ],
        "sensors": [
          {
            "sensorId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba:temperature",
            "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
            "deviceId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba",
            "metricType": "temperature",
            "latestValue": 28.5,
            "unit": "C",
            "observedAt": "2026-10-03T00:30:15Z",
            "health": "HEALTHY"
          },
          {
            "sensorId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba:humidity",
            "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
            "deviceId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba",
            "metricType": "humidity",
            "latestValue": 65.0,
            "unit": "%",
            "observedAt": "2026-10-03T00:30:15Z",
            "health": "HEALTHY"
          },
          {
            "sensorId": "57c13343-f2cd-45ce-b65d-0bd843ae95d3:LIGHT_SENSOR",
            "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
            "deviceId": "57c13343-f2cd-45ce-b65d-0bd843ae95d3",
            "metricType": "LIGHT_SENSOR",
            "latestValue": 1.0,
            "unit": "boolean",
            "observedAt": "2026-10-03T00:30:20Z",
            "health": "HEALTHY"
          },
          {
            "sensorId": "5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2:MOTION_SENSOR",
            "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
            "deviceId": "5e854e0e-612b-4aa5-95c8-e9fd44f2b8f2",
            "metricType": "MOTION_SENSOR",
            "latestValue": 1.0,
            "unit": "boolean",
            "observedAt": "2026-10-03T00:30:25Z",
            "health": "HEALTHY"
          }
        ]
      }
    ],
    "unassignedSensors": []
  }
}
```

---

### 5.5. Lấy Lịch Sử Thay Đổi Dữ Liệu Cảm Biến (Device State History)
Xem biến động của các mốc ghi nhận của cảm biến.

* **Endpoint:** `GET /api/v1/devices/{deviceId}/history`
* **Response Mẫu:**
```json
{
  "code": 1000,
  "result": [
    {
      "id": 1052,
      "previousState": {
        "temperature": 27.8,
        "humidity": 68.0
      },
      "newState": {
        "temperature": 28.5,
        "humidity": 65.0
      },
      "source": "TELEMETRY",
      "changedAt": "2026-10-03T00:30:15Z"
    }
  ]
}
```

> [!NOTE]
> **Cơ chế Throttling cho cảm biến:**
> Đối với các thiết bị loại `*SENSOR`, Backend tự động giới hạn chỉ ghi lịch sử vào CSDL **tối đa 1 lần mỗi 5 phút** (hoặc khi trạng thái có sự thay đổi đáng kể), nhằm tránh tràn dữ liệu khi ESP32 gửi tin liên tục (5s - 25s/lần).

---

### 5.6. Lắng Nghe Chỉ Số Cảm Biến Realtime Qua WebSocket (STOMP)
Mỗi khi ESP32 publish dữ liệu mới lên MQTT, Backend xử lý và phát ngay sự kiện WebSocket đến Client đang kết nối:

* **WebSocket URL:** `ws://<backend-ip>:8080/ws`
* **Giao thức:** STOMP over WebSocket
* **Subscribe Destination:** `/topic/homes/{homeId}/events`
* **Event Type:** `SENSOR_READING_UPDATED`
* **Payload Mẫu:**
```json
{
  "type": "SENSOR_READING_UPDATED",
  "homeId": "7d91cb61-7e88-4ca3-b67f-44e4bb3c4379",
  "deviceId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba",
  "data": {
    "sensorId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba:temperature",
    "roomId": "8f8b1d91-23e5-4d2c-806d-cf7d6e5a41a2",
    "deviceId": "0bb7dc6e-0556-413d-a507-66bc8225b4ba",
    "metricType": "temperature",
    "latestValue": 28.5,
    "unit": "C",
    "observedAt": "2026-10-03T00:30:15Z",
    "health": "HEALTHY"
  }
}
```

---

---

## 6. Bảng Tổng Hợp Endpoint API

| Nhóm | Chức năng | Method | Endpoint | Tham số / Body |
| :--- | :--- | :--- | :--- | :--- |
| **LED / Đèn** | Bật / Tắt | `POST` | `/api/v1/devices/{id}/command` | `{"action": "TURN_ON"}` hoặc `{"action": "TURN_OFF"}` |
| **LED / Đèn** | Chỉnh độ sáng | `POST` | `/api/v1/devices/{id}/command` | `{"action": "SET_BRIGHTNESS", "brightness": 80}` |
| **LED / Đèn** | Đổi màu RGB | `POST` | `/api/v1/devices/{id}/command` | `{"action": "SET_RGB", "r": 255, "g": 0, "b": 128}` |
| **LED / Đèn** | Tắt cả phòng | `POST` | `/api/v1/rooms/{roomId}/command` | `{"action": "TURN_OFF"}` |
| **Điều Hòa** | Nguồn (Power) | `POST` | `/api/v1/devices/{id}/air-conditioner/power` | `?power=true\|false` |
| **Điều Hòa** | Đặt nhiệt độ | `POST` | `/api/v1/devices/{id}/air-conditioner/temperature` | `?temperature=24` |
| **Điều Hòa** | Tăng/Giảm độ | `POST` | `/api/v1/devices/{id}/air-conditioner/temperature-plus` | *(none)* |
| **Điều Hòa** | Tốc độ quạt | `POST` | `/api/v1/devices/{id}/air-conditioner/fan` | `?fan=AUTO\|LOW\|MED\|HIGH` |
| **Điều Hòa** | Chế độ | `POST` | `/api/v1/devices/{id}/air-conditioner/mode` | `?mode=COOL\|DRY\|FAN\|HEAT` |
| **Điều Hòa** | Đảo gió | `POST` | `/api/v1/devices/{id}/air-conditioner/swing` | `?enabled=true\|false` |
| **Điều Hòa** | Hẹn giờ | `POST` | `/api/v1/devices/{id}/air-conditioner/timer` | `?hour=2&halfHour=false` |
| **Điều Hòa** | Trạng thái | `GET` | `/api/v1/devices/{id}/air-conditioner/state` | *(none)* |
| **Cổng / Cửa** | Mở cổng | `POST` | `/api/v1/devices/{id}/gate/open` | `?nodeId={nodeId}` *(optional)* |
| **Cổng / Cửa** | Đóng cổng | `POST` | `/api/v1/devices/{id}/gate/close` | `?nodeId={nodeId}` *(optional)* |
| **Cổng / Cửa** | Dừng cổng | `POST` | `/api/v1/devices/{id}/gate/stop` | `?nodeId={nodeId}` *(optional)* |
| **Cổng / Cửa** | Lệnh tổng hợp | `POST` | `/api/v1/devices/{id}/gate/command` | `{"action": "OPEN"\|"CLOSE"\|"STOP"}` |
| **Cổng / Cửa** | Trạng thái | `GET` | `/api/v1/devices/{id}/gate/state` | *(none)* |
| **Cảm Biến** | Trạng thái tức thời 1 cảm biến | `GET` | `/api/v1/devices/{id}` | *(none)* |
| **Cảm Biến** | Toàn bộ cảm biến trong Nhà | `GET` | `/api/v1/homes/{homeId}/devices` | *(none)* |
| **Cảm Biến** | Cảm biến trong từng Phòng | `GET` | `/api/v1/rooms/{roomId}/devices` | *(none)* |
| **Cảm Biến** | Snapshot cảm biến (Twin Snapshot) | `GET` | `/api/v1/homes/{homeId}/twin` | *(none - Khuyên dùng)* |
| **Cảm Biến** | Lịch sử dữ liệu cảm biến | `GET` | `/api/v1/devices/{id}/history` | *(none)* |
| **Cảm Biến** | Giả lập dữ liệu đo (Dev Profile) | `POST` | `/api/v1/dev/sensors/mock-reading` | `{"deviceId", "metricType", "value", "unit"}` |

---

## 7. Xử Lý Các Lỗi Thường Gặp (Troubleshooting)

1. **`TIMEOUT_NO_ACK` (Device did not acknowledge within 5000 ms):**
   * **Nguyên nhân 1:** ESP32 chưa kết nối MQTT Broker hoặc kết nối sai broker URL/port.
   * **Nguyên nhân 2:** Topic subscribe trên ESP32 không khớp với `hesta/nodes/{nodeId}/devices/{deviceId}/command`.
   * **Nguyên nhân 3:** Buffer của `PubSubClient` trên ESP32 bị tràn do gói tin gửi quá dài (đối với cổng trượt, backend đã tối ưu gói tin xuống ~17 bytes).
2. **`DEVICE_NOT_FOUND` (Mã lỗi 1004):**
   * Device ID chưa tồn tại trong cơ sở dữ liệu.
   * Nếu đang test thiết bị IoT mới chưa tạo trên Web/App: truyền thêm query parameter `?nodeId={nodeId}` hoặc body `nodeId` để backend định tuyến thẳng đến topic MQTT của node tương ứng mà không cần tra cứu DB.
3. **`GATE_ACTION_INVALID` (Mã lỗi 1040):**
   * Action gửi lên cho cổng không hợp lệ. Chỉ chấp nhận `OPEN`, `CLOSE`, `STOP`.
4. **Cảm biến hiển thị `OFFLINE` hoặc không có `currentState`:**
   * **Nguyên nhân 1:** ESP32 chưa publish telemetry lên đúng topic `hesta/nodes/{nodeId}/devices/{deviceId}/telemetry` hoặc `.../state`.
   * **Nguyên nhân 2:** Trường `deviceId` trong payload telemetry không khớp với `id` của thiết bị trong cơ sở dữ liệu.
   * **Nguyên nhân 3:** Thiết bị quá thời gian không gửi tín hiệu (heartbeat timeout). Ngay khi có gói tin telemetry mới gửi đến, hệ thống sẽ tự động cập nhật trạng thái thiết bị sang `ONLINE`.
5. **Dữ liệu cảm biến không hiển thị trên WebSocket `/topic/homes/{homeId}/events`:**
   * Kiểm tra thiết bị cảm biến đã được gán vào Phòng (`Room`) và Ngôi nhà (`Home`) chưa. Nếu thiết bị chưa thuộc về Home nào, Backend sẽ không thể xác định `homeId` để phân phối sự kiện STOMP.
