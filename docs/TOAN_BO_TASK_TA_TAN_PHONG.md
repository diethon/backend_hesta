# HỒ SƠ TỔNG HỢP TOÀN BỘ TASK & USE CASE — TẠ TẤN PHONG
## ĐỒ ÁN TỐT NGHIỆP CAPSTONE: HESTA SMART HOME

> **Hướng dẫn thêm task mới:** Bảng Master ở Mục 1 chỉ chứa 100% nhiệm vụ do Phong trực tiếp phụ trách. Khi có task mới phát sinh, copy dòng mẫu ở **[Mục 8: Template Thêm Task Mới]** và dán tiếp tục vào cuối Bảng 1.

---

### BẢNG THÔNG TIN TỔNG QUAN
| Thông tin | Chi tiết |
| :--- | :--- |
| **Thành viên phụ trách** | **Tạ Tấn Phong** |
| **Phạm vi cốt lõi** | • Hạ tầng phân tán & MQTT Core Gateway<br>• Trình giả lập phần cứng IoT Mock ESP32 Simulator (`simulator.py`)<br>• Quản lý thiết bị & Điều khiển nhóm (Batch Execution)<br>• Phân tích dữ liệu IoT, Độ bền phần cứng & Điện năng EVN<br>• Độ tin cậy (Circuit Breaker, Redisson Lock, Failover) & Chẩn đoán từ xa |
| **Ranh giới bàn giao** | • Đã chuyển giao cho **Nguyễn Công Toàn**: Lập trình firmware C++ nạp chip thật, hàn nối mạch GPIO relay, driver cảm biến vật lý.<br>• Đã chuyển giao cho **Hoàng Tống Việt Đức**: Pipeline giọng nói Whisper/STT, mô hình NLU Intent/Entity.<br>*(Toàn bộ các task chuyển giao đã được tách riêng sang Bảng 2, không nằm trong Bảng Master).* |
| **Trạng thái kiểm thử** | **PASS 100% TEST SUITES** (Backend: 304/304 tests, Frontend: 121/121 tests, 0 lỗi TypeScript). |

---

## 1. BẢNG MASTER: TOÀN BỘ NHIỆM VỤ TRỰC TIẾP CỦA PHONG (100% PHONG THỰC HIỆN)

