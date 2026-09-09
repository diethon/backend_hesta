-- =====================================================================
-- HESTA SMART HOME SYSTEM — PostgreSQL Database Schema
-- functional "Data:" specs 3.2–3.10, and Business Rules 5.1)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- for gen_random_uuid()

-- =====================================================================
-- =====================================================================
-- 2. AUTH & USER MANAGEMENT  (Entity #1 User + supporting tables)
--    Functional refs: 3.2.1–3.2.13 | BR-AUTH-01..10, BR-USER-01..08,
--    BR-PROF-01..03
-- =====================================================================

CREATE TABLE users (
                       id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       full_name               VARCHAR(150) NOT NULL,
                       email                   VARCHAR(255) NOT NULL UNIQUE,     -- BR-AUTH-01
                       password_hash           VARCHAR(255),                     -- NULL for Google-only accounts, BCrypt (BR-AUTH-02)
                       phone_number            VARCHAR(20),
                       avatar_url              TEXT,
                       provider                VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
                       google_uid              VARCHAR(255) UNIQUE,               -- BR-AUTH-05/06
                       platform_role           VARCHAR(20) NOT NULL DEFAULT 'USER',  -- system-level Admin flag (Admin Usecase diagram)
                       status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                       failed_login_attempts   SMALLINT NOT NULL DEFAULT 0,       -- BR-AUTH-03
                       locked_until             TIMESTAMPTZ,                       -- BR-AUTH-03 (15-minute lockout)
                       last_active_at          TIMESTAMPTZ,
                       created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (               -- session/device management, BR-AUTH-08/09
                                id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                device_id     VARCHAR(255) NOT NULL,
                                device_type   VARCHAR(50),
                                token_hash    VARCHAR(255) NOT NULL,
                                is_revoked    BOOLEAN NOT NULL DEFAULT false,
                                expires_at    TIMESTAMPTZ NOT NULL,
                                created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE password_reset_otps (          -- BR-AUTH-07
                                     id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                     otp_hash       VARCHAR(255) NOT NULL,
                                     attempt_count  SMALLINT NOT NULL DEFAULT 0,   -- max 3 (BR-AUTH-07)
                                     is_used        BOOLEAN NOT NULL DEFAULT false,
                                     expires_at     TIMESTAMPTZ NOT NULL,          -- 15 minutes (BR-AUTH-07)
                                     created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- 3. HOME / ROOM STRUCTURE  (Entities #2 Home, #3 HomeMember, #4 Room)
--    Functional refs: 3.2.6–3.2.10 | BR-USER-01..08
-- =====================================================================

CREATE TABLE homes (
                       id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       name         VARCHAR(150) NOT NULL,
                       address      TEXT,
                       created_by   UUID NOT NULL REFERENCES users(id),   -- the person who created the home; also gets a home_members row with role = 'OWNER'
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE home_members (                 -- Entity #3: role/membership per home
                              id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              home_id                   UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                              user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                              role                      VARCHAR(20) NOT NULL DEFAULT 'MEMBER',     -- BR-USER-03: OWNER = full control of this home, MEMBER = restricted
                              status                    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- Feature-level permission flags (3.2.10 "permissionFlags"), independent
    -- of which rooms the member can access (that part is in
    -- home_member_room_access below). Defaults reflect the SRS role summary:
    -- Member = assigned rooms/scenes/Digital Twin only, no override/remote by default.
                              allow_voice_override      BOOLEAN NOT NULL DEFAULT false,   -- "Allow Voice Control override"
                              allow_scene_creation      BOOLEAN NOT NULL DEFAULT false,   -- "Allow Scene Creation"
                              allow_remote_control      BOOLEAN NOT NULL DEFAULT false,   -- "Allow Remote Control outside home network"
                              invited_by                UUID REFERENCES users(id),
                              joined_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
                              UNIQUE (home_id, user_id)                              -- BR-USER-02
);
CREATE INDEX idx_home_members_home ON home_members(home_id);

CREATE TABLE rooms (                        -- Entity #4
                       id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       home_id      UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                       name         VARCHAR(100) NOT NULL,
                       layout_x     NUMERIC(10,3),
                       layout_y     NUMERIC(10,3),
                       icon         VARCHAR(50),
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE home_member_room_access (      -- RBAC device/room visibility, BR-DEV-01
                                         home_member_id  UUID NOT NULL REFERENCES home_members(id) ON DELETE CASCADE,
                                         room_id         UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
                                         PRIMARY KEY (home_member_id, room_id)
);

CREATE TABLE user_preferences (             -- 3.2.13 Configure User Preferences
                                  user_id                  UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                                  preferred_temperature    NUMERIC(4,1),
                                  preferred_brightness     SMALLINT,
                                  default_room_id          UUID REFERENCES rooms(id),
                                  theme                    VARCHAR(20) DEFAULT 'system',
                                  language                 VARCHAR(10) DEFAULT 'vi',            -- BR: bilingual, default Vietnamese
                                  voice_feedback_enabled   BOOLEAN NOT NULL DEFAULT true,
                                  notify_security          BOOLEAN NOT NULL DEFAULT true,       -- BR-NOT-03: app must keep this locked ON
                                  notify_automation        BOOLEAN NOT NULL DEFAULT true,
                                  notify_system            BOOLEAN NOT NULL DEFAULT true,
                                  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- 4. DEVICE MANAGEMENT  (Entities #5 Device, #6 DeviceStateHistory,
--    #7 SensorReading)
--    Functional refs: 3.3.1–3.3.11 | BR-DEV-01..05, BR-PAIR-01..05,
--    BR-CTRL-01..04, BR-MON-01..02
-- =====================================================================

CREATE TABLE edge_nodes (                   -- ESP32 controller nodes (BR-PAIR-01..04)
                            id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            home_id           UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                            node_code         VARCHAR(100) NOT NULL,       -- e.g. "ESP32-01"
                            mac_address       VARCHAR(20) NOT NULL UNIQUE,
                            ip_address        VARCHAR(45),
                            firmware_version  VARCHAR(30),
                            status            VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
                            last_ack_at       TIMESTAMPTZ,                 -- handshake verification, BR-PAIR-03
                            paired_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                            UNIQUE (home_id, node_code)
);

CREATE TABLE devices (                      -- Entity #5
                         id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         home_id            UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                         room_id            UUID REFERENCES rooms(id) ON DELETE SET NULL,
                         node_id            UUID REFERENCES edge_nodes(id) ON DELETE SET NULL,
                         name               VARCHAR(150) NOT NULL,
                         device_type        VARCHAR(50) NOT NULL,        -- LIGHT, FAN, AC, DOOR_LOCK, TEMP_SENSOR, MOTION_SENSOR...
                         gpio_pin           SMALLINT,
                         mqtt_topic         VARCHAR(255),
                         status            VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
                         current_state      JSONB NOT NULL DEFAULT '{}', -- e.g. {"power":"ON","brightness":80}
                         icon               VARCHAR(50),
                         digital_twin_x     NUMERIC(10,3),
                         digital_twin_y     NUMERIC(10,3),
                         digital_twin_z     NUMERIC(10,3),
                         last_seen          TIMESTAMPTZ,
                         created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                         updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                         UNIQUE (node_id, gpio_pin)                       -- BR-PAIR-04
);
CREATE INDEX idx_devices_home ON devices(home_id);
CREATE INDEX idx_devices_room ON devices(room_id);

CREATE TABLE device_state_history (         -- Entity #6
                                      id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                      device_id       UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                                      previous_state  JSONB,
                                      new_state       JSONB NOT NULL,
                                      source          VARCHAR(20) NOT NULL,
                                      changed_by      UUID REFERENCES users(id),
                                      is_test         BOOLEAN NOT NULL DEFAULT false,
                                      changed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dsh_device_time ON device_state_history(device_id, changed_at DESC);
-- BR-MON-02: purge rows older than 30 days via scheduled job for edge deployments.

CREATE TABLE sensor_readings (              -- Entity #7
                                 id            BIGSERIAL PRIMARY KEY,
                                 device_id     UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                                 metric_type   VARCHAR(50) NOT NULL,     -- TEMPERATURE, HUMIDITY, LIGHT_LEVEL, MOTION...
                                 value         NUMERIC(10,3) NOT NULL,
                                 unit          VARCHAR(20),
                                 recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sensor_readings_device_time ON sensor_readings(device_id, recorded_at DESC);
-- NOTE (NFR 5.2.5): the SRS specifies sensor telemetry should be stored as
-- time-series in InfluxDB for Energy Analytics/trend charts. This table
-- keeps a relational copy for simple joins/reporting; at production scale,
-- either drop this table in favor of InfluxDB, or convert it to a
-- TimescaleDB hypertable (`SELECT create_hypertable('sensor_readings','recorded_at')`).

-- =====================================================================
-- 5. SCENE MANAGEMENT  (Entities #8 Scene, #9 SceneAction, #10 SceneSchedule)
--    Functional refs: 3.5.1–3.5.6 | BR-SCN-01..05, BR-EXE-01..02
-- =====================================================================

CREATE TABLE scenes (                       -- Entity #8
                        id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        home_id      UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                        name         VARCHAR(150) NOT NULL,
                        icon         VARCHAR(50),
                        created_by   UUID REFERENCES users(id),
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        UNIQUE (home_id, name)                   -- BR-SCN-01
);

CREATE TABLE scene_actions (                -- Entity #9
                               id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               scene_id      UUID NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
                               device_id     UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                               target_state  JSONB NOT NULL,
                               order_index   SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_scene_actions_scene ON scene_actions(scene_id);

CREATE TABLE scene_schedules (               -- Entity #10
                                 id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 scene_id        UUID NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
                                 scheduled_time  TIME NOT NULL,
                                 repeat_days     SMALLINT[] NOT NULL DEFAULT '{}',   -- 1=Mon ... 7=Sun
                                 is_active       BOOLEAN NOT NULL DEFAULT true,
                                 created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- 6. VOICE CONTROL  (Entity #11 VoiceCommand + supporting conversation table)
--    Functional refs: 3.6.1–3.6.11 | BR-VC-01..09
-- =====================================================================

CREATE TABLE voice_conversations (          -- supports multi-turn context (3.6.8/3.6.9/3.6.11)
                                     id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                     home_id      UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                     context      JSONB NOT NULL DEFAULT '{}',
                                     started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     ended_at     TIMESTAMPTZ
);

CREATE TABLE voice_commands (               -- Entity #11
                                id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                conversation_id     UUID REFERENCES voice_conversations(id) ON DELETE SET NULL,
                                user_id             UUID NOT NULL REFERENCES users(id),
                                home_id             UUID NOT NULL REFERENCES homes(id),
                                transcribed_text    TEXT,
                                detected_language   VARCHAR(10),
                                confidence_score    NUMERIC(5,4),                -- BR-VC-04 threshold check
                                intent               VARCHAR(100),
                                target_device_id    UUID REFERENCES devices(id),
                                target_location     VARCHAR(100),
                                action              VARCHAR(100),
                                parameters          JSONB,
                                status              VARCHAR(20) NOT NULL DEFAULT 'RECOGNIZED',
                                created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_voice_commands_user_time ON voice_commands(user_id, created_at DESC);

-- =====================================================================
-- 7. GESTURE CONTROL  (Entity #12 GestureEvent + supporting mapping table)
--    Functional refs: 3.6.12–3.6.17 | BR-GC-01..02
-- =====================================================================

CREATE TABLE gesture_mappings (             -- configurable mapping, BR-GC-02
                                  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  home_id       UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                  gesture_name  VARCHAR(100) NOT NULL,
                                  action        VARCHAR(100) NOT NULL,
                                  device_id     UUID REFERENCES devices(id),
                                  scene_id      UUID REFERENCES scenes(id),
                                  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  UNIQUE (home_id, gesture_name)
);

CREATE TABLE gesture_events (               -- Entity #12
                                id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                user_id              UUID REFERENCES users(id),
                                home_id              UUID NOT NULL REFERENCES homes(id),
                                recognized_gesture   VARCHAR(100),
                                confidence_score     NUMERIC(5,4),               -- BR-GC-01 threshold, e.g. >= 0.80
                                mapping_id           UUID REFERENCES gesture_mappings(id),
                                target_device_id     UUID REFERENCES devices(id),
                                action               VARCHAR(100),
                                parameters           JSONB,
                                execution_status     VARCHAR(20),
                                occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_gesture_events_user_time ON gesture_events(user_id, occurred_at DESC);

-- =====================================================================
-- 8. AUTOMATION  (Entities #13 AutomationRule, #14 RuleCondition,
--    #15 RuleAction, #16 AutomationSchedule, #17 AutomationExecution)
--    Functional refs: 3.8.1–3.8.7 | BR-AUTO-01..07
-- =====================================================================

CREATE TABLE automation_rules (             -- Entity #13
                                  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  home_id      UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                  name         VARCHAR(150) NOT NULL,
                                  trigger_type VARCHAR(20) NOT NULL,
                                  enabled      BOOLEAN NOT NULL DEFAULT false,     -- BR-AUTO-01
                                  created_by   UUID REFERENCES users(id),
                                  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rule_conditions (              -- Entity #14
                                 id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 rule_id        UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
                                 device_id      UUID REFERENCES devices(id),        -- sensor/device reference
                                 operator       VARCHAR(10) NOT NULL,                -- '=','>','<','>=','<=','!='
                                 value          VARCHAR(100) NOT NULL,
                                 logical_group  VARCHAR(10) DEFAULT 'AND',
                                 order_index    SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE rule_actions (                 -- Entity #15
                              id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              rule_id         UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
                              device_id       UUID REFERENCES devices(id),
                              scene_id        UUID REFERENCES scenes(id),
                              action_command  VARCHAR(100) NOT NULL,
                              parameters      JSONB,
                              order_index     SMALLINT NOT NULL DEFAULT 0,
                              CHECK (device_id IS NOT NULL OR scene_id IS NOT NULL)
);

CREATE TABLE automation_schedules (         -- Entity #16
                                      id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                      rule_id         UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
                                      scheduled_time  TIME NOT NULL,
                                      repeat_days     SMALLINT[] NOT NULL DEFAULT '{}',
                                      is_active       BOOLEAN NOT NULL DEFAULT true,
                                      created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE automation_executions (        -- Entity #17
                                       id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                       rule_id          UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
                                       trigger_source   VARCHAR(50),           -- SENSOR / SCHEDULE / TEST
                                       matched_at       TIMESTAMPTZ,
                                       started_at       TIMESTAMPTZ,
                                       completed_at     TIMESTAMPTZ,
                                       status           VARCHAR(20) NOT NULL,
                                       is_test          BOOLEAN NOT NULL DEFAULT false,   -- 3.8.5 Test Rule
                                       result_detail    JSONB                              -- per-action outcomes (Partial failure support)
);
CREATE INDEX idx_auto_exec_rule_time ON automation_executions(rule_id, matched_at DESC);

-- =====================================================================
-- 9. AI BEHAVIOR ANALYSIS  (Entity #18 BehaviorEvent, #19 AutomationRecommendation,
--    #20 ProactiveAction + supporting BehaviorPattern/BehaviorPrediction)
--    Functional refs: 3.9.1–3.9.9
-- =====================================================================

CREATE TABLE behavior_events (              -- Entity #18
                                 id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 home_id         UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                 user_id         UUID REFERENCES users(id),
                                 device_id       UUID REFERENCES devices(id),
                                 event_type      VARCHAR(50) NOT NULL,
                                 action          VARCHAR(100),
                                 previous_state  JSONB,
                                 current_state   JSONB,
                                 event_source    VARCHAR(50),
                                 occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_behavior_events_home_time ON behavior_events(home_id, occurred_at DESC);

-- Supporting tables (needed by 3.9.2–3.9.4 but not named as separate items
-- in the SRS's 23-entity summary table; included so recommendations have
-- somewhere to point their "source prediction or pattern" reference).
CREATE TABLE behavior_patterns (
                                   id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   home_id                UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                   user_id                UUID REFERENCES users(id),
                                   device_id              UUID REFERENCES devices(id),
                                   time_pattern           VARCHAR(100),
                                   context                JSONB,
                                   occurrence_frequency   INTEGER,
                                   confidence_score       NUMERIC(5,4),
                                   status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                                   created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE behavior_predictions (
                                      id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                      pattern_id        UUID NOT NULL REFERENCES behavior_patterns(id) ON DELETE CASCADE,
                                      home_id           UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                      device_id         UUID REFERENCES devices(id),
                                      predicted_action  VARCHAR(100),
                                      predicted_time    TIMESTAMPTZ,
                                      confidence_score  NUMERIC(5,4),
                                      status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                      created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE automation_recommendations (   -- Entity #19
                                            id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                            home_id                UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                            source_pattern_id      UUID REFERENCES behavior_patterns(id),
                                            source_prediction_id   UUID REFERENCES behavior_predictions(id),
                                            device_id              UUID REFERENCES devices(id),
                                            trigger_condition      JSONB,
                                            proposed_action        JSONB,
                                            explanation            TEXT,
                                            confidence_score       NUMERIC(5,4),
                                            status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                            created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                                            resolved_at            TIMESTAMPTZ,
                                            resolved_by            UUID REFERENCES users(id),
                                            rejection_reason       TEXT
);
CREATE INDEX idx_recommendations_home_status ON automation_recommendations(home_id, status);

CREATE TABLE proactive_actions (            -- Entity #20
                                   id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   recommendation_id   UUID REFERENCES automation_recommendations(id) ON DELETE CASCADE,
                                   home_id             UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                   device_id           UUID REFERENCES devices(id),
                                   trigger_condition   JSONB,
                                   action_command      JSONB,
                                   execution_status     VARCHAR(20),
                                   execution_time      TIMESTAMPTZ,
                                   overridden_by       UUID REFERENCES users(id),      -- 3.9.9 Manual Cancel/Override
                                   overridden_at       TIMESTAMPTZ,
                                   created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- 10. SECURITY, ANOMALY & NOTIFICATIONS  (Entities #21 SecurityEvent,
--     #22 DeviceAnomaly, #23 Notification)
--     Functional refs: 3.10.1–3.10.10 | BR-NOT-01..03
-- =====================================================================

CREATE TABLE security_events (              -- Entity #21 (covers Motion + Intrusion)
                                 id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 home_id       UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                 device_id     UUID REFERENCES devices(id),          -- reporting sensor
                                 event_type    VARCHAR(20) NOT NULL,
                                 location      VARCHAR(150),
                                 severity      VARCHAR(20),
                                 status        VARCHAR(20) NOT NULL DEFAULT 'DETECTED',
                                 detected_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_security_events_home_time ON security_events(home_id, detected_at DESC);

CREATE TABLE device_anomalies (             -- Entity #22
                                  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  home_id         UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
                                  device_id       UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                                  anomaly_type    VARCHAR(50) NOT NULL,
                                  current_value   VARCHAR(100),
                                  expected_range  VARCHAR(100),
                                  severity      VARCHAR(20),
                                  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
                                  detected_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_anomalies_home_time ON device_anomalies(home_id, detected_at DESC);

CREATE TABLE notifications (                -- Entity #23
    -- Also fulfils the "Send Security Alert" / "Send Anomaly Alert" delivery
    -- record described in 3.10.3/3.10.6 (alertId/recipientId/deliveryStatus),
    -- since the SRS's 23-entity list has no separate Alert entity.
                               id                               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               recipient_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                               home_id                          UUID REFERENCES homes(id) ON DELETE CASCADE,
                               type                             VARCHAR(20) NOT NULL,
                               title                            VARCHAR(150) NOT NULL,
                               message                          TEXT NOT NULL,
                               priority_level                   VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',  -- BR-NOT-01
                               source_security_event_id         UUID REFERENCES security_events(id),
                               source_anomaly_id                UUID REFERENCES device_anomalies(id),
                               source_automation_execution_id   UUID REFERENCES automation_executions(id),
                               is_read                          BOOLEAN NOT NULL DEFAULT false,
                               delivery_status                  VARCHAR(20) NOT NULL DEFAULT 'SENT',
                               created_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_recipient_time ON notifications(recipient_id, created_at DESC);
-- BR-NOT-02: purge rows older than 30 days via scheduled job.

-- =====================================================================
-- 11. AUDIT LOG  (required by NFR 5.2.5, not one of the 23 ERD entities
--     but explicitly mandated: "all critical actions must be logged")
-- =====================================================================

CREATE TABLE audit_logs (
                            id              BIGSERIAL PRIMARY KEY,
                            actor_user_id   UUID REFERENCES users(id),
                            home_id         UUID REFERENCES homes(id),
                            action          VARCHAR(100) NOT NULL,   -- LOGIN, DELETE_USER, UPDATE_DEVICE_CONFIG, TRIGGER_SECURITY_ALERT...
                            target_type     VARCHAR(50),
                            target_id       UUID,
                            detail          JSONB,
                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_actor_time ON audit_logs(actor_user_id, created_at DESC);

-- =====================================================================
-- 12. TRIGGERS FOR KEY BUSINESS RULES
-- =====================================================================

-- 12.1 Generic updated_at maintenance
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_homes_updated_at BEFORE UPDATE ON homes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_devices_updated_at BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_scenes_updated_at BEFORE UPDATE ON scenes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_rules_updated_at BEFORE UPDATE ON automation_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_patterns_updated_at BEFORE UPDATE ON behavior_patterns
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 12.2 BR-USER-07: Last Owner Protection — a home must always keep >= 1 OWNER
-- (renamed from "last Admin": the per-home full-control role is OWNER, not
-- ADMIN — ADMIN is now the separate system-level platform_role on users).
CREATE OR REPLACE FUNCTION enforce_last_owner() RETURNS TRIGGER AS $$
DECLARE
owner_count INTEGER;
  target_home UUID;
BEGIN
  target_home := COALESCE(OLD.home_id, NEW.home_id);

  IF (TG_OP = 'DELETE' AND OLD.role = 'OWNER')
     OR (TG_OP = 'UPDATE' AND OLD.role = 'OWNER' AND (NEW.role <> 'OWNER' OR NEW.status = 'DISABLED')) THEN
SELECT COUNT(*) INTO owner_count
FROM home_members
WHERE home_id = target_home AND role = 'OWNER' AND status = 'ACTIVE' AND id <> OLD.id;

IF owner_count = 0 THEN
      RAISE EXCEPTION 'BR-USER-07 violation: home % must keep at least one active Owner', target_home;
END IF;
END IF;

RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_last_owner_update
    BEFORE UPDATE ON home_members
    FOR EACH ROW EXECUTE FUNCTION enforce_last_owner();
CREATE TRIGGER trg_last_owner_delete
    BEFORE DELETE ON home_members
    FOR EACH ROW EXECUTE FUNCTION enforce_last_owner();

-- =====================================================================
-- END OF SCHEMA
-- =====================================================================