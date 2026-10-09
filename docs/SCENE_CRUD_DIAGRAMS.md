# Scene – Class và Sequence Diagrams

> Bộ diagram đầy đủ cho Scene & Automation (inventory, UML và các nhánh lỗi): [SCENE_AUTOMATION_DIAGRAMS.md](SCENE_AUTOMATION_DIAGRAMS.md).

Ngày đồng bộ với source code: **2026-10-04**.

Tài liệu mô tả Scene CRUD, quản lý action, thực thi, lịch sử và lập lịch đã có trong backend. Luồng Automation gọi Scene và scheduler dùng chung được mô tả nhất quán với [AUTOMATION_BEHAVIOR_IMPLEMENTATION.md](AUTOMATION_BEHAVIOR_IMPLEMENTATION.md).

Các sơ đồ dùng **PlantUML** và cùng một quy ước:

- Class diagram dùng ký hiệu UML cho class/interface, thuộc tính, phương thức và bội số. Class diagram không có nút database.
- Sequence truy xuất dữ liệu dùng lifeline **Database** với ký hiệu database thông thường; controller, service và repository đều là participant hình chữ nhật. PostgreSQL là công nghệ thực tế của backend.
- Sequence có `autonumber`, mũi tên gọi/trả về và **thanh kích hoạt (activation bar)** đầy đủ cho controller, service, repository, Database và lời gọi lồng nhau. Thanh này biểu diễn thời gian xử lý trên lifeline.
- **Composition**: `*--`, hình thoi đặc tại bên sở hữu; Scene sở hữu SceneAction/SceneSchedule, AutomationRule sở hữu RuleCondition/RuleAction với cascade/orphan removal.
- **Aggregation**: `o--`, hình thoi rỗng tại bên chứa; request/response DTO gom danh sách DTO phần tử. Quan hệ này mô tả cấu trúc dữ liệu trong bộ nhớ, không đặt thêm quy tắc cascade hoặc vòng đời cho entity.
- **Dependency**: `..>`, nét đứt hướng về lớp/interface được sử dụng; áp dụng cho các collaborator được inject và DTO mà service tạo/nhận.
- **Inheritance / generalization**: `--|>`, nét liền với tam giác rỗng hướng về kiểu cha; chỉ dùng khi có quan hệ kế thừa giữa các lớp/interface được đưa vào sơ đồ.
- **Realization / implementation**: `..|>`, nét đứt với tam giác rỗng hướng về interface; các service implementation triển khai service interface.
- **Association**: `--` hoặc `-->` kèm bội số/role cho tham chiếu giữa entity. Các quan hệ UML thể hiện cấu trúc lớp, không phải các bước gọi runtime.
- Mỗi đường nối trong class diagram có nhãn ghi rõ loại quan hệ ngay trên đường nối; role như `actions`, `conditions`, `targetDevice` được ghi kèm trong ngoặc khi có.
- Các class diagram chọn lọc trường và phương thức liên quan đến từng luồng; không liệt kê mọi tiện ích nội bộ. Sequence biểu diễn luồng hợp lệ; lỗi validation/quyền được ném bằng `AppException`, trả qua `GlobalExceptionHandler` và dừng luồng. Lỗi từng command được ghi nhận để tiếp tục action còn lại.
- User được lấy từ JWT (`CustomUserDetails`); mọi API trả `ApiResponse<T>` với mã thành công `1000`.

## 1. API hiện tại

Base path: `/api/v1/homes/{homeId}/scenes`. Các quyền dưới đây đều yêu cầu membership `ACTIVE`.

- `POST /`: tạo Scene — OWNER.
- `GET /`, `GET /{sceneId}`: danh sách, chi tiết — member.
- `GET /action-types`: tổng hợp action từ capabilities của Devices trong Home — member.
- `PUT /{sceneId}`: sửa Scene, bao gồm bật/tắt qua `enabled` — OWNER.
- `DELETE /{sceneId}`: xóa Scene chưa được Automation Rule tham chiếu — OWNER.
- `POST /{sceneId}/actions`, `DELETE /{sceneId}/actions/{actionId}`, `PUT /{sceneId}/actions/reorder`: quản lý action — OWNER.
- `POST /{sceneId}/execute`: chạy Scene thủ công — member.
- `GET /{sceneId}/executions`: tối đa 100 lần thực thi mới nhất theo `startedAt` giảm dần — member.
- `GET /{sceneId}/schedules`: danh sách lịch — member.
- `POST /{sceneId}/schedules`, `PUT /{sceneId}/schedules/{scheduleId}`, `DELETE /{sceneId}/schedules/{scheduleId}`: tạo/sửa/xóa lịch — OWNER.

Endpoint chạy Scene trong code là **`/execute`**, không phải `/activate`. API lịch nằm trong `SceneController` và sử dụng `ScheduleService`, không có `SceneScheduleController`, `SceneScheduleService` hoặc `SceneScheduleRunner` riêng.

## 2. Class diagrams

### 2.1. Scene CRUD và persistence

