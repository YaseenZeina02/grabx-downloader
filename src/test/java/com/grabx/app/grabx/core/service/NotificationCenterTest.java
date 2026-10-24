package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class NotificationCenterTest {
    static class MutableClock extends Clock {
        Instant value = Instant.parse("2026-10-16T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
        void advance(long seconds) { value = value.plusSeconds(seconds); }
    }
    @Test void groupsBurstsCountsUnreadAndRetainsAtMostFiftyEvents() {
        var clock = new MutableClock();
        var center = new NotificationCenter(NotificationPreferences.Values.defaults(), clock);
        center.publish(NotificationCenter.Kind.COMPLETED, "Complete", "one.mp4");
        clock.advance(1); center.publish(NotificationCenter.Kind.COMPLETED, "Complete", "two.mp4");
        assertEquals(1, center.entries().size()); assertEquals(2, center.entries().getFirst().count());
        assertEquals(1, center.unreadProperty().get());
        center.markRead(); assertEquals(0, center.unreadProperty().get());
        clock.advance(1); center.publish(NotificationCenter.Kind.COMPLETED, "Complete", "three.mp4");
        assertEquals(3, center.entries().getFirst().count()); assertEquals(1, center.unreadProperty().get());
        for (int i = 0; i < 60; i++) { clock.advance(4); center.publish(NotificationCenter.Kind.FAILED, "Failed", "file " + i); }
        assertEquals(50, center.entries().size()); assertEquals(50, center.unreadProperty().get());
        assertThrows(UnsupportedOperationException.class, () -> center.entries().clear());
        center.clear(); assertTrue(center.entries().isEmpty()); assertEquals(0, center.unreadProperty().get());
    }
    @Test void categoryPreferencesAndBannersAreIndependentAndShutdownStaysQuiet() {
        var center = new NotificationCenter(new NotificationPreferences.Values(true, false, true, false, false));
        var shown = new ArrayList<NotificationCenter.Entry>(); center.onBanner(shown::add);
        center.publish(NotificationCenter.Kind.COMPLETED, "Complete", "file");
        center.publish(NotificationCenter.Kind.RECONNECTING, "Retry", "file");
        center.connectionChanged(false);
        center.publish(NotificationCenter.Kind.FAILED, "Failed", "file");
        assertEquals(1, center.entries().size()); assertTrue(shown.isEmpty());
        center.configure(NotificationPreferences.Values.defaults());
        center.connectionChanged(true); assertEquals(1, shown.size());
        center.configure(new NotificationPreferences.Values(false, true, true, true, true));
        assertFalse(center.monitorsConnection()); assertFalse(center.isOffline());
        center.publish(NotificationCenter.Kind.FAILED, "Failed", "file"); assertEquals(2, center.entries().size());
        center.close(); center.configure(NotificationPreferences.Values.defaults());
        assertFalse(center.monitorsConnection());
        center.publish(NotificationCenter.Kind.COMPLETED, "Complete", "file"); assertEquals(2, center.entries().size());
    }
    @Test void removesOnlySelectedEntryAndRecountsUnread() {
        var clock = new MutableClock();
        var center = new NotificationCenter(NotificationPreferences.Values.defaults(), clock);
        center.publish(NotificationCenter.Kind.FAILED, "Failed", "one");
        var first = center.entries().getFirst();
        clock.advance(5); center.publish(NotificationCenter.Kind.COMPLETED, "Done", "two");
        var second = center.entries().getFirst();
        center.remove(first);
        assertEquals(java.util.List.of(second), center.entries());
        assertEquals(1, center.unreadProperty().get());
        center.remove(first); assertEquals(1, center.entries().size());
        center.markRead(); center.remove(center.entries().getFirst());
        assertTrue(center.entries().isEmpty()); assertEquals(0, center.unreadProperty().get());
    }
    @Test void preferencesRoundTripAndDoNotTouchSoundSettings() {
        var prefs = new SoundFeedbackServiceTest.MemoryPreferences();
        prefs.putBoolean("grabx.sounds.enabled", false);
        var storage = new NotificationPreferences(prefs);
        assertEquals(NotificationPreferences.Values.defaults(), storage.load());
        var saved = new NotificationPreferences.Values(true, false, true, false, true);
        storage.save(saved); assertEquals(saved, new NotificationPreferences(prefs).load());
        assertFalse(prefs.getBoolean("grabx.sounds.enabled", true));
    }
}
