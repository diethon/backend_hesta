package com.hesta.backend.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.config.TwinHealthConfig;
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.controller.MockSensorController;
import com.hesta.backend.controller.TwinSnapshotController;
import com.hesta.backend.dto.command.SensorReadingInput;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.GlobalExceptionHandler;
import com.hesta.backend.exception.MockSensorRequestExceptionHandler;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.realtime.config.RealtimeConfig;
import com.hesta.backend.realtime.config.RealtimeWebSocketConfig;
import com.hesta.backend.realtime.publisher.DeviceSensorRealtimeListener;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisherImpl;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.realtime.publisher.TwinHealthActivityListener;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.security.RealtimeWebSocketChannelInterceptor;
import com.hesta.backend.realtime.transport.WebSocketRealtimeTransport;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.security.*;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.SensorReadingIngestionService;
import com.hesta.backend.service.TwinSnapshotService;
import com.hesta.backend.service.TwinHealthEvaluationService;
import com.hesta.backend.service.impl.TwinHealthEvaluationServiceImpl;
import com.hesta.backend.service.impl.TwinHealthStatusResolverImpl;
import com.hesta.backend.support.MutableClock;
import com.hesta.backend.service.impl.RealtimeSubscriptionServiceImpl;
import com.hesta.backend.service.impl.SensorReadingIngestionServiceImpl;
import com.hesta.backend.service.impl.TwinSnapshotServiceImpl;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP -> PostgreSQL commit -> existing listener/publisher/broker -> real STOMP socket. */
@SpringBootTest(classes = MockSensorPipelineIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.mock-sensors.enabled=true", "app.realtime.websocket.heartbeat=1s",
                "app.twin.health.stale-after=30s", "app.twin.health.offline-after=5m",
                "app.twin.health.evaluation-interval=1h", "app.twin.health.scheduling-enabled=false",
                "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false", "spring.jpa.open-in-view=false"})
