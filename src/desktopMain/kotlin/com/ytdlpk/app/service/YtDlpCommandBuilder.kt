package com.ytdlpk.app.service

import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.PlaylistMode

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
            "-o", "${options.outputDirectory}/${options.fileNameTemplate}",
            "--merge-output-format", options.mergeOutputFormat
        )

        if (options.playlistMode == PlaylistMode.SINGLE) {
            cmd += "--no-playlist"
        }

        options.selectedFormat?.let { selected ->
            val selector = formatService.buildFormatSelector(
                selected = selected,
                selectedTab = options.selectedFormatTab,
                pairedVideoOnly = options.selectedVideoOnlyFormat,
                pairedAudioOnly = options.selectedAudioOnlyFormat
            )
            cmd += listOf("-f", selector)
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
}
