# Đặc tả Digital Twin phía backend

`GET /api/v1/homes/{homeId}/twin` trả về `ApiResponse<TwinHomeSnapshotResponse>`
với mã thành công `1000`. Cơ chế xác thực sử dụng Bearer JWT và
`CustomUserDetails` hiện có. Chỉ `HomeMember` ở trạng thái `ACTIVE` (`OWNER` hoặc
`MEMBER`) mới được đọc dữ liệu nhà; vai trò hệ thống `ADMIN` không được bỏ qua
điều kiện thành viên. Các mã lỗi và trạng thái HTTP hiện có vẫn được áp dụng:
401 khi chưa xác thực, 403 khi không đủ quyền, 404 khi không tìm thấy nhà.

## Cấu trúc bản chụp trạng thái (snapshot)

| DTO | Các trường |
| --- | --- |
| TwinHomeSnapshotResponse | UUID homeId, String name, rooms[], unassignedDevices[], unassignedSensors[] |
| TwinRoomSnapshotResponse | UUID roomId, UUID homeId, String name, String icon, devices[], sensors[] |
| TwinDeviceSnapshotResponse | UUID deviceId, UUID roomId, String name, DeviceType deviceType, String icon, DeviceStatus status, JsonNode currentState, OffsetDateTime lastSeen, TwinHealthStatus healthStatus |
| TwinSensorSnapshotResponse | String sensorId, UUID roomId, UUID deviceId, String metricType, BigDecimal latestValue, String unit, OffsetDateTime observedAt, TwinHealthStatus healthStatus |

Mọi danh sách đều có mặt trong phản hồi, kể cả danh sách rỗng. Các nút chưa được
gán phòng có `roomId` là null và nằm trong các danh sách chưa gán tương ứng của
nhà. Hệ thống không tạo phòng giả. Nút thiết bị bao gồm cả phần cứng cảm biến và
thiết bị chấp hành. Danh sách cảm biến của phòng chứa từng luồng chỉ số đo riêng.
`icon`, `lastSeen` và `unit` của cảm biến có thể là null; thời điểm đo chưa biết
không được thay bằng thời gian hiện tại. Thời gian dùng định dạng ISO-8601 có độ
lệch múi giờ; dấu thời gian trong cấu trúc bao sự kiện là `Instant`.
`currentState` giữ nguyên trạng thái JSON hiện có, bao gồm kiểu giá trị đơn và
giá trị lồng nhau, thông qua một `JsonNode` tách biệt, thay vì đưa trực tiếp đối
tượng map dùng để lưu trữ hoặc các thực thể JPA ra ngoài.

## Nguồn dữ liệu chuẩn và định danh

Phiên bản mã nguồn này không có thực thể Sensor hay danh mục cảm biến riêng.
Bảng `sensor_readings` hiện có lưu `(device_id, metric_type, value, unit, recorded_at)`.
`SensorReading` chỉ ánh xạ bảng đó. Trạng thái Twin khi vận hành không có bảng
trạng thái riêng; vị trí hiển thị được lưu riêng qua [API bố cục Twin](TWIN_LAYOUT.md).

Một nút cảm biến đại diện cho một luồng thiết bị/chỉ số. ID ổn định của nó gồm
chuỗi UUID chuẩn của thiết bị, dấu hai chấm và giá trị `metric_type` được lưu
nguyên văn, ví dụ `00000000-0000-4000-8000-000000000022:TEMPERATURE`.
Chỉ số không được chuẩn hóa hoặc suy ra từ khả năng của thiết bị. ID không phụ
thuộc vào ID bản ghi đo, vị trí trong danh sách, phòng được gán, giá trị hay dấu
thời gian. Các chỉ số khác nhau trên cùng thiết bị có ID cảm biến riêng. Thiết
bị chưa có số đo vẫn xuất hiện trong `devices`; chỉ khi có số đo thì hệ thống
mới biết các luồng chỉ số của nó. Không có siêu dữ liệu để suy ra đơn vị của
chỉ số chưa từng được đo.

