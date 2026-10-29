package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.util.WindowBounds;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowBoundsTest {
    @Test void oversizedFrameFitsScaledLaptopWorkArea() {
        var area = new Rectangle2D(0, 0, 1093, 574);
        assertEquals(area, WindowBounds.fit(new Rectangle2D(150, 50, 1280, 820), area));
    }

    @Test void disconnectedMonitorFrameMovesToAvailableWorkArea() {
        assertEquals(new Rectangle2D(-1000, 30, 900, 500), WindowBounds.fit(
                new Rectangle2D(1800, -80, 900, 500), new Rectangle2D(-1600, 30, 1500, 850)));
    }

    @Test void validFrameKeepsUserSizeAndPosition() {
        var requested = new Rectangle2D(100, 50, 950, 600);
        assertEquals(requested, WindowBounds.fit(requested, new Rectangle2D(0, 0, 1366, 728)));
    }
}