| STT | Mã Task | Mã UC | Tên Nhiệm Vụ / Tính Năng | Phân Hệ | Phân Quyền | Chi Tiết Kỹ Thuật & Thuật Toán | API / Topic MQTT | File Mã Nguồn Chính | Sprint & Ưu Tiên | Trạng Thái |
| :---: | :---: | :---: | :--- | :--- | :---: | :--- | :--- | :--- | :---: | :---: |
| 1 | `PHO-001` | `UC-DEV-01` | Thiết kế Entity Room / Device / StateHistory | Database | Owner, Admin | Thiết kế lược đồ DB quan hệ thiết bị, phòng và lưu vết lịch sử | Migration SQL | `Device.java`, `Room.java`, `DeviceStateHistory.java` | Sprint 1 (P0) | Hoàn thành |
| 2 | `PHO-002` | `UC-DEV-01` | Device CRUD Service & Controller cơ bản | Backend | Owner, Admin | Viết API tạo, đọc, cập nhật thông tin thiết bị theo phòng | `GET/POST /api/v1/homes/{id}/devices` | `DeviceController.java`, `DeviceServiceImpl.java` | Sprint 1 (P0) | Hoàn thành |
| 3 | `PHO-003` | `UC-DEV-02` | Định nghĩa Generic DeviceCommandService Interface | Shared Core | All Roles | Xây dựng giao diện điều khiển dùng chung tham số động `Map<String, Object>` | Java Interface | `DeviceCommandService.java` | Sprint 1 (P0) | Hoàn thành |
| 4 | `PHO-004` | `UC-INF-01` | Chốt MQTT Topic Convention phân cấp | MQTT Core | Admin | Chuẩn hóa topic: `hesta/nodes/{nodeId}/devices/{devId}/command\|ack\|state` | Contract | `mqtt-contract.md` | Sprint 1 (P0) | Hoàn thành |
| 5 | `PHO-005` | `UC-INF-01` | Định nghĩa Payload JSON Command & ACK | MQTT Core | Admin | Chuẩn hóa cấu trúc gói tin gửi/nhận kèm correlation ID và timestamp | JSON Spec | `PHONG_MODULES_INTEGRATION_GUIDE.md` | Sprint 1 (P0) | Hoàn thành |
| 6 | `PHO-006` | `UC-INF-01` | Xây dựng MqttConfig & Connection Manager | MQTT Core | Admin | Thiết lập kết nối Eclipse Paho MQTT Broker với client pool | Broker TCP | `MqttConfig.java` | Sprint 1 (P0) | Hoàn thành |
| 7 | `PHO-007` | `UC-INF-01` | Xây dựng Publisher / Subscriber Gateway | MQTT Core | Admin | Tạo cổng trung chuyển publish lệnh và subscribe state/ack đa luồng | Broker Channel | `MqttMessageReceiver.java` | Sprint 1 (P0) | Hoàn thành |
| 8 | `PHO-008` | `UC-SIM-01` | Xây dựng Mock ESP32 Simulator chạy lệnh ON/OFF | IoT Simulator | System / Dev | Viết script Python mô phỏng 3 node ESP32 (AC, Cổng, Cảm biến), phát ACK <20ms & telemetry | MQTT Pub/Sub | `iot_hesta/simulator.py`, `README_SIMULATOR.md` | Sprint 1 (P1) | Hoàn thành |
| 9 | `PHO-011` | `UC-DEV-01` | Giao diện Device List / Detail cơ bản | Frontend | Owner, Member | Xây dựng giao diện hiển thị danh sách thiết bị và trạng thái | Web Route | `DevicePage.tsx`, `DeviceCard.tsx` | Sprint 1 (P1) | Hoàn thành |
| 10 | `PHO-012` | `N/A` | Unit Test Smoke Test MQTT & Core | Testing | System | Viết bộ kiểm thử tự động cho Gateway và Service | JUnit 5 | `MqttDeviceCommandServiceImplTest.java` | Sprint 1 (P1) | Hoàn thành |
| 11 | `PHO-016` | `UC-DEV-02` | Implement MqttDeviceCommandServiceImpl thật | Backend | Owner, Member | Hoàn thiện luồng gửi lệnh MQTT, chờ ACK và cập nhật trạng thái | `POST /api/v1/devices/{id}/command` | `MqttDeviceCommandServiceImpl.java` | Sprint 2 (P0) | Hoàn thành |
| 12 | `PHO-017` | `UC-DEV-02` | Timeout & Retry Policy cho lệnh điều khiển | Backend | Owner, Member | Thiết lập timeout (5s) tránh rò rỉ thread khi node không phản hồi | Async Timeout | `MqttDeviceCommandServiceImpl.java` | Sprint 2 (P0) | Hoàn thành |
| 13 | `PHO-018` | `UC-MON-01` | Đồng bộ trạng thái thời gian thực (State Sync) | Realtime | Owner, Member | Nhận State từ MQTT ➔ Cập nhật DB ➔ Bắn WebSocket báo UI | WS `/topic/device-state` | `MqttMessageReceiver.java`, `realtimeSlice.ts` | Sprint 2 (P0) | Hoàn thành |
| 14 | `PHO-019` | `UC-DEV-02` | Điều khiển Bật/Tắt thiết bị tức thì trên Web | Frontend | Owner, Member | Nút công tắc phản hồi siêu tốc, tự bám theo state thật | Web UI | `DeviceCard.tsx`, `DevicePage.tsx` | Sprint 2 (P1) | Hoàn thành |
| 15 | `PHO-021` | `N/A` | Integration Test MQTT trên môi trường thực | Testing | System | Kiểm thử tích hợp gửi lệnh, nhận ACK và lưu lịch sử | Integration Test | `RealtimeWebSocketIntegrationTest.java` | Sprint 2 (P1) | Hoàn thành |
| 16 | `PHO-024` | `UC-DEV-03` | Điều khiển phòng & nhóm thiết bị (Room Control) | Backend | Owner, Member | Xử lý gửi lệnh hàng loạt cho toàn bộ thiết bị trong phòng | `POST /api/v1/rooms/{id}/command` | `DeviceServiceImpl.java` | Sprint 3 (P0) | Hoàn thành |
| 17 | `PHO-025` | `UC-MON-03` | Ghi nhận Device History & Device Settings | Backend | Owner, Member | Ghi vết mọi sự kiện ON/OFF, nguồn tác động (App, Voice, Rule) | `GET /api/v1/devices/{id}/history` | `DeviceStateHistoryRepository.java` | Sprint 3 (P1) | Hoàn thành |
| 18 | `PHO-026` | `UC-INF-01` | Reconnect & State Reconciliation khi mất mạng | Reliability | Admin | Tự động kết nối lại broker và bù trạng thái thiết bị | MQTT Reconnect | `MqttCallbackExtended` in `MqttConfig.java` | Sprint 3 (P0) | Hoàn thành |
| 19 | `PHO-027` | `UC-DEV-02` | Idempotency Engine chống duplicate message | Reliability | All Roles | Bắt trùng lặp correlation ID, tránh thực thi lại lệnh cũ | Correlation Map | `MqttDeviceCommandServiceImpl.java` | Sprint 3 (P1) | Hoàn thành |
| 20 | `PHO-028` | `N/A` | Regression Test toàn bộ Device & MQTT | Testing | System | Chạy kiểm thử hồi quy bảo đảm 100% test pass khi merge code | `mvn test` | 304 unit tests pass 100% | Sprint 4 (P0) | Hoàn thành |
| 21 | `PHO-029` | `UC-INF-02` | Đo lường hiệu năng RTT Latency & Throughput | Metrics | Admin | Thu thập số liệu độ trễ Round-Trip Time thực tế đưa vào báo cáo | Benchmark | `MqttDeviceCommandServiceImpl.java` | Sprint 4 (P0) | Hoàn thành |
| 22 | `PHO-030` | `N/A` | Viết tài liệu tích hợp kiến trúc Device/MQTT | Docs | Team | Viết tài liệu chuẩn giao tiếp cho team FE và IoT | Markdown | `PHONG_MODULES_INTEGRATION_GUIDE.md` | Sprint 4 (P1) | Hoàn thành |
| 23 | `PHO-031` | `N/A` | Chuẩn bị kịch bản Demo hoàn chỉnh Hội đồng | Demo | All Roles | Xây dựng kịch bản trình diễn tương tác trực tiếp trước hội đồng | Demo Plan | `TOAN_BO_TASK_TA_TAN_PHONG.md` | Sprint 4 (P0) | Hoàn thành |
| 24 | `PHO-032` | `UC-ENG-01` | Thuật toán tính kWh (Riemann) & Tiền điện EVN 6 bậc | Năng Lượng | Owner | Tích phân số Riemann $\sum (P_i \times \Delta t_i)/1000$ nhân biểu giá 6 bậc EVN | `GET /api/v1/homes/{id}/energy/summary` | `EnergyAnalyticsServiceImpl.java` | Mới (P0) | Hoàn thành |
| 25 | `PHO-033` | `UC-ENG-02` | Dashboard Biểu đồ Năng lượng 24h & Donut Chart | Năng Lượng | Owner | Biểu đồ Area Chart tải điện 24h và Donut Chart phân bổ tỷ lệ % thiết bị | `GET /api/v1/homes/{id}/energy/timeseries` | `EnergyDashboardPage.tsx`, `EnergyController.java` | Mới (P0) | Hoàn thành |
| 26 | `PHO-034` | `UC-MON-01` | Thống kê Uptime/Downtime & Availability Profiling | Giám Sát | Owner | Tính tỉ lệ sẵn sàng hoạt động $Uptime = \frac{T_{online}}{T_{total}} \times 100\%$ trong 7 ngày | `GET /api/v1/devices/{id}/analytics/uptime` | `DeviceProfilingServiceImpl.java`, `DeviceDrawer.tsx` | Mới (P1) | Hoàn thành |
| 27 | `PHO-035` | `UC-MON-03` | Visual Audit Timeline & Xuất Báo Cáo Excel (POI) | Kiểm Toán | Owner, Member | Timeline lịch sử trực quan; xuất file `.xlsx` chính thức đóng dấu HESTA | `GET /api/v1/devices/{id}/history/export` | `DeviceServiceImpl.java`, Apache POI | Mới (P1) | Hoàn thành |
| 28 | `PHO-036` | `UC-INF-02` | Giám sát độ trễ mạng hai chiều (MQTT Latency Gauge) | Hạ Tầng | Admin | Đo RTT ms (Min, Max, Avg, P95) của 100 lệnh gần nhất; kim đo vòng cung | `GET /api/v1/admin/diagnostics/summary` | `SystemDiagnosticsPage.tsx`, `MqttDiagnosticsService` | Mới (P0) | Hoàn thành |
| 29 | `PHO-037` | `UC-INF-03` | Resilience4j Circuit Breaker & Exponential Retry | Hạ Tầng | Admin | Tự ngắt mạch (`OPEN`) khi node lỗi 5 lần; từ chối lệnh trong 5ms chống treo luồng | `POST /api/v1/admin/diagnostics/circuit-breaker/{node}/trip` | `MqttDiagnosticsServiceImpl.java` | Mới (P0) | Hoàn thành |
| 30 | `PHO-038` | `UC-INF-01` | Runtime Broker Failover & Dead Man's Snitch Watchdog | Hạ Tầng | Admin | Watchdog 60s phát hiện node mất nguồn; tự hồi phục kết nối broker | WebSocket Event | `MqttCallbackExtended`, `NodeDiagnosticsResponse.java` | Mới (P1) | Hoàn thành |
| 31 | `PHO-039` | `UC-DEV-02` | Redisson Distributed Lock & Exactly-Once Idempotency | Tin Cậy | All Roles | Khóa `device:lock:{id}` trong 1-2s; Toast cảnh báo chặn spam click liên tục | `COMMAND_IN_PROGRESS_DUPLICATE_REJECTED` | `MqttDeviceCommandServiceImpl.java`, Redisson | Mới (P0) | Hoàn thành |
| 32 | `PHO-040` | `UC-INF-04` | Live MQTT Packet Sniffer Console trên Web Dashboard | Chẩn Đoán | Admin | Terminal đen tuyền hiển thị trực tiếp gói tin In/Out, topic, QoS, JSON payload | WS `/topic/mqtt-sniffer` | `MqttSnifferConsole.tsx`, `SystemDiagnosticsPage.tsx` | Mới (P1) | Hoàn thành |
| 33 | `PHO-041` | `UC-DEV-03` | Quản lý nhóm tùy biến & Master Dimmer Slider | Nhóm Thiết Bị | Owner, Member | Gom thiết bị xuyên phòng; thanh trượt Master Dimmer đồng bộ nhiều đèn song song | `POST /api/v1/device-groups/{id}/command` | `DeviceGroupServiceImpl.java`, `DevicePage.tsx` | Mới (P1) | Hoàn thành |
| 34 | `PHO-042` | `UC-MON-02` | Đếm chu kỳ đóng ngắt & Cảnh báo dội xung Relay | Độ Bền | Owner | Đếm số lần chuyển mạch so với 100k chu kỳ; cảnh báo chattering nếu >10 lần/phút | `GET /api/v1/devices/{id}/health` | `DeviceHealthServiceImpl.java`, `DeviceDrawer.tsx` | Mới (P0) | Hoàn thành |
| 35 | `PHO-043` | `UC-DEV-04` | Khóa ưu tiên điều khiển tay 30 phút (Manual Override) | Xung Đột | Owner, Member | Bấm tay bật đèn ➔ Khóa kịch bản AI trong 30 phút; huy hiệu đếm ngược `[ 29:59 ]` | `POST /api/v1/devices/{id}/automation/cancel` | `ManualOverrideServiceImpl.java`, `DeviceCard.tsx` | Mới (P0) | Hoàn thành |
| 36 | `PHO-044` | `UC-DEV-05` | Khám phá năng lực tự động (Edge-Driven Catalog) | Kiến Trúc | Admin, Owner | ESP32 ném JSON danh mục thiết bị lên topic catalog; backend lưu JSONB vào EdgeNode | `hesta/nodes/+/catalog` | `MqttMessageReceiver.java`, `EdgeNode.java` | Kiến trúc | Hoàn thành |
| 37 | `PHO-045` | `UC-SYS-01` | Quản trị lưu trữ: Throttling & Auto-Cleanup Job | Hệ Thống | System | Lọc trùng không ghi DB; giới hạn cảm biến 5p/lần; job 2:00 AM xóa dữ liệu > 30 ngày | `@Scheduled(cron="0 0 2 * * ?")` | `DeviceHistoryCleanupJob.java` | Nền tảng | Hoàn thành |
| 38 | `PHO-046` | `UC-DEV-06` | Điều khiển từ xa an toàn qua mạng Internet ngoài | Bảo Mật | Owner, Member | Gửi lệnh từ xa qua HTTPS REST API và JWT Token an toàn; không mở port broker | `HTTPS POST /api/v1/devices/...` | `SecurityConfig.java`, `DeviceController.java` | Bảo mật | Hoàn thành |
| 39 | `PHO-047` | `UC-INF-05` | MQTT Ingress Hardening & Dead-Letter Queue (DLQ) | Hạ Tầng | Admin | Token-bucket rate limit 50 msg/s/node, cách ly gói tin hỏng vào DLQ ring-buffer, REST replay endpoint | `POST /api/v1/admin/diagnostics/dlq/replay` | `MqttDlqServiceImpl.java`, `AdminDiagnosticsController.java` | Nâng Cao (P0) | Hoàn thành |
| 40 | `PHO-048` | `UC-DEV-07` | Kiến trúc Device Shadow (Desired vs Reported State) | Tin Cậy | Owner, Member | Phân tách Desired vs Reported, phiên bản đơn điệu monotonic, tự động tính Delta và Reconciler | `GET /api/v1/devices/{id}/shadow` | `DeviceShadowServiceImpl.java`, `DeviceController.java` | Nâng Cao (P0) | Hoàn thành |
| 41 | `PHO-049` | `UC-ENG-03` | Demand Response & Peak Shaving Cắt Tải Đỉnh | Năng Lượng | Owner | Giới hạn công suất đỉnh kW, cắt tải tham lam theo thứ tự LUXURY -> COMFORT, khóa an toàn SAFETY/ESSENTIAL | `POST /api/v1/homes/{id}/dr/evaluate` | `DemandResponseServiceImpl.java`, `EnergyController.java` | Nâng Cao (P1) | Hoàn thành |
| 42 | `PHO-050` | `UC-INF-06` | OTA Firmware Campaign Orchestrator | Fleet IoT | Admin | Quản lý kho firmware, Canary Rollout (10%->50%->100%), tự động dừng khẩn cấp Auto-Abort khi lỗi > 20% | `POST /api/v1/admin/ota/campaigns` | `OtaCampaignServiceImpl.java`, `AdminOtaController.java` | Nâng Cao (P1) | Hoàn thành |
| 43 | `PHO-051` | `UC-ENG-04` | HESTA Ví Điện: Smart Prepaid Energy Wallet & Live Burn-Rate | Năng Lượng | Owner | Ví tiền điện trả trước số động, đồng hồ tốc độ đốt tiền (Burn-Rate đ/h) nhảy realtime từng giây theo công suất W, tự kích hoạt ngắt tải thông minh (Auto Load-Shedding) bảo vệ ngân sách | `POST /api/v1/homes/{id}/wallet/topup`, `GET /api/v1/homes/{id}/wallet/burn-rate` | `EnergyWalletServiceImpl.java`, `EnergyWalletCard.tsx` | Dấu Ấn (P0) | Kế hoạch làm sau |
| 44 | `PHO-052` | `UC-MON-04` | HESTA Pulse: Nhịp Tim Ngôi Nhà (Realtime Vitality ECG) | Trực Quan Hóa | All Roles | Dải sóng điện tâm đồ 2D ECG co bóp theo nhịp tải điện (W) và tần số gói tin MQTT, chuyển đổi 3 trạng thái cảm xúc (Thư thái - Hăng say - Căng thẳng quá tải) | WS `/topic/house-pulse` | `HousePulseWebSocketHandler.java`, `HousePulseEcg.tsx` | Dấu Ấn (P0) | Kế hoạch làm sau |
| 45 | `PHO-053` | `UC-INF-07` | HESTA Edge-Survive: Chế Độ Độc Lập Khi Rút Dây Mạng | Tin Cậy | Owner, Member | Trải nghiệm 'Rút dây mạng nhà vẫn sống': Giao diện Web tự chuyển chế độ Edge Node dự phòng khi ngắt WAN, bật tắt thiết bị <50ms qua Local Broker, tự động đồng bộ khi có mạng lại | WebSocket Network Probe, Local Gateway Proxy | `NetworkResilienceService.java`, `EdgeSurviveBanner.tsx` | Dấu Ấn (P0) | Kế hoạch làm sau |

