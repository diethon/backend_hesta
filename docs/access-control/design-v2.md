# THIẾT KẾ KIẾN TRÚC PHÂN QUYỀN TRUY CẬP CHI TIẾT (GRANULAR ACCESS CONTROL V2)

> **Tài liệu đặc tả kiến trúc và thiết kế hệ thống phân quyền chi tiết cho thành viên gia đình (Hesta Smart Home)**  
> **Phiên bản:** 2.0.0  
> **Trạng thái:** Triển khai Phase 1 (Nền tảng, DDL Script, Central Service, Deny-by-default Scanner, Unit Tests)  
> **Phạm vi:** Backend (`backend_hesta`)  

---

## 1. TỔNG QUAN VÀ NGUYÊN TẮC BẢO MẬT (SECURITY POSTURE)

Hệ thống phân quyền của Hesta nâng cấp từ mô hình nhị phân (`OWNER` toàn quyền, `MEMBER` toàn quyền trong nhà) lên mô hình phân quyền đa cấp, chi tiết đến từng phòng (Room) và từng thiết bị (Device).

### 1.1. Các nguyên tắc cốt lõi (Hard Security Principles)
1. **Deny by default (Từ chối mặc định):** Mọi tài nguyên không được cấp quyền tường minh sẽ mặc định bị từ chối truy cập.
2. **Fail-closed (Đóng an toàn khi có lỗi hoặc thiếu thông tin):**
   - Tài nguyên ẩn (quyền `NONE`): Trả về HTTP `404 Not Found` (không để lộ sự tồn tại của thiết bị, phòng hay tài nguyên trong hệ thống).
   - Tài nguyên nhìn thấy nhưng không đủ quyền thực hiện hành động (quyền `VIEW` nhưng cố gửi lệnh điều khiển): Trả về HTTP `403 Forbidden`.
3. **Trạng thái thành viên bắt buộc:** Mọi truy vấn và kiểm tra quyền **BẮT BUỘC** kiểm tra `status == MemberStatus.ACTIVE`. Thành viên ở trạng thái `SUSPENDED` hoặc `INVITED` bị coi như không có quyền (từ chối ngay lập tức).
4. **Loại trừ hoàn toàn thiết bị đã xóa mềm (`is_deleted == true`):** Thiết bị xóa mềm không bao giờ xuất hiện trong danh sách cấp quyền, danh sách hiển thị, hay tính toán điều kiện thực thi kịch bản (Scene).
5. **Đơn nhất định danh (Consistent Identifiers):** Sử dụng `userId` làm định danh thống nhất cho thành viên trên toàn bộ các REST endpoints (`/homes/{homeId}/members/{userId}/access`).

---

## 2. CÁC QUYẾT ĐỊNH SẢN PHẨM (PRODUCT DECISIONS 1 - 9)

1. **Thiết bị chưa gán phòng (Unassigned Devices):** Thành viên `CUSTOM` mặc định **KHÔNG THỂ THẤY và KHÔNG ĐIỀU KHIỂN ĐƯỢC** thiết bị chưa gán phòng, trừ khi Chủ nhà (`OWNER`) thiết lập một ghi đè thiết bị tường minh (`device override`).
2. **Kịch bản (Scenes):** Thành viên `CUSTOM` chỉ nhìn thấy và được kích hoạt kịch bản khi và chỉ khi có quyền `CONTROL` trên **100% các thiết bị sống (live devices, `is_deleted = false`)** trong kịch bản đó. Nếu kịch bản không chứa thiết bị sống nào, kịch bản sẽ bị ẩn với thành viên `CUSTOM`.
3. **Tự động hóa (Automation Rules):** Thành viên `CUSTOM` nhận `403 Forbidden` trên **MỌI API** tự động hóa (danh sách quy tắc, chi tiết, lịch sử kích hoạt, kiểm tra thử). Thành viên `ALL` giữ nguyên quyền đọc/ghi như hiện nay.
4. **Mô hình 3D (Digital Twin):**
   - Với thành viên `CUSTOM`, thiết bị có quyền `NONE` bị loại bỏ hoàn toàn khỏi snapshot dữ liệu và bố cục layout 3D.
   - Các phòng không có bất kỳ thiết bị khả dụng nào vẫn giữ vỏ không gian 3D (để không phá vỡ cấu trúc mô hình tòa nhà), nhưng nhãn tên phòng (`room name label`) bị xóa bỏ.
