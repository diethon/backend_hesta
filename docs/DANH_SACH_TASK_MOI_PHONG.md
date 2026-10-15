# DANH SÁCH TASK MỚI CHO TẠ TẤN PHONG (BACKEND ➔ FRONTEND)
## Dự Án HESTA Smart Home — Báo Cáo & Bảo Vệ Đồ Án Tốt Nghiệp Capstone

---

## 1. BỐI CẢNH & ĐỊNH NGHĨA VAI TRÒ HỆ THỐNG (ROLE MODEL)

Sau khi chuyển giao phần **Firmware ESP32** cho Toàn và phần **Voice & NLU** cho Đức, phạm vi trách nhiệm của **Phong** tập trung chuyên sâu vào:
1. **MQTT Core & Hạ tầng phân tán (Distributed IoT Architecture)**
2. **Device Management & Group Batch Execution (Quản lý thiết bị & điều khiển nhóm)**
3. **IoT Data Analytics & Energy Engine (Phân tích dữ liệu lịch sử & điện năng)**

### Mô hình phân quyền 3 Role trong HESTA (Smart Home as a Service):
* **🛠️ ADMIN (Bên Cung Cấp Giải Pháp IoT & Nền Tảng Phần Mềm):**  
  Quản trị viên nền tảng, kỹ thuật viên vận hành broker MQTT, giám sát các Edge Node, xử lý ngắt mạch bảo vệ và chẩn đoán sự cố kỹ thuật từ xa mà không xâm phạm quyền riêng tư của khách hàng.
* **🏠 OWNER (Chủ Nhà - Quản Lý Gia Đình):**  
  Khách hàng mua giải pháp, đứng tên ngôi nhà. Quản lý tài chính tiền điện, an toàn phần cứng, cấu hình nhóm thiết bị, xuất báo cáo kiểm toán. **Owner đồng thời là một thành viên sống trong nhà**, thừa hưởng toàn bộ quyền sinh hoạt của Member.
* **👤 MEMBER (Các Thành Viên Còn Lại Trong Nhà):**  
  Con cái, người thân sinh hoạt trong nhà. Được phân quyền điều khiển thiết bị, dùng thanh trượt Master Slider điều khiển nhóm, kích hoạt chế độ ưu tiên thủ công (khóa tự động 30 phút).

---

## 2. MA TRẬN PHÂN QUYỀN RBAC CHO 12 TASK MỚI

> **Ghi chú:** Ký hiệu **★** thể hiện quyền mà **OWNER** đương nhiên sở hữu vì vừa là Chủ nhà vừa là thành viên sinh hoạt trong gia đình.

| Nhóm Nghiệp Vụ | Mã Task | Tên Tính Năng | ADMIN (Bên Giải Pháp) | OWNER (Chủ Nhà) | MEMBER (Thành Viên) |
| :--- | :---: | :--- | :---: | :---: | :---: |
| **Năng Lượng & Kiểm Toán** | `PHO-032` | Thuật toán tính tiền điện EVN bậc thang | ❌ | ✅ *(Chỉ Chủ nhà)* | ❌ |
| | `PHO-033` | Dashboard Biểu đồ Năng lượng 24h | ❌ | ✅ | ❌ |
| | `PHO-034` | Thống kê Uptime & Tần suất sử dụng | ❌ | ✅ | ❌ |
| | `PHO-035` | Xem Visual Event Timeline sự kiện | ❌ | ★ *(Thừa hưởng)* | ✅ |
| | `PHO-035` | Xuất Báo cáo Kiểm toán Excel (.xlsx) | ❌ | ✅ *(Chỉ Chủ nhà)* | ❌ |
| **Bảo Trì & An Toàn Thiết Bị** | `PHO-042` | Xem % Tuổi thọ Relay & Cảnh báo Chattering | ❌ | ✅ *(Báo bảo hành)* | ❌ |
| **Điều Khiển Sinh Hoạt** | `PHO-041` | Tạo / Sửa / Xóa Nhóm thiết bị | ❌ | ✅ *(Quản trị nhà)* | ❌ |
| | `PHO-041` | Kéo Master Dimmer điều khiển nhóm | ❌ | ★ *(Thừa hưởng)* | ✅ *(Sinh hoạt)* |
| | `PHO-043` | Huy hiệu đếm ngược 30 phút (Override) | ❌ | ★ *(Thừa hưởng)* | ✅ *(Sinh hoạt)* |
| | `PHO-039` | Chống click spam (Redisson Lock) | ✅ | ★ *(Thừa hưởng)* | ✅ *(Áp dụng toàn bộ)* |
| **Hạ Tầng Kỹ Thuật (Admin)** | `PHO-036` | Đo độ trễ MQTT RTT Latency Gauge | ✅ *(Chuyên môn)* | ❌ | ❌ |
| | `PHO-037` | Cấu hình & Giả lập ngắt mạch Circuit Breaker | ✅ | ❌ | ❌ |
| | `PHO-038` | Giám sát Broker Failover & Dead Man's Snitch | ✅ | ❌ | ❌ |
| | `PHO-040` | Live MQTT Packet Sniffer Console | ✅ *(Debug hệ thống)* | ❌ | ❌ |

