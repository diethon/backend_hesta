# Mock-only sensor pipeline

This development adapter proves the existing Twin/realtime contract before
hardware ingestion exists. It is not a finalized hardware protocol or production
ingestion API. There is no MQTT, device provisioning, sensor registry, new table,
or frontend work in the mock pipeline. The separate [Twin health feature](TWIN_HEALTH.md)
now derives freshness from its committed observations without redesigning ingestion.

## Entry point and access

`POST /api/v1/dev/sensors/mock-reading` is registered only when BOTH conditions hold:

- Spring profile `mock-sensors` is active, and neither `prod` nor `production` is active.
- `app.mock-sensors.enabled=true` (environment variable `APP_MOCK_SENSORS_ENABLED=true`).

It is disabled by default. Do not enable it on a production deployment. The
existing stateless Bearer JWT filter is unchanged. The service requires an ACTIVE
HomeMember for the device's home, using the authenticated `CustomUserDetails` ID.
An ADMIN platform role does not bypass that membership check. The caller cannot
supply the authoritative home ID, room ID, sensor ID, or user identity.

## Mock input and validation

`MockSensorReadingRequest` contains `deviceId`, `metricType`, `value`, `unit`, and
`observedAt`. The thin controller maps it to source-independent `SensorReadingInput`.
`SensorReadingIngestionServiceImpl` validates that input for every caller, including
direct service calls. Validation is centralized in the service rather than being
limited to the HTTP adapter. Malformed JSON, nonnumeric values, and unparseable
offset timestamps are rejected by Spring MVC using the existing `ApiResponse`
error shape.

| Field | Rule |
| --- | --- |
| deviceId | Required UUID; device must exist and not be soft-deleted |
| metricType | Required, non-blank String of at most 50 characters; no NUL; preserved exactly, with no trim/case conversion |
| value | Required BigDecimal; representable by NUMERIC(10,3) without rounding; absolute value below 10000000 and at most three nonzero fractional places |
| unit | Existing nullable String; at most 20 characters; no NUL |
| observedAt | Required offset timestamp; provisional input supports years 1–9999 and microsecond precision, matching PostgreSQL's timestamp precision without rounding |

No physical temperature/humidity ranges are invented. Missing or invalid data is
never substituted with zero or the current time. A null unit is preserved.

Errors use existing `AppException`/`ErrorCode` conventions: 1120 device ID required,
1121 invalid metric, 1122 invalid numeric value, 1123 invalid unit, 1124 invalid or
missing observation time, 1116 unreadable body, 1102 unknown/deleted device, 1100
missing home, 1004 unauthenticated, and 1005 unauthorized. Parsing failures such as
`"observedAt":"yesterday"` return 1116; validly parsed timestamps outside the
supported precision/year window return 1124.

## Persistence, latest state, and realtime

```text
Mock REST request -> SensorReadingInput -> SensorReadingIngestionService
  -> validate -> lock/load Device -> authorize its actual Home
  -> save existing SensorReading -> query latest (deviceId, exact metricType)
  -> if saved ID is latest: existing SensorReadingUpdatedEvent
  -> COMMIT -> existing DeviceSensorRealtimeListener (AFTER_COMMIT)
  -> existing RealtimeEventPublisher -> existing STOMP home destination
```

The transaction runs at READ_COMMITTED and locks the Device row for writing.
Submissions through this service for the same device serialize their persistence
and latest-state decisions, including when no prior reading exists. Different
devices are independent. This deliberately simple lock also serializes different
metrics on the same device. Future producers must use this service rather than
writing directly if they need the same concurrency behavior.

The repository selects greatest `recordedAt`, then greatest reading ID for equal
times, exactly as the existing Twin query does. Identity-generated IDs are
available after save. Historical readings are persisted with `latest=false` and
produce no application or realtime event. Equal-time new rows win by their greater
ID. The existing Twin snapshot sees the same committed latest reading naturally;
there is no second latest-state store.

Success returns HTTP 200 with `ApiResponse<SensorReadingAcceptedResponse>`:
`code=1000`, `result.readingId`, `result.latest`, and `result.reading` containing the
accepted `TwinSensorSnapshotResponse`. For historical input, `reading` is the
accepted historical row, not a replacement for the current Twin node. `latest`
records the transaction's decision; it is not a websocket delivery acknowledgement
or a promise that no later transaction will supersede it.

