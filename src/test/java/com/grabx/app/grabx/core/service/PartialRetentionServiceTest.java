package com.grabx.app.grabx.core.service;
import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
class PartialRetentionServiceTest {
    @TempDir Path directory;
    @Test void expiresPausedPartsButKeepsActiveDownloadsAndCompletedFiles() throws Exception {
        var items = FXCollections.<DownloadRow>observableArrayList();
        for (var state : new DownloadRow.State[]{DownloadRow.State.PAUSED, DownloadRow.State.DOWNLOADING,
                DownloadRow.State.COMPLETED}) {
            var row = new DownloadRow("url", state.name(), 1, directory.toString(), "Direct", "Auto");
            row.outputFile.set(directory.resolve(state.name()));
            row.setState(state);
            Files.writeString(row.outputFile.get(), "final file");
            Path part = Files.writeString(DirectPartialFiles.part(row.outputFile.get(), 0), "part");
            Files.setLastModifiedTime(part, FileTime.fromMillis(1));
            items.add(row);
        }
        var service = new PartialRetentionService(items, new HashMap<>(), row -> fail("No active process"),
                () -> 7, () -> {});
        service.sweep();
        assertFalse(DirectPartialFiles.hasParts(items.get(0).outputFile.get()));
        assertEquals(DownloadRow.State.CANCELLED, items.get(0).getState());
        assertTrue(DirectPartialFiles.hasParts(items.get(1).outputFile.get()));
        assertTrue(DirectPartialFiles.hasParts(items.get(2).outputFile.get()));
        for (var row : items) assertEquals("final file", Files.readString(row.outputFile.get()));
    }
}