---

## 3. CHI TIẾT 12 TASK MỚI (TỪ BACKEND ĐẾN FRONTEND)

---

### NHÓM I: NĂNG LƯỢNG & PHÂN TÍCH DỮ LIỆU IOT (IOT DATA ANALYTICS & ENERGY)

#### 1. [PHO-032] Thuật toán Ước tính Tiêu thụ Điện (kWh) & Tính tiền bậc thang EVN 6 bậc
* **Độ ưu tiên:** P0 | **Sprint:** 3 | **Role:** `OWNER`
* **Mục tiêu:** Ước tính điện năng tiêu thụ và hóa đơn tiền điện sinh hoạt theo thời gian thực mà không cần gắn đồng hồ đo đắt tiền.
* **Chi tiết Backend (BE):**
  * Tạo `EnergyCalculationService` & `EnergyCalculationServiceImpl`.
  * Thuật toán **tích phân số (Riemann Sum Integration)**: Quét bảng `DeviceStateHistory` lấy thời gian thiết bị ở trạng thái `ON`, nhân với công suất danh định `wattage` (W) trong `capabilities` để tính ra số $kWh = \sum (P_i \times \Delta t_i) / 1000$.
  * Hàm tính tiền điện sinh hoạt 6 bậc theo biểu giá hiện hành của Bộ Công Thương / EVN.
  * API: `GET /api/v1/homes/{homeId}/energy/summary?period=MONTHLY`.
* **Chi tiết Frontend (FE):**
  * 3 thẻ Card KPI trên đầu trang Năng Lượng: *Điện năng tháng này (kWh)* | *Tiền điện tạm tính (VNĐ)* | *Bậc giá điện sinh hoạt (1–6)*.
* **Cách Show Hội Đồng:**
  * Bật thử một chiếc Điều hòa trên Web ➔ Tiêu thụ điện và tiền điện tạm tính nhảy số tăng lên theo thời gian thực. Trình bày công thức tích phân số trước hội đồng.

---

#### 2. [PHO-033] Dashboard Biểu đồ Năng lượng 24h & Donut Chart Phân bổ Thiết bị
* **Độ ưu tiên:** P0 | **Sprint:** 3 | **Role:** `OWNER`
* **Mục tiêu:** Trực quan hóa phụ tải điện năng và cơ cấu tiêu thụ trong nhà.
* **Chi tiết Backend (BE):**
  * API: `GET /api/v1/homes/{homeId}/energy/timeseries?range=24H|7D|30D` (trả về mảng dữ liệu tiêu thụ theo từng mốc giờ).
  * API: `GET /api/v1/homes/{homeId}/energy/breakdown` (trả về tỷ lệ % tiêu thụ của từng thiết bị).
* **Chi tiết Frontend (FE):**
  * Thêm route `/home/:homeId/energy` vào `AppRoutes.tsx`; thêm icon **"⚡ Tiêu Thụ Điện"** trên `AppSidebar.tsx`.
  * Trang `EnergyDashboardPage.tsx`:
    * **Biểu đồ Cột/Miền (Bar/Area Chart):** Thể hiện phụ tải điện 24 giờ qua (thấy rõ các đỉnh giờ cao điểm trưa và tối).
    * **Biểu đồ Tròn (Donut Chart):** Tỷ lệ % tiêu thụ giữa các thiết bị (Điều hòa 60%, Bình nóng lạnh 25%, Đèn 10%...).
* **Cách Show Hội Đồng:**
  * Bấm vào tab "Tiêu Thụ Điện" trên Sidebar ➔ Biểu đồ trực quan phụ tải điện hiện lên đầy đủ màu sắc hiện đại.

---

