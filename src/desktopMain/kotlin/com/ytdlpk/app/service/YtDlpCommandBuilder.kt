package com.ytdlpk.app.service

import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import java.util.Locale

class YtDlpCommandBuilder(
    private val formatService: FormatService
) {
    fun build(
        ytDlpPath: String,
        ffmpegPath: String,
        options: DownloadOptions
    ): List<String> {
        validatePathOption("outputDirectory", options.outputDirectory)
        validatePathOption("fileNameTemplate", options.fileNameTemplate)

        val cmd = mutableListOf(
            ytDlpPath,
            "--newline",
            "--ffmpeg-location", ffmpegPath,
            "-o", "${options.outputDirectory}/${options.fileNameTemplate}"
        )

        if (shouldForceMergeOutputFormat(options)) {
            cmd += listOf("--merge-output-format", options.mergeOutputFormat)
        }

        if (options.playlistMode == PlaylistMode.SINGLE) {
            cmd += "--no-playlist"
        }

        val formatSelector = options.quickFormatSelector
            ?: options.selectedFormat?.let { selected ->
                formatService.buildFormatSelector(
                    selected = selected,
                    selectedTab = options.selectedFormatTab,
                    pairedVideoOnly = options.selectedVideoOnlyFormat,
                    pairedAudioOnly = options.selectedAudioOnlyFormat,
                    videoCodecPreference = options.videoCodecPreference
                )
            }
        formatSelector?.let {
            cmd += listOf("-f", it)
        }

        if (options.extractAudio) {
            cmd += listOf("-x", "--audio-format", options.audioFormat)
        }

        if (options.includeAutoSubs) {
            cmd += listOf("--write-auto-subs", "--write-subs", "--sub-lang", options.subLang, "--convert-subs", "srt")
        }

        cmd += options.url
        return cmd
    }

    private fun validatePathOption(name: String, value: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        val forbiddenTokens = listOf('\u0000', '\n', '\r')
        require(forbiddenTokens.none { value.contains(it) }) { "$name contains invalid control characters" }
        require(!value.contains("\$(") && !value.contains('`')) {
            "$name contains disallowed shell-like template tokens"
        }
    }

    private fun shouldForceMergeOutputFormat(options: DownloadOptions): Boolean {
        val mergeOutputFormat = options.mergeOutputFormat.lowercase(Locale.ROOT)
        if (mergeOutputFormat.isBlank() || options.extractAudio) return false
        if (mergeOutputFormat != "mp4") return true
        if (options.quickFormatSelector != null) return false

        val selected = options.selectedFormat ?: return false
        return when (selected.kind) {
            FormatKind.VIDEO_ONLY -> {
                val audio = options.selectedAudioOnlyFormat
                selected.isMp4CompatibleVideo() && audio != null && audio.isMp4CompatibleAudio()
            }
            FormatKind.AUDIO_ONLY -> false
            FormatKind.VIDEO_AUDIO -> selected.isMp4CompatibleVideo()
            FormatKind.UNKNOWN -> false
        }
    }

    private fun FormatEntry.isMp4CompatibleVideo(): Boolean {
        val ext = ext.lowercase(Locale.ROOT)
        val codec = (vcodec ?: rawText).lowercase(Locale.ROOT)
        return ext in MP4_VIDEO_EXTENSIONS &&
            (codec.isBlank() ||
                codec.startsWith("avc1") ||
                codec.startsWith("h264") ||
                codec.contains(" h264") ||
                codec.contains("h.264") ||
                codec.startsWith("hev1") ||
                codec.startsWith("hvc1") ||
                codec.contains(" hevc") ||
                codec.contains("h.265"))
    }

    private fun FormatEntry.isMp4CompatibleAudio(): Boolean {
        val ext = ext.lowercase(Locale.ROOT)
        val codec = (acodec ?: rawText).lowercase(Locale.ROOT)
        return ext in MP4_AUDIO_EXTENSIONS ||
            codec.startsWith("mp4a") ||
            codec.contains(" aac") ||
            codec.contains("alac")
    }

    private companion object {
        private val MP4_VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov")
        private val MP4_AUDIO_EXTENSIONS = setOf("m4a", "aac", "alac")
    }
}
