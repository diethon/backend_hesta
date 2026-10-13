package com.hesta.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> time;
    private final ZoneId zone;

    public MutableClock(Instant initialTime) {
        this(new AtomicReference<>(initialTime), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> time, ZoneId zone) {
        this.time = time;
        this.zone = zone;
    }

    public void set(Instant instant) { time.set(instant); }
    public void advance(Duration duration) { time.updateAndGet(now -> now.plus(duration)); }
    @Override public Instant instant() { return time.get(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(time, zone); }
}