`TwinSnapshotServiceImpl` kiểm tra quyền trước, rồi tải phòng, thiết bị và các
số đo mới nhất bằng ba truy vấn theo lô trong giao dịch chỉ đọc `REPEATABLE_READ`.
`TwinSnapshotMapper` nhóm các nút theo quan hệ phòng thực tế. Truy vấn thiết bị
tải kèm phòng để tránh truy vấn tải lười cho từng nút. Thiết bị đã xóa mềm bị
loại bỏ. Truy vấn dữ liệu đo được giới hạn theo nhà và chọn `recorded_at` lớn
nhất cho mỗi cặp `(device_id, metric_type)`; nếu trùng thời điểm thì chọn ID
bản ghi đo lớn nhất. Truy vấn chỉ trả về bản ghi mới nhất của từng chỉ số,
không trả về toàn bộ lịch sử đo. Đơn vị vẫn là chuỗi có thể null hiện có
(`°C`, `%`, `boolean`, v.v.); giá trị vẫn là số, kể cả số đo chuyển động.
Không bổ sung enum đơn vị khác.

## Cập nhật thời gian thực theo từng nút

Cấu trúc bao dùng chung `RealtimeEvent<T>` giữ nguyên các trường `eventId`,
`type`, `homeId`, `deviceId`, `data` và `timestamp`. Đích nhận vẫn là
`/topic/homes/{homeId}/events` trên điểm cuối STOMP hiện có.

| Loại sự kiện | Kiểu của data | Khóa nút |
| --- | --- | --- |
| DEVICE_STATE_CHANGED | TwinDeviceSnapshotResponse | data.deviceId |
| SENSOR_READING_UPDATED | TwinSensorSnapshotResponse | data.sensorId |

`data` là dữ liệu thay thế đầy đủ cho một nút, dùng cùng các trường với bản
chụp trạng thái. Nó không bao giờ chứa `rooms` hoặc toàn bộ nhà. `deviceId`
trong cả cấu trúc bao và phần dữ liệu đều trỏ đến thiết bị; cấu trúc bao giữ
`homeId`, phần dữ liệu giữ `roomId`. Sự kiện cảm biến có `metricType`,
`latestValue`, `unit` và `observedAt`, cho phép từng chỉ số trên cùng thiết bị
thay đổi độc lập. Sự kiện của chỉ số mới được ghi nhận có thể thêm nút cảm biến đó.

Phương thức cập nhật trạng thái thiết bị hiện có đưa `DeviceStateChangedEvent`
với dữ liệu tách biệt vào hàng đợi sau khi lưu trạng thái.
`DeviceSensorRealtimeListener` chỉ phát sự kiện qua `RealtimeEventPublisher`
hiện có ở pha `AFTER_COMMIT`. Giao dịch bị hoàn tác hoặc việc phát ngoài giao
dịch đều không gửi sự kiện.

[Luồng cảm biến giả lập](MOCK_SENSOR_PIPELINE.md) chỉ dành cho môi trường phát
triển hiện sử dụng điểm tích hợp chuẩn `SensorReadingUpdatedEvent`. Chưa có
nguồn phát từ phần cứng thật. Dịch vụ dùng lại được phát sự kiện sau trong giao
dịch lưu trữ khi chấp nhận một số đo mới nhất:

```java
applicationEventPublisher.publishEvent(new SensorReadingUpdatedEvent(
        reading.getDevice().getHome().getId(), twinSnapshotMapper.sensor(reading)));
```

Bộ điều hợp giả lập phải được bật chủ động và giữ nguyên đặc tả này mà không
xử lý MQTT. Dữ liệu lịch sử được bổ sung không được phát dưới dạng cập nhật
trạng thái mới nhất. Dấu thời gian của sự kiện là thời điểm phát;
`observedAt` là thời điểm đo gốc.

## Ví dụ có thể kiểm chứng và kiểm thử

- [Phản hồi bản chụp trạng thái](examples/twin-snapshot.json): một nhà, hai phòng,
  ba thiết bị, ba luồng cảm biến (gồm hai chỉ số trên cùng một thiết bị).
- [Sự kiện thiết bị](examples/twin-device-event.json).
- [Sự kiện cảm biến](examples/twin-sensor-event.json).

