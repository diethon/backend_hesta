# Lưu trữ bố cục hiển thị Digital Twin

API bố cục lưu thông tin hình học để hiển thị riêng với bản chụp trạng thái
Twin khi vận hành. API không sao chép trạng thái thiết bị, giá trị cảm biến,
dấu thời gian hay trạng thái hoạt động vào các bản ghi bố cục. Mỗi nhà có tối
đa một bố cục hiện hành.

## API

Cả hai điểm cuối đều yêu cầu xác thực, sử dụng cấu trúc bao `ApiResponse<T>`
hiện có và các đường dẫn dưới `/api/v1`:

```text
GET /api/v1/homes/{homeId}/twin-layout
PUT /api/v1/homes/{homeId}/twin-layout
```

Thành viên có quyền có thể đọc bố cục nhà. Thành viên có vai trò `OWNER` có
thể lưu; thành viên `MEMBER` đang hoạt động, thành viên bị vô hiệu hóa, người
không phải thành viên hoặc người gọi chưa xác thực đều không thể lưu. Vai trò
hệ thống `ADMIN` không được bỏ qua điều kiện thành viên của nhà.

Khi chưa có bố cục, GET trả về `revision: 0` cùng `rooms` và `nodes` rỗng.
Việc chưa bố trí vị trí là hợp lệ: dịch vụ không tạo bản ghi cho các nút nghiệp
vụ chưa được đặt vị trí. Một yêu cầu PUT đầy đủ thay thế toàn bộ vị trí phòng
và nút trước đó; các mục bị bỏ khỏi yêu cầu sẽ bị xóa trong cùng giao dịch.

## Tọa độ và định danh

Tất cả tọa độ có kiểu `NUMERIC(10,3)`, được chuẩn hóa về `[0, 1]`. Chiều rộng
và chiều cao phòng nằm trong `(0, 1]`; mỗi hình chữ nhật phải thỏa mãn
`x + width <= 1` và `y + height <= 1`. Các phòng được phép chồng lấn. Giá trị
có hơn ba chữ số thập phân bị từ chối trước khi lưu, bảo đảm độ chính xác giữa
ứng dụng và cơ sở dữ liệu thống nhất.

Bản ghi phòng tham chiếu đến một phòng hiện có trong nhà đích. Nút DEVICE
dùng UUID của thiết bị hiện có và phải tham chiếu đến thiết bị chưa bị xóa
trong nhà đó. Nút SENSOR dùng định danh chuẩn hiện có
`<device UUID>:<exact metricType>` và phải khớp với một luồng `SensorReading`
mới nhất thuộc nhà đó. `roomId` hiển thị của nút là tùy chọn và được kiểm tra
để bảo đảm thuộc cùng nhà; nó không thay đổi `Device.room` hay trạng thái vận hành.
Mỗi vị trí phòng có `floor` nguyên từ 1 đến 100. Client cũ không gửi trường
này được hiểu là tầng 1 để giữ tương thích với bố cục một tầng đã có.

## Lưu trữ và xử lý đồng thời

Migration bổ sung `supabase/migrations/20260917102640_create_twin_layout.sql` tạo:

- `twin_layouts`: một bản ghi cho mỗi nhà, `revision` tăng đơn điệu, các dấu
  thời gian phục vụ theo dõi thay đổi;
- `twin_room_layouts`: thông tin hình học của phòng;
- `twin_node_layouts`: vị trí hiển thị DEVICE/SENSOR.

Migration tiến `20260920150000_add_floor_to_twin_room_layouts.sql` thêm
`floor_number` có mặc định 1 và ràng buộc 1–100. Không sửa migration đã áp dụng.

Khóa ngoại xóa lan truyền các bản ghi bố cục con khi bố cục hoặc nhà bị xóa,
nhưng các bản ghi bố cục không bao giờ xóa phòng, thiết bị hay số đo nghiệp
vụ. Cơ sở dữ liệu kiểm tra khóa trùng lặp, loại nút, giới hạn tọa độ chuẩn hóa
và quy tắc mỗi nhà chỉ có một bố cục.

PUT yêu cầu `expectedRevision`. Dịch vụ khóa bản ghi nhà rồi đến bản ghi bố
cục hiện tại, kiểm tra toàn bộ yêu cầu, xóa các vị trí bị bỏ khỏi yêu cầu,
thêm các bản ghi được gửi và tăng phiên bản trong một giao dịch. Phiên bản
cũ trả về cấu trúc phản hồi xung đột hiện có với
`TWIN_LAYOUT_REVISION_CONFLICT`. Khóa bản ghi nhà cũng tuần tự hóa các lần
lưu đầu tiên diễn ra đồng thời.

Khi đọc, các vị trí phòng cũ không hợp lệ hoặc mất tham chiếu bị loại bỏ.
Định danh nút không hợp lệ cũng bị loại bỏ; nút hợp lệ có phòng hiển thị đã
bị xóa được trả về với `roomId: null`. Nhờ đó, dữ liệu bố cục cũ không làm lộ
tham chiếu nghiệp vụ đã xóa hoặc thuộc nhà khác, đồng thời không sao chép hay
thay đổi trạng thái vận hành.

## Ví dụ