---

## 2. BẢNG CÁC NHIỆM VỤ ĐÃ CHUYỂN GIAO CHO THÀNH VIÊN KHÁC (BÀN GIAO TOÀN & ĐỨC)

> *Ghi chú: Toàn chỉ làm phần cứng bo mạch vật lý nạp chip C++; Đức làm phần nhận diện giọng nói AI. Trình giả lập phần cứng ảo bằng phần mềm (`iot_hesta/simulator.py`) thuộc quyền sở hữu của Phong (Task PHO-008 trên Bảng Master).*

| STT | Mã Task Gốc | Tên Nhiệm Vụ Ban Đầu | Thành Viên Tiếp Nhận | Lý Do Bàn Giao & Phạm Vi Mới | Trạng Thái Bàn Giao |
| :---: | :---: | :--- | :---: | :--- | :---: |
| 1 | `PHO-009` | Dựng Intent Classification prototype | **Hoàng Tống Việt Đức** | Đức nhận phụ trách trọn gói AI Voice & Speech trong `voice-service` Python | Đã bàn giao |
| 2 | `PHO-010` | Dựng Entity Extraction prototype | **Hoàng Tống Việt Đức** | Đức phát triển pipeline trích xuất thực thể giọng nói tự nhiên | Đã bàn giao |
| 3 | `PHO-013` | Firmware subscribe command & đóng ngắt GPIO/Relay | **Nguyễn Công Toàn** | Toàn chuyên trách lập trình C++ FreeRTOS trên vi điều khiển ESP32 | Đã bàn giao |
| 4 | `PHO-014` | Publish ACK & State phần cứng thật | **Nguyễn Công Toàn** | Toàn trực tiếp viết driver phần cứng gửi ACK chân relay | Đã bàn giao |
| 5 | `PHO-015` | Gói tin Heartbeat & Giám sát Online/Offline phần cứng | **Nguyễn Công Toàn** | Toàn lập trình timer trên ESP32 phát heartbeat định kỳ 30s | Đã bàn giao |
| 6 | `PHO-020` | Nối NLU Voice ➔ Device Resolution ➔ Gửi Lệnh | **Hoàng Tống Việt Đức** | Đức xây dựng dispatcher gọi sang endpoint nội bộ `/internal/**` của Phong | Đã bàn giao |
| 7 | `PHO-022` | Nâng cấp NLU xử lý câu phức tạp & đa tham số | **Hoàng Tống Việt Đức** | Đức tinh chỉnh mô hình ngôn ngữ tiếng Việt nhận diện số đo phần trăm, nhiệt độ | Đã bàn giao |
| 8 | `PHO-023` | Ambiguity Detection phát hiện câu lệnh mơ hồ | **Hoàng Tống Việt Đức** | Đức phát triển mô hình hội thoại đa lượt giải quyết mơ hồ giọng nói | Đã bàn giao |