5. **Cảnh báo an toàn (Safety Alerts):** Các cảnh báo an toàn cấp cao (rò rỉ khí gas `GAS_LEAK`, báo cháy `FIRE_ALARM`, xâm nhập khẩn cấp) được phát tới **MỌI thành viên đang hoạt động (`ACTIVE`)** trong nhà, bất kể phạm vi phân quyền, nhưng nội dung cảnh báo được hiển thị dưới dạng văn bản chung, không để lộ tên/ID của phòng hay thiết bị bị ẩn. Mọi thông báo thông thường khác đều được lọc theo quyền truy cập thiết bị.
6. **Bảo mật danh tính thành viên:** Thành viên `CUSTOM` chỉ nhìn thấy tên đầy đủ (`fullName`) và vai trò (`role`) của các thành viên khác trong nhà; trường `email` bị ẩn hoàn toàn (trả về null/ẩn). Chủ nhà (`OWNER`) và thành viên `ALL` tiếp tục thấy email như cũ.
7. **Phòng mới tạo:** Mặc định mức quyền `NONE` cho toàn bộ thành viên `CUSTOM` hiện có. Thiết bị mới tạo trong phòng hoặc được chuyển vào phòng sẽ thừa hưởng mức quyền của phòng đó (trừ khi có ghi đè thiết bị).
8. **Lời mời tham gia (`Invitations`):** Mặc định quyền `ALL` (duy trì tương thích ngược 100%). Chủ nhà có thể tùy chọn gửi kèm cấu hình `CUSTOM` ngay trong payload lời mời.
9. **Di chuyển thiết bị giữa các phòng:** Khi thiết bị được chuyển sang phòng khác, mức quyền hiệu dụng (`effective level`) của thiết bị tự động thay đổi theo phòng mới (trừ khi có ghi đè thiết bị cụ thể).

---

## 3. BẢNG ĐỐI SOÁT CÁC ĐIỀU CHỈNH THIẾT KẾ (CORRECTIONS C1 - C12)

| Mã | Nội dung điều chỉnh | Áp dụng | Chi tiết thực hiện |
| :--- | :--- | :---: | :--- |
| **C1** | **Đồng nhất ID & Xác thực đầu vào** | **Y** | Endpoint sử dụng `{userId}`. Service kiểm tra `memberId`, `roomId`, `deviceId` phải thuộc `{homeId}` và chưa bị xóa mềm. Nếu không thuộc nhà -> 404; nếu truyền ID không hợp lệ trong payload -> 400. Payload lời mời được xác thực khi tạo và tái xác thực khi join (bỏ qua và log các ID phòng/thiết bị đã bị xóa mà không làm gián đoạn join). |
| **C2** | **Chế độ xem cho chính mình (Self View)** | **Y** | `GET /homes/{homeId}/members/{userId}/access` khi caller tự xem quyền của mình (hoặc endpoint `/me/access`) chỉ trả về các phòng và thiết bị caller có quyền truy cập, không bao giờ lộ danh sách hay tên các phòng/thiết bị bị `NONE`. Chủ nhà xem được toàn bộ ma trận quyền. |
| **C3** | **Vòng đời thành viên & Dữ liệu** | **Y** | Xóa/đình chỉ thành viên: xóa các bản ghi truy cập hoặc từ chối tức thì vì `status != ACTIVE`. Gia nhập lại: áp dụng quyền từ lời mời mới. Nâng cấp lên `OWNER`: xóa toàn bộ các dòng `CUSTOM` và gán `access_scope = ALL`. Xóa thiết bị/phòng: cập nhật cascading/dọn dẹp hoặc tự động coi như unassigned. |
| **C4** | **Kiểm soát đồng thời (Concurrency)** | **Y** | `PUT` cập nhật quyền thay thế toàn bộ ma trận trong một `@Transactional`, kiểm tra `updated_at` / version để trả về `409 Conflict` nếu có xung đột ghi đè giữa nhiều Chủ nhà. |
| **C5** | **Chuẩn hóa mã lỗi HTTP** | **Y** | Mức quyền `NONE` -> `404 Not Found`. Không đủ quyền (ví dụ `VIEW` gửi lệnh điều khiển) -> `403 Forbidden`. |
| **C6** | **Xử lý các cờ phân quyền cũ** | **Y** | Giữ nguyên 3 cột legacy `allow_voice_override`, `allow_scene_creation`, `allow_remote_control`. Phạm vi `ALL` giữ nguyên cơ chế cũ; phạm vi `CUSTOM` bị chi phối HOÀN TOÀN bởi cấp độ truy cập (`NONE`, `VIEW`, `CONTROL`). |
| **C7** | **Kiến trúc Realtime (Thiết kế cho Phase 2c)** | **Y** | Chuyển sang Spring User Destinations (`/user/queue/...`). Cấm thành viên `CUSTOM` subscribe topic chia sẻ `/topic/homes/{homeId}/events`. Khi thu hồi/giảm quyền hoặc đình chỉ thành viên, Backend chủ động ngắt kết nối WebSocket session của user. Sự kiện không có thiết bị (kịch bản, quy tắc) cũng được lọc quyền. Cache phân quyền in-memory trên mỗi nhà với cơ chế invalidation tường minh. |
| **C8** | **Đường truyền giọng nói (Voice Path)** | **Y** | Lệnh giọng nói từ voice-service bắt buộc phải truyền kèm `userId` và `homeId` và chạy qua `HomeAuthorizationService`. Hiện tại do voice-service chỉ dùng static `X-Internal-Api-Key` mà không có `userId`, thành viên `CUSTOM` sẽ bị cấm điều khiển qua giọng nói (fail closed). Đề xuất chuyển sang dùng JWT hoặc mTLS / token ký số mang danh tính user. |
| **C9** | **Tương thích ngược API** | **Y** | `POST /invitations` hỗ trợ cả `?email=...` lẫn JSON body chứa `access`. Danh sách `GET /members` chỉ trả về `accessScope` và thống kê quyền khi caller là `OWNER`. Thêm trường phụ `accessLevel` (`VIEW` \| `CONTROL`) vào `DeviceResponse` để giao diện frontend dễ dàng vô hiệu hóa nút bấm. |
| **C10** | **Thiết kế Database Scripts** | **Y** | Script migration có tính idempotent (`IF NOT EXISTS`, DO blocks), bọc trong transaction, có rollback script riêng biệt. Bật Row Level Security (`ENABLE ROW LEVEL SECURITY`) trên 2 bảng mới. Loại bỏ index dư thừa vì đã có `UNIQUE (member_id, ...)`. Sử dụng hàm chuẩn `gen_random_uuid()`. |
| **C11** | **Bộ khung Deny-by-default** | **Y** | Tạo annotation chính sách `@HomeAccessPolicy` và cơ chế quét. Viết test kiểm tra tự động quét 100% handler methods từ Spring MVC mapping; test sẽ FAIL nếu có bất kỳ endpoint nào không khai báo chính sách và không nằm trong public allowlist. |
| **C12** | **Chia nhỏ Phase 2** | **Y** | Tách Phase 2 thành: Phase 2a (Thực thi REST trên Devices, Scenes, Groups, Energy, Twin, Rooms, Notifications, Recommendations, Voice); Phase 2b (Access API & Invitations); Phase 2c (Realtime WebSocket & Cache). |