```plantuml
@startuml Scene_CRUD_Classes
title Scene CRUD: controller, service và repository
!pragma layout smetana
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

left to right direction
hide circle
skinparam classAttributeIconSize 0
class SceneController {
    +actionTypes(userDetails, homeId): ResponseEntity<ApiResponse<List<String>>>
    +createScene(userDetails, homeId, request): ResponseEntity<ApiResponse<SceneResponse>>
    +getScenes(userDetails, homeId): ResponseEntity<ApiResponse<List<SceneResponse>>>
    +getScene(userDetails, homeId, sceneId): ResponseEntity<ApiResponse<SceneResponse>>
    +updateScene(userDetails, homeId, sceneId, request): ResponseEntity<ApiResponse<SceneResponse>>
    +deleteScene(userDetails, homeId, sceneId): ResponseEntity<ApiResponse<Void>>
    +addAction(userDetails, homeId, sceneId, request): ResponseEntity<ApiResponse<SceneActionResponse>>
    +removeAction(userDetails, homeId, sceneId, actionId): ResponseEntity<ApiResponse<Void>>
    +reorderActions(userDetails, homeId, sceneId, request): ResponseEntity<ApiResponse<SceneResponse>>
}
interface SceneService {
    +getActionTypes(authenticatedUserId, homeId): List<String>
    +createScene(authenticatedUserId, homeId, request): SceneResponse
    +getScenesForHome(authenticatedUserId, homeId): List<SceneResponse>
    +getScene(authenticatedUserId, homeId, sceneId): SceneResponse
    +updateScene(authenticatedUserId, homeId, sceneId, request): SceneResponse
    +deleteScene(authenticatedUserId, homeId, sceneId): void
    +addAction(authenticatedUserId, homeId, sceneId, request): SceneActionResponse
    +removeAction(authenticatedUserId, homeId, sceneId, sceneActionId): void
    +reorderActions(authenticatedUserId, homeId, sceneId, request): SceneResponse
}
class SceneServiceImpl {
    -homeAuthorizationService: HomeAuthorizationService
    -sceneRepository: SceneRepository
    -sceneActionRepository: SceneActionRepository
    -deviceRepository: DeviceRepository
    -automationRuleRepository: AutomationRuleRepository
    +getActionTypes(authenticatedUserId, homeId): List<String>
    +createScene(authenticatedUserId, homeId, request): SceneResponse
    +getScenesForHome(authenticatedUserId, homeId): List<SceneResponse>
    +getScene(authenticatedUserId, homeId, sceneId): SceneResponse
    +updateScene(authenticatedUserId, homeId, sceneId, request): SceneResponse
    +deleteScene(authenticatedUserId, homeId, sceneId): void
    +addAction(authenticatedUserId, homeId, sceneId, request): SceneActionResponse
    +removeAction(authenticatedUserId, homeId, sceneId, sceneActionId): void
    +reorderActions(authenticatedUserId, homeId, sceneId, request): SceneResponse
}
class HomeAuthorizationService {
    -homeRepository: HomeRepository
    -homeMemberRepository: HomeMemberRepository
    +requireAccess(userId, homeId): Home
    +requireSceneManagement(userId, homeId): Home
    +requireLayoutManagement(userId, homeId): Home
}
interface SceneRepository <<repository>> {
    +findAllByHomeIdOrderByNameAsc(homeId): List<Scene>
    +findByIdAndHomeId(id, homeId): java.util.Optional<Scene>
    +existsByHomeIdAndName(homeId, name): boolean
    +existsByHomeIdAndNameAndIdNot(homeId, name, id): boolean
    +findById(id): Optional<Scene>
    +findAllById(ids): List<Scene>
    +save(entity): Scene
    +saveAndFlush(entity): Scene
    +saveAll(entities): List<Scene>
    +delete(entity): void
}
interface SceneActionRepository <<repository>> {
    +findAllBySceneIdOrderByOrderAsc(sceneId): List<SceneAction>
    +existsBySceneIdAndOrder(sceneId, order): boolean
    +existsBySceneIdAndOrderAndIdNot(sceneId, order, id): boolean
    +countBySceneId(sceneId): long
    +deferOrderConstraint(): void
    +findById(id): Optional<SceneAction>
    +findAllById(ids): List<SceneAction>
    +save(entity): SceneAction
    +saveAndFlush(entity): SceneAction
    +saveAll(entities): List<SceneAction>
    +delete(entity): void
}
interface DeviceRepository <<repository>> {
    +findByHomeId(homeId): List<Device>
    +findById(id): Optional<Device>
    +findAllById(ids): List<Device>
}
interface AutomationRuleRepository <<repository>> {
    +existsActionForScene(sceneId): boolean
}
interface HomeRepository <<repository>> {
    +findById(id): Optional<Home>
}
interface HomeMemberRepository <<repository>> {
    +findByHomeIdAndUserId(homeId, userId): Optional<HomeMember>
}
SceneController ..> SceneService : dependency
SceneServiceImpl ..> HomeAuthorizationService : dependency
SceneServiceImpl ..> SceneRepository : dependency
SceneServiceImpl ..> SceneActionRepository : dependency
SceneServiceImpl ..> DeviceRepository : dependency
SceneServiceImpl ..> AutomationRuleRepository : dependency
SceneServiceImpl ..|> SceneService : realization / implementation
HomeAuthorizationService ..> HomeRepository : dependency
HomeAuthorizationService ..> HomeMemberRepository : dependency
@enduml
```

`SceneServiceImpl` gọi `AutomationRuleRepository.existsActionForScene` trước khi xóa; Scene đang được RuleAction sử dụng bị từ chối với `SCENE_IN_USE`. `HomeAuthorizationService` đọc Home và HomeMember qua hai repository, kiểm tra `ACTIVE` và thêm `OWNER` cho thao tác quản lý.

### 2.2. Scene entity và DTO

```plantuml
@startuml Scene_Domain_Classes
title Scene: entity và DTO
!pragma layout smetana
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

left to right direction
hide circle
skinparam classAttributeIconSize 0
class Home {

}
class Device {
    +supportsAction(action): boolean
}
class Scene {
    -id: UUID
    -home: Home
    -name: String
    -icon: String
    -description: String
    -enabled: boolean
    -actions: List<SceneAction>
    -schedules: List<SceneSchedule>
    -createdAt: OffsetDateTime
    -updatedAt: OffsetDateTime
}
class SceneAction {
    -id: UUID
    -scene: Scene
    -targetDevice: Device
    -action: String
    -value: JsonNode
    -order: int
    -createdAt: OffsetDateTime
    -updatedAt: OffsetDateTime
}
class SceneSchedule {
    -id: UUID
    -scene: Scene
    -scheduledTime: LocalTime
    -repeatDays: Short[]
    -active: boolean
    -createdAt: OffsetDateTime
}
class SceneExecution {
    -id: UUID
    -scene: Scene
    -triggerSource: String
    -status: ExecutionStatus
    -startedAt: OffsetDateTime
    -completedAt: OffsetDateTime
    -resultDetail: JsonNode
}
class CreateSceneRequest {
    -name: String
    -icon: String
    -description: String
    -enabled: Boolean
    -actions: List<SceneActionRequest>
}
class UpdateSceneRequest {
    -name: String
    -icon: String
    -description: String
    -enabled: Boolean
    -actions: List<SceneActionRequest>
}
class SceneActionRequest {
    -targetDeviceId: UUID
    -action: String
    -value: JsonNode
    -order: Integer
}
class ReorderSceneActionsRequest {

}
class SceneResponse {
    -id: UUID
    -homeId: UUID
    -name: String
    -icon: String
    -description: String
    -enabled: boolean
    -actions: List<SceneActionResponse>
    -createdAt: OffsetDateTime
    -updatedAt: OffsetDateTime
}
class SceneActionResponse {
    -id: UUID
    -targetDeviceId: UUID
    -targetDeviceName: String
    -action: String
    -value: JsonNode
    -order: int
    -createdAt: OffsetDateTime
    -updatedAt: OffsetDateTime
}
class SceneExecutionResponse {
    -id: UUID
    -sceneId: UUID
    -triggerSource: String
    -status: String
    -startedAt: OffsetDateTime
    -completedAt: OffsetDateTime
    -resultDetail: JsonNode
}
Home "1" -- "0..*" Scene : association
Scene "1" *-- "0..*" SceneAction : composition
Scene "1" *-- "0..*" SceneSchedule : composition
Scene "1" -- "0..*" SceneExecution : association
Device "1" -- "0..*" SceneAction : association (targetDevice)
CreateSceneRequest "1" o-- "0..*" SceneActionRequest : aggregation (actions)
UpdateSceneRequest "1" o-- "0..*" SceneActionRequest : aggregation (actions)
SceneResponse "1" o-- "0..*" SceneActionResponse : aggregation (actions)
@enduml
```

