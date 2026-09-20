# Độ mới dữ liệu Digital Twin: ACTIVE / STALE / OFFLINE

`TwinHealthStatus` là trạng thái suy ra để phục vụ hiển thị, tách biệt với
`DeviceStatus` và `currentState` hiện có. Tính năng này không thay đổi trạng
thái nghiệp vụ của thiết bị hay `lastSeen`. Trạng thái hoạt động suy ra không
được lưu vào cơ sở dữ liệu.

## Thời gian tham chiếu chuẩn và quy tắc chính xác

`TwinHealthStatusResolverImpl` là nơi duy nhất thực hiện so sánh các ngưỡng.
Thành phần này dùng `java.time.Clock` được tiêm vào (mặc định UTC) hoặc một
`Instant` đánh giá được truyền tường minh.

| Điều kiện | healthStatus |
| --- | --- |
| Thiếu dấu thời gian tham chiếu | OFFLINE |
| age < staleAfter | ACTIVE |
| staleAfter <= age < offlineAfter | STALE |
| age >= offlineAfter | OFFLINE |

`age = evaluatedAt - referenceTime`, được so sánh chính xác dưới dạng
`Duration` của Java. Dấu thời gian trong tương lai có tuổi dữ liệu âm và luôn
được xác định là ACTIVE cho đến khi đồng hồ vượt các ngưỡng độ mới tương ứng.
Dấu thời gian không bao giờ bị ghi lại hay thay thế bằng thời điểm yêu cầu
bản chụp trạng thái hoặc thời điểm phát WebSocket.

- Thiết bị chỉ dùng `Device.lastSeen` đã lưu.
- Luồng cảm biến dùng `SensorReading.recordedAt` mới nhất, được trả ra qua
  `observedAt`. Mới nhất vẫn là thời điểm đo lớn nhất, sau đó là ID bản ghi đo
  lớn nhất nếu trùng thời điểm. Mỗi luồng `(deviceId, exact metricType)` độc lập.
- Sự kiện cảm biến **không** cập nhật lastSeen hay trạng thái hoạt động của
  thiết bị. TEMPERATURE mới không làm mới HUMIDITY. Số đo mới nhất được chấp
  nhận có thể đã ở trạng thái stale/offline khi đến và được phân loại tương ứng.

## Cấu hình

Giá trị mặc định chỉ được khai báo tại các annotation liên kết cấu hình qua
hàm khởi tạo của `TwinHealthProperties`. Cơ chế ghi đè cấu hình/biến môi trường
thông thường của Spring Boot vẫn áp dụng; các giá trị được liên kết và kiểm
tra khi khởi động. Cần khởi động lại ứng dụng để thay đổi những thiết lập này.

| Thuộc tính | Mặc định | Biến môi trường |
| --- | --- | --- |
| app.twin.health.stale-after | 1m | APP_TWIN_HEALTH_STALEAFTER |
| app.twin.health.offline-after | 5m | APP_TWIN_HEALTH_OFFLINEAFTER |
| app.twin.health.evaluation-interval | 15s | APP_TWIN_HEALTH_EVALUATIONINTERVAL |
| app.twin.health.scheduling-enabled | true | APP_TWIN_HEALTH_SCHEDULINGENABLED |

Bắt buộc `staleAfter > 0`, `offlineAfter > staleAfter` và
`evaluationInterval > 0`. Khoảng thời gian hoặc thứ tự ngưỡng không hợp lệ
làm quá trình liên kết cấu hình thất bại với lỗi rõ ràng theo từng thuộc
tính. Công tắc lập lịch phục vụ kiểm thử có kết quả xác định hoặc việc chủ
động kích hoạt đánh giá từ bên ngoài; môi trường vận hành thường nên bật.
Nếu tắt, bên gọi phải tự gọi `evaluateAll()` để nhận các chuyển trạng thái
theo thời gian và dọn bộ nhớ đệm.

## Bản chụp trạng thái và sự kiện chứa đầy đủ một nút