---

## 4. KẾT QUẢ KHẢO SÁT HIỆN TRẠNG TỪ MÃ NGUỒN (A1 - A13)

### A1. Vị trí đọc và thực thi của `allow_voice_override`, `allow_scene_creation`, `allow_remote_control`
- **Thực tế:** Cả 3 cờ được khai báo tại `HomeMember.java:45-55` và được gán giá trị mặc định `true` cho Chủ nhà tại `AuthServiceImpl.java:414-416` và `HomeServiceImpl.java:429-433`.
- **Thực thi:** **KHÔNG ĐƯỢC ĐỌC VÀ KHÔNG ĐƯỢC KIỂM TRA TẠI BẤT KỲ ĐÂU** trong toàn bộ logic nghiệp vụ, service, controller hay validator. Đây là các trường dữ liệu tĩnh/chờ từ trước. Chúng tôi bảo lưu nguyên vẹn các cột này để không ảnh hưởng cấu trúc cơ sở dữ liệu hiện tại.

### A2. Việc kiểm tra `status == ACTIVE` và hành vi đối với `SUSPENDED` / `INVITED`
- `HomeAuthorizationService.loadAuthorizedHome()`: Kiểm tra chặt chẽ `membership.getStatus() != MemberStatus.ACTIVE` -> ném `AppException(ErrorCode.UNAUTHORIZED)`.
- `RealtimeSubscriptionServiceImpl.authorizeSubscription()`: Lọc `membership.getStatus() == MemberStatus.ACTIVE`.
- **Lỗ hổng hiện tại:** Trong `DeviceRepository.hasAccessToDevice` và `RoomRepository.hasAccessToRoom` (được gọi bởi `@deviceAccessValidator`), câu lệnh JPQL join `HomeMember hm` chỉ kiểm tra `hm.user.id = :userId` mà **KHÔNG KIỂM TRA** `hm.status = 'ACTIVE'`. Do đó, người dùng bị `SUSPENDED` vẫn có thể vượt qua `@deviceAccessValidator` trên một số API đơn lẻ.
- **Khắc phục trong giải pháp mới:** Toàn bộ việc kiểm tra quyền tập trung về `HomeAuthorizationService`, bắt buộc `status == MemberStatus.ACTIVE` trên mọi truy vấn.