Scene sở hữu actions/schedules bằng `cascade=ALL`, `orphanRemoval=true`. `SceneExecution` tham chiếu Scene qua khóa ngoại; entity Scene không có collection executions. DTO Scene có `icon` và timestamps; lịch sử dùng `SceneExecutionResponse` riêng.

### 2.3. Thực thi Scene

```plantuml
@startuml Scene_Execution_Classes
title Scene execution: port gửi lệnh, override và lịch sử
!pragma layout smetana
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

left to right direction
hide circle
skinparam classAttributeIconSize 0
class SceneController {
    +executeScene(userDetails, homeId, sceneId): ResponseEntity<ApiResponse<SceneExecutionResponse>>
    +sceneExecutions(userDetails, homeId, sceneId): ResponseEntity<ApiResponse<List<SceneExecutionResponse>>>
}
interface SceneExecutionService {
    +execute(userId, homeId, sceneId): SceneExecutionResponse
    +executeScheduled(homeId, sceneId): SceneExecutionResponse
    +executeFromAutomation(homeId, sceneId): SceneExecutionResponse
    +history(userId, homeId, sceneId): List<SceneExecutionResponse>
}
class SceneExecutionServiceImpl {
    -homeAuthorizationService: HomeAuthorizationService
    -sceneRepository: SceneRepository
    -executionRepository: SceneExecutionRepository
    -deviceCommandService: DeviceCommandService
    -objectMapper: ObjectMapper
    -behaviorEventRecorder: BehaviorEventRecorder
    -manualOverrideService: ManualOverrideService
    +execute(userId, homeId, sceneId): SceneExecutionResponse
    +executeScheduled(homeId, sceneId): SceneExecutionResponse
    +executeFromAutomation(homeId, sceneId): SceneExecutionResponse
    +history(userId, homeId, sceneId): List<SceneExecutionResponse>
}
class HomeAuthorizationService {
    +requireAccess(userId, homeId): Home
}
interface SceneRepository <<repository>> {
    +findByIdAndHomeId(id, homeId): java.util.Optional<Scene>
}
interface SceneExecutionRepository <<repository>> {
    +findTop100BySceneIdOrderByStartedAtDesc(sceneId): List<SceneExecution>
    +findById(id): Optional<SceneExecution>
    +findAllById(ids): List<SceneExecution>
    +save(entity): SceneExecution
    +saveAndFlush(entity): SceneExecution
    +saveAll(entities): List<SceneExecution>
    +delete(entity): void
}
interface DeviceCommandService {
    +sendCommand(deviceId, action, Map<String, parameters, source): CompletableFuture<CommandResult>
    +sendRoomCommand(roomId, action, Map<String, parameters, source): CompletableFuture<java.util.List<CommandResult>>
}
class MqttDeviceCommandServiceImpl {
    +sendCommand(deviceId, action, Map<String, parameters, source): CompletableFuture<CommandResult>
}
class MockDeviceCommandServiceImpl {
    +sendCommand(deviceId, action, Map<String, parameters, source): CompletableFuture<CommandResult>
}
interface ManualOverrideService {
    +activate(device, userId, reason): OffsetDateTime
    +isActive(deviceId): boolean
}
class ManualOverrideServiceImpl {
    +isActive(deviceId): boolean
}
interface BehaviorEventRecorder {
    +recordCommand(device, action, source, result): void
    +recordSensor(device, event): void
    +recordExecution(home, userId, eventType, targetId, status): void
}
class BehaviorEventRecorderImpl {
    +recordCommand(device, action, source, result): void
    +recordExecution(home, userId, eventType, targetId, status): void
}
interface BehaviorEventRepository <<repository>> {
    +findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, eventType): java.util.Optional<BehaviorEvent>
}
interface DeviceRepository <<repository>> {
    +findById(id): Optional<Device>
    +save(entity): Device
}
interface DeviceStateHistoryRepository <<repository>> {
    +save(entity): DeviceStateHistory
}
interface UserRepository <<repository>> {
    +findById(id): Optional<User>
}
SceneController ..> SceneExecutionService : dependency
SceneExecutionServiceImpl ..> HomeAuthorizationService : dependency
SceneExecutionServiceImpl ..> SceneRepository : dependency
SceneExecutionServiceImpl ..> SceneExecutionRepository : dependency
SceneExecutionServiceImpl ..> DeviceCommandService : dependency
SceneExecutionServiceImpl ..> BehaviorEventRecorder : dependency
SceneExecutionServiceImpl ..> ManualOverrideService : dependency
SceneExecutionServiceImpl ..|> SceneExecutionService : realization / implementation
MqttDeviceCommandServiceImpl ..> DeviceRepository : dependency
MqttDeviceCommandServiceImpl ..|> DeviceCommandService : realization / implementation
MockDeviceCommandServiceImpl ..|> DeviceCommandService : realization / implementation
ManualOverrideServiceImpl ..> BehaviorEventRepository : dependency
ManualOverrideServiceImpl ..> UserRepository : dependency
ManualOverrideServiceImpl ..|> ManualOverrideService : realization / implementation
BehaviorEventRecorderImpl ..> BehaviorEventRepository : dependency
BehaviorEventRecorderImpl ..> DeviceStateHistoryRepository : dependency
BehaviorEventRecorderImpl ..> DeviceRepository : dependency
BehaviorEventRecorderImpl ..> UserRepository : dependency
BehaviorEventRecorderImpl ..|> BehaviorEventRecorder : realization / implementation
@enduml
```

`MqttDeviceCommandServiceImpl` là bean `@Primary` hiện tại của `DeviceCommandService`; Mock là implementation phục vụ kiểm thử/phát triển. Controller gọi port service; runtime chỉ gọi implementation được inject, không có lời gọi trung gian qua một đối tượng interface riêng.

### 2.4. Lập lịch dùng chung với Automation

