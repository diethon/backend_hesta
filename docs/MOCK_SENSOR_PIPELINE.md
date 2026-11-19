# Luồng xử lý cảm biến chỉ dùng cho giả lập

Bộ điều hợp dành cho phát triển này kiểm chứng đặc tả Twin/thời gian thực
hiện có trước khi có luồng tiếp nhận từ phần cứng. Đây chưa phải giao thức
phần cứng hoàn chỉnh hay API tiếp nhận cho môi trường vận hành. Luồng giả
lập không bao gồm MQTT, cấp cấu hình thiết bị, danh mục cảm biến, bảng mới
hay công việc frontend. [Tính năng trạng thái hoạt động của Twin](TWIN_HEALTH.md)
riêng biệt hiện suy ra độ mới từ các số đo đã xác nhận giao dịch mà không
thiết kế lại luồng tiếp nhận.

## Điểm tiếp nhận và quyền truy cập

`POST /api/v1/dev/sensors/mock-reading` chỉ được đăng ký khi ĐỒNG THỜI thỏa
mãn hai điều kiện:

- Hồ sơ Spring `mock-sensors` đang bật, còn `prod` và `production` đều không bật.
- `app.mock-sensors.enabled=true` (biến môi trường `APP_MOCK_SENSORS_ENABLED=true`).

Mặc định điểm cuối bị tắt. Không bật trên môi trường vận hành chính thức.
Bộ lọc Bearer JWT không lưu trạng thái hiện có được giữ nguyên. Dịch vụ yêu
cầu người dùng là `HomeMember` ở trạng thái `ACTIVE` của nhà chứa thiết bị,
dựa trên ID từ `CustomUserDetails` đã xác thực. Vai trò hệ thống `ADMIN`
không được bỏ qua kiểm tra thành viên này. Bên gọi không thể cung cấp ID
nhà, ID phòng, ID cảm biến hay danh tính người dùng để làm nguồn dữ liệu chuẩn.

## Dữ liệu giả lập đầu vào và kiểm tra hợp lệ

`MockSensorReadingRequest` gồm `deviceId`, `metricType`, `value`, `unit` và
`observedAt`. Controller mỏng ánh xạ dữ liệu sang `SensorReadingInput`, độc
lập với nguồn gửi. `SensorReadingIngestionServiceImpl` kiểm tra đầu vào cho
mọi bên gọi, kể cả gọi trực tiếp dịch vụ. Việc kiểm tra tập trung trong dịch
vụ, không chỉ giới hạn ở bộ điều hợp HTTP. JSON sai định dạng, giá trị không
phải số và dấu thời gian có độ lệch múi giờ không phân tích được sẽ bị Spring
MVC từ chối theo cấu trúc lỗi `ApiResponse` hiện có.

| Trường | Quy tắc |
| --- | --- |
| deviceId | UUID bắt buộc; thiết bị phải tồn tại và chưa bị xóa mềm |
| metricType | String bắt buộc, không rỗng hoặc chỉ chứa khoảng trắng, tối đa 50 ký tự; không có NUL; giữ nguyên, không cắt khoảng trắng hay đổi hoa/thường |
| value | BigDecimal bắt buộc; biểu diễn được bằng NUMERIC(10,3) mà không làm tròn; giá trị tuyệt đối nhỏ hơn 10000000 và phần thập phân sau khi bỏ số 0 tận cùng có tối đa ba chữ số |
| unit | String có thể null hiện có; tối đa 20 ký tự; không có NUL |
| observedAt | Dấu thời gian có độ lệch múi giờ bắt buộc; đầu vào tạm thời hỗ trợ năm 1–9999 và độ chính xác micro giây, khớp độ chính xác dấu thời gian của PostgreSQL mà không làm tròn |

Không tự đặt giới hạn vật lý cho nhiệt độ/độ ẩm. Dữ liệu thiếu hoặc không
hợp lệ không bao giờ được thay bằng số 0 hay thời gian hiện tại. Đơn vị
null được giữ nguyên.