---

## 3. BẢNG PHÂN BỔ USE CASE THEO CHỨC NĂNG (DÀNH CHO BÁO CÁO ĐỒ ÁN)

| Mã Use Case | Tên Use Case Nghiệp Vụ | Mã Task Trực Tiếp | Actor | Mục Tiêu & Mô Tả Tóm Tắt | Giao Diện / API Tương Ứng |
| :---: | :--- | :---: | :---: | :--- | :--- |
| **`UC-SIM-01`** | Giả lập phần cứng IoT đa thiết bị (Simulator) | PHO-008 | System, Dev | Khởi chạy `simulator.py` mô phỏng 3 bo mạch ESP32 (AC, Cổng, Cảm biến), phản hồi ACK <20ms | `iot_hesta/simulator.py` |
| **`UC-DEV-01`** | Quản lý danh mục & Cấu hình thiết bị | PHO-001, 002, 011 | Owner, Admin | CRUD thiết bị, gán phòng, quản lý năng lực động qua JSON | `DevicePage.tsx`, `/api/v1/homes/{id}/devices` |
| **`UC-DEV-02`** | Điều khiển đơn thiết bị & Chống spam | PHO-003, 016, 019, 039 | Owner, Member | Bật/tắt, đổi màu/nhiệt độ; Redisson Lock chặn click spam | `DeviceCard.tsx`, `/api/v1/devices/{id}/command` |
| **`UC-DEV-03`** | Quản lý nhóm tùy biến & Master Dimmer | PHO-024, 041 | Owner, Member | Tạo nhóm xuyên phòng, kéo 1 slider điều khiển đồng loạt | Tab Nhóm Thiết Bị, `/api/v1/device-groups` |
| **`UC-DEV-04`** | Khóa điều khiển tay 30 phút (Override) | PHO-043 | Owner, Member | Bấm tay khóa tự động AI 30p; huy hiệu đếm ngược `[ 29:59 ]` | Badge trên thẻ đèn, `/automation/cancel` |
| **`UC-DEV-05`** | Khám phá năng lực phần cứng tự động | PHO-044 | Admin, Owner | Tự nhận catalog từ ESP32; hiển thị menu không sai chính tả | Topic `hesta/nodes/+/catalog` |
| **`UC-DEV-06`** | Điều khiển từ xa an toàn qua Internet | PHO-046 | Owner, Member | Điều khiển ngoài nhà qua HTTPS + JWT, không mở port MQTT | `DeviceController.java`, `SecurityConfig.java` |
| **`UC-MON-01`** | Giám sát trạng thái & Uptime Profiling | PHO-018, 034 | Owner, Member | Theo dõi Online/Offline và tỉ lệ sẵn sàng hoạt động 7 ngày | Tab Phân Tích trong `DeviceDrawer.tsx` |
| **`UC-MON-02`** | Tuổi thọ phần cứng & Cảnh báo dội xung | PHO-042 | Owner | % Tuổi thọ relay (100k chu kỳ); cảnh báo cháy nổ dội xung | Thẻ Tuổi Thọ Relay trong `DeviceDrawer.tsx` |
| **`UC-MON-03`** | Visual Audit Timeline & Xuất Excel | PHO-025, 035 | Owner, Member | Xem lịch sử thao tác; xuất file kiểm toán `.xlsx` Apache POI | Nút `[Xuất Báo Cáo Excel]`, `/history/export` |
| **`UC-ENG-01`** | Tính kWh Riemann & Tiền điện EVN 6 bậc | PHO-032 | Owner | Tích phân số năng lượng và hóa đơn điện sinh hoạt tạm tính | 3 Thẻ KPI trên `EnergyDashboardPage.tsx` |
| **`UC-ENG-02`** | Biểu đồ phụ tải 24h & Donut phân bổ | PHO-033 | Owner | Đỉnh phụ tải giờ cao điểm và tỷ lệ % tiêu thụ từng thiết bị | Area Chart & Donut Chart trên trang Năng Lượng |
| **`UC-INF-01`** | Quản trị kết nối Broker & Failover | PHO-004, 006, 026, 038 | Admin | Tự kết nối lại, bù trạng thái; watchdog 60s báo node chết | `MqttConfig.java`, Thẻ trạng thái Node |
| **`UC-INF-02`** | Giám sát độ trễ mạng (RTT Latency) | PHO-029, 036 | Admin | Đo RTT ms hai chiều (Min/Max/Avg/P95); kim đo vòng cung | Latency Gauge trên `SystemDiagnosticsPage.tsx` |
| **`UC-INF-03`** | Ngắt mạch bảo vệ quá tải Circuit Breaker | PHO-037 | Admin | Tự ngắt mạch khi node lỗi 5 lần, từ chối lệnh trong 5ms | Thẻ Circuit Breaker, Endpoint `/trip` |
| **`UC-INF-04`** | Soi gói tin MQTT thời gian thực (Sniffer) | PHO-040 | Admin | Terminal đen tuyền hiển thị trực tiếp gói tin In/Out qua WS | `MqttSnifferConsole.tsx` |
| **`UC-SYS-01`** | Throttling DB & Auto-Cleanup Job | PHO-045 | System | Chống spam ghi DB, xóa vĩnh viễn dữ liệu cũ hơn 30 ngày | Job định kỳ 2:00 AM mỗi ngày |
| **`UC-ENG-04`** | HESTA Ví Điện & Live Burn-Rate | PHO-051 | Owner | Ví trả trước số động, đo tốc độ đốt tiền đ/h, tự ngắt tải khi chạm ngân sách | `EnergyWalletCard.tsx`, `/api/v1/homes/{id}/wallet` |
| **`UC-MON-04`** | HESTA Pulse: Nhịp Tim Ngôi Nhà (2D ECG) | PHO-052 | All Roles | Dải sóng nhịp tim co bóp theo tải W & MQTT, chuyển màu 3 trạng thái cảm xúc | `HousePulseEcg.tsx`, `/topic/house-pulse` |
| **`UC-INF-07`** | HESTA Edge-Survive: Rút dây mạng nhà vẫn sống | PHO-053 | Owner, Member | Local Failover bật tắt <50ms khi mất WAN Internet, tự bù đồng bộ khi online lại | `EdgeSurviveBanner.tsx`, `NetworkResilienceService` |

