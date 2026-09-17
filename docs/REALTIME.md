# Shared realtime backend

## Transport and architecture

HESTA uses one WebSocket endpoint with STOMP messaging for application realtime updates:

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

The handshake endpoint is `ws://<host>/ws`. The in-process STOMP simple broker serves `/topic`;
`/app` is reserved for future client-to-server messages. Client `SEND` frames are currently
rejected, preventing clients from injecting events into broker topics. Feature modules must not
create their own WebSocket configuration, destination, or `SimpMessagingTemplate` usage.

## Authentication and subscription authorization

The HTTP handshake endpoint is open only so browser-compatible WebSocket clients can upgrade the
connection. Authentication is mandatory on the subsequent STOMP `CONNECT` frame. Send the same
access token used by the REST API as a native STOMP header:

```text
Authorization: Bearer <access-token>
```

Tokens are never accepted in query parameters. The backend validates the JWT, loads the current
`CustomUserDetails`, and rejects missing, invalid, disabled-account, or locked-account connections.
An upgraded socket must send its first STOMP frame within 10 seconds, preventing idle anonymous
handshakes from holding a connection indefinitely.

Subscribe to exactly one home destination per subscription:

```text
/topic/homes/{homeId}/events
```

Every STOMP `SUBSCRIBE` frame is intercepted. The authenticated user must have an `ACTIVE`
`HomeMember` record for the destination's `homeId`; non-members and inactive members are rejected.
Rejected frames produce the normal STOMP error flow. The simple broker owns session state and
removes subscriptions on `DISCONNECT` or transport error.

## Event contract

`RealtimeEvent<T>` remains transport-independent and contains:

- `eventId`: non-blank unique event identifier
- `type`: a `RealtimeEventType`
- `homeId`: required home scope
- `deviceId`: optional device scope
- `data`: generic, non-null payload
- `timestamp`: event creation time

The shared event types are `SENSOR_READING_UPDATED`, `DEVICE_STATE_CHANGED`, and
`NOTIFICATION_CREATED`. Add a value to `RealtimeEventType` for a future event; do not create another
WebSocket infrastructure.

## Publishing from a future service

Inject only `RealtimeEventPublisher`:

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

The publisher derives `/topic/homes/{homeId}/events` from the event and delegates to the shared
WebSocket transport. When an event represents a database change, publish only after the transaction
commits so clients cannot observe rolled-back state.

## Configuration and verification

`app.realtime.websocket.heartbeat` controls both STOMP heartbeat directions and defaults to `20s`.
Override it with `REALTIME_WEBSOCKET_HEARTBEAT` using an explicit duration such as `30s`.

Run the backend-only realtime verification tests with:

```powershell
mvn -Dtest=RealtimeEventTest,RealtimeEventPublisherImplTest,RealtimeDestinationsTest,WebSocketRealtimeTransportTest,RealtimeWebSocketChannelInterceptorTest,RealtimeSubscriptionServiceTest,WebSocketRealtimePropertiesTest,RealtimeWebSocketIntegrationTest test
```

## Scaling limitations

The STOMP simple broker and connected sessions live in one backend instance. WebSocket does not by
itself provide cross-instance delivery, durable replay, or distributed ordering. A future
horizontal deployment will need a shared broker or pub/sub layer (for example Redis Pub/Sub,
RabbitMQ, or Kafka) between backend instances. No broker is introduced by this refactor.

The current infrastructure intentionally provides transport, authentication, home-scoped
authorization, routing, lifecycle cleanup, heartbeat, and future bidirectional capability only.
Feature business logic remains outside this layer. Notification Core now integrates through
`RealtimeEventPublisher`; see [NOTIFICATION_CORE.md](NOTIFICATION_CORE.md).

The canonical device/sensor node payloads, initial home snapshot endpoint, and
after-commit feature hooks are documented in [DIGITAL_TWIN.md](DIGITAL_TWIN.md).