### A3. Định danh `{memberId}` trong các endpoint `/members`
- Trong `GET /{homeId}/members`, trường `id` trả về cho client chính là `m.getUser().getId()` (User ID).
- Trong `PUT /{homeId}/members/{memberId}/role` và `DELETE /{homeId}/members/{memberId}`, `HomeServiceImpl` tìm kiếm bằng `homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)`. Như vậy biến đường dẫn `{memberId}` trong thực tế chính là **User ID**.
- **Quyết định thiết kế:** Endpoint phân quyền chi tiết sử dụng đường dẫn `/homes/{homeId}/members/{userId}/access`, trong đó `{userId}` là User ID, đồng nhất với toàn bộ API hiện hữu.

### A4. Toàn bộ luồng điều khiển giọng nói (Voice Path)
1. **Frontend:** Người dùng nói vào micro -> Web Speech API chuyển thành văn bản -> gọi `POST http://localhost:8001/api/voice/process` (tới `voice-service` Python). Request chỉ gồm `{ text, homeId }`, **KHÔNG HỀ CÓ `userId` hay Token JWT**.
2. **Voice-service:** Xử lý NLP/LLM -> lấy danh sách thiết bị từ Backend qua `GET /internal/devices?homeId=...` với header `X-Internal-Api-Key: super_secret_voice_key_123`.
3. **Thực thi lệnh:** Gửi `POST /internal/devices/{deviceId}/actions` với `{ action, parameters }`, xác thực bằng `InternalApiKeyFilter` thông qua `X-Internal-Api-Key`.
4. **Kết luận:** Lệnh giọng nói hiện tại hoàn toàn bỏ qua danh tính người dùng (`userId` không được truyền). Do đó, để đảm bảo an toàn tuyệt đối, thành viên `CUSTOM` sẽ **KHÔNG ĐƯỢC PHÉP** gửi lệnh qua voice service (fail closed) cho đến khi voice-service được nâng cấp để truyền User JWT/UserId.

### A5. Camera và dữ liệu Media
- Backend hiện tại **KHÔNG CÓ** bất kỳ endpoint stream video, WebRTC signaling, RTSP proxy hay snapshot hình ảnh nào. Camera an ninh chỉ tồn tại dưới dạng metadata thiết bị trong danh mục (`CAMERA_AI` trong `Device`, `cameraDeviceId` trong `ParentalGuardian`).

### A6. Nguồn dữ liệu bảng điều khiển (Dashboard)
- Các số liệu "N đèn đang bật", nhiệt độ, độ ẩm trung bình, công suất tiêu thụ điện **ĐƯỢC TÍNH TOÁN TRỰC TIẾP TRÊN FRONTEND** (tại `HomePage.tsx`, `HomeHeroLiveCamera.tsx`) từ danh sách thiết bị lấy về qua `GET /api/v1/homes/{homeId}/devices` và dữ liệu năng lượng từ `GET /api/v1/homes/{homeId}/energy/summary`.
- Không có endpoint tổng hợp dashboard đơn lẻ nào trên backend. Khi backend lọc danh sách thiết bị theo quyền của thành viên `CUSTOM`, toàn bộ số liệu trên dashboard tự động chính xác theo các thiết bị mà thành viên đó được phép thấy.

### A7. Nguồn gốc và cấu trúc Thông báo (Notifications)
- Thực thể `Notification` chỉ có các trường: `recipient_id`, `home_id`, `type`, `title`, `message`, `priority_level`, `is_read`, `created_at`.
- Không có cột khóa ngoại `device_id` hay `room_id`. Tên thiết bị/phòng nếu có chỉ nằm trong chuỗi văn bản text.
- Cảnh báo an toàn (`CRITICAL`) được tạo ra từ hệ thống tự động hóa hoặc giám hộ phụ huynh và gửi đến người dùng.

### A8. Dữ liệu Năng lượng (Energy Analytics)
- Dữ liệu điện năng (`DeviceStateHistory`) được lưu theo từng thiết bị (`deviceId`). Điện năng toàn nhà được tính bằng hàm tổng `sum()` trên danh sách các thiết bị thuộc nhà trong `EnergyAnalyticsServiceImpl.java`.
- Không có thiết bị công tơ tổng vật lý riêng biệt.

