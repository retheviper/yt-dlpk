# yt-dlpk (Kotlin + Compose Desktop)

A cross-platform `yt-dlp` desktop GUI built with Kotlin + Compose Multiplatform (Windows/macOS/Linux).

Language versions:
- English: `README.md`
- Japanese: `README.ja.md`
- Korean: `README.ko.md`

## Current Scope (MVP+)

- URL input + Analyze
  - Fetches metadata (`title/channel/duration/thumbnail/playlist`) via `yt-dlp --dump-single-json`
  - Fetches formats via `yt-dlp -F`
- Format selection UI
  - Tabs: `Video+Audio / Video only / Audio only`
  - Table-like list with columns: `Format | Resolution | Bitrate`
  - Sorted by higher resolution/bitrate first
  - Selected row is highlighted with a colored dot marker
  - Default selection (Video+Audio): highest resolution first
- Download options
  - Subtitles: `--write-subs --write-auto-subs --sub-lang --convert-subs srt`
  - Audio extraction: `-x --audio-format` (dropdown)
  - Output folder chooser
  - Filename template
  - Playlist mode toggle (`Playlist all / Single only`)
  - Merge output format dropdown
- Progress and control
  - Download progress via `--newline`
  - Cancel support
  - Analyze status text animation in Progress area
  - Download button disabled until Analyze is completed
- Tool management
  - Detects app-managed binaries and system PATH binaries
  - Auto-downloads `yt-dlp` / `ffmpeg` if missing
  - `tool-sources.json` is configurable
  - Global tab includes: installed versions, latest check, update actions
  - If tool is system-managed, update action shows guidance dialog
- UI/UX updates
  - Thumbnail preview with playlist fallback to first entry thumbnail
  - Fixed minimum/initial window size
  - Theme options: `SYSTEM / DARK / LIGHT`
  - Live language switching: `English / 日本語 / 한국어`

## Architecture

- UI layer (Compose) separated from domain/services
- State management with `StateFlow`
- Async process execution with coroutine-based process runner
- Core services:
  - `FormatService`
  - `YtDlpCommandBuilder`
  - `YtDlpService`
  - `ToolManager`

## Run

```bash
./gradlew run
```

## Package

```bash
./gradlew packageDistributionForCurrentOS
```

Configured targets: `Dmg`, `Msi`, `Deb`, `Rpm`.

## Manual Test Checklist

1. Run `./gradlew run`
2. Enter a URL, click `Analyze`
3. Confirm metadata/thumbnail and format list are shown
4. Confirm default selected format is highest resolution in Video+Audio
5. Change tab and select another format row
6. Start download and confirm progress updates
7. Cancel and confirm stop behavior
8. Verify subtitle/audio extraction/output format options
9. Open Global tab and test latest-check/update buttons
10. Switch language/theme and verify immediate UI update

## Notes / Known Limitations

- `yt-dlp -F` output differs by site; parser is heuristic-based
- ffmpeg provider asset naming can change over time
- Thumbnail loading depends on source URL accessibility

## Key Files

- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `src/desktopMain/kotlin/com/ytdlpk/app/Main.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/ui/App.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/ui/AppViewModel.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/FormatService.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/YtDlpCommandBuilder.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/YtDlpService.kt`
- `src/desktopMain/kotlin/com/ytdlpk/app/service/ToolManager.kt`
- `src/desktopMain/resources/tool-sources.json`