#### 3. [PHO-034] Thống kê Tần suất Sử dụng & Uptime/Downtime Availability Profiling
* **Độ ưu tiên:** P1 | **Sprint:** 3 | **Role:** `OWNER`
* **Mục tiêu:** Phân tích độ sẵn sàng và thói quen hoạt động của thiết bị trong gia đình.
* **Chi tiết Backend (BE):**
  * Tạo `DeviceProfilingService`: Quét bảng `DeviceStateHistory` tính tổng thời gian ON vs OFF trong tuần.
  * Tính toán chỉ số sẵn sàng **Uptime Availability %** ($Uptime = \frac{T_{online}}{T_{total}} \times 100\%$).
  * API: `GET /api/v1/devices/{deviceId}/analytics/uptime?days=7`.
* **Chi tiết Frontend (FE):**
  * Trong `DeviceDrawer.tsx`, bổ sung tab **"Phân Tích Sử Dụng"**:
    * Chỉ số Uptime: `🟢 99.4% Availability (7 ngày qua)`.
    * Dải timeline thanh ngang thể hiện các khung giờ thiết bị BẬT (xanh) và TẮT (xám) trong ngày.
* **Cách Show Hội Đồng:**
  * Mở ngăn kéo thiết bị ➔ Xem dải timeline hoạt động trong ngày được trực quan hóa như hệ thống quản lý IoT công nghiệp.

---

#### 4. [PHO-035] Visual Audit Timeline & Xuất Báo Cáo Kiểm Toán Excel (Apache POI)
* **Độ ưu tiên:** P1 | **Sprint:** 3 | **Role:** `OWNER` (Xuất file), `MEMBER` (Xem timeline)
* **Mục tiêu:** Thay thế JSON thô bằng dòng thời gian sự kiện trực quan và cho phép tải báo cáo kiểm toán chính thức.
* **Chi tiết Backend (BE):**
  * Tích hợp thư viện **Apache POI**, tạo `DeviceAuditExportService`.
  * Đọc `DeviceStateHistory` và xuất ra file nhị phân `.xlsx` có header, logo HESTA, định dạng màu ON (xanh) / OFF (xám), người tác động và mốc thời gian.
  * API: `GET /api/v1/devices/{deviceId}/history/export?from=...&to=...`.
* **Chi tiết Frontend (FE):**
  * Nâng cấp mục Lịch sử trong `DeviceDrawer.tsx` thành **Visual Event Timeline** (icon người dùng, robot automation, cử chỉ).
  * Bổ sung nút bấm độc quyền cho Owner: **`[ 📥 Xuất Báo Cáo Excel ]`**.
* **Cách Show Hội Đồng:**
  * Bấm nút "Xuất Báo Cáo Excel" ➔ File `.xlsx` được tải về máy ngay lập tức, mở file cho hội đồng xem bảng kiểm toán an ninh chuyên nghiệp.

---

### NHÓM II: HẠ TẦNG KỸ THUẬT & ĐỘ TIN CẬY MQTT (DISTRIBUTED SYSTEMS & RELIABILITY)

#### 5. [PHO-036] Trang Giám Sát Hạ Tầng & Đồng Hồ Đo Độ Trễ MQTT RTT Latency Gauge
* **Độ ưu tiên:** P0 | **Sprint:** 3 | **Role:** `ADMIN`
* **Mục tiêu:** Giám sát chất lượng đường truyền mạng từ Backend xuống các bo mạch Edge Node ESP32.
* **Chi tiết Backend (BE):**
  * Trong `MqttDeviceCommandServiceImpl.java`, đo chính xác Roundtrip Latency (từ lúc publish command đến khi nhận correlate ACK).
  * Lưu trữ thống kê độ trễ (Min, Max, Avg, P95 ms) cho 100 lệnh gần nhất.
  * API: `GET /api/v1/diagnostics/mqtt-latency`.
* **Chi tiết Frontend (FE):**
  * Thêm route `/admin/diagnostics` và menu **"🩺 Giám Sát Hạ Tầng"** cho Admin.
  * **Đồng hồ đo độ trễ (Live Latency Gauge):** Kim đo dạng vòng cung (vd: `🟢 28 ms - Tốc độ cao`).
  * Bảng danh sách 10 lệnh điều khiển gần nhất kèm thời gian phản hồi ms.
* **Cách Show Hội Đồng:**
  * Bấm nút bật đèn trên web ➔ Đồng hồ RTT trên màn hình Admin nhảy số cập nhật ngay 28ms, khẳng định hệ thống phản hồi siêu tốc.