### A9. Kiến trúc Realtime và WebSocket
- Backend hiện là **ĐƠN THỂ (SINGLE-INSTANCE)**: Schedulers in-memory, Spring WebSocket SimpleBroker in-memory.
- Các lớp phát sự kiện: `DeviceSensorRealtimeListener`, `NotificationRealtimeListener`, `TwinHealthActivityListener` thông qua `RealtimeEventPublisherImpl`.
- Các loại sự kiện: `SENSOR_READING_UPDATED`, `DEVICE_STATE_CHANGED`, `TWIN_HEALTH_STATUS_CHANGED` (đều chứa `deviceId`, không chứa `roomId`), và `NOTIFICATION_CREATED`.
- Ánh xạ phiên WebSocket: `RealtimeWebSocketChannelInterceptor` xác thực JWT khi kết nối (`CONNECT`), gắn `CustomUserDetails` vào `accessor.setUser(...)`. Khi `SUBSCRIBE`, interceptor kiểm tra quyền truy cập nhà qua `subscriptionService.authorizeSubscription(userId, homeId)`. Hiện tại tất cả sự kiện đều phát chung vào topic `/topic/homes/{homeId}/events`.

### A10. Bố cục 3D Digital Twin & Tìm kiếm
- Layout Digital Twin (`TwinLayoutResponse`) chứa danh sách `rooms` (`roomId`, `x`, `y`, `width`, `height`, `floor`) và `nodes` (`nodeId`, `roomId`, `x`, `y`). Trong đó `nodeId` chính là `deviceId`.
- Hệ thống **KHÔNG CÓ** bất kỳ endpoint tìm kiếm chung (global search) nào cho thiết bị hay thực thể nhà.

### A11. Danh mục Kiểm soát Truy cập Toàn diện (Toàn bộ 176 endpoints)
(Chi tiết được trình bày tại Mục 5 của tài liệu này).

### A12. Đánh giá Row Level Security (RLS) & Quyền Database
- Toàn bộ các bảng hiện có trong schema `public` của Supabase hiện **CHƯA BẬT RLS** (`relrowsecurity = false`).
- Backend kết nối thông qua tài khoản quản trị `postgres.jekwfgqlnlxyaozdwxfz`. Đây là vai trò chủ sở hữu bảng (table owner) có thuộc tính `BYPASSRLS`.
- Khi kích hoạt RLS (`ALTER TABLE ... ENABLE ROW LEVEL SECURITY`) trên 2 bảng mới `member_room_access` và `member_device_access` mà không khai báo policy, mọi truy cập từ bên ngoài qua Supabase Data API/PostgREST (vai trò `anon`, `authenticated`) sẽ tự động bị từ chối triệt để (deny-by-default), trong khi Backend kết nối bằng `postgres.*` vẫn hoạt động bình thường.

### A13. Quy trình Quản lý Schema
- Flyway hiện tắt (`spring.flyway.enabled=false`), Hibernate chạy ở chế độ kiểm tra (`spring.jpa.hibernate.ddl-auto=validate`).
- Các thay đổi cấu trúc database được thực hiện bằng cách chạy file migration SQL thủ công trong Supabase SQL Editor.
- Do đó, script DDL phân quyền phải là **ADDITIVE (tương thích cộng dồn)** để backend hiện tại vẫn khởi động và chạy bình thường mà không bị crash khi database được nâng cấp trước.

---

## 5. MA TRẬN CHÍNH SÁCH BẢO VỆ ENDPOINT TOÀN DIỆN (ENFORCEMENT MATRIX)

Dưới đây là ma trận phân quyền cho toàn bộ các nhóm chức năng chính liên quan đến Home Context:

| Nhóm chức năng | Phương thức & Đường dẫn | Controller & Phương thức | Chính sách OWNER | Chính sách MEMBER (ALL) | Chính sách MEMBER (CUSTOM) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Thiết bị** | `GET /api/v1/homes/{homeId}/devices` | `DeviceController.getDevices` | Cho phép (toàn bộ) | Cho phép (toàn bộ) | Chỉ trả về thiết bị có mức quyền >= `VIEW` (gồm cả trường `accessLevel`) |
| **Thiết bị** | `GET /api/v1/devices/{id}` | `DeviceController.getDeviceById` | Cho phép | Cho phép | Cho phép nếu >= `VIEW`, trả về 404 nếu `NONE` |
| **Điều khiển** | `POST /api/v1/devices/{id}/actions` | `DeviceController.executeAction` | Cho phép | Cho phép | Yêu cầu `CONTROL`. Nếu `VIEW` -> 403, nếu `NONE` -> 404 |
| **Điều khiển** | `POST /api/v1/devices/{id}/power` | `DeviceController.setPowerState` | Cho phép | Cho phép | Yêu cầu `CONTROL`. Nếu `VIEW` -> 403, nếu `NONE` -> 404 |
| **Điều hòa** | `POST /api/v1/devices/{id}/air-conditioner/*` | `AirConditionerController.*` | Cho phép | Cho phép | Yêu cầu `CONTROL`. Nếu `VIEW` -> 403, nếu `NONE` -> 404 |
| **Cổng** | `POST /api/v1/devices/{id}/gate/*` | `GateController.*` | Cho phép | Cho phép | Yêu cầu `CONTROL`. Nếu `VIEW` -> 403, nếu `NONE` -> 404 |
| **Lịch sử thiết bị** | `GET /api/v1/devices/{id}/history` | `DeviceController.getDeviceHistory` | Cho phép | Cho phép | Cho phép nếu >= `VIEW`, trả về 404 nếu `NONE` |
| **Cảm biến** | `GET /api/v1/devices/{id}/sensor-readings` | `DeviceController.getSensorReadings` | Cho phép | Cho phép | Cho phép nếu >= `VIEW`, trả về 404 nếu `NONE` |
| **Nhóm thiết bị** | `GET /api/v1/homes/{homeId}/device-groups` | `DeviceGroupController.listGroups` | Cho phép | Cho phép | Chỉ trả về nhóm có ít nhất 1 thiết bị >= `VIEW` (ẩn thiết bị `NONE` bên trong) |
| **Nhóm thiết bị** | `POST /api/v1/device-groups/{id}/actions` | `DeviceGroupController.executeGroupAction` | Cho phép | Cho phép | Cho phép nếu có `CONTROL` trên TẤT CẢ thiết bị sống của nhóm |
| **Kịch bản** | `GET /api/v1/homes/{homeId}/scenes` | `SceneController.getScenes` | Cho phép (toàn bộ) | Cho phép (toàn bộ) | Chỉ trả về kịch bản có `CONTROL` trên 100% thiết bị sống |
| **Kịch bản** | `GET /api/v1/homes/{homeId}/scenes/{id}` | `SceneController.getScene` | Cho phép | Cho phép | Cho phép nếu có `CONTROL` trên 100% thiết bị, ngược lại 404 |
| **Kịch bản** | `POST /api/v1/homes/{homeId}/scenes/{id}/execute` | `SceneController.executeScene` | Cho phép | Cho phép | Cho phép nếu có `CONTROL` trên 100% thiết bị sống, ngược lại 403 |
| **Kịch bản (CRUD)**| `POST/PUT/DELETE /api/v1/homes/{homeId}/scenes/**` | `SceneController.*` | Cho phép | 403 Forbidden | 403 Forbidden |
| **Tự động hóa** | `* /api/v1/homes/{homeId}/automation-rules/**` | `AutomationRuleController.*` | Cho phép | Cho phép | **403 Forbidden** trên TẤT CẢ các API tự động hóa |
| **Năng lượng** | `GET /api/v1/homes/{homeId}/energy/analytics` | `EnergyAnalyticsController.getHomeEnergyAnalytics` | Cho phép (toàn nhà) | Cho phép (toàn nhà) | Chỉ tổng hợp và hiển thị phân tích trên các thiết bị >= `VIEW` |
| **3D Twin** | `GET /api/v1/homes/{homeId}/twin` | `TwinSnapshotController.getSnapshot` | Cho phép | Cho phép | Loại bỏ thiết bị `NONE`. Phòng không có thiết bị giữ vỏ 3D nhưng ẩn tên |
| **3D Twin Layout** | `GET /api/v1/homes/{homeId}/twin-layout` | `TwinLayoutController.getLayout` | Cho phép | Cho phép | Loại bỏ node của thiết bị `NONE` khỏi layout |
| **3D Twin Layout** | `PUT /api/v1/homes/{homeId}/twin-layout` | `TwinLayoutController.saveLayout` | Cho phép | 403 Forbidden | 403 Forbidden |
| **Phòng** | `GET /api/v1/homes/{homeId}/rooms` | `RoomController.getRooms` | Cho phép | Cho phép | Cho phép xem danh sách phòng (phòng không có quyền thiết bị giữ thông tin cơ bản) |
| **Phòng (CRUD)** | `POST/PUT/DELETE /api/v1/homes/{homeId}/rooms/**` | `RoomController.*` | Cho phép | 403 Forbidden | 403 Forbidden |
| **Thành viên** | `GET /api/v1/homes/{homeId}/members` | `HomeController.getMembers` | Thấy đủ (kèm email, accessScope, thống kê) | Thấy tên & role & email | Thấy tên & role (**ẩn hoàn toàn email**) |
| **Thành viên** | `GET /api/v1/homes/{homeId}/members/{userId}/access` | `HomeMemberAccessController.getAccess` | Thấy toàn bộ ma trận | Thấy toàn bộ | **Chỉ thấy danh sách các phòng/thiết bị mình có quyền** (không lộ tên/ID phòng bị ẩn) |
| **Thành viên** | `PUT /api/v1/homes/{homeId}/members/{userId}/access` | `HomeMemberAccessController.updateAccess` | Cho phép | 403 Forbidden | 403 Forbidden |
| **Thông báo** | `GET /api/v1/notifications` | `NotificationController.list` | Toàn bộ thông báo của mình | Toàn bộ | Lọc bỏ thông báo liên quan đến thiết bị/phòng bị `NONE` (trừ Safety Alerts) |
| **Gợi ý AI** | `GET /api/v1/homes/{homeId}/recommendations/**` | `RecommendationController.*` | Cho phép | Cho phép | Lọc chỉ hiển thị gợi ý liên quan đến các thiết bị có quyền `CONTROL` |
| **Voice Internal** | `POST /internal/devices/{id}/actions` | `InternalDeviceController.executeAction` | Cho phép | Cho phép | **Fail closed (Từ chối)** nếu không có thông tin xác thực danh tính user |