The event's `data` uses `TwinSensorSnapshotResponse`, minimally extended by the
later health feature with `healthStatus`. The sensor identity remains
`<device UUID>:<exact metricType>`. Its home/device/room context comes from the
loaded Device relationships. `observedAt` preserves the input observation time;
the `RealtimeEvent` timestamp is publication time. Only one sensor node is sent.
Unassigned devices have `roomId=null`. Rollback publishes nothing through the
existing AFTER_COMMIT listener. The shared transport still has no durable replay
or global ordering guarantee; this change does not add one.

## Examples

- [Valid mock request](examples/mock-sensor-request.json).
- [Resulting SENSOR_READING_UPDATED shape](examples/mock-sensor-event.json).
- [Invalid numeric request](examples/mock-sensor-invalid-request.json).
- [Its HTTP 400 error body](examples/mock-sensor-invalid-response.json).
- [Captured successful demo and rejection logs](examples/mock-sensor-demo.txt)
  from the real PostgreSQL/HTTP/STOMP integration scenario.

The illustrative IDs must be replaced with a device/home that the authenticated
user can access. Event ID and publication timestamp are generated at runtime.
`MockSensorExamplesTest` verifies example serialization against actual DTOs.

## Recordable demo with no frontend or MQTT

Use the real-HTTP integration harness as the debug STOMP subscriber. It starts a
backend on a random local port using the existing MVC/security/JPA/STOMP components,
creates isolated generated test records, performs the flow, and deletes its own
records afterward. It imports no MQTT components. PostgreSQL must be a local
Supabase database with the existing migrations applied; no schema changes occur.

Set `HESTA_TEST_DATABASE_URL` to `jdbc:postgresql://127.0.0.1:54322/postgres` (or
your local PostgreSQL port), and provide `HESTA_TEST_DATABASE_USERNAME` and
`HESTA_TEST_DATABASE_PASSWORD` in your environment. The test refuses nonlocal
database URLs and is skipped when those opt-in settings are absent. It generates
short-lived JWTs in memory and never prints them.

Record the terminal while running:

```powershell
mvn.cmd '-Dtest=MockSensorPipelineIntegrationTest#mockHttpInput_commit_deliversNewestEventsAndHistoricalInputKeepsTwinState' test
```

The single scenario performs these steps in order:

1. Start the backend using the test configuration and local PostgreSQL.
2. Authenticate the real WebSocket STOMP client and subscribe to a valid Home.
3. Send a valid mock reading (29.4) through the HTTP endpoint.
4. Verify its persisted row and print the accepted reading ID.
5. Receive and print the actual `SENSOR_READING_UPDATED` JSON on the socket.
6. Send a newer reading (30.0).
7. Receive and print its realtime value.
8. Send an older historical reading (28.0).
9. Verify `latest=false`, no sensor event, and GET Twin still showing 30.0.
10. Send an invalid numeric reading, verify clear HTTP 400/code 1122, no new row,
    and no sensor event.

Look for `MOCK DEMO` log lines. Subscription readiness is verified by a received
device probe through the existing broker before sending sensor input; the probe
is test-only and is not part of ingestion. The real event publisher/listener,
database, authentication, membership checks, and STOMP transport are not mocked.

For a manually running backend, explicitly configure a **local** Spring datasource
and start it with profile `mock-sensors` plus `APP_MOCK_SENSORS_ENABLED=true`.
Do not rely on the legacy `local` profile's datasource defaults. Connect a debug
STOMP subscriber to `/ws`, use a Bearer token in the STOMP CONNECT header, and
subscribe to `/topic/homes/{homeId}/events`. The HTTP request can be sent with:

```powershell
$headers = @{ Authorization = "Bearer $env:HESTA_DEMO_ACCESS_TOKEN" }
$body = Get-Content docs/examples/mock-sensor-request.json -Raw | ConvertFrom-Json
$body.deviceId = $env:HESTA_DEMO_DEVICE_ID
$body.observedAt = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.ffffffzzz')
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/dev/sensors/mock-reading' `
    -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json)
