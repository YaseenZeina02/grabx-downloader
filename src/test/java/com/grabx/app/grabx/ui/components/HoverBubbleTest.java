package com.grabx.app.grabx.ui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoverBubbleTest {

    @Test
    void showsBubbleOnlyWhenTextExceedsItsAvailableWidth() {
        assertTrue(HoverBubble.isOverflowing(121, 100));
        assertFalse(HoverBubble.isOverflowing(100, 100));
        assertFalse(HoverBubble.isOverflowing(100.5, 100));
        assertFalse(HoverBubble.isOverflowing(100, 0));
    }
}