---

## 6. MÔ HÌNH DỮ LIỆU & THIẾT KẾ MIGRATION (DATA MODEL)

### 6.1. Thay đổi cấu trúc bảng
1. Bổ sung cột `access_scope VARCHAR(20) NOT NULL DEFAULT 'ALL'` vào bảng `home_members`.
2. Bổ sung cột `access_scope VARCHAR(20) NOT NULL DEFAULT 'ALL'` và `access_payload JSONB` vào bảng `home_invitations`.
3. Tạo mới 2 bảng lưu trữ ma trận phân quyền:
   - `member_room_access` (`id`, `member_id`, `room_id`, `access_level`, `created_at`, `updated_at`). Khóa unique `(member_id, room_id)`.
   - `member_device_access` (`id`, `member_id`, `device_id`, `access_level`, `created_at`, `updated_at`). Khóa unique `(member_id, device_id)`.
4. Bật Row Level Security (`ENABLE ROW LEVEL SECURITY`) cho 2 bảng mới.

### 6.2. Tính toán Quyền hiệu dụng (`effectiveLevel`)
Thuật toán xác định mức quyền hiệu dụng cho một thiết bị $D$:
```
nếu caller là OWNER -> CONTROL
nếu caller có access_scope == ALL -> CONTROL
nếu tồn tại bản ghi trong member_device_access cho thiết bị D -> lấy access_level của thiết bị D
ngược lại nếu thiết bị D thuộc phòng R:
    nếu tồn tại bản ghi trong member_room_access cho phòng R -> lấy access_level của phòng R
    ngược lại -> NONE (phòng mới hoặc phòng chưa cấp quyền)
ngược lại (thiết bị D không thuộc phòng nào / unassigned) -> NONE
```

---

## 7. ĐẶC TẢ CENTRAL AUTHORIZATION SERVICE (`HomeAuthorizationService`)

Dịch vụ tập trung `HomeAuthorizationService` được mở rộng với các phương thức cốt lõi:
- `loadAccessContext(UUID homeId, UUID userId)`: Tải context phân quyền (`MemberAccessContext`) trong **ĐÚNG 1 QUERY** (hoặc truy vấn kết hợp tối ưu), lưu cache trong phạm vi request (Request-scoped memoization).
- `getEffectiveLevel(MemberAccessContext context, Device device)`: Trả về `AccessLevel` (`NONE`, `VIEW`, `CONTROL`).
- `requireDeviceAccess(UUID userId, UUID deviceId, AccessLevel minLevel)`: Kiểm tra quyền. Nếu level là `NONE` -> ném 404; nếu nhỏ hơn `minLevel` -> ném 403.
- `getVisibleDeviceIds(UUID homeId, UUID userId, AccessLevel minLevel)`: Trả về tập hợp ID các thiết bị khả dụng có mức quyền tối thiểu `minLevel`.
- `canRunScene(UUID userId, UUID sceneId)`: Kiểm tra user có quyền `CONTROL` trên toàn bộ thiết bị sống trong kịch bản.
- `canControlGroup(UUID userId, UUID groupId)`: Kiểm tra user có quyền `CONTROL` trên toàn bộ thiết bị sống trong nhóm.
- `requireOwner(UUID userId, UUID homeId)`: Kiểm tra bắt buộc vai trò `OWNER` và trạng thái `ACTIVE`.