---

## 4. BẢNG PHÂN QUYỀN MA TRẬN RBAC CHO 20 USE CASE

| Mã Use Case | Tên Nghiệp Vụ | 🛠️ ADMIN (Giải Pháp) | 🏠 OWNER (Chủ Nhà) | 👤 MEMBER (Thành Viên) |
| :---: | :--- | :---: | :---: | :---: |
| `UC-SIM-01` | Trình giả lập phần cứng IoT Simulator | ✅ | ❌ | ❌ |
| `UC-DEV-01` | Quản lý danh mục & cấu hình thiết bị | ✅ | ✅ | ❌ |
| `UC-DEV-02` | Điều khiển đơn thiết bị & Redisson Lock | ✅ | ✅ | ✅ |
| `UC-DEV-03` | Quản lý nhóm tùy biến & Master Dimmer | ❌ | ✅ *(Tạo nhóm)* | ✅ *(Kéo slider)* |
| `UC-DEV-04` | Khóa ưu tiên điều khiển tay 30 phút | ❌ | ✅ | ✅ |
| `UC-DEV-05` | Khám phá năng lực phần cứng tự động | ✅ | ✅ | ❌ |
| `UC-DEV-06` | Điều khiển thiết bị từ xa qua Internet | ❌ | ✅ | ✅ |
| `UC-MON-01` | Giám sát trạng thái & Uptime Profiling | ❌ | ✅ | ✅ |
| `UC-MON-02` | Tuổi thọ relay & Cảnh báo dội xung | ❌ | ✅ | ❌ |
| `UC-MON-03` | Visual Audit Timeline & Xuất Excel | ❌ | ✅ *(Xuất file)* | ✅ *(Xem timeline)* |
| `UC-ENG-01` | Tính kWh Riemann & Tiền điện EVN | ❌ | ✅ *(Quản trị chi phí)* | ❌ |
| `UC-ENG-02` | Biểu đồ phụ tải 24h & Donut cơ cấu | ❌ | ✅ | ❌ |
| `UC-INF-01` | Broker Failover & Dead Man's Snitch | ✅ | ❌ | ❌ |
| `UC-INF-02` | Đo độ trễ mạng (RTT Latency Gauge) | ✅ | ❌ | ❌ |
| `UC-INF-03` | Ngắt mạch bảo vệ Circuit Breaker | ✅ | ❌ | ❌ |
| `UC-INF-04` | Live MQTT Packet Sniffer Console | ✅ | ❌ | ❌ |
| `UC-SYS-01` | Throttling DB & Auto-Cleanup Job | ✅ | ❌ | ❌ |
| `UC-ENG-04` | HESTA Ví Điện & Live Burn-Rate | ❌ | ✅ *(Nạp ví & Cắt tải)* | ❌ |
| `UC-MON-04` | HESTA Pulse: Nhịp Tim 2D ECG | ✅ | ✅ | ✅ *(Xem nhịp tim)* |
| `UC-INF-07` | Edge-Survive: Chế độ độc lập ngoại tuyến | ✅ | ✅ | ✅ |

