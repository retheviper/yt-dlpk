package com.ytdlpk.app.model

data class AppState(
    val url: String = "",
    val isAnalyzing: Boolean = false,
    val isDownloading: Boolean = false,
    val metadata: VideoMetadata? = null,
    val formats: List<FormatEntry> = emptyList(),
    val selectedFormatTab: FormatKind = FormatKind.VIDEO_AUDIO,
    val selectedFormatId: String? = null,
    val playlistMode: PlaylistMode = PlaylistMode.PLAYLIST,
    val settings: AppSettings = AppSettings(),
    val progress: ProgressInfo = ProgressInfo(
        percent = null,
        speed = null,
        eta = null,
        currentFile = null,
        itemIndex = null,
        itemTotal = null
    ),
    val logs: List<String> = emptyList(),
    val lastError: String? = null,
    val infoMessage: String? = null,
    val toolsReady: Boolean = false,
    val toolStatus: String = "Checking tools...",
    val ytDlpVersion: String = "-",
    val ffmpegVersion: String = "-",
    val latestYtDlpVersion: String = "-",
    val latestFfmpegVersion: String = "-",
    val checkingLatestTools: Boolean = false
) {
    val selectedFormat: FormatEntry?
        get() = formats.firstOrNull { it.formatId == selectedFormatId }

    val filteredFormats: List<FormatEntry>
        get() = formats.filter { entry ->
            when (selectedFormatTab) {
                FormatKind.VIDEO_AUDIO -> entry.kind == FormatKind.VIDEO_AUDIO
                FormatKind.VIDEO_ONLY -> entry.kind == FormatKind.VIDEO_ONLY
                FormatKind.AUDIO_ONLY -> entry.kind == FormatKind.AUDIO_ONLY
                FormatKind.UNKNOWN -> true
            }
        }
}
