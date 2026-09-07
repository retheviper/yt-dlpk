# Tool management

The application package contains the JVM, application code, and a download-source manifest. It does **not** contain yt-dlp, FFmpeg, or Deno executables. On first use, working system installations take precedence; missing tools are downloaded into `~/.yt-dlpk/tools/bin`. Subsequent launches reuse these files and can prepare tools without network access.

## Required tools and sources

| Tool | Distribution |
| --- | --- |
| yt-dlp | [Official standalone releases](https://github.com/yt-dlp/yt-dlp#release-files), including Python and EJS scripts. Windows and Linux select x64/ARM64; macOS uses the universal executable. |
| ffmpeg + ffprobe | Both must work in the same directory. Windows/Linux use [BtbN static GPL builds](https://github.com/BtbN/FFmpeg-Builds). Intel macOS uses [Evermeet](https://evermeet.cx/ffmpeg/). Apple Silicon uses [Martin Riedl's native release builds](https://ffmpeg.martin-riedl.de/). |
| Deno | A working Deno 2.3+ installation, otherwise the native [official release](https://github.com/denoland/deno/releases). Its resolved absolute path is passed to yt-dlp for analysis and downloads. |

The executable URL matrix is maintained in `src/desktopMain/resources/tool-sources.json`. Linux remains experimental. The [yt-dlp EJS setup guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS) explains the YouTube runtime requirement. The app does not request remote EJS components; official standalone yt-dlp distributions already include them.

## Installation and updates

- Finder-launched apps search the usual Homebrew and Deno directories in addition to PATH. Subprocesses receive these search paths too.
- FFmpeg readiness includes ffprobe. An older managed installation missing ffprobe is repaired on the next setup.
- Downloads and extraction use a separate staging directory. Every executable is run with its version argument before any installed file is replaced. FFmpeg and ffprobe are validated together; a combined archive is downloaded once. Replacement failures attempt to restore the previous files.
- Temporary payloads are removed after success or failure. Archives are streamed to disk instead of loading whole executables into memory.
- Setup and managed updates share a lock per tool. Updating one managed tool does not download or replace unrelated tools. Tool-update actions are unavailable during analysis/downloads, and duplicate clicks are ignored.
- System updates use the existing package-manager/self-update path. Managed tools are updated within the app directory.
- GUI commands ignore unrelated yt-dlp CLI configuration and delimit the URL with `--`, so GUI settings and URL input retain their meaning.

## Verification

`./gradlew desktopTest` runs deterministic regression tests, including failed installation, missing ffprobe, concurrent setup, runtime selection, cancellation, immediate retry, and URL changes. Tests requiring POSIX fixture executables run on macOS/Linux.

The optional live check downloads real tools into an isolated directory, with system-tool discovery disabled:

```sh
YTDLPK_TOOL_SMOKE_HOME=/tmp/yt-dlpk-managed-smoke \
  ./gradlew desktopTest --tests '*ManagedToolsSmokeTest*' --rerun-tasks
```

This check requires network access and keeps the downloaded tools at the supplied path for further media-processing checks. It does not modify the normal app data directory.