---

## 5. BẢNG MÃ NGUỒN BACKEND & FRONTEND TƯƠNG ỨNG

| STT | Phân Hệ | File Mã Nguồn Backend | File Giao Diện Frontend / Công Cụ |
| :---: | :--- | :--- | :--- |
| 1 | **IoT Simulation** | Không có (kết nối qua MQTT) | `iot_hesta/simulator.py`, `README_SIMULATOR.md` |
| 2 | **MQTT Gateway** | `MqttConfig.java`, `MqttMessageReceiver.java` | Chạy nền |
| 3 | **Device Control** | `MqttDeviceCommandServiceImpl.java`, `DeviceController.java` | `DeviceCard.tsx`, `DevicePage.tsx` |
| 4 | **Device Groups** | `DeviceGroupServiceImpl.java`, `DeviceGroupController.java` | `DevicePage.tsx` (Tab Nhóm), Modal tạo nhóm |
| 5 | **Manual Override** | `ManualOverrideServiceImpl.java`, `ManualControlServiceImpl.java` | Huy hiệu đếm ngược trên `DeviceCard.tsx` |
| 6 | **Energy Engine** | `EnergyAnalyticsServiceImpl.java`, `EnergyController.java` | `EnergyDashboardPage.tsx` |
| 7 | **Uptime & Health** | `DeviceProfilingServiceImpl.java`, `DeviceHealthServiceImpl.java` | `DeviceDrawer.tsx` (Tab Phân Tích & Tuổi Thọ) |
| 8 | **Excel Export** | `DeviceServiceImpl.java` (Apache POI) | Nút xuất Excel trong `DeviceDrawer.tsx` |
| 9 | **Diagnostics** | `MqttDiagnosticsServiceImpl.java`, `AdminDiagnosticsController.java` | `SystemDiagnosticsPage.tsx`, `MqttSnifferConsole.tsx` |
| 10 | **Storage Throttling** | `DeviceHistoryCleanupJob.java`, `DeviceStateHistoryRepository.java` | Scheduled Job chạy 2:00 AM |
| 11 | **DLQ & Hardening** | `MqttDlqServiceImpl.java`, `AdminDiagnosticsController.java` | `DeadLetterQueueConsole.tsx`, `SystemDiagnosticsPage.tsx` (Tab DLQ) |
| 12 | **Device Shadow** | `DeviceShadowServiceImpl.java`, `DeviceController.java` | `DeviceShadowSection.tsx`, `DeviceDrawer.tsx` (Mục Bóng Thiết Bị) |
| 13 | **Demand Response** | `DemandResponseServiceImpl.java`, `EnergyController.java` | `DemandResponseCard.tsx`, `EnergyDashboardPage.tsx` (Thẻ Cắt Tải Đỉnh) |
| 14 | **OTA Orchestrator** | `OtaCampaignServiceImpl.java`, `AdminOtaController.java` | `OtaCampaignManager.tsx`, `SystemDiagnosticsPage.tsx` (Tab OTA) |