```plantuml
@startuml Shared_Scheduling_Classes
title ScheduleService và ScheduleRunner dùng chung cho Scene / Rule
!pragma layout smetana
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

left to right direction
hide circle
skinparam classAttributeIconSize 0
class SceneController {
    +schedules(userDetails, homeId, sceneId): ResponseEntity<ApiResponse<List<ScheduleResponse>>>
    +createSchedule(userDetails, homeId, sceneId, request): ResponseEntity<ApiResponse<ScheduleResponse>>
    +updateSchedule(userDetails, homeId, sceneId, scheduleId, request): ResponseEntity<ApiResponse<ScheduleResponse>>
    +deleteSchedule(userDetails, homeId, sceneId, scheduleId): ResponseEntity<ApiResponse<Void>>
}
class AutomationRuleController {
    +schedules(user, homeId, ruleId): ResponseEntity<ApiResponse<List<ScheduleResponse>>>
    +createSchedule(user, homeId, ruleId, request): ResponseEntity<ApiResponse<ScheduleResponse>>
    +updateSchedule(user, homeId, ruleId, scheduleId, request): ResponseEntity<ApiResponse<ScheduleResponse>>
    +deleteSchedule(user, homeId, ruleId, scheduleId): ResponseEntity<ApiResponse<Void>>
}
interface ScheduleService {
    +sceneSchedules(userId, homeId, sceneId): List<ScheduleResponse>
    +saveSceneSchedule(userId, homeId, sceneId, scheduleId, request): ScheduleResponse
    +deleteSceneSchedule(userId, homeId, sceneId, scheduleId): void
    +ruleSchedules(userId, homeId, ruleId): List<ScheduleResponse>
    +saveRuleSchedule(userId, homeId, ruleId, scheduleId, request): ScheduleResponse
    +deleteRuleSchedule(userId, homeId, ruleId, scheduleId): void
}
class ScheduleServiceImpl {
    -authorization: HomeAuthorizationService
    -scenes: SceneRepository
    -sceneSchedules: SceneScheduleRepository
    -rules: AutomationRuleRepository
    -ruleSchedules: AutomationScheduleRepository
    -zoneName: String
    +sceneSchedules(userId, homeId, sceneId): List<ScheduleResponse>
    +saveSceneSchedule(userId, homeId, sceneId, scheduleId, request): ScheduleResponse
    +deleteSceneSchedule(userId, homeId, sceneId, scheduleId): void
    +ruleSchedules(userId, homeId, ruleId): List<ScheduleResponse>
    +saveRuleSchedule(userId, homeId, ruleId, scheduleId, request): ScheduleResponse
    +deleteRuleSchedule(userId, homeId, ruleId, scheduleId): void
}
class ScheduleRunner {
    -sceneSchedules: SceneScheduleRepository
    -ruleSchedules: AutomationScheduleRepository
    -sceneExecutionService: SceneExecutionService
    -automationEngine: AutomationEngine
    -jdbcTemplate: JdbcTemplate
    -zoneName: String
    +runDueSchedules(): void
    +runDueSchedules(now): void
}
class HomeAuthorizationService {
    +requireAccess(userId, homeId): Home
    +requireSceneManagement(userId, homeId): Home
}
interface SceneRepository <<repository>> {
    +findByIdAndHomeId(id, homeId): java.util.Optional<Scene>
}
interface AutomationRuleRepository <<repository>> {
    +findByIdAndHomeId(id, homeId): Optional<AutomationRule>
}
interface SceneScheduleRepository <<repository>> {
    +findAllBySceneIdOrderByScheduledTimeAsc(sceneId): List<SceneSchedule>
    +findAllByActiveTrueAndScheduledTime(scheduledTime): List<SceneSchedule>
    +findById(id): Optional<SceneSchedule>
    +findAllById(ids): List<SceneSchedule>
    +save(entity): SceneSchedule
    +saveAndFlush(entity): SceneSchedule
    +saveAll(entities): List<SceneSchedule>
    +delete(entity): void
}
interface AutomationScheduleRepository <<repository>> {
    +findAllByRuleIdOrderByScheduledTimeAsc(ruleId): List<AutomationSchedule>
    +findAllByActiveTrueAndScheduledTime(scheduledTime): List<AutomationSchedule>
    +findById(id): Optional<AutomationSchedule>
    +findAllById(ids): List<AutomationSchedule>
    +save(entity): AutomationSchedule
    +saveAndFlush(entity): AutomationSchedule
    +saveAll(entities): List<AutomationSchedule>
    +delete(entity): void
}
interface SceneExecutionService {
    +executeScheduled(homeId, sceneId): SceneExecutionResponse
}
interface AutomationEngine {
    +executeScheduled(homeId, ruleId): AutomationExecutionResponse
}
class ScheduleRequest {
    -scheduledTime: LocalTime
    -repeatDays: List<Short>
    -active: Boolean
}
class ScheduleResponse {
    -id: UUID
    -scheduledTime: LocalTime
    -repeatDays: List<Short>
    -active: boolean
    -nextRunAt: OffsetDateTime
}
SceneController ..> SceneExecutionService : dependency
SceneController ..> ScheduleService : dependency
AutomationRuleController ..> AutomationEngine : dependency
AutomationRuleController ..> HomeAuthorizationService : dependency
AutomationRuleController ..> ScheduleService : dependency
ScheduleServiceImpl ..> HomeAuthorizationService : dependency
ScheduleServiceImpl ..> SceneRepository : dependency
ScheduleServiceImpl ..> SceneScheduleRepository : dependency
ScheduleServiceImpl ..> AutomationRuleRepository : dependency
ScheduleServiceImpl ..> AutomationScheduleRepository : dependency
ScheduleServiceImpl ..|> ScheduleService : realization / implementation
ScheduleRunner ..> SceneScheduleRepository : dependency
ScheduleRunner ..> AutomationScheduleRepository : dependency
ScheduleRunner ..> SceneExecutionService : dependency
ScheduleRunner ..> AutomationEngine : dependency
class JdbcTemplate {
    +update(sql, args): int
}
ScheduleRunner ..> JdbcTemplate : dependency (claim)
ScheduleService ..> ScheduleRequest : dependency
ScheduleService ..> ScheduleResponse : dependency
@enduml
```

## 3. Sequence diagrams

Các đường dẫn rút gọn trong sequence đều nằm dưới `/api/v1/homes/{homeId}`.

### 3.1. Tạo Scene

```plantuml
@startuml Scene_Create
title Tạo Scene
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneServiceImpl" as Service
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "DeviceRepository" as DeviceRepo
database "Database" as DB
Client -> Controller: POST /api/v1/homes/{homeId}/scenes
activate Controller
Controller -> Service: createScene(userId, homeId, request)
activate Service
Service -> Auth: requireSceneManagement(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE và OWNER
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Service: Home đã xác thực
deactivate Auth
Service -> Service: validateSceneRequest; chuẩn hóa name/icon/description
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> SceneRepo: existsByHomeIdAndName(homeId, name)
activate SceneRepo
SceneRepo -> DB: EXISTS scenes theo Home + name
activate DB
DB --> SceneRepo: false (tên chưa tồn tại)
deactivate DB
SceneRepo --> Service: false (tên chưa tồn tại)
deactivate SceneRepo
Service -> Service: validateActionOrders(actionsOrEmpty(request.actions))
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> DeviceRepo: findAllById(targetDeviceIds)
activate DeviceRepo
DeviceRepo -> DB: SELECT Devices
activate DB
DB --> DeviceRepo: Devices
deactivate DB
DeviceRepo --> Service: Devices
deactivate DeviceRepo
Service -> Service: buildActions: cùng Home, capabilities, action/value\nChuẩn hóa null value thành {} khi lưu
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> SceneRepo: saveAndFlush(scene)
activate SceneRepo
SceneRepo -> DB: INSERT scenes + scene_actions (cascade)
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
Service --> Controller: SceneResponse (TURN_ON/TURN_OFF trả value=null)
deactivate Service
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
@enduml
```

### 3.2. Xem Scene và các action type

