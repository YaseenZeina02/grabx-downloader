# GrabX refactoring plan

This document is the working map for reducing `MainController` to UI wiring and
moving application behavior into focused, testable components. Refactoring is
incremental: every extraction must compile and preserve the current user-visible
behavior before the next one begins.

## Current state

`MainController` is roughly 4,150 lines and currently combines FXML wiring with
download orchestration, URL probing, settings, playlist UI, thumbnail handling,
filesystem integration, and process utilities. Several services already exist,
so the first priority is to finish those boundaries instead of creating parallel
implementations.

## Target ownership

| Responsibility | Target owner | Current status |
| --- | --- | --- |
| Main window FXML fields and event wiring | `MainController` | Keep, then simplify |
| Download list filtering | `DownloadService` | Extracted; remove controller duplication |
| Pause/resume/cancel state | `DownloadStateCoordinator` | Extracted |
| Download execution and progress | `DownloadRunner` | Extracted; still depends on controller helpers |
| Download-row actions | `DownloadRowActions` | Extracted |
| Add-link dialog | `AddLinkDialogController` | Currently named `AddLinkDialogService`; finish extraction, then rename |
| Clipboard monitoring | `ClipboardService` | Extracted |
| Download history | `HistoryService` | Extracted; controller still supplies callbacks |
| Missing-file monitoring | `MissingWatcherService` | Extracted |
| Playlist window and selection UI | `PlaylistController` + playlist FXML | Still mostly in `MainController` |
| Playlist probing | `PlaylistProbeService` | Partially split across controller/probe classes |
| Playlist download queue | `PlaylistBatchService` | Extracted; controller still owns queue state |
| Settings UI and persistence | `SettingsController` + `SettingsService` | Still in `MainController` |
| Mini mode | `MiniModeController` | Still in `MainController` |
| yt-dlp execution | `YtDlpClient` | Commands and parsers are currently duplicated |
| FFmpeg discovery | `FfmpegManager` | Extracted |
| Thumbnail loading/cache | `ThumbnailCacheManager` | Extracted; controller still has UI helpers |
| Native file dialogs/reveal | `NativeDialogs` / platform service | Partially extracted |
| In-scene hover UI | `HoverTooltipService` | Extracted |

## Safe extraction order

1. Remove unused code and temporary diagnostics.
2. Introduce consistent application logging, starting with process execution.
3. Centralize yt-dlp command execution and result handling.
4. Finish settings extraction.
5. Extract mini mode.
6. Extract playlist UI and move its queue state to `PlaylistBatchService`.
7. Remove controller callbacks that are no longer necessary.
8. Reduce `MainController` to initialization, bindings, and navigation.

## Refactoring rules

- Do not combine behavior changes with code movement.
- Build after every extraction and manually smoke-test the affected flow.
- Keep each commit focused and independently reversible.
- Do not swallow operational failures silently; log context at the boundary that
  can explain the failure.
- Avoid adding another yt-dlp command path while centralization is in progress.
- Preserve the user's download history and preferences formats.

## Smoke-test checklist

- Application starts and the main FXML loads.
- Add-link dialog probes a single video.
- Video and audio downloads start and report progress.
- Pause, resume, cancel, retry, and clear still work.
- Playlist opens, probes entries, and starts a selected batch.
- History is restored after restarting GrabX.
- Completed downloads can be revealed in the file manager.
