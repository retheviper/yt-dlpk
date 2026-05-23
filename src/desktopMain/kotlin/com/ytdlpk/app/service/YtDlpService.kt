package com.ytdlpk.app.service

import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.ProgressInfo
import com.ytdlpk.app.model.VideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AnalyzeResult(
    val metadata: VideoMetadata,
    val formats: List<com.ytdlpk.app.model.FormatEntry>
)

class YtDlpService(
    private val processRunner: ProcessRunner,
    private val formatService: FormatService,
    private val commandBuilder: YtDlpCommandBuilder,
    private val progressParser: ProgressParser
) {
    suspend fun analyze(ytDlpPath: String, url: String, playlistMode: PlaylistMode): AnalyzeResult {
        return coroutineScope {
            val metadata = async { analyzeMetadata(ytDlpPath, url, playlistMode) }
            val formats = async { formatService.fetchFormats(ytDlpPath, url, playlistMode) }
            AnalyzeResult(
                metadata = metadata.await(),
                formats = formats.await()
            )
        }
    }

    suspend fun analyzeMetadata(ytDlpPath: String, url: String, playlistMode: PlaylistMode): VideoMetadata {
        val cmd = mutableListOf(
            ytDlpPath,
            "--dump-single-json",
            "--skip-download",
            "--no-warnings",
            "--playlist-items", "1"
        )
        if (playlistMode == PlaylistMode.SINGLE) {
            cmd += "--no-playlist"
        }
        cmd += url

        val result = processRunner.run(cmd)
        if (result.exitCode != 0 || result.stdoutLines.isEmpty()) {
            error("Analyze failed: ${result.stderrLines.joinToString("\n")}")
        }

        val obj = Json.parseToJsonElement(result.stdoutLines.joinToString("\n")).jsonObject
        val entries = obj["entries"] as? JsonArray
        val firstEntry = entries
            ?.firstOrNull()
            ?.jsonObjectOrNull()

        return metadataFromJson(obj, firstEntry ?: obj, entries)
    }

    suspend fun analyzeFormats(
        ytDlpPath: String,
        url: String,
        playlistMode: PlaylistMode = PlaylistMode.PLAYLIST
    ) = formatService.fetchFormats(ytDlpPath, url, playlistMode)

    fun startDownload(
        scope: CoroutineScope,
        ytDlpPath: String,
        ffmpegPath: String,
        options: DownloadOptions,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit,
        onProgress: (ProgressInfo) -> Unit,
        onExit: (Int) -> Unit
    ): RunningProcess {
        val command = commandBuilder.build(ytDlpPath, ffmpegPath, options)
        var progress = ProgressInfo(null, null, null, null, null, null)

        return processRunner.runStreaming(
            scope = scope,
            command = command,
            onStdoutLine = { line ->
                onStdoutLine(line)
                progress = progressParser.parse(line, progress)
                onProgress(progress)
            },
            onStderrLine = onStderrLine,
            onExit = onExit
        )
    }
}

private fun metadataFromJson(root: JsonObject, source: JsonObject, entries: JsonArray?): VideoMetadata {
    val playlistCount = root.intOrNull("playlist_count") ?: entries?.size
    return VideoMetadata(
        title = source.stringOrNull("title") ?: root.stringOrNull("title") ?: "(unknown)",
        uploader = source.stringOrNull("channel") ?: source.stringOrNull("uploader")
            ?: root.stringOrNull("channel") ?: root.stringOrNull("uploader"),
        durationSeconds = source["duration"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: root["duration"]?.jsonPrimitive?.content?.toLongOrNull(),
        thumbnailUrl = source.stringOrNull("thumbnail") ?: root.stringOrNull("thumbnail"),
        isPlaylist = root.stringOrNull("_type") == "playlist" || entries != null,
        playlistCount = playlistCount
    )
}

private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? {
    return runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}

private fun kotlinx.serialization.json.JsonObject.intOrNull(key: String): Int? {
    return runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? {
    return runCatching { this.jsonObject }.getOrNull()
}
