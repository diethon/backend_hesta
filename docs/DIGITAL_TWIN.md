# Backend Digital Twin contract

`GET /api/v1/homes/{homeId}/twin` returns `ApiResponse<TwinHomeSnapshotResponse>`
with success code `1000`. Authentication uses the existing Bearer JWT and
`CustomUserDetails`. Only an ACTIVE HomeMember (OWNER or MEMBER) can read the home;
platform ADMIN does not bypass home membership. Existing error codes and HTTP
statuses apply: 401 unauthenticated, 403 unauthorized, 404 missing home.

## Snapshot schema

| DTO | Fields |
| --- | --- |
| TwinHomeSnapshotResponse | UUID homeId, String name, rooms[], unassignedDevices[], unassignedSensors[] |
| TwinRoomSnapshotResponse | UUID roomId, UUID homeId, String name, String icon, devices[], sensors[] |
| TwinDeviceSnapshotResponse | UUID deviceId, UUID roomId, String name, DeviceType deviceType, String icon, DeviceStatus status, JsonNode currentState, OffsetDateTime lastSeen |
| TwinSensorSnapshotResponse | String sensorId, UUID roomId, UUID deviceId, String metricType, BigDecimal latestValue, String unit, OffsetDateTime observedAt |

All collections are present, including empty collections. `roomId` is null for
unassigned nodes, which appear in the home's explicit unassigned collections.
No synthetic Room is created. Device nodes include sensor hardware as well as
actuators. A room's sensor list contains the individual measured metric streams.
`icon`, `lastSeen`, and sensor `unit` may be null; unknown observations are not
replaced with the current time. Times are ISO-8601 offset timestamps; the event
envelope timestamp is an Instant. `currentState` preserves the existing JSON
runtime state, including its native scalar and nested value types, through a
detached JsonNode rather than exposing the persistence map or JPA entities.

## Source of truth and identity

This checkout has no separate Sensor entity or sensor registry. The existing
`sensor_readings` table stores `(device_id, metric_type, value, unit, recorded_at)`.
`SensorReading` only maps that existing table. There is no Twin table or migration.

A sensor node represents one device/metric stream. Its stable ID is the canonical
device UUID string, a colon, and the exact stored `metric_type`, for example
`00000000-0000-4000-8000-000000000022:TEMPERATURE`. The metric is not normalized or
inferred from capabilities. The ID does not depend on the reading row ID, list
position, room assignment, value, or timestamp. Different metrics on the same
device have distinct sensor IDs. A device with no readings still appears in
`devices`; it has no known sensor streams until readings establish those metrics.
There is no metadata from which to infer a never-observed metric's unit.

`TwinSnapshotServiceImpl` authorizes first, then loads rooms, devices, and latest
readings in three bulk queries inside a read-only REPEATABLE_READ transaction.
`TwinSnapshotMapper` groups nodes by their real room relationship. Device queries
fetch rooms to avoid per-node lazy queries. Soft-deleted devices are excluded.
The telemetry query is home-scoped and chooses the greatest `recorded_at` for
each `(device_id, metric_type)`, breaking equal-time ties by greatest reading ID.
It returns the latest row per metric, not the whole telemetry history. Units are
the existing nullable strings (`°C`, `%`, `boolean`, etc.); values remain numeric,
including numeric motion readings. No alternative unit enum is introduced.

## Granular realtime updates

The unchanged shared `RealtimeEvent<T>` envelope contains `eventId`, `type`,
`homeId`, `deviceId`, `data`, and `timestamp`. The destination remains
`/topic/homes/{homeId}/events` on the existing STOMP endpoint.

| Event type | data type | Node key |
| --- | --- | --- |
| DEVICE_STATE_CHANGED | TwinDeviceSnapshotResponse | data.deviceId |
| SENSOR_READING_UPDATED | TwinSensorSnapshotResponse | data.sensorId |

`data` is a complete replacement for one node using the same fields as the
snapshot. It never contains `rooms` or a complete home. Both envelope `deviceId`
and payload `deviceId` refer to the device; the envelope preserves `homeId` and
the payload preserves `roomId`. Sensor events include `metricType`, `latestValue`,
`unit`, and `observedAt`, allowing one of several metrics on a device to change
independently. An event for a newly observed metric can insert that sensor node.

The existing device state update method queues `DeviceStateChangedEvent` with a
detached payload after saving state. `DeviceSensorRealtimeListener` publishes
through the existing `RealtimeEventPublisher` only AFTER_COMMIT. Rollback or
publication outside a transaction does not send an event.

The development-only [mock sensor pipeline](MOCK_SENSOR_PIPELINE.md) now exercises
the canonical `SensorReadingUpdatedEvent` hook. No real hardware producer exists.
The reusable service emits this inside its persistence transaction when it
accepts a new latest reading:

```java
applicationEventPublisher.publishEvent(new SensorReadingUpdatedEvent(
        reading.getDevice().getHome().getId(), twinSnapshotMapper.sensor(reading)));
```

The mock adapter is opt-in and preserves this contract without MQTT processing.
Historical backfills must not be emitted as latest-state updates. The event timestamp is
publication time; `observedAt` is the original observation time.

## Executable examples and verification

- [Snapshot response](examples/twin-snapshot.json): one home, two rooms, three
  devices, three sensor streams (including two metrics on one device).
