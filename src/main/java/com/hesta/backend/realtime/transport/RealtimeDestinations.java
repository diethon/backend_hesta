package com.hesta.backend.realtime.transport;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RealtimeDestinations {

    public static final String WEBSOCKET_ENDPOINT = "/ws";
    public static final String TOPIC_PREFIX = "/topic";
    public static final String QUEUE_PREFIX = "/queue";
    public static final String USER_PREFIX = "/user";
    public static final String APPLICATION_PREFIX = "/app";

    private static final String HOME_EVENTS_PREFIX = TOPIC_PREFIX + "/homes/";
    private static final String USER_HOME_EVENTS_PREFIX = USER_PREFIX + QUEUE_PREFIX + "/homes/";
    private static final String QUEUE_HOME_EVENTS_PREFIX = QUEUE_PREFIX + "/homes/";
    private static final String HOME_EVENTS_SUFFIX = "/events";

    private RealtimeDestinations() {
    }

    public static String homeEvents(UUID homeId) {
        return HOME_EVENTS_PREFIX + Objects.requireNonNull(homeId, "homeId must not be null")
                + HOME_EVENTS_SUFFIX;
    }

    public static String userHomeEvents(UUID homeId) {
        return QUEUE_HOME_EVENTS_PREFIX + Objects.requireNonNull(homeId, "homeId must not be null")
                + HOME_EVENTS_SUFFIX;
    }

    public static boolean isSharedTopic(String destination) {
        return destination != null && destination.startsWith(HOME_EVENTS_PREFIX) && destination.endsWith(HOME_EVENTS_SUFFIX);
    }

    public static Optional<UUID> extractHomeId(String destination) {
        if (destination == null || !destination.endsWith(HOME_EVENTS_SUFFIX)) {
            return Optional.empty();
        }

        String prefix;
        if (destination.startsWith(HOME_EVENTS_PREFIX)) {
            prefix = HOME_EVENTS_PREFIX;
        } else if (destination.startsWith(USER_HOME_EVENTS_PREFIX)) {
            prefix = USER_HOME_EVENTS_PREFIX;
        } else if (destination.startsWith(QUEUE_HOME_EVENTS_PREFIX)) {
            prefix = QUEUE_HOME_EVENTS_PREFIX;
        } else {
            return Optional.empty();
        }

        String homeId = destination.substring(
                prefix.length(),
                destination.length() - HOME_EVENTS_SUFFIX.length()
        );
        if (homeId.contains("/")) {
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(homeId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