Lỗi tuân theo quy ước `AppException`/`ErrorCode` hiện có: 1120 khi thiếu ID
thiết bị, 1121 khi chỉ số không hợp lệ, 1122 khi giá trị số không hợp lệ,
1123 khi đơn vị không hợp lệ, 1124 khi thiếu hoặc sai thời điểm đo, 1116 khi
không đọc được nội dung yêu cầu, 1102 khi thiết bị không tồn tại/đã xóa,
1100 khi không tìm thấy nhà, 1004 khi chưa xác thực và 1005 khi không đủ
quyền. Lỗi phân tích như `"observedAt":"yesterday"` trả về 1116; dấu thời
gian phân tích được nhưng vượt giới hạn năm/độ chính xác hỗ trợ trả về 1124.

## Lưu trữ, trạng thái mới nhất và cập nhật thời gian thực

```text
Mock REST request -> SensorReadingInput -> SensorReadingIngestionService
  -> validate -> lock/load Device -> authorize its actual Home
  -> save existing SensorReading -> query latest (deviceId, exact metricType)
  -> if saved ID is latest: existing SensorReadingUpdatedEvent
  -> COMMIT -> existing DeviceSensorRealtimeListener (AFTER_COMMIT)
  -> existing RealtimeEventPublisher -> existing STOMP home destination
```

Giao dịch chạy ở mức `READ_COMMITTED` và khóa ghi bản ghi thiết bị. Các yêu
cầu gửi qua dịch vụ cho cùng một thiết bị được tuần tự hóa khi lưu và quyết
định trạng thái mới nhất, kể cả khi chưa có số đo trước đó. Các thiết bị
khác nhau độc lập. Cơ chế khóa đơn giản có chủ đích này cũng tuần tự hóa
những chỉ số khác nhau trên cùng thiết bị. Các nguồn phát sau này phải dùng
dịch vụ này thay vì ghi trực tiếp nếu cần cùng hành vi xử lý đồng thời.

Repository chọn `recordedAt` lớn nhất, rồi ID bản ghi đo lớn nhất nếu trùng
thời điểm, đúng như truy vấn Twin hiện có. ID do cơ sở dữ liệu sinh bằng cơ
chế identity có sẵn sau khi lưu. Số đo lịch sử được lưu với `latest=false`
và không tạo sự kiện ứng dụng hay thời gian thực. Bản ghi mới cùng thời
điểm được ưu tiên nhờ ID lớn hơn. Bản chụp Twin hiện có tự nhiên đọc cùng
số đo mới nhất đã xác nhận; không có kho trạng thái mới nhất thứ hai.

Thành công trả về HTTP 200 với `ApiResponse<SensorReadingAcceptedResponse>`:
`code=1000`, `result.readingId`, `result.latest` và `result.reading` chứa
`TwinSensorSnapshotResponse` đã được chấp nhận. Với dữ liệu lịch sử,
`reading` là bản ghi lịch sử được chấp nhận, không phải dữ liệu thay thế
nút Twin hiện tại. `latest` ghi nhận quyết định của giao dịch; nó không
phải xác nhận chuyển phát WebSocket hay cam kết rằng không có giao dịch
sau đó thay thế kết quả này.

`data` của sự kiện dùng `TwinSensorSnapshotResponse`, được tính năng trạng
thái hoạt động bổ sung sau đó mở rộng tối thiểu bằng `healthStatus`. Định
danh cảm biến vẫn là `<device UUID>:<exact metricType>`. Ngữ cảnh nhà/thiết
bị/phòng lấy từ các quan hệ của thiết bị đã tải. `observedAt` giữ nguyên
thời điểm đo đầu vào; dấu thời gian của `RealtimeEvent` là thời điểm phát.
Chỉ một nút cảm biến được gửi. Thiết bị chưa gán phòng có `roomId=null`.
Hoàn tác giao dịch không phát gì qua bộ lắng nghe `AFTER_COMMIT` hiện có.
Kênh truyền dùng chung vẫn không bảo đảm phát lại bền vững hay thứ tự toàn
cục; thay đổi này không bổ sung các khả năng đó.