`TwinDeviceSnapshotResponse` bổ sung trường `TwinHealthStatus healthStatus`,
giữ nguyên `DeviceStatus status`, `currentState` và `lastSeen`.
`TwinSensorSnapshotResponse` bổ sung `healthStatus` và giữ toàn bộ trường
hiện có. Cấu trúc nhà/phòng, UUID, ID cảm biến nguyên bản và cấu trúc bao
`ApiResponse` đều không đổi. `TwinSnapshotMapper` đánh giá mọi nút trong một
bản chụp tại cùng một thời điểm của `Clock`, dùng bộ phân giải chung; thao
tác đọc bản chụp không thay đổi bộ nhớ đệm.

Các sự kiện `DEVICE_STATE_CHANGED` và `SENSOR_READING_UPDATED` hiện có tiếp
tục gửi DTO đầy đủ của một nút, nay có thêm healthStatus. Bộ lắng nghe
`AFTER_COMMIT` hiện có tính lại trạng thái hoạt động khi phát dựa trên thời
gian tham chiếu chuẩn trong dữ liệu, nên giao dịch kéo dài không thể phát
ACTIVE một cách máy móc dựa vào thời điểm DTO được ánh xạ ban đầu.

## Đánh giá theo thời gian và bộ nhớ đệm chuyển trạng thái

Mỗi bản chạy ứng dụng có một `TwinHealthScheduler` và một
`TwinHealthEvaluationService`. Bộ lập lịch thiết lập trạng thái cơ sở ngay
khi khởi động, sau đó chạy với khoảng chờ cố định đã cấu hình tính từ lúc
mỗi lượt quét kết thúc. Nó có một luồng xử lý riêng, tách biệt với bộ lập
lịch heartbeat WebSocket hiện có. Không có luồng xử lý hay bộ hẹn giờ riêng
cho từng thiết bị/cảm biến.

Một lượt quét tải hai tập dữ liệu chiếu chỉ gồm các giá trị đơn theo lô:
thiết bị chưa bị xóa và số đo mới nhất của từng luồng cảm biến hiện hữu.
Không tải lịch sử đo và không truy vấn tải lười theo từng nút. Các truy vấn
loại bỏ thiết bị đã xóa mềm và cùng chạy trong một giao dịch chỉ đọc
`REPEATABLE_READ`. Bộ đánh giá kết thúc giao dịch đọc trước khi phát chuyển
trạng thái qua `RealtimeEventPublisher`.

Khóa bộ nhớ đệm là `(DEVICE, device UUID string)` và `(SENSOR, canonical sensorId)`.
ID cảm biến vẫn là `<device UUID>:<exact metricType>`, được tạo bởi cùng hàm
hỗ trợ dùng khi ánh xạ bản chụp. Chỉ trạng thái được tính gần nhất được lưu
đệm. Việc đọc và cập nhật bộ nhớ đệm được tuần tự hóa để tránh lượt quét đồng
thời ghi đè kết quả đánh giá mới hơn đã được xác nhận. Trạng thái không đổi
thì không phát sự kiện. Khi trạng thái thay đổi, hệ thống phát một sự kiện
trạng thái hoạt động có kiểu xác định; bộ nhớ đệm cập nhật sau khi phát thành
công. Nếu phát thất bại, trạng thái cũ trong bộ nhớ đệm được giữ để thử lại.

Lần đầu thấy một nút chỉ thiết lập trạng thái cơ sở, không phát sự kiện.
Khởi động lại ứng dụng không tạo hàng loạt chuyển trạng thái giả; ứng dụng
khách mới dùng bản chụp REST để lấy trạng thái hoạt động hiện tại. Mỗi lượt
quét đầy đủ xóa các khóa không còn trong tập nút hiện hữu, bao gồm thiết bị
đã xóa và luồng cảm biến đã bị loại bỏ. Kích thước bộ nhớ đệm phụ thuộc vào
số nút hiện hữu, không phụ thuộc lịch sử đo hay mọi ID từng được ghi nhận.

## Phục hồi sau hoạt động đã xác nhận giao dịch

`TwinHealthActivityListener` chỉ theo dõi `DeviceStateChangedEvent` và
`SensorReadingUpdatedEvent` hiện có ở pha `AFTER_COMMIT`, sau khi sự kiện
chứa đầy đủ một nút của chúng được phát. Nó đánh giá ngay thiết bị hoặc đúng
chỉ số cảm biến bị ảnh hưởng, không đợi chu kỳ lập lịch tiếp theo.

