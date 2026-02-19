package com.ytdlpk.app.ui

import com.ytdlpk.app.model.AppSettings
import com.ytdlpk.app.model.AppState
import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.ToolPaths
import com.ytdlpk.app.service.RunningProcess
import com.ytdlpk.app.service.SettingsRepository
import com.ytdlpk.app.service.ToolManager
import com.ytdlpk.app.service.YtDlpService
import com.ytdlpk.app.util.formatSortScore
import com.ytdlpk.app.util.parseResolutionScore
import com.ytdlpk.app.util.resolutionMaxSide
import com.ytdlpk.app.util.resolutionMinSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.createDirectories

class AppViewModel(
    private val appHome: Path,
    private val settingsRepository: SettingsRepository,
    private val toolManager: ToolManager,
    private val ytDlpService: YtDlpService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AppState(settings = settingsRepository.load()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var tools: ToolPaths? = null
    private var runningProcess: RunningProcess? = null

    init {
        appHome.createDirectories()
        ensureTools()
    }

    fun onUrlChange(url: String) = update { it.copy(url = url) }

    fun onTabChange(kind: FormatKind) = update { state ->
        state.copy(
            selectedFormatTab = kind,
            selectedFormatId = pickBestFormatId(state.formats, kind)
        )
    }

    fun onFormatSelected(formatId: String) = update { it.copy(selectedFormatId = formatId) }

    fun onPlaylistMode(mode: PlaylistMode) = update { it.copy(playlistMode = mode) }

    fun onSettingsChange(settings: AppSettings) {
        settingsRepository.save(settings)
        update { it.copy(settings = settings) }
    }

    fun dismissInfoMessage() = update { it.copy(infoMessage = null) }

    fun checkLatestToolVersions() {
        scope.launch {
            update { it.copy(checkingLatestTools = true) }
            val latestYt = toolManager.getLatestYtDlpVersion()
            val latestFf = toolManager.getLatestFfmpegVersion()
            update {
                it.copy(
                    latestYtDlpVersion = latestYt,
                    latestFfmpegVersion = latestFf,
                    checkingLatestTools = false
                )
            }
        }
    }

    fun updateYtDlp() {
        val currentTools = tools ?: return
        if (!currentTools.ytDlpManaged) {
            update { it.copy(infoMessage = "yt-dlp is managed by system PATH. Please update it directly on your system.") }
            return
        }
        scope.launch {
            try {
                update { it.copy(toolStatus = "Updating yt-dlp...") }
                val updated = toolManager.updateManagedYtDlp { status -> update { s -> s.copy(toolStatus = status) } }
                tools = currentTools.copy(ytDlpPath = updated.ytDlpPath, ytDlpManaged = updated.ytDlpManaged)
                refreshInstalledVersions()
                update { it.copy(toolStatus = "yt-dlp updated", infoMessage = "yt-dlp updated successfully.") }
            } catch (e: Throwable) {
                update { it.copy(infoMessage = "yt-dlp update failed: ${e.message}") }
            }
        }
    }

    fun updateFfmpeg() {
        val currentTools = tools ?: return
        if (!currentTools.ffmpegManaged) {
            update { it.copy(infoMessage = "ffmpeg is managed by system PATH. Please update it directly on your system.") }
            return
        }
        scope.launch {
            try {
                update { it.copy(toolStatus = "Updating ffmpeg...") }
                val updated = toolManager.updateManagedFfmpeg { status -> update { s -> s.copy(toolStatus = status) } }
                tools = currentTools.copy(ffmpegPath = updated.ffmpegPath, ffmpegManaged = updated.ffmpegManaged)
                refreshInstalledVersions()
                update { it.copy(toolStatus = "ffmpeg updated", infoMessage = "ffmpeg updated successfully.") }
            } catch (e: Throwable) {
                update { it.copy(infoMessage = "ffmpeg update failed: ${e.message}") }
            }
        }
    }

    fun addLog(message: String) {
        update { it.copy(logs = (it.logs + message).takeLast(400)) }
    }

    fun analyze() {
        val snapshot = state.value
        if (!snapshot.toolsReady || snapshot.url.isBlank()) return

        scope.launch {
            update { it.copy(isAnalyzing = true, lastError = null) }
            try {
                val toolPaths = requireNotNull(tools)
                val (metadata, formats) = coroutineScope {
                    val metadataDef = async {
                        ytDlpService.analyzeMetadata(toolPaths.ytDlpPath, snapshot.url, snapshot.playlistMode)
                    }
                    val formatsDef = async {
                        ytDlpService.analyzeFormats(toolPaths.ytDlpPath, snapshot.url)
                    }
                    metadataDef.await() to formatsDef.await()
                }
                val defaultTab = when {
                    formats.any { it.kind == FormatKind.VIDEO_AUDIO } -> FormatKind.VIDEO_AUDIO
                    formats.any { it.kind == FormatKind.VIDEO_ONLY } -> FormatKind.VIDEO_ONLY
                    formats.any { it.kind == FormatKind.AUDIO_ONLY } -> FormatKind.AUDIO_ONLY
                    else -> FormatKind.VIDEO_AUDIO
                }
                val selected = pickBestFormatId(formats, defaultTab)
                update {
                    it.copy(
                        metadata = metadata,
                        formats = formats,
                        selectedFormatTab = defaultTab,
                        selectedFormatId = selected,
                        logs = (it.logs + "Analyze complete: ${formats.size} formats").takeLast(400)
                    )
                }
            } catch (e: Throwable) {
                update {
                    it.copy(
                        lastError = e.message,
                        logs = (it.logs + "Analyze failed: ${e.message}").takeLast(400)
                    )
                }
            } finally {
                update { it.copy(isAnalyzing = false) }
            }
        }
    }

    fun download() {
        val snapshot = state.value
        val toolPaths = tools ?: return
        if (snapshot.isDownloading || snapshot.url.isBlank()) return

        val options = DownloadOptions(
            url = snapshot.url,
            playlistMode = snapshot.playlistMode,
            outputDirectory = snapshot.settings.outputDirectory,
            fileNameTemplate = snapshot.settings.fileNameTemplate,
            selectedFormat = snapshot.selectedFormat,
            mergeBestAudioForVideoOnly = snapshot.settings.mergeBestAudioForVideoOnly,
            includeAutoSubs = snapshot.settings.includeAutoSubs,
            subLang = snapshot.settings.subLang,
            extractAudio = snapshot.settings.extractAudio,
            audioFormat = snapshot.settings.audioFormat,
            mergeOutputFormat = snapshot.settings.mergeOutputFormat
        )

        update {
            it.copy(
                isDownloading = true,
                lastError = null,
                progress = it.progress.copy(percent = null, speed = null, eta = null, currentFile = null)
            )
        }

        runningProcess = ytDlpService.startDownload(
            scope = scope,
            ytDlpPath = toolPaths.ytDlpPath,
            ffmpegPath = toolPaths.ffmpegPath,
            options = options,
            onStdoutLine = { addLog(it) },
            onStderrLine = { addLog("[err] $it") },
            onProgress = { progress -> update { it.copy(progress = progress) } },
            onExit = { code ->
                update {
                    it.copy(
                        isDownloading = false,
                        progress = if (code == 0) it.progress.copy(percent = 100.0, speed = null, eta = null) else it.progress,
                        logs = (it.logs + "Download finished with code $code").takeLast(400),
                        lastError = if (code == 0) null else "Download failed: exit code $code"
                    )
                }
            }
        )
    }

    fun cancelDownload() {
        runningProcess?.cancel()
        runningProcess = null
        update {
            it.copy(
                isDownloading = false,
                logs = (it.logs + "Download cancelled").takeLast(400)
            )
        }
    }

    private fun ensureTools() {
        scope.launch {
            try {
                update { it.copy(toolStatus = "Checking tools...") }
                val resolved = toolManager.ensureTools { status -> update { state -> state.copy(toolStatus = status) } }
                tools = resolved
                refreshInstalledVersions()
                update {
                    it.copy(
                        toolsReady = true,
                        toolStatus = "Tools ready",
                        logs = (it.logs + "yt-dlp: ${resolved.ytDlpPath}" + "\n" + "ffmpeg: ${resolved.ffmpegPath}").takeLast(400)
                    )
                }
            } catch (e: Throwable) {
                update {
                    it.copy(
                        toolsReady = false,
                        toolStatus = "Tool setup failed",
                        lastError = e.message,
                        logs = (it.logs + "Tool setup failed: ${e.message}").takeLast(400)
                    )
                }
            }
        }
    }

    private fun refreshInstalledVersions() {
        val t = tools ?: return
        val ytVersion = toolManager.getYtDlpVersion(t.ytDlpPath)
        val ffVersion = toolManager.getFfmpegVersion(t.ffmpegPath)
        update {
            it.copy(
                ytDlpVersion = ytVersion + if (t.ytDlpManaged) " (app)" else " (system)",
                ffmpegVersion = ffVersion + if (t.ffmpegManaged) " (app)" else " (system)"
            )
        }
    }

    private fun update(block: (AppState) -> AppState) {
        _state.update(block)
    }

    private fun pickBestFormatId(formats: List<FormatEntry>, kind: FormatKind): String? {
        val candidates = formats.filter { it.kind == kind }
        if (candidates.isEmpty()) return formats.firstOrNull()?.formatId
        return when (kind) {
            FormatKind.VIDEO_AUDIO -> candidates
                .sortedWith(
                    compareByDescending<FormatEntry> { resolutionMaxSide(it.resolution) }
                        .thenByDescending { resolutionMinSide(it.resolution) }
                        .thenByDescending { parseResolutionScore(it.resolution) }
                        .thenByDescending { it.tbrKbps ?: it.abrKbps ?: 0.0 }
                        .thenByDescending { it.fps ?: 0 }
                )
                .firstOrNull()
                ?.formatId
            else -> candidates.maxByOrNull { formatSortScore(it) }?.formatId
        }
    }
}