## Ví dụ

- [Yêu cầu giả lập hợp lệ](examples/mock-sensor-request.json).
- [Cấu trúc SENSOR_READING_UPDATED được tạo](examples/mock-sensor-event.json).
- [Yêu cầu có giá trị số không hợp lệ](examples/mock-sensor-invalid-request.json).
- [Nội dung lỗi HTTP 400 tương ứng](examples/mock-sensor-invalid-response.json).
- [Nhật ký minh họa thành công và từ chối yêu cầu](examples/mock-sensor-demo.txt)
  ghi lại từ kịch bản tích hợp PostgreSQL/HTTP/STOMP thật.

Cần thay các ID minh họa bằng thiết bị/nhà mà người dùng đã xác thực có
quyền truy cập. ID sự kiện và thời điểm phát được sinh khi chạy.
`MockSensorExamplesTest` kiểm chứng việc tuần tự hóa ví dụ với DTO thực tế.

## Chạy minh họa có thể ghi hình, không cần frontend hay MQTT

Dùng bộ kiểm thử tích hợp HTTP thật làm bộ nhận STOMP phục vụ gỡ lỗi. Nó
khởi động backend ở một cổng cục bộ ngẫu nhiên bằng các thành phần
MVC/bảo mật/JPA/STOMP hiện có, tạo dữ liệu kiểm thử biệt lập, thực hiện
luồng xử lý rồi xóa dữ liệu do chính nó tạo. Bộ kiểm thử không nạp thành
phần MQTT. PostgreSQL phải là cơ sở dữ liệu Supabase cục bộ đã áp dụng các
migration hiện có; không thay đổi lược đồ.

Đặt `HESTA_TEST_DATABASE_URL` thành `jdbc:postgresql://127.0.0.1:54322/postgres`
(hoặc cổng PostgreSQL cục bộ của bạn), đồng thời cung cấp
`HESTA_TEST_DATABASE_USERNAME` và `HESTA_TEST_DATABASE_PASSWORD` qua môi
trường. Kiểm thử từ chối URL cơ sở dữ liệu không cục bộ và bị bỏ qua khi
thiếu các thiết lập kích hoạt chủ động này. Nó tạo JWT ngắn hạn trong bộ
nhớ và không bao giờ in chúng.

Ghi hình cửa sổ dòng lệnh khi chạy:

```powershell
mvn.cmd '-Dtest=MockSensorPipelineIntegrationTest#mockHttpInput_commit_deliversNewestEventsAndHistoricalInputKeepsTwinState' test
```

Kịch bản duy nhất thực hiện lần lượt:

1. Khởi động backend bằng cấu hình kiểm thử và PostgreSQL cục bộ.
2. Xác thực ứng dụng khách WebSocket STOMP thật và đăng ký nhận sự kiện của
   một nhà hợp lệ.
3. Gửi số đo giả lập hợp lệ (29.4) qua điểm cuối HTTP.
4. Kiểm tra bản ghi đã lưu và in ID số đo được chấp nhận.
5. Nhận và in JSON `SENSOR_READING_UPDATED` thực tế trên socket.
6. Gửi số đo mới hơn (30.0).
7. Nhận và in giá trị thời gian thực của nó.
8. Gửi số đo lịch sử cũ hơn (28.0).
9. Kiểm tra `latest=false`, không có sự kiện cảm biến và GET Twin vẫn trả
   về 30.0.
10. Gửi số đo không hợp lệ, kiểm tra HTTP 400/mã 1122 rõ ràng, không có bản
    ghi mới và không có sự kiện cảm biến.

Tìm các dòng nhật ký `MOCK DEMO`. Trước khi gửi đầu vào cảm biến, việc đăng
ký nhận sự kiện được xác minh là sẵn sàng bằng cách nhận sự kiện thăm dò
thiết bị qua broker hiện có; sự kiện này chỉ dùng trong kiểm thử, không
thuộc luồng tiếp nhận. Bộ phát/bộ lắng nghe sự kiện, cơ sở dữ liệu, xác
thực, kiểm tra thành viên và kênh truyền STOMP đều dùng thành phần thật.