```plantuml
@startuml Scene_Read
title Xem danh sách / chi tiết / action types
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneServiceImpl" as Service
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "DeviceRepository" as DeviceRepo
database "Database" as DB
Client -> Controller: GET /scenes [/{sceneId} hoặc /action-types]
activate Controller
Controller -> Service: getScenesForHome / getScene / getActionTypes(userId, homeId, ...)
activate Service
Service -> Auth: requireAccess(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Service: Home đã xác thực
deactivate Auth
alt Danh sách Scene
Service -> SceneRepo: findAllByHomeIdOrderByNameAsc(homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scenes + actions + target devices
activate DB
DB --> SceneRepo: List<Scene>
deactivate DB
SceneRepo --> Service: List<Scene>
deactivate SceneRepo
else Chi tiết Scene
Service -> SceneRepo: findByIdAndHomeId(sceneId, homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scene + actions + target devices
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
else Action types
Service -> DeviceRepo: findByHomeId(homeId)
activate DeviceRepo
DeviceRepo -> DB: SELECT Devices của Home
activate DB
DB --> DeviceRepo: Devices
deactivate DB
DeviceRepo --> Service: Devices
deactivate DeviceRepo
Service -> Service: Lấy capabilities.values; distinct; sorted
activate Service
Service --> Service: Hoàn tất
deactivate Service
end
Service --> Controller: SceneResponse / List<SceneResponse> / List<String>
deactivate Service
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
@enduml
```

### 3.3. Sửa Scene và bật/tắt bằng PUT

```plantuml
@startuml Scene_Update
title Cập nhật Scene
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneServiceImpl" as Service
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "DeviceRepository" as DeviceRepo
participant "SceneActionRepository" as ActionRepo
database "Database" as DB
Client -> Controller: PUT /scenes/{sceneId}
activate Controller
Controller -> Service: updateScene(userId, homeId, sceneId, request)
activate Service
Service -> Auth: requireSceneManagement(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE và OWNER
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Service: Home đã xác thực
deactivate Auth
Service -> SceneRepo: findByIdAndHomeId(sceneId, homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scene + actions + target devices
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
Service -> Service: validateSceneRequest; chuẩn hóa name/icon/description
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> SceneRepo: existsByHomeIdAndNameAndIdNot(homeId, name, sceneId)
activate SceneRepo
SceneRepo -> DB: EXISTS tên của Scene khác trong Home
activate DB
DB --> SceneRepo: false
deactivate DB
SceneRepo --> Service: false
deactivate SceneRepo
opt request.actions != null
Service -> Service: validateActionOrders(request.actions)
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> DeviceRepo: findAllById(targetDeviceIds)
activate DeviceRepo
DeviceRepo -> DB: SELECT Devices
activate DB
DB --> DeviceRepo: Devices
deactivate DB
DeviceRepo --> Service: Devices
deactivate DeviceRepo
Service -> Service: buildActions: xác thực Home, capabilities, action/value
activate Service
Service --> Service: Hoàn tất
deactivate Service
opt oldActions.size + replacementActions.size > 1
Service -> ActionRepo: deferOrderConstraint()
activate ActionRepo
ActionRepo -> DB: SET CONSTRAINTS uq_scene_actions_scene_order DEFERRED
activate DB
DB --> ActionRepo: Constraint deferred
deactivate DB
ActionRepo --> Service: Constraint deferred
deactivate ActionRepo
end
Service -> Service: clear actions; addAll replacementActions (orphanRemoval)
activate Service
Service --> Service: Hoàn tất
deactivate Service
end
Service -> Service: Cập nhật name, icon, description, enabled
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> SceneRepo: saveAndFlush(scene)
activate SceneRepo
SceneRepo -> DB: UPDATE scenes; thay children nếu có trong request
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
note over Service
actions=null: giữ nguyên actions.
actions=[]: xóa toàn bộ actions.
Toàn bộ cập nhật thuộc cùng transaction.
end note
Service --> Controller: SceneResponse
deactivate Service
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
@enduml
```

### 3.4. Xóa Scene và kiểm tra Scene đang được sử dụng

```plantuml
@startuml Scene_Delete
title Xóa Scene
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneServiceImpl" as Service
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "AutomationRuleRepository" as RuleRepo
database "Database" as DB
Client -> Controller: DELETE /scenes/{sceneId}
activate Controller
Controller -> Service: deleteScene(userId, homeId, sceneId)
activate Service
Service -> Auth: requireSceneManagement(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE và OWNER
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Service: Home đã xác thực
deactivate Auth
Service -> SceneRepo: findByIdAndHomeId(sceneId, homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scene + actions + target devices
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
Service -> RuleRepo: existsActionForScene(sceneId)
activate RuleRepo
RuleRepo -> DB: EXISTS rule_actions.scene_id
activate DB
DB --> RuleRepo: true / false
deactivate DB
RuleRepo --> Service: true / false
deactivate RuleRepo
alt Scene đang được RuleAction tham chiếu
Service -> Service: Ném AppException(SCENE_IN_USE); không xóa
activate Service
Service --> Service: Hoàn tất
deactivate Service
else Scene chưa được tham chiếu
Service -> SceneRepo: delete(scene)
activate SceneRepo
SceneRepo -> DB: DELETE Scene; cascade actions/schedules/executions
activate DB
DB --> SceneRepo: Đã xóa
deactivate DB
SceneRepo --> Service: Đã xóa
deactivate SceneRepo
end
Service --> Controller: void hoặc AppException(SCENE_IN_USE)
deactivate Service
Controller --> Client: ApiResponse(code=1000) hoặc error response qua GlobalExceptionHandler
deactivate Controller
@enduml
```

### 3.5. Quản lý thứ tự SceneAction

```plantuml
@startuml Scene_Actions
title Thêm / xóa / sắp xếp SceneAction
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneServiceImpl" as Service
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "DeviceRepository" as DeviceRepo
participant "SceneActionRepository" as ActionRepo
database "Database" as DB
Client -> Controller: POST /actions, DELETE /actions/{actionId}, PUT /actions/reorder
activate Controller
Controller -> Service: addAction / removeAction / reorderActions(userId, homeId, sceneId, ...)
activate Service
Service -> Auth: requireSceneManagement(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE và OWNER
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Service: Home đã xác thực
deactivate Auth
Service -> SceneRepo: findByIdAndHomeId(sceneId, homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scene + actions + target devices
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
alt Thêm action
Service -> Service: Kiểm tra order từ 0 đến actions.size
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> DeviceRepo: findById(targetDeviceId)
activate DeviceRepo
DeviceRepo -> DB: SELECT Device
activate DB
DB --> DeviceRepo: Device
deactivate DB
DeviceRepo --> Service: Device
deactivate DeviceRepo
Service -> Service: buildAction: cùng Home, capabilities, action/value
activate Service
Service --> Service: Hoàn tất
deactivate Service
else Xóa action
Service -> Service: Tìm actionId thuộc Scene hoặc SCENE_ACTION_NOT_FOUND
activate Service
Service --> Service: Hoàn tất
deactivate Service
else Sắp xếp
Service -> Service: validateReorderPayload: đủ ID, không trùng, đúng tập ID
activate Service
Service --> Service: Hoàn tất
deactivate Service
end
opt Số action cần xử lý > 1
Service -> ActionRepo: deferOrderConstraint()
activate ActionRepo
ActionRepo -> DB: SET CONSTRAINTS uq_scene_actions_scene_order DEFERRED
activate DB
DB --> ActionRepo: Constraint deferred
deactivate DB
ActionRepo --> Service: Constraint deferred
deactivate ActionRepo
end
Service -> Service: Thêm và dịch order / xóa và dồn order / gán order mới
activate Service
Service --> Service: Hoàn tất
deactivate Service
Service -> SceneRepo: saveAndFlush(scene)
activate SceneRepo
SceneRepo -> DB: INSERT/UPDATE/DELETE scene_actions
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Service: Scene
deactivate SceneRepo
Service --> Controller: SceneActionResponse / void / SceneResponse
deactivate Service
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
@enduml
```