---

#### 6. [PHO-037] Resilience4j Circuit Breaker & Exponential Backoff Retry Policy
* **Độ ưu tiên:** P0 | **Sprint:** 3 | **Role:** `ADMIN`
* **Mục tiêu:** Ngắt mạch bảo vệ chống treo thread pool khi Node ESP32 mất nguồn hoặc mạng chập chờn.
* **Chi tiết Backend (BE):**
  * Tích hợp **Resilience4j Circuit Breaker**: Nếu 1 Node mất kết nối liên tiếp 5 lần ➔ Chuyển trạng thái sang `OPEN`, từ chối lệnh ngay sau 5ms thay vì để thread bị treo 5s.
  * Thuật toán **Exponential Backoff + Jitter** thử lại thông minh (1s, 2s, 4s).
  * API: `GET /api/v1/diagnostics/circuit-breaker/status`.
  * API: `POST /api/v1/diagnostics/circuit-breaker/{nodeCode}/trip` (Giả lập ngắt mạch để demo).
* **Chi tiết Frontend (FE):**
  * Thẻ trạng thái Circuit Breaker của từng Node: `CLOSED` (Xanh) hoặc `CIRCUIT OPEN` (Đỏ cảnh báo).
  * Toast đỏ thông báo khi gửi lệnh tới thiết bị đang bị ngắt mạch: *"Node đang ngắt mạch bảo vệ do mất nguồn"*.
* **Cách Show Hội Đồng:**
  * Giả lập rút nguồn ESP32 ➔ Gửi lệnh ➔ Hệ thống phản hồi lỗi ngay trong 5ms mà không bị quay vòng chờ, thẻ trạng thái trên web chuyển sang màu đỏ "CIRCUIT OPEN".

---

#### 7. [PHO-038] Runtime Broker Failover & Dead Man's Snitch Node Heartbeat Monitor
* **Độ ưu tiên:** P1 | **Sprint:** 3 | **Role:** `ADMIN`
* **Mục tiêu:** Tự động hồi phục sau khi mất kết nối Broker và phát hiện các bo mạch bị đứt tín hiệu.
* **Chi tiết Backend (BE):**
  * Triển khai `MqttCallbackExtended`: Lắng nghe sự kiện kết nối Broker bị đứt và phục hồi trong lúc runtime ➔ Tự động kích hoạt State Reconciliation mà không cần restart server.
  * Thuật toán **Dead Man's Snitch**: Bộ đếm lùi heartbeat của từng Node. Nếu quá 60s không nhận bản tin ➔ Chuyển trạng thái sang `OFFLINE` và phát cảnh báo.
* **Chi tiết Frontend (FE):**
  * Thẻ trạng thái Node hiển thị: `Last seen: 5 giây trước`.
  * Nếu Node rớt mạng quá 60s, thẻ tự động mờ đi và chuyển sang xám qua kết nối WebSocket thời gian thực.
* **Cách Show Hội Đồng:**
  * Tắt bật lại broker Mosquitto ➔ Backend tự kết nối lại và bắn gói tin cập nhật state lên web mà không cần F5 tải lại trang.

---

#### 8. [PHO-039] Redisson Distributed Lock & Exactly-Once Idempotency Engine
* **Độ ưu tiên:** P0 | **Sprint:** 2/3 | **Role:** Toàn bộ (`ADMIN`, `OWNER`, `MEMBER`)
* **Mục tiêu:** Đảm bảo tính toàn vẹn hệ thống (Exactly-Once Delivery), chống race-condition và click spam.
* **Chi tiết Backend (BE):**
  * Tích hợp thư viện **Redisson**: Thay thế `ConcurrentHashMap` bằng `RLock` phân tán trên Redis theo khóa `device:lock:{deviceId}`.
  * Khóa nhận lệnh trong 1–2s: Nếu lệnh trước chưa nhận xong ACK, lệnh sau sẽ bị trả về mã `COMMAND_IN_PROGRESS_DUPLICATE_REJECTED`.
* **Chi tiết Frontend (FE):**
  * Nút bấm tự động mờ đi và có vòng xoay loading nhẹ khi đang gửi lệnh.
  * Nếu người dùng click spam liên tục, FE bắt lỗi và hiện Toast cảnh báo: *"Thiết bị đang thực thi lệnh trước, vui lòng không click liên tục."*
