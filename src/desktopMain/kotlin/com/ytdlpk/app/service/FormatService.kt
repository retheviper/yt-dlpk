package com.ytdlpk.app.service

import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind

class FormatService(
    private val processRunner: ProcessRunner
) {
    suspend fun fetchFormats(ytDlpPath: String, url: String): List<FormatEntry> {
        val result = processRunner.run(
            listOf(
                ytDlpPath,
                "-F",
                "--no-warnings",
                "--playlist-items", "1",
                url
            )
        )
        if (result.exitCode != 0) {
            error("Failed to fetch formats: ${result.stderrLines.joinToString("\n")}")
        }
        return parseFormatTable(result.stdoutLines)
    }

    fun parseFormatTable(lines: List<String>): List<FormatEntry> {
        val entries = mutableListOf<FormatEntry>()
        var started = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            if (trimmed.startsWith("ID ") || trimmed.contains("ID") && trimmed.contains("EXT")) {
                started = true
                continue
            }
            if (trimmed.startsWith("-")) {
                continue
            }
            if (!started) {
                continue
            }

            val m = ROW_REGEX.find(trimmed) ?: continue
            val formatId = m.groupValues[1]
            val ext = m.groupValues[2]
            val rest = m.groupValues[3]

            val resolution = RESOLUTION_REGEX.find(rest)?.value
                ?: if (rest.contains("audio only", ignoreCase = true)) "audio only" else null
            val fps = FPS_REGEX.find(rest)?.groupValues?.get(1)?.toIntOrNull()
            val tbr = TBR_REGEX.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
            val abr = ABR_REGEX.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()

            val lower = rest.lowercase()
            val kind = when {
                lower.contains("audio only") -> FormatKind.AUDIO_ONLY
                lower.contains("video only") || lower.contains("acodec none") -> FormatKind.VIDEO_ONLY
                lower.contains("video") || resolution != null -> FormatKind.VIDEO_AUDIO
                else -> FormatKind.UNKNOWN
            }

            val vcodec = CODEC_REGEX.findAll(rest)
                .map { it.value }
                .firstOrNull { !it.contains("mp4a") && !it.contains("opus") && !it.contains("aac") }
            val acodec = CODEC_REGEX.findAll(rest)
                .map { it.value }
                .firstOrNull { it.contains("mp4a") || it.contains("opus") || it.contains("aac") }

            entries += FormatEntry(
                formatId = formatId,
                ext = ext,
                resolution = resolution,
                fps = fps,
                vcodec = vcodec,
                acodec = acodec,
                tbrKbps = tbr,
                abrKbps = abr,
                note = rest,
                kind = kind,
                rawText = trimmed
            )
        }

        return entries
    }

    fun buildFormatSelector(
        selected: FormatEntry,
        selectedTab: FormatKind,
        pairedVideoOnly: FormatEntry? = null,
        pairedAudioOnly: FormatEntry? = null
    ): String {
        return when (selected.kind) {
            FormatKind.VIDEO_ONLY -> {
                when (selectedTab) {
                    FormatKind.VIDEO_ONLY -> selected.formatId
                    else -> when {
                        pairedAudioOnly?.kind == FormatKind.AUDIO_ONLY -> "${selected.formatId}+${pairedAudioOnly.formatId}"
                        else -> "${selected.formatId}+bestaudio/best"
                    }
                }
            }
            FormatKind.AUDIO_ONLY -> {
                when (selectedTab) {
                    FormatKind.AUDIO_ONLY -> selected.formatId
                    else -> if (pairedVideoOnly?.kind == FormatKind.VIDEO_ONLY) {
                        "${pairedVideoOnly.formatId}+${selected.formatId}"
                    } else {
                        selected.formatId
                    }
                }
            }
            else -> selected.formatId
        }
    }

    companion object {
        private val ROW_REGEX = Regex("^(\\S+)\\s+(\\S+)\\s+(.+)$")
        private val RESOLUTION_REGEX = Regex("(\\d{3,4}x\\d{3,4}|\\d{3,4}p|audio only)", RegexOption.IGNORE_CASE)
        private val FPS_REGEX = Regex("(\\d{2,3})fps", RegexOption.IGNORE_CASE)
        // Match bitrate tokens like "192k", "192kbps", but not "192KiB"/"48kHz".
        private val TBR_REGEX = Regex("(\\d+(?:\\.\\d+)?)k(?:bps)?\\b(?!i?b|hz)", RegexOption.IGNORE_CASE)
        private val ABR_REGEX = Regex("audio.*?(\\d+(?:\\.\\d+)?)k(?:bps)?\\b(?!i?b|hz)", RegexOption.IGNORE_CASE)
        private val CODEC_REGEX = Regex("[a-z0-9]{3,}(?:\\.[a-z0-9]+)*", RegexOption.IGNORE_CASE)
    }
}
