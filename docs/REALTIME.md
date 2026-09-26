# Backend thời gian thực dùng chung

## Kênh truyền và kiến trúc

HESTA dùng một điểm cuối WebSocket với giao thức nhắn tin STOMP để cập
nhật ứng dụng theo thời gian thực:

```text
Feature service
      |
      v
RealtimeEventPublisher
      |
      v
Shared WebSocket transport
      |
      v
/topic/homes/{homeId}/events
```

Điểm cuối bắt tay là `ws://<host>/ws`. Broker STOMP đơn giản chạy trong
cùng tiến trình phục vụ `/topic`; `/app` được dành cho thông điệp từ ứng
dụng khách đến máy chủ trong tương lai. Các khung `SEND` từ ứng dụng khách
hiện bị từ chối, ngăn việc chèn sự kiện vào topic của broker. Các mô-đun
chức năng không được tạo cấu hình WebSocket, đích nhận hay cách sử dụng
`SimpMessagingTemplate` riêng.

## Xác thực và phân quyền đăng ký nhận sự kiện

Điểm cuối bắt tay HTTP được mở chỉ để các ứng dụng khách WebSocket tương
thích với trình duyệt có thể nâng cấp kết nối. Khung STOMP `CONNECT` tiếp
theo bắt buộc phải xác thực. Gửi cùng access token dùng cho REST API dưới
dạng tiêu đề gốc của STOMP:

```text
Authorization: Bearer <access-token>
```

Không chấp nhận token trong tham số truy vấn. Backend kiểm tra JWT, tải
`CustomUserDetails` hiện tại và từ chối kết nối thiếu token, token không
hợp lệ, tài khoản bị vô hiệu hóa hoặc bị khóa. Socket đã nâng cấp phải gửi
khung STOMP đầu tiên trong vòng 10 giây, tránh kết nối bắt tay ẩn danh
không hoạt động chiếm tài nguyên vô thời hạn.

Mỗi lượt đăng ký chỉ được chọn đúng một đích nhận của nhà:

```text
/topic/homes/{homeId}/events
```

Mọi khung STOMP `SUBSCRIBE` đều được chặn để kiểm tra. Người dùng đã xác
thực phải có bản ghi `HomeMember` ở trạng thái `ACTIVE` cho `homeId` của
đích nhận; người không phải thành viên và thành viên không hoạt động bị từ
chối. Khung bị từ chối đi qua luồng lỗi STOMP thông thường. Broker đơn
giản quản lý trạng thái phiên và xóa đăng ký khi `DISCONNECT` hoặc khi kênh
truyền gặp lỗi.

## Đặc tả sự kiện

`RealtimeEvent<T>` độc lập với kênh truyền và gồm:

- `eventId`: định danh sự kiện duy nhất, không rỗng hoặc chỉ chứa khoảng trắng.
- `type`: một giá trị `RealtimeEventType`.
- `homeId`: phạm vi nhà bắt buộc.
- `deviceId`: phạm vi thiết bị tùy chọn.
- `data`: dữ liệu có kiểu tổng quát, không null.
- `timestamp`: thời điểm tạo sự kiện.

Các loại sự kiện dùng chung là `SENSOR_READING_UPDATED`,
`DEVICE_STATE_CHANGED`, `TWIN_HEALTH_STATUS_CHANGED` và `NOTIFICATION_CREATED`.
Để bổ sung sự kiện mới, thêm giá trị vào `RealtimeEventType`; không tạo
hạ tầng WebSocket khác.

## Phát sự kiện từ dịch vụ bổ sung sau này

Chỉ tiêm `RealtimeEventPublisher`:

```java
public record DeviceStatePayload(boolean power) {
}

RealtimeEvent<DeviceStatePayload> event = RealtimeEvent.create(
        RealtimeEventType.DEVICE_STATE_CHANGED,
        homeId,
        deviceId,
        new DeviceStatePayload(true)
);

realtimeEventPublisher.publish(event);
```

Bộ phát suy ra `/topic/homes/{homeId}/events` từ sự kiện và chuyển cho kênh
truyền WebSocket dùng chung. Khi sự kiện đại diện cho thay đổi cơ sở dữ
liệu, chỉ phát sau khi giao dịch được xác nhận để ứng dụng khách không
nhìn thấy trạng thái đã bị hoàn tác.

## Cấu hình và kiểm chứng

`app.realtime.websocket.heartbeat` điều khiển heartbeat STOMP theo cả hai
chiều và mặc định là `20s`. Ghi đè bằng `REALTIME_WEBSOCKET_HEARTBEAT`, dùng
khoảng thời gian tường minh như `30s`.

Chạy các kiểm thử thời gian thực chỉ dành cho backend bằng:

```powershell
mvn -Dtest=RealtimeEventTest,RealtimeEventPublisherImplTest,RealtimeDestinationsTest,WebSocketRealtimeTransportTest,RealtimeWebSocketChannelInterceptorTest,RealtimeSubscriptionServiceTest,WebSocketRealtimePropertiesTest,RealtimeWebSocketIntegrationTest test
```

## Giới hạn mở rộng

Broker STOMP đơn giản và các phiên kết nối nằm trong một bản chạy backend.
Bản thân WebSocket không cung cấp chuyển phát giữa nhiều bản chạy, phát
lại bền vững hay bảo đảm thứ tự phân tán. Khi triển khai mở rộng theo
chiều ngang, cần broker dùng chung hoặc lớp phát/đăng ký nhận giữa các bản
chạy backend, ví dụ Redis Pub/Sub, RabbitMQ hoặc Kafka. Đợt tái cấu trúc
này không bổ sung broker.

Hạ tầng hiện tại chủ đích chỉ cung cấp kênh truyền, xác thực, phân quyền
theo nhà, định tuyến, dọn dẹp vòng đời, heartbeat và khả năng hai chiều
trong tương lai. Logic nghiệp vụ của chức năng nằm ngoài lớp này. Lõi
thông báo hiện tích hợp qua `RealtimeEventPublisher`; xem
[NOTIFICATION_CORE.md](NOTIFICATION_CORE.md).

Dữ liệu chuẩn của nút thiết bị/cảm biến, điểm cuối bản chụp nhà ban đầu và
các điểm tích hợp sau khi xác nhận giao dịch được mô tả trong
[DIGITAL_TWIN.md](DIGITAL_TWIN.md). Các trường độ mới suy ra và chuyển
trạng thái hoạt động độc lập của từng nút được mô tả trong
[TWIN_HEALTH.md](TWIN_HEALTH.md).