* **Cách Show Hội Đồng:**
  * Click chuột liên tục 10 lần vào nút Bật đèn ➔ Giao diện hiện Toast chặn spam, relay dưới phần cứng chỉ đóng đúng 1 lần duy nhất, bảo vệ thiết bị.

---

#### 9. [PHO-040] Live MQTT Packet Sniffer / Inspector Console ngay trên Web Dashboard
* **Độ ưu tiên:** P1 | **Sprint:** 4 | **Role:** `ADMIN`
* **Mục tiêu:** Bảng công cụ soi trực tiếp các gói tin MQTT Inbound/Outbound phục vụ Debug và hỗ trợ kỹ thuật từ xa.
* **Chi tiết Backend (BE):**
  * Kênh trung chuyển nội bộ: Mỗi khi có gói tin qua MQTT Gateway, phát bản tin tóm tắt qua WebSocket kênh `/topic/mqtt-sniffer`.
* **Chi tiết Frontend (FE):**
  * Tab **"Soi Gói Tin MQTT (Live Sniffer)"** trong trang Quản trị Admin:
    * Bảng log thời gian thực: Cột Thời gian, Topic, Chiều (IN/OUT), QoS, Payload JSON.
    * Ô tìm kiếm và filter theo Topic hoặc Device ID.
* **Cách Show Hội Đồng:**
  * Mở tab Live Sniffer ➔ Bấm Bật đèn trên điện thoại ➔ Một dòng log màu xanh bắn ra trên màn hình máy chiếu hiển thị chính xác topic `.../command` và gói tin ACK `.../ack` trả về.

---

### NHÓM III: QUẢN LÝ THIẾT BỊ NÂNG CAO & AN TOÀN (ADVANCED DEVICE CONTROL & SAFETY)

#### 10. [PHO-041] Quản Lý Nhóm Thiết Bị Tùy Biến (Custom Groups) & Thanh Master Dimmer Slider
* **Độ ưu tiên:** P1 | **Sprint:** 3 | **Role:** `OWNER` (Tạo nhóm), `MEMBER` (Điều khiển nhóm)
* **Mục tiêu:** Gom thiết bị xuyên phòng và điều khiển hàng loạt với thanh trượt Master Slider.
* **Chi tiết Backend (BE):**
  * Entity `DeviceGroup` (`id`, `name`, `homeId`, Many-to-Many với `Device`).
  * API CRUD Device Group và API gửi lệnh nhóm: `POST /api/v1/device-groups/{groupId}/command`.
  * Thực thi song song qua `CompletableFuture.allOf()`, gom kết quả trả về mã lỗi chi tiết từng thiết bị (Partial Success).
* **Chi tiết Frontend (FE):**
  * Trong `DevicePage.tsx`, bổ sung tab **"Nhóm Thiết Bị"** bên cạnh danh sách Phòng.
  * Modal tạo nhóm mới (Owner).
  * **Group Card với Thanh trượt Master Dimmer:** Kéo 1 thanh trượt độ sáng ➔ Cả nhóm đèn cùng mượt mà đổi độ sáng đồng loạt.
  * Thanh tiến trình: `Đang thực thi... [3/3 thiết bị đã nhận ACK]`.
* **Cách Show Hội Đồng:**
  * Chuyển sang tab Nhóm ➔ Kéo thanh trượt Master Slider ➔ Cả 3 đèn trong phòng cùng đổi độ sáng, thanh tiến trình xanh mướt hiện lên xác nhận 3/3 ACK.

---

#### 11. [PHO-042] Đếm Chu Kỳ Đóng Ngắt Relay & Thuật toán Phát hiện Dội Xung Chattering
* **Độ ưu tiên:** P0 | **Sprint:** 4 | **Role:** `OWNER` (Theo dõi bảo dưỡng)
* **Mục tiêu:** Tính toán độ hao mòn cơ học và cảnh báo nguy cơ cháy nổ do dội xung tiếp điểm relay.
* **Chi tiết Backend (BE):**
  * Bộ đếm chu kỳ `switchCycles` tăng lên mỗi lần đổi trạng thái trong `DeviceServiceImpl`.
  * Thuật toán phát hiện dội xung: Nếu số lần đổi trạng thái > 10 lần trong 60 giây ➔ Cưỡng bức ngắt thiết bị, bật cờ cảnh báo nguy cơ cháy nổ.
  * API: `GET /api/v1/devices/{deviceId}/health`.
