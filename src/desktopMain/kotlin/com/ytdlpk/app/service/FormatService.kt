package com.ytdlpk.app.service

import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.VideoCodecPreference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class FormatService(
    private val processRunner: ProcessRunner
) {
    suspend fun fetchFormats(
        ytDlpPath: String,
        url: String,
        playlistMode: PlaylistMode = PlaylistMode.PLAYLIST
    ): List<FormatEntry> {
        val command = mutableListOf(
            ytDlpPath,
            "-F",
            "--no-warnings",
            "--playlist-items", "1"
        )
        if (playlistMode == PlaylistMode.SINGLE) {
            command += "--no-playlist"
        }
        command += url

        val result = processRunner.run(
            command
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

            val codecSection = rest.substringAfterLast("|", rest)
            val vcodec = CODEC_REGEX.findAll(codecSection)
                .map { it.value }
                .firstOrNull { !it.contains("mp4a") && !it.contains("opus") && !it.contains("aac") }
            val acodec = CODEC_REGEX.findAll(codecSection)
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

    fun parseFormatJson(formats: JsonArray?): List<FormatEntry> {
        if (formats == null) return emptyList()
        return formats.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val formatId = obj.stringOrNull("format_id") ?: return@mapNotNull null
            val ext = obj.stringOrNull("ext") ?: "-"
            val vcodec = obj.stringOrNull("vcodec")
            val acodec = obj.stringOrNull("acodec")
            val resolution = obj.stringOrNull("resolution")
                ?: resolutionFromDimensions(obj.intOrNull("width"), obj.intOrNull("height"))
                ?: if (vcodec == "none" && acodec != null && acodec != "none") "audio only" else null
            val fps = obj.intOrNull("fps")
            val tbr = obj.doubleOrNull("tbr")
            val abr = obj.doubleOrNull("abr")
            val note = obj.stringOrNull("format_note")
                ?: obj.stringOrNull("format")
                ?: obj.stringOrNull("dynamic_range")
            val kind = when {
                vcodec == "none" && acodec != null && acodec != "none" -> FormatKind.AUDIO_ONLY
                acodec == "none" && vcodec != null && vcodec != "none" -> FormatKind.VIDEO_ONLY
                vcodec != null && vcodec != "none" -> FormatKind.VIDEO_AUDIO
                else -> FormatKind.UNKNOWN
            }
            FormatEntry(
                formatId = formatId,
                ext = ext,
                resolution = resolution,
                fps = fps,
                vcodec = vcodec?.takeUnless { it == "none" },
                acodec = acodec?.takeUnless { it == "none" },
                tbrKbps = tbr,
                abrKbps = abr,
                note = note,
                kind = kind,
                rawText = obj.stringOrNull("format").orEmpty()
            )
        }
    }

    fun buildFormatSelector(
        selected: FormatEntry,
        selectedTab: FormatKind,
        pairedVideoOnly: FormatEntry? = null,
        pairedAudioOnly: FormatEntry? = null,
        videoCodecPreference: VideoCodecPreference = VideoCodecPreference.ALL
    ): String {
        return when (selected.kind) {
            FormatKind.VIDEO_ONLY -> {
                val selectedVideo = selected.formatId.withCodecFilter(videoCodecPreference)
                when (selectedTab) {
                    FormatKind.VIDEO_ONLY -> selectedVideo
                    else -> when {
                        pairedAudioOnly?.kind == FormatKind.AUDIO_ONLY -> "$selectedVideo+${pairedAudioOnly.formatId}"
                        else -> "$selectedVideo+bestaudio/best"
                    }
                }
            }
            FormatKind.AUDIO_ONLY -> {
                when (selectedTab) {
                    FormatKind.AUDIO_ONLY -> selected.formatId
                    else -> if (pairedVideoOnly?.kind == FormatKind.VIDEO_ONLY) {
                        "${pairedVideoOnly.formatId.withCodecFilter(videoCodecPreference)}+${selected.formatId}"
                    } else {
                        selected.formatId
                    }
                }
            }
            else -> selected.formatId.withCodecFilter(videoCodecPreference)
        }
    }

    fun codecFilter(preference: VideoCodecPreference): String? {
        return when (preference) {
            VideoCodecPreference.ALL -> null
            VideoCodecPreference.H264 -> "vcodec^=avc1"
            VideoCodecPreference.H265 -> "vcodec~='^(hev1|hvc1)'"
            VideoCodecPreference.AV1 -> "vcodec^=av01"
            VideoCodecPreference.VP9 -> "vcodec~='^(vp9|vp09)'"
        }
    }

    private fun String.withCodecFilter(preference: VideoCodecPreference): String {
        val filter = codecFilter(preference) ?: return this
        return "$this[$filter]"
    }

    private fun resolutionFromDimensions(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return "${width}x$height"
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

private fun JsonObject.stringOrNull(key: String): String? {
    return runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}

private fun JsonObject.intOrNull(key: String): Int? {
    return runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
}

private fun JsonObject.doubleOrNull(key: String): Double? {
    return runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
}