- [Device event](examples/twin-device-event.json).
- [Sensor event](examples/twin-sensor-event.json).

`TwinContractSerializationTest` compares all three files with actual Spring
Jackson serialization. Mapping tests cover identities, relationships, runtime
state, nullable data, unassigned nodes, and detached state. Service tests use the
real HomeAuthorizationService to verify active members, non-members, disabled
owners, anonymous callers, and missing homes. MVC tests exercise the real
security filter chain and authenticated principal extraction. Realtime tests
verify a single-node payload, home/device/sensor context, commit, and rollback.

```powershell
mvn.cmd '-Dtest=TwinSnapshotMapperTest,TwinSnapshotServiceTest,TwinSnapshotControllerTest,TwinContractSerializationTest,DeviceStateRealtimeTest,DeviceSensorRealtimeTransactionTest' test
```

`SensorReadingRepositoryTest` additionally verifies latest-per-metric selection,
timestamp ties, home isolation, soft deletion, and unassigned devices against
PostgreSQL with rollback. It runs only when `HESTA_TEST_DATABASE_URL` explicitly
names localhost or 127.0.0.1 and uses `HESTA_TEST_DATABASE_USERNAME` and
`HESTA_TEST_DATABASE_PASSWORD`. Apply the existing Supabase migrations to that
local database first; the test uses Hibernate validation and never creates or
updates the schema. Never point tests at shared Cloud data. When running the
full suite, also override Spring's datasource properties to a safe test database,
because the legacy test local profile has separate datasource configuration.

Verification on 2026-09-17 (Java 21, Spring Boot 3.4.3):

- Targeted tests: **23 passed**, no failures, errors, or skips, including the
  PostgreSQL repository test against the local Supabase schema.
- Full `mvn.cmd test`: **111 tests, 110 passed, 1 error, no skips**. The existing
  `MqttSmokeTest.testPublishRoundTrip` could not connect to the local MQTT broker.
  Both attempts to download a temporary broker image failed on Docker DNS
  resolution. All Twin tests and the application context test passed. This run
  supplied `app.realtime.websocket.heartbeat=20s` in the test process because the
  legacy test resource configuration omits it. No source configuration changed.
- `mvn.cmd -DskipTests package`: **BUILD SUCCESS**, producing
  `target/backend-0.0.1-SNAPSHOT.jar`. Tests were run separately as recorded above;
  the full test suite is not green because of the broker error.
- `git diff --check`: passed. Changes are limited to backend Java/tests and docs.

## Implementation file inventory

Paths below are relative to `src/main/java/com/hesta/backend/` unless stated otherwise.

| Created production files | Purpose |
| --- | --- |
| dto/response/TwinHomeSnapshotResponse.java | Home snapshot |
| dto/response/TwinRoomSnapshotResponse.java | Room snapshot |
| dto/response/TwinDeviceSnapshotResponse.java | Device snapshot and event data |
| dto/response/TwinSensorSnapshotResponse.java | Sensor snapshot and event data |
| entity/SensorReading.java | Existing telemetry table mapping |
| repository/SensorReadingRepository.java | Latest per metric query |
| mapper/TwinSnapshotMapper.java | Shared snapshot/event node mapping |
| service/TwinSnapshotService.java | Snapshot service interface |
| service/impl/TwinSnapshotServiceImpl.java | Authorized snapshot read |
| controller/TwinSnapshotController.java | GET endpoint |
| dto/command/DeviceStateChangedEvent.java | Device transaction event |
| dto/command/SensorReadingUpdatedEvent.java | Sensor transaction event hook |
| realtime/publisher/DeviceSensorRealtimeListener.java | After-commit publication |

Modified production files: `repository/DeviceRepository.java` adds the bulk device
query with room fetching; `service/impl/DeviceServiceImpl.java` queues the device
event after updating state.

Created under `src/test/java/com/hesta/backend/`: `controller/TwinSnapshotControllerTest.java`,
`mapper/TwinSnapshotMapperTest.java`, `mapper/TwinContractSerializationTest.java`,
`service/TwinSnapshotServiceTest.java`, `service/DeviceStateRealtimeTest.java`,
`repository/SensorReadingRepositoryTest.java`,
`realtime/publisher/DeviceSensorRealtimeTransactionTest.java`, and `support/TwinFixtures.java`.

Created documentation: this file and the three JSON files linked above.
Modified documentation: `docs/REALTIME.md` links this contract.

## Boundaries and limitations

- `status`, `lastSeen`, and `observedAt` are the stored raw values. This contract
  does not calculate ACTIVE/STALE/OFFLINE thresholds or change ingestion's
  responsibility for maintaining lastSeen/status.
- The shared broker has no durable replay or ordering/version guarantee. Initial
  snapshot/event race reconciliation, reconnects, and out-of-order updates remain
  consumer concerns. Observation time is supplied, but is not a monotonic version.
- Structural updates such as room creation, reassignment, or device removal have
  no new event type in this task. Reload the snapshot when those structures change.
- The latest-per-metric query uses existing indexes. Large telemetry retention
  volumes may warrant a separately reviewed query/index optimization.
- No frontend files, React/Redux/TypeScript, new WebSocket/STOMP infrastructure,
  Digital Twin persistence, layout editor, or health thresholds were added.