* **Chi tiết Frontend (FE):**
  * Trong `DeviceDrawer.tsx`, thêm thẻ **"Tuổi thọ phần cứng (Relay Health)"**:
    * Thanh Progress Bar hiển thị `% Tuổi thọ còn lại` (vd: 98.5%).
    * Dòng chữ: `Đã đóng ngắt: 1,450 / 100,000 chu kỳ định mức`.
  * Biểu tượng chuông cảnh báo đỏ nhấp nháy nếu phát hiện hiện tượng chattering dội xung.
* **Cách Show Hội Đồng:**
  * Mở chi tiết 1 bóng đèn ➔ Chỉ vào thanh tuổi thọ phần cứng và chu kỳ đóng ngắt, khẳng định đề tài có nghiên cứu sâu về độ bền cơ học và an toàn thiết bị IoT.

---

#### 12. [PHO-043] Ma Trận Ưu Tiên Conflict Resolution & Huy Hiệu Đếm Ngược Khóa Tự Động 30 Phút
* **Độ ưu tiên:** P0 | **Sprint:** 3 | **Role:** `MEMBER` & `OWNER`
* **Mục tiêu:** Giải quyết xung đột giữa ý muốn con người và kịch bản tự động hóa AI.
* **Chi tiết Backend (BE):**
  * Ma trận phân cấp quyền điều khiển: $\text{EMERGENCY (P0)} > \text{MANUAL (P1)} > \text{VOICE/GESTURE (P2)} > \text{AUTOMATION (P3)}$.
  * Quản lý mốc thời gian `manualOverrideUntil = now + 30m`. Chặn các lệnh từ `AutomationEngine` của Quân khi thời gian khóa chưa hết.
  * API: `POST /api/v1/devices/{deviceId}/automation/cancel` (hủy khóa hoặc gia hạn).
* **Chi tiết Frontend (FE):**
  * Khi người dùng bấm nút Bật/Tắt thủ công trên Web:
    * Thẻ thiết bị xuất hiện một **Huy hiệu hổ phách phát sáng**:
      `⚡ Điều khiển tay: Khóa tự động [ 29:59 ]` *(đồng hồ đếm lùi từng giây thời gian thực!)*.
    * Bấm vào Huy hiệu mở popup chọn: `[ Hủy Khóa Ngay ]` hoặc `[ Khóa Thêm 1 Giờ ]`.
* **Cách Show Hội Đồng:**
  * Bấm bật đèn bằng tay ➔ Badge đếm ngược 30 phút xuất hiện nhấp nháy trên thẻ đèn. Giải thích cho hội đồng cách hệ thống ưu tiên ý muốn con người và giải quyết xung đột với kịch bản tự động của AI.

---

## 4. KỊCH BẢN PHỐI HỢP THỰC TẾ GIỮA 3 ROLE (LIVE DEMO SHOWCASE)

1. **Thành viên trong nhà (Member) sinh hoạt hằng ngày:**
   * Bấm Bật đèn bàn học ➔ Kích hoạt **Huy hiệu đếm ngược 30 phút** (`PHO-043`), khóa kịch bản AI không cho tự ý tắt đèn. Nếu lỡ tay click liên tục, Redisson Lock (`PHO-039`) chặn spam nhẹ nhàng bằng Toast thông báo.
2. **Chủ nhà (Owner) quản lý chi tiêu và an toàn:**
   * Mở tab "Tiêu Thụ Điện" (`PHO-032`, `PHO-033`), thấy biểu đồ phụ tải và số tiền điện EVN tạm tính tháng này.
   * Mở chi tiết thiết bị, phát hiện một relay đèn hành lang bị cảnh báo vàng do tiếp điểm dội xung (`PHO-042`) ➔ Bấm nút xuất Báo cáo Excel (`PHO-035`) rồi gửi thông báo cho Bên giải pháp yêu cầu bảo trì.
3. **Bên cung cấp giải pháp (Admin) hỗ trợ kỹ thuật từ xa:**
   * Kỹ thuật viên Admin đăng nhập trang `/admin`, mở **Live MQTT Packet Sniffer** (`PHO-040`) để kiểm tra gói tin của Node nhà khách hàng, kiểm tra độ trễ mạng qua đồng hồ **Latency Gauge** (`PHO-036`) và tạm thời chuyển **Circuit Breaker** (`PHO-037`) sang chế độ an toàn trước khi kỹ thuật viên tới nhà thay linh kiện.
