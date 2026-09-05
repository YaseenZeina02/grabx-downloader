package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferSpeedMeterTest {
    @Test void shortGapDoesNotShowZeroButRealStallDoes() {
        var meter = new TransferSpeedMeter(17000000, 0);
        assertEquals(1000, meter.sample(17001000, 1000000000L));
        assertTrue(meter.sample(17001000, 1400000000L) > 0);
        assertEquals(0, meter.sample(17001000, 6000000000L));
        assertTrue(meter.sample(17002000, 7000000000L) > 0);
    }
    @Test void pauseResetExcludesSavedBytesAndPausedTime() {
        var meter = new TransferSpeedMeter(0, 0);
        meter.sample(1000, 1000000000L);
        meter.reset(1000, 9000000000L);
        assertEquals(1000, meter.sample(2000, 10000000000L));
    }
}