### 3.6. Chạy Scene (API đã triển khai)

```plantuml
@startuml Scene_Execute
title Chạy Scene thủ công
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneExecutionServiceImpl" as Execution
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "MqttDeviceCommandServiceImpl" as Command
participant "SceneExecutionRepository" as ExecutionRepo
participant "UserRepository" as UserRepo
participant "BehaviorEventRecorderImpl" as Recorder
participant "DeviceRepository" as DeviceRepo
participant "DeviceStateHistoryRepository" as HistoryRepo
participant "BehaviorEventRepository" as EventRepo
database "Database" as DB
Client -> Controller: POST /scenes/{sceneId}/execute
activate Controller
Controller -> Execution: execute(userId, homeId, sceneId)
activate Execution
Execution -> Auth: requireAccess(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Execution: Home đã xác thực
deactivate Auth
Execution -> SceneRepo: findByIdAndHomeId(sceneId, homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scene + actions + target devices
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Execution: Scene
deactivate SceneRepo
Execution -> Execution: run(scene, MANUAL, SCENE)\nKiểm tra enabled; sắp actions theo order
activate Execution
Execution --> Execution: Hoàn tất
deactivate Execution
loop Mỗi SceneAction theo order
Execution -> Command: sendCommand(deviceId, action, parameters, SCENE)
activate Command
Command --> Execution: CompletableFuture<CommandResult>
deactivate Command
Execution -> Execution: future.join() - chờ ACK / telemetry / timeout
activate Execution
Execution --> Execution: CommandResult hoặc RuntimeException
deactivate Execution
opt CommandResult.success = true
Execution -> Recorder: recordCommand(device, action, SCENE, result)
activate Recorder
Recorder -> DeviceRepo: findById(deviceId)
activate DeviceRepo
DeviceRepo -> DB: SELECT Device hiện tại
activate DB
DB --> DeviceRepo: Device
deactivate DB
DeviceRepo --> Recorder: Device
deactivate DeviceRepo
Recorder -> HistoryRepo: save(DeviceStateHistory)
activate HistoryRepo
HistoryRepo -> DB: INSERT trạng thái trước/sau và source
activate DB
DB --> HistoryRepo: DeviceStateHistory
deactivate DB
HistoryRepo --> Recorder: DeviceStateHistory
deactivate HistoryRepo
Recorder -> EventRepo: save(BehaviorEvent)
activate EventRepo
EventRepo -> DB: INSERT DEVICE_ACTION
activate DB
DB --> EventRepo: BehaviorEvent
deactivate DB
EventRepo --> Recorder: BehaviorEvent
deactivate EventRepo
Recorder -> DeviceRepo: save(recordedDevice)
activate DeviceRepo
DeviceRepo -> DB: UPDATE current_state theo acknowledgedState
activate DB
DB --> DeviceRepo: Device
deactivate DB
DeviceRepo --> Recorder: Device
deactivate DeviceRepo
Recorder --> Execution: Đã ghi nhận (REQUIRES_NEW) hoặc lỗi ghi nhận
deactivate Recorder
end
Execution -> Execution: Ghi chi tiết thành công/lỗi; tiếp tục action kế tiếp
activate Execution
Execution --> Execution: Hoàn tất
deactivate Execution
end
Execution -> Execution: Tính SUCCESS / PARTIAL / FAILED / SKIPPED
activate Execution
Execution --> Execution: Hoàn tất
deactivate Execution
Execution -> ExecutionRepo: save(SceneExecution)
activate ExecutionRepo
ExecutionRepo -> DB: INSERT scene_executions + result_detail
activate DB
DB --> ExecutionRepo: SceneExecution
deactivate DB
ExecutionRepo --> Execution: SceneExecution
deactivate ExecutionRepo
Execution -> Recorder: recordExecution(home, userId, SCENE_EXECUTION, sceneId, status)
activate Recorder
Recorder -> UserRepo: findById(userId)
activate UserRepo
UserRepo -> DB: SELECT User
activate DB
DB --> UserRepo: User hoặc null
deactivate DB
UserRepo --> Recorder: User hoặc null
deactivate UserRepo
Recorder -> EventRepo: save(BehaviorEvent)
activate EventRepo
EventRepo -> DB: INSERT SCENE_EXECUTION
activate DB
DB --> EventRepo: BehaviorEvent
deactivate DB
EventRepo --> Recorder: BehaviorEvent
deactivate EventRepo
opt User được tìm thấy
Recorder -> EventRepo: save(BehaviorEvent)
activate EventRepo
EventRepo -> DB: INSERT USER_INTERACTION / EXECUTE_SCENE
activate DB
DB --> EventRepo: BehaviorEvent
deactivate DB
EventRepo --> Recorder: BehaviorEvent
deactivate EventRepo
end
Recorder --> Execution: Đã ghi nhận hoặc lỗi ghi nhận (không đổi execution result)
deactivate Recorder
note over Execution
Scene disabled: SCENE_DISABLED, không gửi lệnh.
MANUAL bỏ qua kiểm tra manual override.
Không có action được thử: SKIPPED.
Lỗi command được lưu vào resultDetail; vòng lặp tiếp tục.
end note
Execution --> Controller: SceneExecutionResponse
deactivate Execution
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
@enduml
```

### 3.7. Xem lịch sử thực thi Scene

```plantuml
@startuml Scene_History
title Lịch sử chạy Scene
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "SceneExecutionServiceImpl" as Execution
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as SceneRepo
participant "SceneExecutionRepository" as ExecutionRepo
database "Database" as DB
Client -> Controller: GET /scenes/{sceneId}/executions
activate Controller
Controller -> Execution: history(userId, homeId, sceneId)
activate Execution
Execution -> Auth: requireAccess(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Execution: Home đã xác thực
deactivate Auth
Execution -> SceneRepo: findByIdAndHomeId(sceneId, homeId)
activate SceneRepo
SceneRepo -> DB: SELECT Scene + actions + target devices
activate DB
DB --> SceneRepo: Scene
deactivate DB
SceneRepo --> Execution: Scene
deactivate SceneRepo
Execution -> ExecutionRepo: findTop100BySceneIdOrderByStartedAtDesc(sceneId)
activate ExecutionRepo
ExecutionRepo -> DB: SELECT 100 scene_executions mới nhất
activate DB
DB --> ExecutionRepo: List<SceneExecution>
deactivate DB
ExecutionRepo --> Execution: List<SceneExecution>
deactivate ExecutionRepo
Execution --> Controller: List<SceneExecutionResponse>
deactivate Execution
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
@enduml
```