---

## 6. BẢNG MINH CHỨNG KIỂM THỬ (QA & AUTOMATED TEST EVIDENCE)

| Phân Hệ Kiểm Thử | Lệnh Kiểm Thử | Số Lượng Tests | Kết Quả Thực Tế | Tỷ Lệ Đạt |
| :--- | :--- | :---: | :---: | :---: |
| **Backend Unit & Slice** | `mvn test-compile -DskipTests` | 337 files | BUILD SUCCESS (0 compile errors) | **100%** |
| **Backend Test Suite** | `mvn test` | 314 tests | 314 passed, 0 failures, 0 errors | **100%** |
| **Frontend TypeScript** | `npm run build` (`tsc -b && vite build`) | 2738 modules | 0 TypeScript errors, bundle build thành công | **100%** |
| **Frontend Test Suite** | `npm test` | 121 tests | 121 passed, 0 failures | **100%** |

---

## 7. BẢNG KỊCH BẢN SHOWCASE BẢO VỆ ĐỒ ÁN (DEMO SCENARIOS)

| STT | Tên Kịch Bản Demo | Các Bước Thao Tác Trình Diễn | Hiện Tượng Quan Sát Trên Màn Hình | Điểm Nhấn Kiến Trúc Trình Bày |
| :---: | :--- | :--- | :--- | :--- |
| **1** | **Simulator & Điều Khiển Siêu Tốc** | Chạy `simulator.py`, mở Admin tab `Giám Sát`, bấm bật đèn | Terminal Simulator hiện lệnh tím; kim RTT nhảy **~20ms**; Sniffer hiện log xanh | Chu trình khép kín FE-BE-MQTT-Simulator không cần phụ thuộc mạch thật |
| **2** | **Tích Phân Năng Lượng & Tiền Điện EVN** | Bật Điều hòa, mở tab `Tiêu Thụ Điện` | Số kWh và Tiền điện VNĐ nhảy tăng theo thời gian thực | Thuật toán tích phân Riemann $\sum \frac{P_i \times \Delta t_i}{1000}$ & bảng giá 6 bậc EVN |
| **3** | **Độ Bền Phần Cứng & Xuất Excel** | Mở ngăn kéo đèn ➔ Bấm `[Xuất Báo Cáo Excel]` | File `.xlsx` tải về ngay lập tức; mở file xem bảng kiểm toán | Đếm chu kỳ cơ học relay, thư viện Apache POI xuất báo cáo chuyên nghiệp |
| **4** | **Chống Spam, Khóa Tay 30p & Ngắt Mạch** | Click 5 lần liên tục; xem badge; bấm Trip Circuit | Toast chặn spam; Badge hổ phách `[ 29:59 ]`; Thẻ đỏ `CIRCUIT OPEN` | Redisson Lock; giải quyết xung đột ưu tiên con người; Resilience4j ngắt mạch 5ms |
| **5** | **HESTA Ví Điện (Burn-Rate & Load Shedding)** | Nạp ví 500k, bật bình đun ➔ Kim Burn-Rate vọt đỏ ➔ Bấm Cắt tải | Kim Burn-Rate vọt lên `-3.800 đ/h`; gạt nút Cắt Tải khẩn cấp ➔ Bình đun ngắt ngay, số dư dừng rơi | Đưa tài chính EVN vào Smart Home: Riemann calculation + Auto Load-Shedding bảo toàn ngân sách |
| **6** | **HESTA Pulse (Nhịp Tim Ngôi Nhà 2D ECG)** | Bật các thiết bị tải nặng ➔ Quan sát dải sóng nhịp tim ECG | Sóng giật mạnh, tần số tăng gấp đôi, đổi sắc cam/đỏ rực; tắt thiết bị sóng dịu lại | Trực quan hóa Telemetry 2D Canvas động phản ánh sức sống ngôi nhà thời gian thực |
| **7** | **HESTA Edge-Survive (Rút Mạng Nhà Vẫn Sống)** | Rút cáp LAN/Wi-Fi hoặc bấm giả lập ngắt WAN ➔ Bật/tắt công tắc | Badge chuyển sang `[ ⚡ CHẾ ĐỘ ĐỘC LẬP ]`, bấm bật đèn phản hồi <50ms qua Local Broker | Kiến trúc High Availability: Local Failover và đồng bộ hóa ngoại tuyến khi khôi phục mạng |
| **8** | **Hàng Đợi Chết DLQ & Replay Gói Tin Lỗi (PHO-047)** | Vào Admin Diagnostics ➔ Tab `Hàng Đợi DLQ` ➔ Bấm `[Giả Lập Bắn Rác MQTT]` ➔ Quan sát thẻ cảnh báo đỏ ➔ Bấm `[Thử Lại (Replay)]` | Payload sai cú pháp JSON hoặc mã hóa lạ lập tức bị cách ly vào DLQ; bấm Replay đẩy lại bus thành công hoặc xóa rác | Hệ thống phòng thủ Ingress Rate Limiting & Dead-Letter Queue bảo vệ backend không sập vì hacker/bản tin rác |
| **9** | **Device Shadow: Desired vs Reported (PHO-048)** | Mở ngăn kéo thiết bị ➔ Xem bảng Shadow ➔ Bấm cập nhật trạng thái mong muốn (Desired: ON) khi thiết bị đang offline | Delta chênh lệch sáng viền hổ phách cảnh báo `Đang lệch trạng thái`; khi thiết bị online hoặc bấm `[Đồng Bộ Ngay]` ➔ Reported khớp Desired | Chuẩn kiến trúc AWS IoT Core Device Shadow: giải quyết dứt điểm rủi ro lệch trạng thái do mất sóng/rớt mạng |
| **10** | **Demand Response & Cắt Tải Đỉnh (PHO-049)** | Vào Dashboard Tiêu Thụ Điện ➔ Bật Auto-Shedding ➔ Bấm `[ ⚡ Giả Lập Quá Tải 5500W ]` | Đồng hồ phụ tải vọt đỏ quá ngưỡng 5000W; thuật toán lập tức tự động ngắt thiết bị thứ yếu (Đèn trang trí, Bình nóng lạnh) nhưng bảo vệ an toàn Bơm cứu hỏa | Demand Response công nghiệp: Bảng ưu tiên tải (SAFETY > ESSENTIAL > SHEDDABLE), tích hợp cùng thuật toán EVN |
| **11** | **Canary OTA Firmware Orchestration (PHO-050)** | Vào Admin Diagnostics ➔ Tab `Cập Nhật OTA` ➔ Bấm `[ + Tạo Chiến Dịch Mới ]` chọn v2.1.0 ➔ Quan sát Rollout Stepper 10% ➔ 50% ➔ 100% | Thanh tiến trình nhảy cuốn chiếu theo từng lô thiết bị; tỷ lệ lỗi hiển thị thời gian thực; nút `HỦY KHẨN CẤP` tự động kích hoạt nếu failure rate > 15% | Chiến dịch cập nhật firmware an toàn chuẩn Telco/Tesla: Triển khai cuốn chiếu không bao giờ làm sập toàn bộ thiết bị trong nhà |

---

## 8. TEMPLATE THÊM TASK MỚI (COPY & PASTE KHI CẦN)

Khi phát sinh task mới của Phong, copy dòng dưới đây và dán nối tiếp vào cuối **Bảng 1 (Bảng Master)**:

```markdown
| 46 | PHO-054 | UC-XXX | Tên Nhiệm Vụ Mới | Phân Hệ | Quyền Hạn | Mô tả logic & thuật toán | API Endpoint / Topic | File Code Chính | Sprint & Ưu Tiên | Trạng Thái |
```