Quá trình phục hồi đọc lại tham chiếu đã xác nhận hiện tại qua một giao dịch
đọc ngắn `REQUIRES_NEW`: ở pha `AFTER_COMMIT`, tài nguyên của giao dịch đã
kết thúc vẫn có thể còn được gắn với luồng xử lý. Cách này cũng tránh việc
hàm gọi lại chạy muộn dùng dấu thời gian sự kiện hoặc ngữ cảnh nhà/phòng đã
cũ. Định tuyến dựa trên quan hệ hiện tại trong cơ sở dữ liệu. Nút không tồn
tại hoặc đã bị xóa sẽ bị xóa khóa bộ nhớ đệm thay vì phát sự kiện phục hồi.

Dịch vụ tiếp nhận hiện có vốn không phát `SensorReadingUpdatedEvent` cho dữ
liệu lịch sử, nên dữ liệu này không thể kích hoạt phục hồi. Ngay cả lượt
quét cũng vẫn chọn số đo mới nhất thực sự. Hoàn tác giao dịch không kích
hoạt cả hai bộ lắng nghe thời gian thực, do đó không thể phát phục hồi trạng
thái hay thay đổi dấu thời gian mới nhất đã xác nhận.

## Đặc tả sự kiện thay đổi trạng thái hoạt động theo thời gian thực

Một giá trị enum dùng chung mới, `TWIN_HEALTH_STATUS_CHANGED`, đại diện cho
mọi chuyển trạng thái hoạt động. Cấu trúc bao `RealtimeEvent<T>` giữ nguyên,
định tuyến qua `/topic/homes/{homeId}/events` và bộ phát/kênh truyền hiện có.
Không có điểm cuối, broker, bộ phát, nhóm topic hay API truy vấn định kỳ cho
frontend mới.

`TwinHealthStatusChangedPayload` gồm:

- `nodeType`: DEVICE hoặc SENSOR.
- `nodeId`: chuỗi UUID thiết bị hoặc ID cảm biến chuẩn.
- `deviceId`: UUID của thiết bị nguồn.
- `roomId`: UUID, hoặc null nếu thiết bị/luồng chưa được gán phòng.
- `previousStatus` và `healthStatus`: các giá trị chuẩn của TwinHealthStatus.
- `referenceTime`: thời gian tham chiếu chuẩn kiểu OffsetDateTime, có thể
  null nếu chưa từng ghi nhận hoạt động của thiết bị.
- `evaluatedAt`: thời điểm đánh giá kiểu Instant từ Clock được tiêm vào.

Dấu thời gian của cấu trúc bao vẫn là thời điểm phát. Phần dữ liệu chỉ chứa
một nút, không chứa bản chụp toàn bộ nhà. Xem [JSON mẫu chính xác](examples/twin-health-event.json),
được kiểm chứng bằng `TwinContractSerializationTest`. Các JSON mẫu bản chụp,
thiết bị và cảm biến hiện có cũng được mở rộng và tiếp tục được kiểm thử tuần tự hóa.

## Ví dụ dòng thời gian

Ví dụ này ghi đè staleAfter thành **30s**, offlineAfter thành **5m**; ngưỡng
stale mặc định khi vận hành là 1m. Kết quả chính xác của bộ phân giải:

| Thời điểm | Thời gian tham chiếu | Trạng thái hoạt động |
| --- | --- | --- |
| 10:00:00 chấp nhận số đo | 10:00:00 | ACTIVE |
| 10:00:29.999 | 10:00:00 | ACTIVE |
| 10:00:30.000 | 10:00:00 | STALE |
| 10:04:59.999 | 10:00:00 | STALE |
| 10:05:00.000 | 10:00:00 | OFFLINE |
| 10:06:00 xác nhận giao dịch chứa số đo mới nhất còn mới | 10:06:00 | ACTIVE |