`TwinContractSerializationTest` đối chiếu cả ba tệp với kết quả tuần tự hóa
thực tế của Spring Jackson. Các kiểm thử ánh xạ bao phủ định danh, quan hệ,
trạng thái vận hành, dữ liệu có thể null, nút chưa gán phòng và trạng thái tách
biệt. Kiểm thử dịch vụ dùng `HomeAuthorizationService` thật để xác minh thành
viên đang hoạt động, người không phải thành viên, chủ nhà bị vô hiệu hóa,
người gọi chưa xác thực và nhà không tồn tại. Kiểm thử MVC dùng chuỗi bộ lọc
bảo mật thật và cách lấy danh tính đã xác thực. Kiểm thử thời gian thực xác
minh dữ liệu một nút, ngữ cảnh nhà/thiết bị/cảm biến, xác nhận và hoàn tác giao dịch.

```powershell
mvn.cmd '-Dtest=TwinSnapshotMapperTest,TwinSnapshotServiceTest,TwinSnapshotControllerTest,TwinContractSerializationTest,DeviceStateRealtimeTest,DeviceSensorRealtimeTransactionTest' test
```

`SensorReadingRepositoryTest` còn kiểm tra cách chọn số đo mới nhất cho từng
chỉ số, trường hợp trùng dấu thời gian, cách ly dữ liệu giữa các nhà, xóa mềm
và thiết bị chưa gán phòng trên PostgreSQL, với giao dịch được hoàn tác. Kiểm
thử chỉ chạy khi `HESTA_TEST_DATABASE_URL` chỉ rõ localhost hoặc 127.0.0.1,
và dùng `HESTA_TEST_DATABASE_USERNAME`, `HESTA_TEST_DATABASE_PASSWORD`.
Trước tiên phải áp dụng các migration Supabase hiện có vào cơ sở dữ liệu cục
bộ đó; kiểm thử dùng cơ chế kiểm tra lược đồ của Hibernate, không tạo hay cập
nhật lược đồ. Không trỏ kiểm thử đến dữ liệu Cloud dùng chung. Khi chạy toàn
bộ bộ kiểm thử, cần ghi đè cả cấu hình nguồn dữ liệu của Spring sang cơ sở dữ
liệu kiểm thử an toàn, vì hồ sơ `local` cũ dành cho kiểm thử có cấu hình nguồn
dữ liệu riêng.

Kết quả kiểm chứng ngày 2026-09-17 (Java 21, Spring Boot 3.4.3):

- Kiểm thử có mục tiêu: **23 ca đạt**, không thất bại, lỗi hay bỏ qua, bao gồm
  kiểm thử repository PostgreSQL trên lược đồ Supabase cục bộ.
- Toàn bộ `mvn.cmd test`: **111 ca, 110 đạt, 1 lỗi, không bỏ qua**.
  `MqttSmokeTest.testPublishRoundTrip` hiện có không kết nối được đến MQTT
  broker cục bộ. Cả hai lần tải image broker tạm đều thất bại do lỗi phân giải
  DNS của Docker. Tất cả kiểm thử Twin và kiểm thử ngữ cảnh ứng dụng đều đạt.
  Lần chạy này cung cấp `app.realtime.websocket.heartbeat=20s` trong tiến trình
  kiểm thử vì cấu hình tài nguyên kiểm thử cũ thiếu giá trị này. Không sửa cấu
  hình trong mã nguồn.
- `mvn.cmd -DskipTests package`: **BUILD SUCCESS**, tạo
  `target/backend-0.0.1-SNAPSHOT.jar`. Kiểm thử được chạy riêng như ghi nhận
  ở trên; toàn bộ bộ kiểm thử chưa đạt do lỗi broker.
- `git diff --check`: đạt. Thay đổi giới hạn ở mã Java, kiểm thử backend và tài liệu.

## Danh sách tệp triển khai

Các đường dẫn dưới đây tính từ `src/main/java/com/hesta/backend/`, trừ khi có ghi chú khác.

