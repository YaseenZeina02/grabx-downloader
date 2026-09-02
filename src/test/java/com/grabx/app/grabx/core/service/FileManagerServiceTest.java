package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileManagerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void revealsFileInFinderOnMac() throws Exception {
        Path file = Files.createFile(tempDir.resolve("video.mp4"));
        List<List<String>> commands = new ArrayList<>();
        FileManagerService service = service("Mac OS X", commands, new AtomicInteger());

        service.reveal(file);

        assertEquals(List.of(List.of("open", "-R", file.toString())), commands);
    }

    @Test
    void revealsFileInExplorerOnWindows() throws Exception {
        Path file = Files.createFile(tempDir.resolve("video.mp4"));
        List<List<String>> commands = new ArrayList<>();
        FileManagerService service = service("Windows 11", commands, new AtomicInteger());

        service.reveal(file);

        assertEquals(List.of(List.of("explorer", "/select,", file.toString())), commands);
    }

    @Test
    void opensContainingFolderOnLinux() throws Exception {
        Path file = Files.createFile(tempDir.resolve("video.mp4"));
        List<List<String>> commands = new ArrayList<>();
        FileManagerService service = service("Linux", commands, new AtomicInteger());

        service.reveal(file);

        assertEquals(List.of(List.of("xdg-open", tempDir.toString())), commands);
    }

    @Test
    void reportsMissingFileWithoutLaunchingCommand() {
        List<List<String>> commands = new ArrayList<>();
        AtomicInteger notices = new AtomicInteger();
        FileManagerService service = service("Mac OS X", commands, notices);

        service.reveal(tempDir.resolve("missing.mp4"));

        assertTrue(commands.isEmpty());
        assertEquals(1, notices.get());
    }

    @Test
    void choosesPlatformFolderCommands() {
        Path folder = Path.of("downloads");

        assertEquals(List.of("open", "downloads"),
                FileManagerService.folderCommand("Mac OS X", folder));
        assertEquals(List.of("explorer", "downloads"),
                FileManagerService.folderCommand("Windows 11", folder));
        assertEquals(List.of("xdg-open", "downloads"),
                FileManagerService.folderCommand("Linux", folder));
    }

    private static FileManagerService service(
            String osName, List<List<String>> commands, AtomicInteger notices
    ) {
        return new FileManagerService(
                osName,
                command -> commands.add(List.copyOf(command)),
                folder -> false,
                Runnable::run,
                notices::incrementAndGet
        );
    }
}
