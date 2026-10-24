package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveConnectionsTest {
    private long now, bytes;
    private int window(AdaptiveConnections policy, long rate) {
        now += AdaptiveConnections.WINDOW;
        bytes += rate * 4;
        return policy.sample(bytes, now, 0, true);
    }

    @Test void scalesTo64OnlyWhenMeasuredThroughputImproves() {
        var policy = new AdaptiveConnections(64);
        policy.reset(0, 0);
        assertEquals(4, policy.connections());
        assertEquals(8, window(policy, 100_000));
        assertEquals(16, window(policy, 180_000));
        assertEquals(32, window(policy, 300_000));
        assertEquals(64, window(policy, 500_000));
        assertEquals(64, window(policy, 800_000));
        assertEquals(64, window(policy, 800_000));
    }

    @Test void unhelpfulExtraConnectionsRollBackAndWaitBeforeTryingAgain() {
        var policy = new AdaptiveConnections(64);
        policy.reset(0, 0);
        assertEquals(8, window(policy, 100_000));
        assertEquals(4, window(policy, 103_000));
        for (int i = 0; i < 7; i++) assertEquals(4, window(policy, 100_000));
        assertEquals(8, window(policy, 100_000));
    }

    @Test void sustainedDropReducesConnectionsAndPauseClearsOldMeasurements() {
        var policy = new AdaptiveConnections(8);
        policy.reset(0, 0);
        assertEquals(8, window(policy, 100_000));
        assertEquals(8, window(policy, 200_000));
        assertEquals(8, window(policy, 100_000));
        assertEquals(4, window(policy, 100_000));
        policy.reset(bytes, now + 100_000_000_000L);
        assertEquals(4, policy.connections());
    }

    @Test void sharedSpeedLimitAndLastPiecesDoNotTriggerExperiments() {
        var policy = new AdaptiveConnections(64);
        policy.reset(0, 0);
        assertEquals(4, policy.sample(1_000_000, 10_000_000_000L, 500_000, true));
        assertEquals(4, policy.sample(2_000_000, 20_000_000_000L, 0, false));
        assertEquals(4, policy.sample(2_000_000, 24_000_000_000L, 0, true));
        assertEquals(8, policy.sample(3_000_000, 28_000_000_000L, 0, true));
    }

    @Test void oldSmallLayoutsAndRetryCeilingsRemainRespected() {
        var single = new AdaptiveConnections(1);
        assertEquals(1, window(single, 100_000));
        var reduced = new AdaptiveConnections(3);
        reduced.reset(bytes, now);
        assertEquals(3, window(reduced, 100_000));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConnections(65));
    }
}
