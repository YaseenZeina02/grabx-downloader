package com.grabx.app.grabx.ui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IconButtonServiceTest {
    @Test
    void providesEveryDownloadRowIcon() {
        DownloadListViewService.Icons icons = IconButtonService.downloadIcons();
        List<String> paths = List.of(
                icons.pause(), icons.resume(), icons.cancel(), icons.openLink(),
                icons.folder(), icons.retry(), icons.clear()
        );
        assertEquals(7, paths.stream().distinct().count());
        paths.forEach(path -> assertFalse(path.isBlank()));
    }
}
