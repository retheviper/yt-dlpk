package com.ytdlpk.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ThemeMode {
    @SerialName("SYSTEM")
    SYSTEM,

    @SerialName("DARK")
    DARK,

    @SerialName("LIGHT")
    LIGHT
}

@Serializable
enum class AppLanguage {
    @SerialName("English")
    ENGLISH,

    @SerialName("日本語")
    JAPANESE,

    @SerialName("한국어")
    KOREAN
}

enum class FormatKind {
    VIDEO_AUDIO,
    VIDEO_ONLY,
    AUDIO_ONLY,
    UNKNOWN
}

data class FormatEntry(
    val formatId: String,
    val ext: String,
    val resolution: String?,
    val fps: Int?,
    val vcodec: String?,
    val acodec: String?,
    val tbrKbps: Double?,
    val abrKbps: Double?,
    val note: String?,
    val kind: FormatKind,
    val rawText: String
)

@Serializable
enum class PlaylistMode {
    @SerialName("PLAYLIST")
    PLAYLIST,

    @SerialName("SINGLE")
    SINGLE
}

@Serializable
enum class QuickQualityProfile {
    @SerialName("BEST")
    BEST,

    @SerialName("UP_TO_2160P")
    UP_TO_2160P,

    @SerialName("UP_TO_1440P")
    UP_TO_1440P,

    @SerialName("UP_TO_1080P")
    UP_TO_1080P,

    @SerialName("UP_TO_720P")
    UP_TO_720P,

    @SerialName("AUDIO_ONLY")
    AUDIO_ONLY
}

@Serializable
enum class HomeTab {
    @SerialName("STANDARD")
    STANDARD,

    @SerialName("QUICK")
    QUICK,

    @SerialName("SETTINGS")
    SETTINGS
}

data class VideoMetadata(
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val isPlaylist: Boolean,
    val playlistCount: Int?
)

data class ProgressInfo(
    val percent: Double?,
    val speed: String?,
    val eta: String?,
    val currentFile: String?,
    val itemIndex: Int?,
    val itemTotal: Int?
)

@Serializable
data class AppSettings(
    val outputDirectory: String = System.getProperty("user.home"),
    val fileNameTemplate: String = "%(title)s.%(ext)s",
    val includeAutoSubs: Boolean = false,
    val subLang: String = "en.*",
    val extractAudio: Boolean = false,
    val audioFormat: String = "mp3",
    val mergeOutputFormat: String = "mp4",
    val quickQualityProfile: QuickQualityProfile = QuickQualityProfile.UP_TO_1080P,
    val quickPlaylistMode: PlaylistMode = PlaylistMode.PLAYLIST,
    val quickDownloadOnPaste: Boolean = false,
    val notifyOnDownloadCompleteWhenInactive: Boolean = true,
    val homeTab: HomeTab = HomeTab.STANDARD,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH
)

data class ToolPaths(
    val ytDlpPath: String,
    val ffmpegPath: String,
    val ytDlpManaged: Boolean,
    val ffmpegManaged: Boolean
)

data class DownloadOptions(
    val url: String,
    val playlistMode: PlaylistMode,
    val selectedFormatTab: FormatKind,
    val outputDirectory: String,
    val fileNameTemplate: String,
    val selectedFormat: FormatEntry?,
    val selectedVideoOnlyFormat: FormatEntry?,
    val selectedAudioOnlyFormat: FormatEntry?,
    val quickFormatSelector: String? = null,
    val includeAutoSubs: Boolean,
    val subLang: String,
    val extractAudio: Boolean,
    val audioFormat: String,
    val mergeOutputFormat: String
)