Sự kiện theo lịch được gửi ở lượt quét đầu tiên tại hoặc sau ngưỡng, không
bảo đảm đẩy đúng từng nano giây tại ngưỡng. Trạng thái hoạt động trong bản
chụp và sự kiện chứa đầy đủ một nút được tính ngay bằng bộ phân giải. Nếu
khoảng nghỉ giữa các lượt quét dài, chuyển thẳng ACTIVE -> OFFLINE là hợp
lệ; hệ thống không tạo sự kiện STALE chưa từng được quan sát.

## Kiểm thử và chạy minh họa chỉ với backend

Kiểm thử dùng ngưỡng 30s/5m tường minh, Clock cố định hoặc được tăng thủ công
và gọi trực tiếp bộ đánh giá. Chúng không tạm dừng để chờ vượt ngưỡng trạng
thái. Thời gian chờ của hàng đợi socket chỉ dùng để đợi việc truyền bất đồng bộ.

- Kiểm thử bộ phân giải bao phủ chính xác các ngưỡng, thời điểm trước ngưỡng
  stale một nano giây, dấu thời gian thiếu, dấu thời gian tương lai và đánh
  giá lặp lại cho kết quả xác định.
- Kiểm thử liên kết cấu hình bao phủ giá trị không hợp lệ, thứ tự ngưỡng và
  tên biến môi trường đã ghi trong tài liệu.
- Kiểm thử bộ đánh giá/lập lịch bao phủ trạng thái cơ sở không phát sự kiện,
  loại bỏ sự kiện trùng, phục hồi, tính độc lập giữa các chỉ số/trạng thái
  thiết bị, định tuyến, dọn nút và chỉ đăng ký một tác vụ.
- Các kiểm thử Twin hiện có kiểm chứng thêm trạng thái hoạt động suy ra,
  đồng thời giữ mọi kiểm tra ánh xạ, bảo mật và tuần tự hóa trước đó.
- Bộ kiểm thử giả lập dùng PostgreSQL/STOMP thật hiện có còn xác minh chuyển
  trạng thái thuần theo thời gian, phục hồi từ STALE/OFFLINE, hoàn tác cảm
  biến/thiết bị, dữ liệu lịch sử, tính độc lập giữa các chỉ số và ngữ nghĩa
  status/lastSeen của thiết bị không đổi.

Sau khi cấu hình `HESTA_TEST_DATABASE_URL`, `HESTA_TEST_DATABASE_USERNAME` và
`HESTA_TEST_DATABASE_PASSWORD` cho môi trường cục bộ, có thể ghi lại phiên
minh họa trạng thái hoạt động có kết quả xác định này:

```powershell
mvn.cmd '-Dtest=MockSensorPipelineIntegrationTest#healthTimeline_transitionsWithoutActivity_andRecoversOnlyAfterFreshCommit' test
```

Phiên minh họa dùng bộ nhận sự kiện kiểm thử backend hiện có và in các sự
kiện `HEALTH DEMO received` từ kết nối STOMP thật. Không cần frontend hay MQTT
broker. [Nhật ký minh họa trạng thái hoạt động](examples/twin-health-demo.txt)
đã ghi lại cả ba chuyển trạng thái. `evaluatedAt` dùng Clock kiểm thử được
tăng thủ công, còn `timestamp` của cấu trúc bao giữ nguyên thời điểm phát
thực tế. Nhật ký chuyển trạng thái khi vận hành dùng mức DEBUG và chỉ gồm
định danh nhà/nút cùng trạng thái hoạt động trước/hiện tại; các lần đánh giá
không thay đổi không ghi dữ liệu đo theo từng nút.

## Giới hạn vận hành

- Bộ nhớ đệm và bộ lập lịch thuộc từng bản chạy ứng dụng. Nhiều bản chạy có
  thể phát độc lập cùng một chuyển trạng thái; chưa có cơ chế loại trùng
  trên toàn cụm.
- Broker chỉ cố gắng chuyển phát, không hỗ trợ phát lại bền vững hay xác nhận
  đúng một lần. Đồng bộ lại sau khi kết nối lại dùng bản chụp Twin hiện có.
- Một lượt quét có độ phức tạp O(số thiết bị hiện tại + số luồng chỉ số hiện
  tại). Hệ thống lớn có thể cần phân trang/chỉ mục hoặc phân chia trách nhiệm
  đánh giá qua một đợt rà soát riêng.