Nếu chạy backend thủ công, hãy cấu hình tường minh nguồn dữ liệu Spring
**cục bộ**, khởi động với hồ sơ `mock-sensors` và
`APP_MOCK_SENSORS_ENABLED=true`. Không dựa vào mặc định nguồn dữ liệu của
hồ sơ `local` cũ. Kết nối bộ nhận STOMP gỡ lỗi đến `/ws`, dùng Bearer token
trong tiêu đề STOMP CONNECT và đăng ký `/topic/homes/{homeId}/events`.
Có thể gửi yêu cầu HTTP bằng:

```powershell
$headers = @{ Authorization = "Bearer $env:HESTA_DEMO_ACCESS_TOKEN" }
$body = Get-Content docs/examples/mock-sensor-request.json -Raw | ConvertFrom-Json
$body.deviceId = $env:HESTA_DEMO_DEVICE_ID
$body.observedAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.ffffffzzz')
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/dev/sensors/mock-reading' `
    -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json)
```

Dùng dấu thời gian muộn hơn số đo mới nhất hiện tại của luồng, rồi tăng
thời gian cho số đo thứ hai. Giảm thời gian để gửi dữ liệu lịch sử. Dùng
giá trị `10000000` để thử trường hợp từ chối số không hợp lệ. Không để token
xuất hiện trong bản ghi hình hay nhật ký.

## Kiểm chứng và thay thế nguồn dữ liệu

Kiểm thử đơn vị bao phủ kiểm tra hợp lệ, giữ nguyên chỉ số, phân quyền,
thiết bị thiếu hoặc đã xóa, lưu dữ liệu được chấp nhận, không phát sự kiện
lịch sử và các điều kiện cho phép triển khai. Kiểm thử tích hợp
PostgreSQL/socket bao phủ toàn bộ luồng đã xác nhận giao dịch, đọc Twin,
trùng thời điểm, hai chỉ số, phòng null, trường sai định dạng/thiếu, thành
viên bị vô hiệu hóa, hoàn tác và gửi đồng thời dữ liệu lịch sử. Các kiểm
thử đặc tả Twin hiện có tiếp tục dùng để kiểm tra hồi quy.

```powershell
mvn.cmd '-Dtest=SensorReadingIngestionServiceTest,MockSensorExposureTest,MockSensorExamplesTest,MockSensorPipelineIntegrationTest,TwinSnapshotMapperTest,TwinSnapshotServiceTest,TwinSnapshotControllerTest,TwinContractSerializationTest,DeviceStateRealtimeTest,DeviceSensorRealtimeTransactionTest,SensorReadingRepositoryTest' test
```

Với đầu vào đã giải mã nhưng bị từ chối, nhật ký chỉ ghi deviceId,
metricType đã giới hạn độ dài và loại bỏ ký tự điều khiển, observedAt cùng
lý do từ chối ổn định. Với nội dung yêu cầu không đọc được, nhật ký chỉ
báo không có ngữ cảnh, không ghi nội dung, tiêu đề, JWT hay văn bản ngoại
lệ. Dữ liệu đo được chấp nhận không được ghi nhật ký theo từng số đo trong
môi trường vận hành; đầu ra minh họa chỉ có trong kiểm thử.

Bộ điều hợp MQTT/gateway sau này có thể ánh xạ đầu vào đã xác thực sang
`SensorReadingInput` và gọi `SensorReadingIngestionService`. Xác thực danh
tính phần cứng và chính sách phân quyền của nó vẫn cần thiết kế riêng;
không tin ID người dùng/nhà trong dữ liệu phần cứng hay bỏ qua phân quyền
bằng cách truyền một ID người dùng tùy ý. Có thể giữ lại kiểm tra hợp lệ,
lưu trữ, chọn bản ghi mới nhất, ánh xạ, sự kiện ứng dụng và luồng thời gian
thực `AFTER_COMMIT`. Phần triển khai này chưa tích hợp phần cứng thật.

## Kết quả kiểm chứng (2026-09-17)

- **57 ca kiểm thử có mục tiêu đạt**, không thất bại, lỗi hay bỏ qua. Bao
  gồm sáu trường hợp tích hợp PostgreSQL/STOMP thật, trường hợp xử lý đồng
  thời, kiểm tra đầu vào và điều kiện mở điểm cuối, tuần tự hóa ví dụ cùng
  toàn bộ kiểm thử Twin hiện có.
- Toàn bộ `mvn.cmd test`: **145 ca, 144 đạt, 1 lỗi, không bỏ qua**. Lỗi duy
  nhất là `MqttSmokeTest.testPublishRoundTrip` hiện có, do kết nối bị từ chối
  tại URL broker được chủ đích giới hạn ở cục bộ. Không sửa hay thêm mã
  MQTT. Tiến trình kiểm thử buộc PostgreSQL dùng localhost, dùng kiểm tra
  lược đồ Hibernate, tắt Flyway và cung cấp cấu hình heartbeat WebSocket
  bắt buộc hiện có.
- `mvn.cmd -DskipTests package`: **BUILD SUCCESS**. Tệp JAR là
  `target/backend-0.0.1-SNAPSHOT.jar`; kiểm thử đã được chạy riêng ở trên.
- `git diff --check`: đạt. Dữ liệu kiểm thử tích hợp được xóa sau đó;
  `session_replication_role` của PostgreSQL được xác minh là `origin`.

Toàn bộ bộ kiểm thử chưa đạt cho đến khi phụ thuộc MQTT bên ngoài của kiểm
thử khói khả dụng. Các kiểm thử luồng giả lập không cần MQTT broker. Nhật
ký chạy gốc nằm tại `target/mock-sensor-targeted.log`,
`target/mock-sensor-full-suite.log` và `target/mock-sensor-build.log`.

## Danh sách tệp

Các tệp mã ứng dụng được tạo trong `src/main/java/com/hesta/backend/`:

- `dto/request/MockSensorReadingRequest.java`
- `dto/command/SensorReadingInput.java`
- `dto/response/SensorReadingAcceptedResponse.java`
- `service/SensorReadingIngestionService.java`
- `service/impl/SensorReadingIngestionServiceImpl.java`
- `controller/MockSensorController.java`
- `exception/MockSensorRequestExceptionHandler.java`

Các tệp mã ứng dụng được sửa: `exception/ErrorCode.java` bổ sung mã kiểm tra
hợp lệ; `repository/DeviceRepository.java` bổ sung tra cứu thiết bị có khóa;
`repository/SensorReadingRepository.java` bổ sung thao tác lưu và tra cứu
luồng mới nhất.

Các tệp kiểm thử được tạo trong `src/test/java/com/hesta/backend/`:
`service/SensorReadingIngestionServiceTest.java`, `controller/MockSensorExposureTest.java`,
`mapper/MockSensorExamplesTest.java` và `realtime/MockSensorPipelineIntegrationTest.java`.
Tạo tài liệu này và năm tệp ví dụ/bằng chứng được liên kết; cập nhật
`docs/DIGITAL_TWIN.md` để trỏ đến nguồn phát giả lập đã triển khai.

Phần triển khai luồng giả lập ban đầu tái sử dụng: `SensorReading`,
`TwinSensorSnapshotResponse`, `TwinSnapshotMapper`, `SensorReadingUpdatedEvent`,
`DeviceSensorRealtimeListener`, `RealtimeEventPublisher`,
`RealtimeEventType.SENSOR_READING_UPDATED` và hạ tầng WebSocket/STOMP dùng
chung. Phần triển khai đó không bổ sung thực thể/bảng Sensor, migration,
frontend, tích hợp phần cứng/MQTT hay ngưỡng trạng thái hoạt động.
[Tính năng trạng thái hoạt động của Twin](TWIN_HEALTH.md) bổ sung sau đó mở
rộng DTO nút, bộ ánh xạ và bộ lắng nghe sau khi xác nhận giao dịch, đồng thời
giữ nguyên luồng tiếp nhận này.