@ActiveProfiles("mock-sensors")
@Slf4j
@EnabledIfEnvironmentVariable(named = "HESTA_TEST_DATABASE_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/.*")
class MockSensorPipelineIntegrationTest {
    private static final String URL = "/api/v1/dev/sensors/mock-reading";
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-09-17T08:20:00Z");
    private static final String SIGNING_KEY = UUID.randomUUID().toString() + UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired SensorReadingIngestionService ingestion;
    @Autowired SensorReadingRepository readings;
    @Autowired TwinSnapshotService snapshots;
    @Autowired RealtimeEventPublisher realtimePublisher;
    @Autowired TwinSnapshotMapper mapper;
    @Autowired MutableClock clock;
    @Autowired TwinHealthEvaluationService health;
    @Autowired org.springframework.context.ApplicationEventPublisher applicationEvents;
    private User user;
    private Home home;
    private Room room;
    private Device device;
    private String token;
    private WebSocketStompClient client;
    private ThreadPoolTaskScheduler scheduler;
    private StompSession session;
    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonNode> healthEvents = new LinkedBlockingQueue<>();

    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("HESTA_TEST_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("HESTA_TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("HESTA_TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("app.jwt.secret", () -> SIGNING_KEY);
    }

    @BeforeEach
    void createCommittedFixtureAndSubscribe() throws Exception {
        clock.set(TIME.toInstant());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            user = User.builder().fullName("Mock pipeline test").email(UUID.randomUUID() + "@example.com")
                    .provider(AuthProvider.LOCAL).passwordHash("test-hash").build();
            em.persist(user);
            home = Home.builder().name("Mock pipeline test").createdBy(user).build();
            em.persist(home);
            em.persist(HomeMember.builder().user(user).role(HomeRole.MEMBER).status(MemberStatus.ACTIVE).build());
            room = Room.builder().home(home).name("Test room").build();
            em.persist(room);
            device = Device.builder().room(room).name("Mock source device").deviceType("SENSOR")
                    .status(DeviceStatus.UNKNOWN).currentState(Map.of()).build();
            em.persist(device);
        });
        health.evaluateAll();
        token = tokens.generateAccessToken(user);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setTaskScheduler(scheduler);
        StompHeaders connect = new StompHeaders();
        connect.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:5173");
        session = client.connectAsync("ws://localhost:" + port + "/ws", handshake, connect,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
        // The simple broker has no receipt support. A received probe proves registration end to end.
        CountDownLatch subscribed = new CountDownLatch(1);
        session.subscribe("/topic/homes/" + home.getId() + "/events", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    JsonNode event = json.readTree((byte[]) payload);
                    if (event.path("type").asText().equals("DEVICE_STATE_CHANGED")) subscribed.countDown();
                    else if (event.path("type").asText().equals("TWIN_HEALTH_STATUS_CHANGED")) healthEvents.add(event);
                    else received.add(event);
                }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }
        });
        for (int attempt = 0; attempt < 100 && subscribed.getCount() > 0; attempt++) {
            realtimePublisher.publish(RealtimeEvent.create(RealtimeEventType.DEVICE_STATE_CHANGED,
                    home.getId(), device.getId(), mapper.device(device)));
            if (subscribed.await(50, TimeUnit.MILLISECONDS)) break;
        }
        assertThat(subscribed.getCount()).isZero();
        log.info("MOCK DEMO backend=http://127.0.0.1:{} subscribedHome={} device={}", port, home.getId(), device.getId());
    }

    @AfterEach
    void closeAndRemoveOnlyOwnedFixtures() {
        if (session != null && session.isConnected()) session.disconnect();
        if (client != null) client.stop();
        if (scheduler != null) scheduler.shutdown();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (home != null) jdbc.update("delete from homes where id = ?", home.getId());
            if (user != null) jdbc.update("delete from users where id = ?", user.getId());
        });
        health.evaluateAll();
    }

    @Test
    void healthTimeline_transitionsWithoutActivity_andRecoversOnlyAfterFreshCommit() throws Exception {
        post(request("TEMPERATURE", "29.4", TIME));
        assertThat(nextEvent().at("/data/healthStatus").asText()).isEqualTo("ACTIVE");
        assertThat(healthEvents).isEmpty(); // first observation establishes a baseline

        clock.advance(Duration.ofMillis(29999));
        health.evaluateAll();
        assertThat(healthEvents).isEmpty();
        clock.advance(Duration.ofMillis(1));
        health.evaluateAll();
        assertHealthEvent("SENSOR", device.getId() + ":TEMPERATURE", "ACTIVE", "STALE");
        health.evaluateAll();
        assertThat(healthEvents.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(snapshots.getSnapshot(user.getId(), home.getId()).rooms().getFirst().sensors().getFirst().healthStatus())
                .isEqualTo(TwinHealthStatus.STALE);

        clock.advance(Duration.ofMillis(269999));
        health.evaluateAll();
        assertThat(healthEvents).isEmpty();
        clock.advance(Duration.ofMillis(1));
        health.evaluateAll();
        assertHealthEvent("SENSOR", device.getId() + ":TEMPERATURE", "STALE", "OFFLINE");

        assertThat(post(request("TEMPERATURE", "10", TIME.minusHours(1))).getBody().at("/result/latest").asBoolean()).isFalse();
        health.evaluateAll();
        assertThat(healthEvents.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();

        OffsetDateTime freshTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ingestion.ingest(user.getId(), new SensorReadingInput(device.getId(), "TEMPERATURE",
                    new BigDecimal("31"), "°C", freshTime));
            assertThat(healthEvents).isEmpty();
            status.setRollbackOnly();
        });
        health.evaluateAll();
        assertThat(healthEvents.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertLatest("TEMPERATURE", "29.4");

        post(request("TEMPERATURE", "32", freshTime));
        assertThat(nextEvent().at("/data/healthStatus").asText()).isEqualTo("ACTIVE");
        assertHealthEvent("SENSOR", device.getId() + ":TEMPERATURE", "OFFLINE", "ACTIVE");
        health.evaluateAll();
        assertThat(healthEvents.poll(200, TimeUnit.MILLISECONDS)).isNull();
        var snapshot = snapshots.getSnapshot(user.getId(), home.getId());
        assertThat(snapshot.rooms().getFirst().devices().getFirst().healthStatus()).isEqualTo(TwinHealthStatus.OFFLINE);
        assertThat(snapshot.rooms().getFirst().devices().getFirst().status()).isEqualTo(DeviceStatus.UNKNOWN);
        assertThat(snapshot.rooms().getFirst().devices().getFirst().lastSeen()).isNull();
    }

    @Test
    void committedDeviceActivity_recoversIndependentlyOfSensorHealth() throws Exception {
        post(request("HUMIDITY", "60", TIME.minusMinutes(10)));
        assertThat(nextEvent().at("/data/healthStatus").asText()).isEqualTo("OFFLINE");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Device managed = em.find(Device.class, device.getId());
            managed.setLastSeen(TIME);
            applicationEvents.publishEvent(new DeviceStateChangedEvent(home.getId(), mapper.device(managed)));
            status.setRollbackOnly();
        });
        health.evaluateAll();
        assertThat(healthEvents.poll(200, TimeUnit.MILLISECONDS)).isNull();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Device managed = em.find(Device.class, device.getId());
            managed.setLastSeen(TIME); // simulate an authoritative activity producer, not sensor ingestion
            applicationEvents.publishEvent(new DeviceStateChangedEvent(home.getId(), mapper.device(managed)));
            assertThat(healthEvents).isEmpty();
        });
        assertHealthEvent("DEVICE", device.getId().toString(), "OFFLINE", "ACTIVE");
        var snapshot = snapshots.getSnapshot(user.getId(), home.getId());
        assertThat(snapshot.rooms().getFirst().devices().getFirst().healthStatus()).isEqualTo(TwinHealthStatus.ACTIVE);
        assertThat(snapshot.rooms().getFirst().devices().getFirst().status()).isEqualTo(DeviceStatus.UNKNOWN);
        assertThat(snapshot.rooms().getFirst().sensors().getFirst().healthStatus()).isEqualTo(TwinHealthStatus.OFFLINE);
    }

    @Test
    void staleSensorRecovery_doesNotRefreshAnotherMetricAndDoesNotDuplicateHealthEvents() throws Exception {
        post(request("TEMPERATURE", "29", TIME));
        nextEvent();
        post(request("HUMIDITY", "60", TIME));
        nextEvent();
        clock.advance(Duration.ofSeconds(30));
        health.evaluateAll();
        JsonNode first = healthEvents.poll(10, TimeUnit.SECONDS);
        JsonNode second = healthEvents.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(List.of(first, second)).allSatisfy(event -> {
            assertThat(event.at("/data/previousStatus").asText()).isEqualTo("ACTIVE");
            assertThat(event.at("/data/healthStatus").asText()).isEqualTo("STALE");
        });
        OffsetDateTime freshTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        post(request("TEMPERATURE", "30", freshTime));
        assertThat(nextEvent().at("/data/healthStatus").asText()).isEqualTo("ACTIVE");
        assertHealthEvent("SENSOR", device.getId() + ":TEMPERATURE", "STALE", "ACTIVE");
        var nodes = snapshots.getSnapshot(user.getId(), home.getId()).rooms().getFirst().sensors();
        assertThat(nodes).filteredOn(node -> node.metricType().equals("HUMIDITY"))
                .singleElement().satisfies(node -> assertThat(node.healthStatus()).isEqualTo(TwinHealthStatus.STALE));
        post(request("TEMPERATURE", "31", freshTime));
        nextEvent();
        health.evaluateAll();
        assertThat(healthEvents.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    private void assertHealthEvent(String type, String nodeId, String previous, String current) throws Exception {
        JsonNode event = healthEvents.poll(10, TimeUnit.SECONDS);
        assertThat(event).as("health change delivered by existing STOMP broker").isNotNull();
        assertThat(event.path("type").asText()).isEqualTo("TWIN_HEALTH_STATUS_CHANGED");
        assertThat(event.path("homeId").asText()).isEqualTo(home.getId().toString());
        assertThat(event.path("deviceId").asText()).isEqualTo(device.getId().toString());
        assertThat(event.at("/data/nodeType").asText()).isEqualTo(type);
        assertThat(event.at("/data/nodeId").asText()).isEqualTo(nodeId);
        assertThat(event.at("/data/roomId").asText()).isEqualTo(room.getId().toString());
        assertThat(event.at("/data/previousStatus").asText()).isEqualTo(previous);
        assertThat(event.at("/data/healthStatus").asText()).isEqualTo(current);
        assertThat(java.time.Instant.parse(event.at("/data/evaluatedAt").asText())).isEqualTo(clock.instant());
        log.info("HEALTH DEMO received {}", event);
    }

    @Test
    void mockHttpInput_commit_deliversNewestEventsAndHistoricalInputKeepsTwinState() throws Exception {
        var first = post(request("TEMPERATURE", "29.4", TIME));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("result").path("latest").asBoolean()).isTrue();
        assertThat(count()).isEqualTo(1);
        log.info("MOCK DEMO first reading accepted and persisted: readingId={}", first.getBody().at("/result/readingId"));
        assertEvent(nextEvent(), "TEMPERATURE", "29.4", TIME, room.getId());
        assertLatest("TEMPERATURE", "29.4");

        var second = post(request("TEMPERATURE", "30.0", TIME.plusMinutes(1)));
        assertThat(second.getBody().path("result").path("latest").asBoolean()).isTrue();
        assertEvent(nextEvent(), "TEMPERATURE", "30.0", TIME.plusMinutes(1), room.getId());

        var older = post(request("TEMPERATURE", "28.0", TIME.minusMinutes(5)));
        assertThat(older.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(older.getBody().path("result").path("latest").asBoolean()).isFalse();
        assertThat(count()).isEqualTo(3);
        assertLatest("TEMPERATURE", "30.0");
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
        log.info("MOCK DEMO historical reading persisted: latest=false; no sensor event; latest value remains 30.0");
        var snapshot = http.exchange("/api/v1/homes/" + home.getId() + "/twin", HttpMethod.GET,
                new HttpEntity<>(headers()), JsonNode.class);
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot.getBody().at("/result/rooms/0/sensors/0/latestValue").decimalValue())
                .isEqualByComparingTo("30.0");
        var invalid = post(request("TEMPERATURE", "10000000", TIME.plusMinutes(2)));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().path("code").asInt()).isEqualTo(1122);
        assertThat(count()).isEqualTo(3);
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
        log.info("MOCK DEMO invalid input rejected: HTTP 400 code=1122; no persistence or sensor event");
    }

    @Test
    void equalTime_andTwoMetricsAndUnassignedDevice_useExistingIdentityAndOrdering() throws Exception {
        jdbc.update("update devices set room_id = null where id = ?", device.getId());
        var first = post(request("TEMPERATURE", "29", TIME));
        assertEvent(nextEvent(), "TEMPERATURE", "29", TIME, null);
        var tied = post(request("TEMPERATURE", "31", TIME));
        assertThat(tied.getBody().at("/result/readingId").asLong()).isGreaterThan(first.getBody().at("/result/readingId").asLong());
        assertThat(tied.getBody().at("/result/latest").asBoolean()).isTrue();
        assertEvent(nextEvent(), "TEMPERATURE", "31", TIME, null);
        assertThat(post(request("HUMIDITY", "60", TIME)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertEvent(nextEvent(), "HUMIDITY", "60", TIME, null);
        var snapshot = snapshots.getSnapshot(user.getId(), home.getId());
        assertThat(snapshot.unassignedSensors()).hasSize(2).extracting(s -> s.sensorId())
                .containsExactlyInAnyOrder(device.getId() + ":TEMPERATURE", device.getId() + ":HUMIDITY");
        assertLatest("TEMPERATURE", "31");
    }

    @Test
    void transactionRollback_discardsPersistedReadingAndPublishesNothing() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var accepted = ingestion.ingest(user.getId(), new SensorReadingInput(device.getId(), "TEMPERATURE",
                    new BigDecimal("29.4"), "°C", TIME));
            assertThat(accepted.latest()).isTrue();
            assertThat(count()).isEqualTo(1);
            assertThat(received).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
        assertThat(snapshots.getSnapshot(user.getId(), home.getId()).rooms().getFirst().sensors()).isEmpty();
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void concurrentHistoricalInput_waitsForNewestCommitAndDoesNotPublishBackwards() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch newerSaved = new CountDownLatch(1);
        CountDownLatch commitNewer = new CountDownLatch(1);
        CountDownLatch olderStarted = new CountDownLatch(1);
        try {
            Future<?> newer = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                ingestion.ingest(user.getId(), new SensorReadingInput(device.getId(), "TEMPERATURE",
                        new BigDecimal("30"), "°C", TIME.plusMinutes(1)));
                newerSaved.countDown();
                try {
                    if (!commitNewer.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit latch timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(newerSaved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> older = workers.submit(() -> {
                olderStarted.countDown();
                return ingestion.ingest(user.getId(), new SensorReadingInput(device.getId(), "TEMPERATURE",
                        new BigDecimal("28"), "°C", TIME)).latest();
            });
            assertThat(olderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(older.isDone()).isFalse();
            commitNewer.countDown();
            newer.get(10, TimeUnit.SECONDS);
            assertThat(older.get(10, TimeUnit.SECONDS)).isFalse();
            assertEvent(nextEvent(), "TEMPERATURE", "30", TIME.plusMinutes(1), room.getId());
            assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
            assertThat(count()).isEqualTo(2);
            assertLatest("TEMPERATURE", "30");
        } finally {
            commitNewer.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invalidHttpInputs_rejectWithoutPersistenceOrRealtime() throws Exception {
        for (String field : List.of("deviceId", "metricType", "value", "observedAt")) {
            var body = request("TEMPERATURE", "29", TIME);
            body.remove(field);
            assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String invalidValue : List.of("10000000", "0.0001")) {
            assertThat(post(request("TEMPERATURE", invalidValue, TIME)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var malformedNumber = request("TEMPERATURE", "29", TIME).put("value", "not-a-number");
        assertThat(post(malformedNumber).getBody().path("code").asInt()).isEqualTo(1116);
        var malformedTime = request("TEMPERATURE", "29", TIME).put("observedAt", "yesterday");
        assertThat(post(malformedTime).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("{broken").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request("TEMPERATURE", "29", TIME).put("deviceId", UUID.randomUUID().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("update devices set is_deleted = true where id = ?", device.getId());
        assertThat(post(request("TEMPERATURE", "29", TIME)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count()).isZero();
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void anonymousOrDisabledMember_cannotUseMockEndpoint() throws Exception {
        assertThat(http.postForEntity(URL, request("TEMPERATURE", "29", TIME), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("update home_members set status = 'DISABLED' where home_id = ? and user_id = ?", home.getId(), user.getId());
        assertThat(post(request("TEMPERATURE", "29", TIME)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count()).isZero();
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    private ObjectNode request(String metric, String value, OffsetDateTime time) {
        return json.createObjectNode().put("deviceId", device.getId().toString()).put("metricType", metric)
                .put("value", new BigDecimal(value)).put("unit", "°C").put("observedAt", time.toString());
    }

    private ResponseEntity<JsonNode> post(Object body) {
        return http.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers()), JsonNode.class);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from sensor_readings where device_id = ?", Long.class, device.getId());
    }

    private JsonNode nextEvent() throws Exception {
        JsonNode event = received.poll(10, TimeUnit.SECONDS);
        assertThat(event).as("SENSOR_READING_UPDATED received through the real STOMP socket").isNotNull();
        return event;
    }

    private void assertEvent(JsonNode event, String metric, String value, OffsetDateTime time, UUID roomId) {
        assertThat(event.path("type").asText()).isEqualTo("SENSOR_READING_UPDATED");
        assertThat(event.path("eventId").asText()).isNotBlank();
        assertThat(event.path("timestamp").asText()).isNotBlank();
        assertThat(event.path("homeId").asText()).isEqualTo(home.getId().toString());
        assertThat(event.path("deviceId").asText()).isEqualTo(device.getId().toString());
        JsonNode data = event.path("data");
        assertThat(data.path("deviceId").asText()).isEqualTo(device.getId().toString());
        assertThat(data.path("sensorId").asText()).isEqualTo(device.getId() + ":" + metric);
        assertThat(data.path("metricType").asText()).isEqualTo(metric);
        assertThat(data.path("latestValue").decimalValue()).isEqualByComparingTo(value);
        assertThat(data.path("unit").asText()).isEqualTo("°C");
        assertThat(OffsetDateTime.parse(data.path("observedAt").asText()).toInstant()).isEqualTo(time.toInstant());
        if (roomId == null) assertThat(data.path("roomId").isNull()).isTrue();
        else assertThat(data.path("roomId").asText()).isEqualTo(roomId.toString());
        assertThat(data.has("rooms")).isFalse();
        log.info("MOCK DEMO received {}", event);
    }

    private void assertLatest(String metric, String expected) {
        var reading = readings.findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(device.getId(), metric).orElseThrow();
        assertThat(reading.getValue()).isEqualByComparingTo(expected);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Device.class)
    @EnableJpaRepositories(basePackageClasses = DeviceRepository.class)
    @Import({MockSensorController.class, TwinSnapshotController.class, GlobalExceptionHandler.class,
            TwinHealthConfig.class, TwinHealthStatusResolverImpl.class, TwinHealthEvaluationServiceImpl.class,
            TwinHealthActivityListener.class,
            MockSensorRequestExceptionHandler.class, SensorReadingIngestionServiceImpl.class, TwinSnapshotMapper.class,
            HomeAuthorizationService.class, TwinSnapshotServiceImpl.class, DeviceSensorRealtimeListener.class,
            RealtimeEventPublisherImpl.class, WebSocketRealtimeTransport.class, RealtimeConfig.class,
            RealtimeWebSocketConfig.class, RealtimeWebSocketChannelInterceptor.class, RealtimeSubscriptionServiceImpl.class,
            SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class, CustomUserDetailsService.class,
            CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
    static class TestApplication {
        @Bean
        @Primary
        MutableClock testClock() { return new MutableClock(TIME.toInstant()); }
    }
}