### 3.8. Quản lý lịch Scene

```plantuml
@startuml Scene_Schedule_Management
title Xem / tạo / sửa / xóa lịch Scene
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
actor "Người dùng" as Client
participant "SceneController" as Controller
participant "ScheduleServiceImpl" as Schedule
participant "HomeAuthorizationService" as Auth
participant "HomeRepository" as HomeRepo
participant "HomeMemberRepository" as MemberRepo
participant "SceneRepository" as TargetRepo
participant "SceneScheduleRepository" as ScheduleRepo
database "Database" as DB
Client -> Controller: GET/POST /scenes/{sceneId}/schedules\nPUT/DELETE /schedules/{scheduleId}
activate Controller
Controller -> Schedule: sceneSchedules / saveSceneSchedule / deleteSceneSchedule(...)
activate Schedule
alt GET danh sách
Schedule -> Auth: requireAccess(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Schedule: Home đã xác thực
deactivate Auth
else POST / PUT / DELETE
Schedule -> Auth: requireSceneManagement(userId, homeId)
activate Auth
Auth -> HomeRepo: findById(homeId)
activate HomeRepo
HomeRepo -> DB: SELECT Home
activate DB
DB --> HomeRepo: Home
deactivate DB
HomeRepo --> Auth: Home
deactivate HomeRepo
Auth -> MemberRepo: findByHomeIdAndUserId(homeId, userId)
activate MemberRepo
MemberRepo -> DB: SELECT HomeMember
activate DB
DB --> MemberRepo: HomeMember
deactivate DB
MemberRepo --> Auth: HomeMember
deactivate MemberRepo
Auth -> Auth: Kiểm tra ACTIVE và OWNER
activate Auth
Auth --> Auth: Hoàn tất
deactivate Auth
Auth --> Schedule: Home đã xác thực
deactivate Auth
end
Schedule -> TargetRepo: findByIdAndHomeId(sceneId, homeId)
activate TargetRepo
TargetRepo -> DB: SELECT Scene thuộc Home
activate DB
DB --> TargetRepo: Scene
deactivate DB
TargetRepo --> Schedule: Scene
deactivate TargetRepo
alt POST / PUT
Schedule -> Schedule: validate(request): time, active; repeatDays duy nhất trong 1..7
activate Schedule
Schedule --> Schedule: Hoàn tất
deactivate Schedule
opt PUT với scheduleId
Schedule -> ScheduleRepo: findById(scheduleId)
activate ScheduleRepo
ScheduleRepo -> DB: SELECT schedule
activate DB
DB --> ScheduleRepo: Schedule
deactivate DB
ScheduleRepo --> Schedule: Schedule
deactivate ScheduleRepo
Schedule -> Schedule: Kiểm tra schedule.scene.id = sceneId
activate Schedule
Schedule --> Schedule: Hoàn tất
deactivate Schedule
end
Schedule -> Schedule: Gán time.withSecond(0).withNano(0), repeatDays, active
activate Schedule
Schedule --> Schedule: Hoàn tất
deactivate Schedule
Schedule -> ScheduleRepo: save(schedule)
activate ScheduleRepo
ScheduleRepo -> DB: INSERT/UPDATE schedule
activate DB
DB --> ScheduleRepo: Schedule
deactivate DB
ScheduleRepo --> Schedule: Schedule
deactivate ScheduleRepo
Schedule -> Schedule: Tính nextRunAt theo hesta.scheduler.zone nếu active
activate Schedule
Schedule --> Schedule: Hoàn tất
deactivate Schedule
else GET
Schedule -> ScheduleRepo: findAllBySceneIdOrderByScheduledTimeAsc(sceneId)
activate ScheduleRepo
ScheduleRepo -> DB: SELECT schedules ORDER BY scheduled_time
activate DB
DB --> ScheduleRepo: Schedules
deactivate DB
ScheduleRepo --> Schedule: Schedules
deactivate ScheduleRepo
Schedule -> Schedule: Tạo response và nextRunAt
activate Schedule
Schedule --> Schedule: Hoàn tất
deactivate Schedule
else DELETE
Schedule -> ScheduleRepo: findById(scheduleId)
activate ScheduleRepo
ScheduleRepo -> DB: SELECT schedule
activate DB
DB --> ScheduleRepo: Schedule
deactivate DB
ScheduleRepo --> Schedule: Schedule
deactivate ScheduleRepo
Schedule -> Schedule: Kiểm tra schedule.scene.id = sceneId
activate Schedule
Schedule --> Schedule: Hoàn tất
deactivate Schedule
Schedule -> ScheduleRepo: delete(schedule)
activate ScheduleRepo
ScheduleRepo -> DB: DELETE schedule
activate DB
DB --> ScheduleRepo: Đã xóa
deactivate DB
ScheduleRepo --> Schedule: Đã xóa
deactivate ScheduleRepo
end
Schedule --> Controller: ScheduleResponse / List<ScheduleResponse> / void
deactivate Schedule
Controller --> Client: ApiResponse(code=1000, result)
deactivate Controller
note over Schedule
repeatDays=[]: chạy mỗi ngày, không phải lịch một lần.
1=thứ Hai, 7=Chủ nhật.
nextRunAt chỉ tính để trả DTO, không lưu trong entity.
end note
@enduml
```

### 3.9. Scheduler dùng chung cho Scene và Automation