| Tệp mã ứng dụng được tạo | Mục đích |
| --- | --- |
| dto/response/TwinHomeSnapshotResponse.java | Bản chụp trạng thái nhà |
| dto/response/TwinRoomSnapshotResponse.java | Bản chụp trạng thái phòng |
| dto/response/TwinDeviceSnapshotResponse.java | Bản chụp trạng thái và dữ liệu sự kiện thiết bị |
| dto/response/TwinSensorSnapshotResponse.java | Bản chụp trạng thái và dữ liệu sự kiện cảm biến |
| entity/SensorReading.java | Ánh xạ bảng dữ liệu đo hiện có |
| repository/SensorReadingRepository.java | Truy vấn số đo mới nhất theo từng chỉ số |
| mapper/TwinSnapshotMapper.java | Ánh xạ nút dùng chung cho bản chụp trạng thái và sự kiện |
| service/TwinSnapshotService.java | Giao diện dịch vụ bản chụp trạng thái |
| service/impl/TwinSnapshotServiceImpl.java | Đọc bản chụp trạng thái sau khi kiểm tra quyền |
| controller/TwinSnapshotController.java | Điểm cuối GET |
| dto/command/DeviceStateChangedEvent.java | Sự kiện giao dịch thiết bị |
| dto/command/SensorReadingUpdatedEvent.java | Điểm tích hợp sự kiện giao dịch cảm biến |
| realtime/publisher/DeviceSensorRealtimeListener.java | Phát sự kiện sau khi xác nhận giao dịch |

Các tệp mã ứng dụng được sửa: `repository/DeviceRepository.java` bổ sung truy
vấn thiết bị theo lô có tải kèm phòng; `service/impl/DeviceServiceImpl.java`
đưa sự kiện thiết bị vào hàng đợi sau khi cập nhật trạng thái.

Các tệp được tạo trong `src/test/java/com/hesta/backend/`:
`controller/TwinSnapshotControllerTest.java`, `mapper/TwinSnapshotMapperTest.java`,
`mapper/TwinContractSerializationTest.java`, `service/TwinSnapshotServiceTest.java`,
`service/DeviceStateRealtimeTest.java`, `repository/SensorReadingRepositoryTest.java`,
`realtime/publisher/DeviceSensorRealtimeTransactionTest.java` và `support/TwinFixtures.java`.

Tài liệu được tạo: tệp này và ba tệp JSON được liên kết ở trên.
Tài liệu được sửa: `docs/REALTIME.md` bổ sung liên kết đến đặc tả này.

## Phạm vi và giới hạn

- `status`, `lastSeen` và `observedAt` vẫn là các giá trị gốc được lưu.
  [Tính năng trạng thái hoạt động của Twin](TWIN_HEALTH.md) riêng biệt hiện suy
  ra `healthStatus` và phát các chuyển trạng thái chỉ do độ mới của dữ liệu,
  đồng thời giữ nguyên trách nhiệm cập nhật lastSeen/status của luồng tiếp nhận.
- Broker dùng chung không hỗ trợ phát lại bền vững hay bảo đảm thứ tự/phiên bản.
  Bên nhận phải xử lý tình huống bản chụp ban đầu và sự kiện đến đồng thời,
  kết nối lại và cập nhật sai thứ tự. Thời điểm đo được cung cấp nhưng không
  phải là số phiên bản tăng đơn điệu.
- Những thay đổi cấu trúc như tạo phòng, gán lại phòng hoặc xóa thiết bị chưa
  có loại sự kiện mới trong phạm vi công việc này. Hãy tải lại bản chụp trạng
  thái khi các cấu trúc đó thay đổi.
- Truy vấn số đo mới nhất theo từng chỉ số sử dụng các chỉ mục hiện có. Khi
  lượng dữ liệu đo lưu giữ lớn, có thể cần tối ưu truy vấn/chỉ mục qua một
  đợt rà soát riêng.
- Phần triển khai này không bổ sung tệp frontend, React/Redux/TypeScript, hạ
  tầng WebSocket/STOMP mới, cơ chế lưu trữ Digital Twin hay trình chỉnh sửa bố
  cục. Các ngưỡng trạng thái hoạt động được định nghĩa trong
  [tính năng trạng thái hoạt động của Twin](TWIN_HEALTH.md) bổ sung sau đó.