- [Phản hồi GET khi chưa có bố cục](examples/twin-layout-empty.json)
- [Phản hồi GET với bố cục đã lưu](examples/twin-layout-saved.json)
- [Yêu cầu PUT](examples/twin-layout-put.json)

`rooms` và `nodes` trong phản hồi chỉ chứa dữ liệu bố cục. Ứng dụng khách ghép
dữ liệu này với bản chụp Twin hiện có theo `roomId` và theo
`DEVICE:{deviceId}` hoặc `SENSOR:{sensorId}`.

## Chạy minh họa backend thủ công

1. Áp dụng migration vào cơ sở dữ liệu PostgreSQL cục bộ biệt lập và khởi
   động backend với cơ chế kiểm tra lược đồ Hibernate được bật.
2. Đăng nhập bằng tài khoản chủ nhà đang hoạt động và gọi GET cho nhà chưa có
   bố cục; kiểm tra phản hồi rỗng và phiên bản bằng 0.
3. Gửi PUT theo yêu cầu mẫu gồm hai phòng, vị trí một thiết bị và một cảm biến.
4. Gọi GET lại và kiểm tra tọa độ chuẩn hóa cùng phiên bản bằng 1.
5. Khởi động lại backend rồi gọi GET; bố cục vẫn được giữ trong PostgreSQL.
6. Thay đổi hình học của phòng, gửi PUT với phiên bản đã nhận và kiểm tra
   phiên bản bằng 2.
7. Gọi GET bằng tài khoản `MEMBER` đang hoạt động; thao tác đọc thành công.
   Gọi PUT bằng tài khoản `MEMBER`; thao tác bị từ chối.
8. Gửi phòng hoặc thiết bị thuộc nhà khác, luồng cảm biến không tồn tại, mục
   trùng lặp, hình học vượt giới hạn hoặc phiên bản cũ; từng trường hợp đều
   bị từ chối và bố cục trước đó được giữ nguyên.

Để kiểm tra nhà nhiều tầng bằng API thật, chạy seed local rồi đăng nhập bằng
`multifloor.owner@hesta.local`. Nhà `Nhà thông minh 3 tầng` có 8 phòng trên ba
tầng, 8 thiết bị và 6 luồng cảm biến được đặt sẵn trong bố cục.

Sau khi chạy `npx supabase db reset --local --yes`, dữ liệu khởi tạo có sẵn bố
cục để đọc cho `Nhà HESTA Demo` (`homeId`
`00000000-0000-4000-8000-000000000201`): bốn hình chữ nhật phòng và mười hai
nút thiết bị/cảm biến, trong đó bản chụp trạng thái có một ổ cắm chưa gán
phòng. Dùng `scripts/Invoke-DigitalTwin2DDemo.ps1` với access token của tài
khoản chủ nhà để in cả hai phần dữ liệu. Thêm `-SendReading` để cập nhật nút
nhiệt độ phòng ngủ qua điểm cuối cảm biến giả lập chỉ dành cho phát triển;
để dùng tùy chọn này, khởi động backend với hồ sơ `mock-sensors` và
`APP_MOCK_SENSORS_ENABLED=true`.

Thay đổi bố cục không phát sự kiện WebSocket. Các đặc tả sự kiện vận hành
`DEVICE_STATE_CHANGED`, `SENSOR_READING_UPDATED` và
`TWIN_HEALTH_STATUS_CHANGED` vẫn tách biệt và giữ nguyên.

## Kết quả kiểm chứng ghi nhận ngày 2026-09-17

Migration được áp dụng bổ sung vào container PostgreSQL cục bộ biệt lập bằng
`npx supabase migration up --local --yes`; không sửa migration hiện có và
không tạo lại cơ sở dữ liệu. PostgreSQL ghi nhận đủ ba bảng bố cục, khóa
ngoại, ràng buộc duy nhất, kiểm tra tọa độ và mục lịch sử migration
`20260917102640`.

- Kiểm thử tập trung vào bố cục: 13 ca đạt, bao gồm controller, service,
  repository, kiểm tra lược đồ Hibernate và thay thế dữ liệu trong giao dịch thật.
- Nhóm kiểm thử hồi quy Twin/trạng thái hoạt động/cảm biến/thời gian thực,
  gồm cả bố cục: 102 ca đạt, không thất bại hay lỗi
  ([target/twin-layout-regression.log](../target/twin-layout-regression.log)).
- Toàn bộ bộ kiểm thử Maven: 191 ca, 190 đạt và một lỗi hiện có tại
  `MqttSmokeTest.testPublishRoundTrip` do MQTT broker cục bộ không khả dụng.
  Không thay đổi mã MQTT.
- `mvn.cmd -DskipTests package`: BUILD SUCCESS; tệp đóng gói là
  `target/backend-0.0.1-SNAPSHOT.jar`.
- Kiểm chứng controller/service sau khi dọn dẹp: 10 ca đạt
  ([target/twin-layout-post-cleanup-tests.log](../target/twin-layout-post-cleanup-tests.log)).
- `git diff --check`: đạt.

Sau các kiểm thử có hoàn tác giao dịch, cả ba bảng bố cục trong cơ sở dữ liệu
kiểm chứng cục bộ đều không còn bản ghi, và không còn nhà/người dùng do kiểm
thử bố cục tạo ra.