---

## 8. BỘ KHUNG DENY-BY-DEFAULT GUARD (C11)

Để đảm bảo không có bất kỳ endpoint nào bị bỏ sót trong tương lai:
1. **Annotation `@HomeAccessPolicy`:** Khai báo tường minh mức độ phân quyền yêu cầu cho mỗi phương thức trong controller:
   - `scope`: `PUBLIC`, `AUTHENTICATED`, `ADMIN`, `HOME_ACCESS`, `HOME_OWNER`, `DEVICE_LEVEL`, `SCENE_LEVEL`.
2. **Kiểm tra tự động (`AuthorizationPolicyScannerTest`):**
   - Quét toàn bộ Spring MVC Handler Mappings khi chạy Unit Test.
   - Bất kỳ endpoint nào không khai báo annotation và không nằm trong `PUBLIC_ALLOWLIST` (như login, register, health, docs) sẽ khiến test **FAIL NGAY LẬP TỨC**.

---

## 9. KẾ HOẠCH PHÂN KỲ TRIỂN KHAI (PHASE BREAKDOWN)

- **Phase 1 (Vòng hiện tại):**
  - Database migration script (`member_access.sql`) và rollback script (`member_access_rollback.sql`).
  - Thực thể JPA, Enums (`AccessScope`, `AccessLevel`), Repositories.
  - Central `HomeAuthorizationService` với đầy đủ thuật toán tính toán quyền hiệu dụng.
  - Deny-by-default annotation và Scanning Test.
  - Bộ Unit Tests toàn diện cho logic phân quyền.
  - **Không can thiệp controller:** Đảm bảo zero behavior change cho người dùng hiện hữu.
- **Phase 2a (Thực thi REST Endpoints):**
  - Áp dụng kiểm tra phân quyền vào 12 controller liên quan đến Home Context: Device, Scene, DeviceGroup, Room, Energy, Twin, Notification, Recommendation, Voice/Internal.
  - Ước tính: ~35 endpoints được bảo vệ, ~15 file mã nguồn được cập nhật.
  - Rủi ro: Cần đảm bảo các câu lệnh truy vấn lọc danh sách tối ưu, tránh N+1 query.
- **Phase 2b (Access Management API & Lời mời):**
  - Xây dựng các API quản lý phân quyền: `GET/PUT /homes/{homeId}/members/{userId}/access`.
  - Nâng cấp `POST /invitations` hỗ trợ gửi kèm `accessPayload`.
  - Ước tính: ~5 endpoints mới, ~6 file mã nguồn.
  - Rủi ro: Xử lý đồng thời (Optimistic locking / Concurrency) khi nhiều Chủ nhà cùng cập nhật quyền.
- **Phase 2c (Realtime & Cache Invalidation):**
  - Chuyển đổi kiến trúc STOMP sang Spring User Destinations (`/user/queue/...`).
  - Xây dựng cơ chế chủ động ngắt kết nối WebSocket khi quyền bị hạ/thu hồi.
  - Tích hợp in-memory access cache với cơ chế invalidation tường minh.
  - Ước tính: ~8 file mã nguồn về WebSocket, Interceptor và Event Listeners.
  - Rủi ro: Đồng bộ phiên kết nối người dùng trong kiến trúc mở rộng.

---

## 10. CÂU HỎI VÀ XÁC NHẬN CHO NGƯỜI DÙNG (OPEN QUESTIONS)
1. **Nâng cấp Voice Service:** Khi triển khai Phase 2a, chúng tôi sẽ áp dụng nguyên tắc fail-closed (thành viên `CUSTOM` không thể điều khiển bằng giọng nói do thiếu định danh người dùng). Bạn có dự định nâng cấp `voice-service` để chuyển tiếp `userId` hoặc Authorization Header từ frontend hay không?
2. **Chế độ xem lịch sử thông báo:** Đối với các thông báo cũ đã lưu trước khi thành viên bị giảm quyền từ `CONTROL` xuống `NONE`, bạn có muốn hệ thống ẩn hoàn toàn lịch sử thông báo cũ đó trên giao diện của họ không? (Đề xuất: Có, ẩn để đảm bảo nguyên tắc deny-by-default).
