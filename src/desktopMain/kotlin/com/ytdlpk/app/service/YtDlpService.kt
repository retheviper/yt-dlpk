package com.ytdlpk.app.service

import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.ProgressInfo
import com.ytdlpk.app.model.VideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class YtDlpService(
    private val processRunner: ProcessRunner,
    private val formatService: FormatService,
    private val commandBuilder: YtDlpCommandBuilder,
    private val progressParser: ProgressParser
) {
    suspend fun analyzeMetadata(ytDlpPath: String, url: String, playlistMode: PlaylistMode): VideoMetadata {
        val cmd = mutableListOf(
            ytDlpPath,
            "--dump-single-json",
            "--skip-download",
            "--no-warnings"
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
        val entries = obj["entries"]
        val playlistCount = (entries as? JsonArray)?.size
        val firstEntry = (entries as? JsonArray)
            ?.firstOrNull()
            ?.jsonObjectOrNull()

        return VideoMetadata(
            title = obj["title"]?.jsonPrimitive?.content ?: "(unknown)",
            uploader = obj.stringOrNull("channel") ?: obj.stringOrNull("uploader"),
            durationSeconds = obj["duration"]?.jsonPrimitive?.content?.toLongOrNull(),
            thumbnailUrl = obj.stringOrNull("thumbnail")
                ?: firstEntry?.stringOrNull("thumbnail"),
            isPlaylist = obj.stringOrNull("_type") == "playlist" || playlistCount != null,
            playlistCount = playlistCount
        )
    }

    suspend fun analyzeFormats(ytDlpPath: String, url: String) = formatService.fetchFormats(ytDlpPath, url)

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

private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? {
    return runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? {
    return runCatching { this.jsonObject }.getOrNull()
}