```plantuml
@startuml Shared_Schedule_Dispatch
title ScheduleRunner: chạy lịch Scene và Rule đến hạn
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial

autonumber
participant "Spring Scheduler" as Clock
participant "ScheduleRunner" as Runner
participant "SceneScheduleRepository" as SceneScheduleRepo
participant "AutomationScheduleRepository" as RuleScheduleRepo
participant "JdbcTemplate" as Jdbc
participant "SceneExecutionServiceImpl" as Execution
participant "AutomationEngineImpl" as Engine
database "Database" as DB
Clock -> Runner: runDueSchedules() - mỗi phút theo hesta.scheduler.zone
activate Runner
Runner -> Runner: Tính time (bỏ giây/nano), localDate và day 1..7
activate Runner
Runner --> Runner: Hoàn tất
deactivate Runner
Runner -> SceneScheduleRepo: findAllByActiveTrueAndScheduledTime(time)
activate SceneScheduleRepo
SceneScheduleRepo -> DB: SELECT active SCENE schedules cùng phút
activate DB
DB --> SceneScheduleRepo: Schedules
deactivate DB
SceneScheduleRepo --> Runner: Schedules
deactivate SceneScheduleRepo
loop Mỗi lịch SCENE
opt Scene/Rule enabled và khớp repeatDays (rỗng = mọi ngày)
Runner -> Jdbc: update(INSERT claim, SCENE, scheduleId, localDate)
activate Jdbc
Jdbc -> DB: INSERT scheduled_run_claims\nON CONFLICT DO NOTHING
activate DB
DB --> Jdbc: rowsInserted = 1 hoặc 0
deactivate DB
Jdbc --> Runner: rowsInserted
deactivate Jdbc
opt rowsInserted = 1 (đã claim lịch)
Runner -> Execution: executeScheduled(homeId, sceneId)
activate Execution
Execution --> Runner: ExecutionResponse hoặc RuntimeException
deactivate Execution
note over Execution
Thực thi actions, kiểm tra override và ghi lịch sử.
Chi tiết ở sequence thực thi tương ứng.
end note
end
end
end
Runner -> RuleScheduleRepo: findAllByActiveTrueAndScheduledTime(time)
activate RuleScheduleRepo
RuleScheduleRepo -> DB: SELECT active RULE schedules cùng phút
activate DB
DB --> RuleScheduleRepo: Schedules
deactivate DB
RuleScheduleRepo --> Runner: Schedules
deactivate RuleScheduleRepo
loop Mỗi lịch RULE
opt Scene/Rule enabled và khớp repeatDays (rỗng = mọi ngày)
Runner -> Jdbc: update(INSERT claim, RULE, scheduleId, localDate)
activate Jdbc
Jdbc -> DB: INSERT scheduled_run_claims\nON CONFLICT DO NOTHING
activate DB
DB --> Jdbc: rowsInserted = 1 hoặc 0
deactivate DB
Jdbc --> Runner: rowsInserted
deactivate Jdbc
opt rowsInserted = 1 (đã claim lịch)
Runner -> Engine: executeScheduled(homeId, ruleId)
activate Engine
Engine --> Runner: ExecutionResponse hoặc RuntimeException
deactivate Engine
note over Engine
Thực thi actions, kiểm tra override và ghi lịch sử.
Chi tiết ở sequence thực thi tương ứng.
end note
end
end
end
note over Runner
RuntimeException được log; tiếp tục lịch còn lại.
Claim được ghi trước thực thi; lỗi không tự retry trong cùng ngày.
Khóa (schedule_type, schedule_id, local_date) ngăn chạy trùng.
end note
Runner --> Clock: Hoàn tất lượt quét
deactivate Runner
@enduml
```

## 4. Quy tắc nghiệp vụ và persistence

- `name` bắt buộc, tối đa 150 ký tự, trim và duy nhất trong Home; `description` tối đa 2000 ký tự; `icon` tối đa 50 ký tự; `enabled` bắt buộc.
- Tạo Scene cho phép actions rỗng. Sửa với `actions=null` giữ nguyên actions; gửi danh sách sẽ thay toàn bộ trong cùng transaction.
- Orders duy nhất và liên tục `0..n-1`; thêm/xóa tự dịch order. Reorder phải gửi đúng toàn bộ tập action ID, không thiếu và không trùng.
- Device phải tồn tại, có Room/Home và cùng Home với Scene. Action được trim/uppercase và kiểm tra `Device.supportsAction` theo capabilities hiện tại.
- `TURN_ON`/`TURN_OFF` nhận value null; `SET_BRIGHTNESS`/`SET_SPEED` nhận số nguyên 0–100; `SET_TEMPERATURE` nhận số; `SET_STATE` nhận object không rỗng. Các action khác có thể được chấp nhận nếu Device hỗ trợ theo source validation hiện tại.
- Service lưu null value thành `{}` để tương thích schema legacy; DTO của `TURN_ON`/`TURN_OFF` vẫn trả null. Thực thi ánh xạ brightness → `level`, speed → `speed`, temperature → `temperature`, SET_STATE → map; action còn lại dùng map rỗng theo `parameters()` hiện tại.
- `execute` dùng trigger `MANUAL`, source `SCENE`; `executeScheduled` dùng `SCHEDULE`/`SCHEDULE`; `executeFromAutomation` dùng `AUTOMATION`/`AUTOMATION`.
- Scene disabled bị từ chối. Scene thủ công không kiểm tra override; Scene do lịch/Automation gọi bỏ action có override 30 phút với `SKIPPED_OVERRIDE`.
- Status: không có action được thử → `SKIPPED`; tất cả actions thành công → `SUCCESS`; có thử nhưng không thành công → `FAILED`; còn lại → `PARTIAL`. Lỗi command không dừng các action sau.
- `BehaviorEventRecorderImpl` ghi command/state history và execution event bằng transaction `REQUIRES_NEW`. Lỗi ghi command/execution được service log và không đổi kết quả execution.
- `repeatDays` là danh sách duy nhất trong 1–7; rỗng nghĩa là mỗi ngày. `scheduledTime` được bỏ giây/nano. `nextRunAt` chỉ tính trong DTO.
- `ScheduleRunner` quét mỗi phút, mặc định zone `Asia/Ho_Chi_Minh`, có thể tắt bằng `hesta.scheduler.enabled=false`. Runner kiểm tra active/time/day/enabled, claim lịch trước thực thi, và tiếp tục nếu một lịch lỗi.
- Claim dùng khóa `(schedule_type, schedule_id, local_date)` trong `scheduled_run_claims`; đã claim thì không tự chạy lại cùng ngày dù lần chạy lỗi.

Migration liên quan: `20260911160000_scene_management_foundation.sql`, `20260911170000_scene_crud_management.sql`, `20260920120000_repair_scene_action_target_state_nullability.sql`, `20260922160000_scene_execution_history.sql`, `20260922161000_scheduled_run_claims.sql`. Trạng thái migration trên database đích được kiểm tra theo [DATABASE_MIGRATION_GUIDE.md](../DATABASE_MIGRATION_GUIDE.md); tài liệu mô tả source code, không xác nhận deployment.

## 5. Đồng bộ frontend và kiểm chứng

`frontend_hesta/src/services/sceneApi.ts` gọi `/execute` và `/executions`; Scene UI có tạo/sửa/xóa/bật tắt, chạy Scene, xem lịch sử và quản lý lịch. API lịch được dùng qua SchedulePanel. Route danh sách: `/homes/:homeId/scenes`; route sửa: `/homes/:homeId/scenes/:sceneId`.

Nguồn đối chiếu: `SceneController`, `SceneServiceImpl`, `SceneExecutionServiceImpl`, `ScheduleServiceImpl`, `ScheduleRunner`, entities/DTO/repositories và các migration nêu trên. Các test hiện có nằm trong `SceneControllerTest`, `SceneServiceTest`, `SceneExecutionServiceTest`, `ScheduleServiceTest`, `ScheduleRunnerTest` và các test frontend Scene. Lần cập nhật tài liệu này chỉ kiểm tra sơ đồ và đối chiếu source, không công bố lại kết quả chạy test ứng dụng.
