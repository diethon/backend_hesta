# HESTA — Thiết kế Cơ sở dữ liệu (PostgreSQL)

Tài liệu này mô tả chi tiết kiến trúc và thiết kế Cơ sở dữ liệu cho dự án HESTA
File schema chính thức: **`V1__init_database.sql`** (đặt trong thư mục `src/main/resources/db/migration`).

## 1. Nguyên tắc thiết kế cốt lõi

- **UUID (gen_random_uuid)** được sử dụng làm khóa chính (Primary Key) cho hầu hết các bảng. Phù hợp với hệ thống IoT phân tán, hỗ trợ tốt cho việc tạo dữ liệu độc lập từ nhiều client (Web/App/ESP32). Riêng `sensor_readings` và `audit_logs` dùng `BIGSERIAL` vì đặc thù insert tần suất rất cao.
- **VARCHAR thay thế ENUM SQL**: Toàn bộ các cột trạng thái (Status, Role, Type...) được thiết kế dưới dạng `VARCHAR(20)`. Điều này giúp hệ thống (đặc biệt là Spring Boot/Hibernate) dễ dàng map dữ liệu với Java Enum bằng `@Enumerated(EnumType.STRING)` mà không gặp khó khăn trong việc migration hay bảo trì mở rộng sau này.
- **JSONB** được sử dụng cho các trường có cấu trúc động (như trạng thái thiết bị `device state`, tham số kịch bản `rule parameters`...). Thiết kế này tuân thủ chuẩn linh hoạt của hệ thống IoT, tránh việc tạo quá nhiều bảng con dư thừa.
- **Trigger Bảo vệ Dữ liệu**: Logic bảo vệ chủ nhà cuối cùng (`BR-USER-07`: Home luôn phải còn ít nhất 1 Owner ACTIVE) được cài đặt trực tiếp bằng Trigger trong Database nhằm đảm bảo tính toàn vẹn dữ liệu ở mức cao nhất, kể cả khi có lỗi từ phía ứng dụng phần mềm.

## 2. Ánh xạ 23 Entity gốc thành bảng thực tế

| # | Entity trong thiết kế gốc | Bảng PostgreSQL tương ứng |
|---|---|---|
| 1 | User | `users` |
| 2 | Home | `homes` |
| 3 | HomeMember | `home_members` |
| 4 | Room | `rooms` |
| 5 | Device | `devices` |
| 6 | DeviceStateHistory | `device_state_history` |
| 7 | SensorReading | `sensor_readings` |
| 8 | Scene | `scenes` |
| 9 | SceneAction | `scene_actions` |
| 10 | SceneSchedule | `scene_schedules` |
| 11 | VoiceCommand | `voice_commands` |
| 12 | GestureEvent | `gesture_events` |
| 13 | AutomationRule | `automation_rules` |
| 14 | RuleCondition | `rule_conditions` |
| 15 | RuleAction | `rule_actions` |
| 16 | AutomationSchedule | `automation_schedules` |
| 17 | AutomationExecution | `automation_executions` |
| 18 | BehaviorEvent | `behavior_events` |
| 19 | AutomationRecommendation | `automation_recommendations` |
| 20 | ProactiveAction | `proactive_actions` |
| 21 | SecurityEvent | `security_events` |
| 22 | DeviceAnomaly | `device_anomalies` |
| 23 | Notification | `notifications` |

*(Lưu ý: Luồng cảnh báo bảo mật và bất thường được gộp chung vào bảng `notifications` thông qua các khóa ngoại `source_security_event_id` và `source_anomaly_id` để tối ưu hóa việc quản lý thông báo tập trung).*

## 3. Các bảng bổ trợ nghiệp vụ

Bên cạnh 23 entity chính, cơ sở dữ liệu bổ sung thêm các bảng sau để đáp ứng đầy đủ yêu cầu nghiệp vụ chi tiết trong tài liệu:

| Bảng | Chức năng & Nghiệp vụ |
|---|---|
| `refresh_tokens` | Quản lý phiên đăng nhập/thiết bị (BR-AUTH-08/09), thu hồi token khi đổi mật khẩu hoặc đăng xuất. |
| `password_reset_otps` | Quản lý mã OTP quên mật khẩu (BR-AUTH-07). |
| `user_preferences` | Lưu trữ cấu hình cá nhân của người dùng (ngôn ngữ, giao diện...). |
| `home_member_room_access` | Hỗ trợ mô hình phân quyền RBAC, xác định chính xác thành viên nào được phép xem/điều khiển phòng nào (BR-DEV-01). |
| `edge_nodes` | Bảng quản lý Bộ điều khiển trung tâm (Hub/ESP32). Thiết bị đầu cuối (Device) sẽ được liên kết với Hub thông qua `node_id`. |
| `voice_conversations` | Lưu ngữ cảnh hội thoại đa lượt (multi-turn) cho tính năng ra lệnh giọng nói. |
| `gesture_mappings` | Quản lý cấu hình ánh xạ từ cử chỉ sang hành động điều khiển (BR-GC-02). |
| `behavior_patterns` & `predictions`| Lưu trữ thói quen người dùng và các dự đoán của AI, làm đầu vào cho hệ thống gợi ý kịch bản (`automation_recommendations`). |
| `audit_logs` | Lưu trữ toàn bộ nhật ký kiểm toán hệ thống (Audit Trail) cho các hành động quan trọng (NFR 5.2.5). |

## 4. Kiến trúc Phân quyền (Role-Based Access Control)

Hệ thống được thiết kế với sự phân định rạch ròi giữa quyền quản trị hệ thống và quyền quản lý nhà ở:

### 4.1. Cấp hệ thống (Platform Level)
* Quản lý tại cột `platform_role` trong bảng `users`.
* Nhận giá trị (Enum): `ADMIN` hoặc `USER`.
* **ADMIN:** Quản trị viên của toàn bộ hệ thống Hesta. Có quyền xem, quản lý người dùng, thiết bị tổng, và theo dõi log hệ thống.

### 4.2. Cấp ngôi nhà (Home Level)
* Quản lý tại cột `role` trong bảng trung gian `home_members`.
* Nhận giá trị (Enum): `OWNER` hoặc `MEMBER`.
* **OWNER:** Chủ nhà, có toàn quyền quản lý ngôi nhà, mời/xóa thành viên, thêm thiết bị. Theo thiết kế, Owner luôn có quyền truy cập mọi phòng trong nhà của mình. Cơ sở dữ liệu có Trigger đảm bảo mỗi nhà luôn có ít nhất 1 Owner hoạt động.
* **MEMBER:** Thành viên được mời vào nhà. Quyền của Member bị giới hạn và được tinh chỉnh chi tiết thông qua:
  - Danh sách phòng được truy cập: Lưu tại bảng trung gian `home_member_room_access`.
  - Các cờ đặc quyền (Flags): `allow_voice_override`, `allow_scene_creation`, `allow_remote_control` lưu trực tiếp tại bảng `home_members`.

*(Lưu ý: Đối tượng "Guest" trong tài liệu thiết kế ban đầu được định nghĩa là người dùng vãng lai chưa đăng nhập, do đó không được phân loại và lưu trữ như một Role thực tế trong Database).*

## 5. Lưu ý Triển khai Hệ thống

1. **Giới hạn phần cứng:** Dựa trên yêu cầu mở rộng linh hoạt, giới hạn cứng số lượng thiết bị ESP32 (Hub) ở mức Database đã được gỡ bỏ. Hệ thống cho phép khả năng mở rộng thoải mái hoặc có thể được điều chỉnh giới hạn ngay tại tầng Backend Application (Service).
2. **Quản lý Dữ liệu Chuỗi thời gian (Time-series):** Bảng `sensor_readings` hiện tại phục vụ tốt cho quy mô dự án Capstone (Join/Report dễ dàng). Tuy nhiên, khi hệ thống xử lý luồng dữ liệu khổng lồ trong thực tế, cần xem xét tích hợp InfluxDB hoặc TimescaleDB theo NFR 5.2.5, và cài đặt các Cron Job định kỳ dọn dẹp dữ liệu cũ (Retention Job).
3. **Bảo mật Dữ liệu Riêng tư:** Các dữ liệu nhạy cảm như file Audio gốc từ tính năng Voice Control, hay Video Keypoints từ tính năng Gesture Control được định hướng xử lý cục bộ trên Edge Node (hoặc không lưu trữ lâu dài trên cloud), nhằm tuân thủ tiêu chuẩn bảo mật IoT. Cơ sở dữ liệu chỉ lưu trữ nội dung văn bản (Transcript) và Sự kiện (Event).