```

Use a timestamp later than that stream's current latest observation, then increment
it for the second reading. Decrement it for historical input. Use value `10000000`
for the numeric rejection. Keep the token out of recordings and logs.

## Verification and source replacement

Unit tests cover validation, exact metric preservation, authorization, missing or
deleted devices, accepted persistence, historical suppression, and deployment
gates. PostgreSQL/socket integration tests cover the entire committed flow, Twin
reads, tied times, two metrics, null room, malformed/missing fields, disabled
membership, rollback, and concurrent historical input. Existing Twin contract tests
remain regression checks.

```powershell
mvn.cmd '-Dtest=SensorReadingIngestionServiceTest,MockSensorExposureTest,MockSensorExamplesTest,MockSensorPipelineIntegrationTest,TwinSnapshotMapperTest,TwinSnapshotServiceTest,TwinSnapshotControllerTest,TwinContractSerializationTest,DeviceStateRealtimeTest,DeviceSensorRealtimeTransactionTest,SensorReadingRepositoryTest' test
```

Rejected decoded input logs only deviceId, bounded/control-character-sanitized
metricType, observedAt, and a stable rejection reason. Unreadable bodies log that
context is unavailable, without logging the body, headers, JWT, or exception text.
Accepted telemetry has no per-reading production log; demo output is test-only.

Future MQTT/gateway adapters can map their authenticated input into
`SensorReadingInput` and invoke `SensorReadingIngestionService`. Hardware identity
authentication and its authorization policy still need a separate design; never
trust a hardware payload's user/home ID or bypass authorization by passing an
arbitrary user ID. Validation, persistence, latest selection, mapping, application
event, and AFTER_COMMIT realtime flow can be retained. No real hardware integration
is claimed by this implementation.

## Verification result (2026-09-17)

- **57 targeted tests passed**, with no failures, errors, or skips. This includes
  six real PostgreSQL/STOMP integration cases, the concurrency case, source input
  validation and exposure gates, sample serialization, and all existing Twin tests.
- Full `mvn.cmd test`: **145 tests, 144 passed, 1 error, no skips**. The only error
  was the existing `MqttSmokeTest.testPublishRoundTrip`, with connection refused
  from the deliberately local-only broker URL. No MQTT code was changed or added.
  The test process forced PostgreSQL to localhost, used Hibernate validation,
  disabled Flyway, and supplied the existing required WebSocket heartbeat setting.
- `mvn.cmd -DskipTests package`: **BUILD SUCCESS**. The JAR is
  `target/backend-0.0.1-SNAPSHOT.jar`; tests were executed separately above.
- `git diff --check`: passed. The integration fixtures were removed afterward;
  PostgreSQL's `session_replication_role` was verified as `origin`.

The full suite is not green until its external MQTT smoke-test dependency is
available. The mock pipeline tests require no MQTT broker. Raw run logs are in
`target/mock-sensor-targeted.log`, `target/mock-sensor-full-suite.log`, and
`target/mock-sensor-build.log`.

## File inventory

Created production files under `src/main/java/com/hesta/backend/`:

- `dto/request/MockSensorReadingRequest.java`
- `dto/command/SensorReadingInput.java`
- `dto/response/SensorReadingAcceptedResponse.java`
- `service/SensorReadingIngestionService.java`
- `service/impl/SensorReadingIngestionServiceImpl.java`
- `controller/MockSensorController.java`
- `exception/MockSensorRequestExceptionHandler.java`

Modified production files: `exception/ErrorCode.java` adds the validation codes;
`repository/DeviceRepository.java` adds the locked device lookup;
`repository/SensorReadingRepository.java` adds save and latest-stream lookup.

Created tests under `src/test/java/com/hesta/backend/`:
`service/SensorReadingIngestionServiceTest.java`, `controller/MockSensorExposureTest.java`,
`mapper/MockSensorExamplesTest.java`, and `realtime/MockSensorPipelineIntegrationTest.java`.
Created this document and its five linked example/evidence files; updated
`docs/DIGITAL_TWIN.md` to point to the implemented mock producer.

Reused by the original mock-pipeline implementation: `SensorReading`, `TwinSensorSnapshotResponse`,
`TwinSnapshotMapper`, `SensorReadingUpdatedEvent`, `DeviceSensorRealtimeListener`,
`RealtimeEventPublisher`, `RealtimeEventType.SENSOR_READING_UPDATED`, and the shared
WebSocket/STOMP infrastructure. No Sensor entity/table, migration, frontend,
hardware/MQTT integration, or health threshold was added by that implementation.
The subsequent [Twin health feature](TWIN_HEALTH.md) extends the node DTOs,
mapper, and committed listener while retaining this ingestion flow.