- Các hàm gọi lại sau khi xác nhận giao dịch được tuần tự hóa ngắn với lượt
  quét và cần kết nối cơ sở dữ liệu mới; cần tính kích thước nhóm kết nối
  cho cả giao dịch đồng thời lẫn các lượt đọc này.
- Số đo mang thời gian tương lai giữ ACTIVE cho đến khi tuổi dữ liệu chạm
  ngưỡng. Quy tắc độ mới có kết quả xác định này không sửa sai lệch đồng hồ nguồn.
- Thiết bị chỉ phục hồi khi nguồn phát thực sự cập nhật lastSeen và phát sự
  kiện hoạt động đã xác nhận hiện có. Luồng tiếp nhận cảm biến chủ đích không
  làm thay hai việc này cho thiết bị. Thay đổi trực tiếp trong cơ sở dữ liệu
  được phát hiện ở lượt quét tiếp theo.

Tính năng này không bao gồm frontend, tích hợp MQTT, thay đổi thông báo,
thực thể Sensor mới, bảng trạng thái hoạt động, migration hay hạ tầng
WebSocket/STOMP mới.

## Kết quả kiểm chứng ghi nhận ngày 2026-09-17

Mọi kiểm tra cơ sở dữ liệu đều dùng PostgreSQL cục bộ biệt lập tại
`127.0.0.1:54322`, không dùng Supabase Cloud. Thông tin xác thực lấy từ môi
trường container cục bộ, không ghi vào cấu hình được Git theo dõi. Cơ chế
kiểm tra lược đồ của Hibernate vẫn được bật.

| Nội dung kiểm chứng | Kết quả | Tệp kết quả cục bộ |
| --- | --- | --- |
| Kiểm thử có mục tiêu cho trạng thái hoạt động, Twin, tiếp nhận giả lập, repository và giao dịch thời gian thực | 89 ca đạt; không thất bại/lỗi/bỏ qua | `target/twin-health-targeted.log` |
| Toàn bộ `mvn.cmd test` | 178 ca: 177 đạt, 1 lỗi MQTT hiện có; không bỏ qua | `target/twin-health-full-suite.log` |
| `mvn.cmd -DskipTests package` sau các lần chạy kiểm thử riêng | BUILD SUCCESS | `target/twin-health-build.log` |
| `git diff --check` | Đạt | Không có lỗi khoảng trắng |

Lỗi duy nhất trong toàn bộ bộ kiểm thử là lỗi có sẵn ở
`MqttSmokeTest.testPublishRoundTrip`: kết nối đến MQTT broker cục bộ bị từ
chối. Không sửa mã/kiểm thử MQTT để che giấu lỗi phụ thuộc bên ngoài này.
Tệp đóng gói là `target/backend-0.0.1-SNAPSHOT.jar`; lệnh đóng gói bỏ qua chạy
lại kiểm thử và không đồng nghĩa toàn bộ bộ kiểm thử đã đạt.

Lần chạy có mục tiêu chọn `TwinHealthStatusResolverTest`,
`TwinHealthPropertiesTest`, `TwinHealthEvaluationServiceTest`,
`TwinHealthSchedulerTest`, `MockSensorPipelineIntegrationTest`,
`SensorReadingIngestionServiceTest`, `MockSensorExposureTest`,
`MockSensorExamplesTest`, `TwinSnapshotMapperTest`, `TwinSnapshotServiceTest`,
`TwinSnapshotControllerTest`, `TwinContractSerializationTest`,
`DeviceStateRealtimeTest`, `DeviceSensorRealtimeTransactionTest` và
`SensorReadingRepositoryTest`.

## Các tệp được tạo và sửa cho tính năng này

Đường dẫn mã ứng dụng dưới đây tính từ `src/main/java/com/hesta/backend/`.

