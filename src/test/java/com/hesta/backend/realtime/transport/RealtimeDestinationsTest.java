package com.hesta.backend.realtime.transport;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeDestinationsTest {

    @Test
    void homeEvents_withHomeId_buildsAndParsesHomeScopedDestination() {
        UUID homeId = UUID.randomUUID();
        String destination = RealtimeDestinations.homeEvents(homeId);

        assertThat(destination).isEqualTo("/topic/homes/" + homeId + "/events");
        assertThat(RealtimeDestinations.extractHomeId(destination)).contains(homeId);
    }

    @Test
    void extractHomeId_withNonHomeOrMalformedDestination_returnsEmpty() {
        assertThat(RealtimeDestinations.extractHomeId("/topic/events")).isEmpty();
        assertThat(RealtimeDestinations.extractHomeId("/topic/homes/not-a-uuid/events")).isEmpty();
        assertThat(RealtimeDestinations.extractHomeId("/topic/homes/"
                + UUID.randomUUID() + "/events/extra")).isEmpty();
    }
}
