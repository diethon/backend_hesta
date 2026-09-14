package com.hesta.backend.service;

import java.util.UUID;

public interface RealtimeSubscriptionService {
    void authorizeSubscription(UUID userId, UUID homeId);
}