| Tệp được tạo | Trách nhiệm |
| --- | --- |
| `enums/TwinHealthStatus.java` | Các giá trị chuẩn ACTIVE / STALE / OFFLINE |
| `enums/TwinNodeType.java` | Phân biệt DEVICE / SENSOR khi truyền dữ liệu |
| `config/TwinHealthProperties.java` | Khoảng thời gian có kiểu, được kiểm tra và khai báo mặc định tại một nơi |
| `config/TwinHealthConfig.java` | Cấu hình Clock và bộ lập lịch riêng với một luồng xử lý |
| `service/TwinHealthStatusResolver.java` | Giao diện tính trạng thái hoạt động dùng chung |
| `service/impl/TwinHealthStatusResolverImpl.java` | Quy tắc ngưỡng thời gian chính xác |
| `service/TwinHealthEvaluationService.java` | Giao diện đánh giá theo lô và từng nút |
| `service/impl/TwinHealthEvaluationServiceImpl.java` | Suy ra chuyển trạng thái, lưu đệm, dọn đệm và tái sử dụng bộ phát hiện có |
| `service/impl/TwinHealthScheduler.java` | Một tác vụ đánh giá định kỳ có thể cấu hình |
| `repository/TwinHealthReference.java` | Phép chiếu giá trị đơn theo lô cho thời gian/ngữ cảnh |
| `dto/response/TwinHealthStatusChangedPayload.java` | Sự kiện trạng thái hoạt động có kiểu cho một nút |
| `realtime/publisher/TwinHealthActivityListener.java` | Phục hồi từ sự kiện đã xác nhận giao dịch hiện có |

Các tệp mã ứng dụng được sửa:

- `dto/response/TwinDeviceSnapshotResponse.java` và
  `dto/response/TwinSensorSnapshotResponse.java`: bổ sung trường trạng thái
  hoạt động và hàm hỗ trợ sao chép.
- `mapper/TwinSnapshotMapper.java`: dùng chung bộ phân giải trạng thái hoạt
  động, một thời điểm Clock cho cả bản chụp và hàm tạo ID cảm biến chuẩn.
- `realtime/model/RealtimeEventType.java`: thêm một loại chuyển trạng thái hoạt động.
- `realtime/publisher/DeviceSensorRealtimeListener.java`: suy ra trạng thái
  hoạt động cho dữ liệu đầy đủ của nút khi phát ở pha `AFTER_COMMIT`.
- `repository/DeviceRepository.java` và `repository/SensorReadingRepository.java`:
  phép chiếu dữ liệu độ mới theo lô và theo nút bị ảnh hưởng.

Các tệp kiểm thử/hỗ trợ được tạo trong `src/test/java/com/hesta/backend/`:

- `config/TwinHealthPropertiesTest.java`
- `service/TwinHealthStatusResolverTest.java`
- `service/TwinHealthEvaluationServiceTest.java`
- `service/TwinHealthSchedulerTest.java`
- `support/MutableClock.java`
- `support/TwinHealthTestSupport.java`

Các tệp kiểm thử hiện có được sửa trong cùng thư mục gốc:

- `mapper/MockSensorExamplesTest.java`
- `mapper/TwinContractSerializationTest.java`
- `mapper/TwinSnapshotMapperTest.java`
- `realtime/MockSensorPipelineIntegrationTest.java`
- `realtime/publisher/DeviceSensorRealtimeTransactionTest.java`
- `repository/SensorReadingRepositoryTest.java`
- `service/DeviceStateRealtimeTest.java`
- `service/SensorReadingIngestionServiceTest.java`
- `service/TwinSnapshotServiceTest.java`

Tài liệu/bằng chứng được tạo: `docs/TWIN_HEALTH.md`,
`docs/examples/twin-health-event.json` và `docs/examples/twin-health-demo.txt`.
Tài liệu được sửa: `docs/DIGITAL_TWIN.md`, `docs/MOCK_SENSOR_PIPELINE.md`,
`docs/REALTIME.md` và bốn ví dụ theo đặc tả hiện tại trong `docs/examples/`:
`twin-snapshot.json`, `twin-device-event.json`, `twin-sensor-event.json` và
`mock-sensor-event.json`.

Rà soát phạm vi: không đọc hay sửa tệp frontend; không thay đổi tệp thực thể,
migration, MQTT, thông báo, xác thực hay hạ tầng WebSocket. Bộ phát, luồng
giả lập và hành vi SENSOR_READING_UPDATED hiện có được giữ nguyên.
DeviceStatus và lastSeen đã lưu giữ nguyên ngữ nghĩa hiện tại.
