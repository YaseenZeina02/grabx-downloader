package com.grabx.app.grabx.ui.components;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileTypePreviewTest {
    @Test void actualFileTypeWinsOverGenericVideoOrAudioMode() {
        assertEquals(FileTypePreview.Kind.IMAGE, FileTypePreview.describe("Photo.PNG", "Video").kind());
        assertEquals(FileTypePreview.Kind.PDF, FileTypePreview.describe("Guide.pdf", "Audio only").kind());
        assertEquals(FileTypePreview.Kind.SHEET, FileTypePreview.describe("Report.xlsx", "Video").kind());
        assertEquals(FileTypePreview.Kind.VIDEO, FileTypePreview.describe("Movie.mp4", "Audio only").kind());
    }

    @Test void derivesReadableFormatFromBasenameAndCompoundArchiveSuffix() {
        assertEquals(new FileTypePreview.Format(FileTypePreview.Kind.ARCHIVE, "TAR.GZ"),
                FileTypePreview.describe("/folder.v1/backup.TAR.GZ", "Direct"));
        assertEquals(new FileTypePreview.Format(FileTypePreview.Kind.APP, "MSI"),
                FileTypePreview.describe("C:\\Downloads\\installer.MSI", "Direct"));
        assertEquals("PDF", FileTypePreview.describe("كتاب.pdf", "Direct").caption());
        assertEquals("FILE", FileTypePreview.describe("/folder.zip/README", "Direct").caption());
    }

    @Test void unknownNamesHaveSafeFallbacksAndModeStillWorksWithoutAnExtension() {
        assertEquals("VIDEO", FileTypePreview.describe("Untitled", "Video").caption());
        assertEquals("AUDIO", FileTypePreview.describe(null, "Audio only").caption());
        assertEquals(new FileTypePreview.Format(FileTypePreview.Kind.FILE, "BIN"),
                FileTypePreview.describe("payload.bin", "Direct"));
        assertEquals("FILE", FileTypePreview.describe(".gitignore", null).caption());
        assertEquals("FILE", FileTypePreview.describe("download.verylongextension", null).caption());
        assertEquals("FILE", FileTypePreview.describe("download.bad\nlabel", null).caption());
    }
}
