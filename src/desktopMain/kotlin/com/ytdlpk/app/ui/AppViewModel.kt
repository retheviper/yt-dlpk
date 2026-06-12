package com.ytdlpk.app.ui

import com.ytdlpk.app.model.AppSettings
import com.ytdlpk.app.model.AppState
import com.ytdlpk.app.model.DownloadOptions
import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.QuickQualityProfile
import com.ytdlpk.app.model.ToolPaths
import com.ytdlpk.app.model.VideoCodecPreference
import com.ytdlpk.app.model.VideoMetadata
import com.ytdlpk.app.model.matchesVideoCodec
import com.ytdlpk.app.service.AnalyzeResult
import com.ytdlpk.app.service.RunningProcess
import com.ytdlpk.app.service.SettingsRepository
import com.ytdlpk.app.service.ToolManager
import com.ytdlpk.app.service.YtDlpService
import com.ytdlpk.app.util.formatSortScore
import com.ytdlpk.app.util.isAppWindowFocused
import com.ytdlpk.app.util.parseResolutionScore
import com.ytdlpk.app.util.resolutionMaxSide
import com.ytdlpk.app.util.resolutionMinSide
import com.ytdlpk.app.util.showDesktopNotification
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories

private data class AnalyzeCacheKey(
    val url: String,
    val playlistMode: PlaylistMode
)

class AppViewModel(
    private val appHome: Path,
    private val settingsRepository: SettingsRepository,
    private val toolManager: ToolManager,
    private val ytDlpService: YtDlpService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AppState(settings = settingsRepository.load()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    @Volatile private var tools: ToolPaths? = null
    @Volatile private var analyzeYtDlpPath: String? = null
    @Volatile private var ffmpegPath: String? = null
    @Volatile private var ytDlpManaged: Boolean = false
    @Volatile private var ffmpegManaged: Boolean = false
    @Volatile private var runningProcess: RunningProcess? = null
    @Volatile private var downloadCancelRequested: Boolean = false
    @Volatile private var lastAnalyzeCacheKey: AnalyzeCacheKey? = null
    @Volatile private var lastAnalyzeResult: AnalyzeResult? = null
    private val ffmpegEnsureMutex = Mutex()

    init {
        appHome.createDirectories()
        ensureTools()
    }

    fun onUrlChange(url: String) = update { it.copy(url = url) }

    fun onTabChange(kind: FormatKind) = update { state ->
        val tabSelection = when (kind) {
            FormatKind.VIDEO_ONLY -> state.selectedVideoOnlyFormat
                ?.takeIf { it.matchesVideoCodec(state.settings.videoCodecPreference) }
                ?.formatId
                ?: pickBestFormatId(
                    state.formats,
                    kind,
                    state.settings.videoCodecPreference
                )
            FormatKind.AUDIO_ONLY -> state.selectedAudioOnlyFormatId ?: pickBestFormatId(state.formats, kind)
            else -> pickBestFormatId(state.formats, kind, state.settings.videoCodecPreference)
        }
        state.copy(
            selectedFormatTab = kind,
            selectedFormatId = tabSelection
        )
    }

    fun onFormatSelected(formatId: String) = update { state ->
        when (state.selectedFormatTab) {
            FormatKind.VIDEO_ONLY -> state.copy(
                selectedFormatId = formatId,
                selectedVideoOnlyFormatId = formatId
            )
            FormatKind.AUDIO_ONLY -> state.copy(
                selectedFormatId = formatId,
                selectedAudioOnlyFormatId = formatId
            )
            else -> state.copy(selectedFormatId = formatId)
        }
    }

    fun onAudioOnlyFormatSelected(formatId: String) = update {
        it.copy(selectedAudioOnlyFormatId = formatId)
    }

    fun onPlaylistMode(mode: PlaylistMode) = update { it.copy(playlistMode = mode) }

    fun onSettingsChange(settings: AppSettings) {
        settingsRepository.save(settings)
        update { state ->
            val selected = state.selectedFormat?.takeIf { it.matchesVideoCodec(settings.videoCodecPreference) }?.formatId
                ?: pickBestFormatId(state.formats, state.selectedFormatTab, settings.videoCodecPreference)
            state.copy(
                settings = settings,
                selectedFormatId = selected,
                selectedVideoOnlyFormatId = state.selectedVideoOnlyFormat
                    ?.takeIf { it.matchesVideoCodec(settings.videoCodecPreference) }
                    ?.formatId
                    ?: pickBestFormatId(state.formats, FormatKind.VIDEO_ONLY, settings.videoCodecPreference)
            )
        }
    }

    fun showInfo(message: String) = update { it.copy(infoMessage = message) }

    fun dismissInfoMessage() = update { it.copy(infoMessage = null) }
    fun dismissErrorDialog() = update { it.copy(errorDialogMessage = null) }

    fun checkLatestToolVersions() {
        scope.launch {
            update { it.copy(checkingLatestTools = true) }
            val (latestYt, latestFf) = coroutineScope {
                val latestYtDef = async { toolManager.getLatestYtDlpVersion() }
                val latestFfDef = async { toolManager.getLatestFfmpegVersion() }
                latestYtDef.await() to latestFfDef.await()
            }
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
        val currentPath = analyzeYtDlpPath ?: tools?.ytDlpPath
        if (currentPath == null) {
            update { it.copy(infoMessage = "yt-dlp is not ready yet.") }
            return
        }
        scope.launch {
            try {
                update { it.copy(toolStatus = "Checking yt-dlp version...") }
                val currentVersion = toolManager.getYtDlpVersion(currentPath)
                val latestVersion = toolManager.getLatestYtDlpVersion()
                val comparison = toolManager.compareVersions(currentVersion, latestVersion)
                update { it.copy(latestYtDlpVersion = latestVersion) }

                if (comparison != null && comparison >= 0) {
                    update { it.copy(toolStatus = "yt-dlp ready", infoMessage = "yt-dlp is already up to date.") }
                    refreshInstalledVersions()
                    return@launch
                }

                if (ytDlpManaged) {
                    update { it.copy(toolStatus = "Updating yt-dlp...") }
                    val updated = toolManager.updateManagedYtDlp { status -> update { s -> s.copy(toolStatus = status) } }
                    analyzeYtDlpPath = updated.ytDlpPath
                    ytDlpManaged = updated.ytDlpManaged
                    tools = (tools ?: ToolPaths(updated.ytDlpPath, ffmpegPath.orEmpty(), updated.ytDlpManaged, ffmpegManaged))
                        .copy(ytDlpPath = updated.ytDlpPath, ytDlpManaged = updated.ytDlpManaged)
                    refreshInstalledVersions()
                    update { it.copy(toolStatus = "yt-dlp updated", infoMessage = "yt-dlp updated successfully.") }
                    return@launch
                }

                val result = toolManager.updateSystemYtDlp(currentPath) { status -> update { s -> s.copy(toolStatus = status) } }
                if (!result.updated) {
                    update { it.copy(toolStatus = "yt-dlp ready", infoMessage = result.message) }
                    return@launch
                }
                refreshInstalledVersions()
                update { it.copy(toolStatus = "yt-dlp updated", infoMessage = "yt-dlp updated successfully.") }
            } catch (e: Throwable) {
                update { it.copy(infoMessage = "yt-dlp update failed: ${e.message}") }
            }
        }
    }

    fun updateFfmpeg() {
        scope.launch {
            try {
                val currentPath = ffmpegPath ?: tools?.ffmpegPath ?: ensureFfmpegReady()
                update { it.copy(toolStatus = "Checking ffmpeg version...") }
                val currentVersion = toolManager.getFfmpegVersion(currentPath)
                val latestVersion = toolManager.getLatestFfmpegVersion()
                val comparison = toolManager.compareVersions(currentVersion, latestVersion)
                update { it.copy(latestFfmpegVersion = latestVersion) }

                if (comparison != null && comparison >= 0) {
                    update { it.copy(toolStatus = "Tools ready", infoMessage = "ffmpeg is already up to date.") }
                    refreshInstalledVersions()
                    return@launch
                }

                if (ffmpegManaged) {
                    update { it.copy(toolStatus = "Updating ffmpeg...") }
                    val updated = toolManager.updateManagedFfmpeg { status -> update { s -> s.copy(toolStatus = status) } }
                    ffmpegPath = updated.ffmpegPath
                    ffmpegManaged = updated.ffmpegManaged
                    tools = (tools ?: ToolPaths(analyzeYtDlpPath.orEmpty(), updated.ffmpegPath, ytDlpManaged, updated.ffmpegManaged))
                        .copy(ffmpegPath = updated.ffmpegPath, ffmpegManaged = updated.ffmpegManaged)
                    refreshInstalledVersions()
                    update { it.copy(toolStatus = "ffmpeg updated", infoMessage = "ffmpeg updated successfully.") }
                    return@launch
                }

                val result = toolManager.updateSystemFfmpeg(currentPath) { status -> update { s -> s.copy(toolStatus = status) } }
                if (!result.updated) {
                    update { it.copy(toolStatus = "Tools ready", infoMessage = result.message) }
                    return@launch
                }
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
        val ytDlpPath = analyzeYtDlpPath ?: tools?.ytDlpPath
        val url = snapshot.url.trim()
        if (!snapshot.ytDlpReady || ytDlpPath == null || url.isBlank()) return

        if (url != snapshot.url) {
            update { it.copy(url = url) }
        }

        val cacheKey = AnalyzeCacheKey(url = url, playlistMode = snapshot.playlistMode)
        val cached = lastAnalyzeResult.takeIf { lastAnalyzeCacheKey == cacheKey }
        if (cached != null) {
            applyAnalyzeResult(
                metadata = cached.metadata,
                formats = cached.formats,
                settings = snapshot.settings,
                logMessage = "Analyze cache hit: ${cached.formats.size} formats"
            )
            return
        }

        scope.launch {
            update {
                it.copy(
                    isAnalyzing = true,
                    lastError = null,
                    metadata = null,
                    formats = emptyList(),
                    selectedFormatId = null,
                    selectedVideoOnlyFormatId = null,
                    selectedAudioOnlyFormatId = null
                )
            }
            try {
                val result = ytDlpService.analyze(ytDlpPath, url, snapshot.playlistMode)
                lastAnalyzeCacheKey = cacheKey
                lastAnalyzeResult = result
                applyAnalyzeResult(
                    metadata = result.metadata,
                    formats = result.formats,
                    settings = snapshot.settings,
                    logMessage = "Analyze complete: ${result.formats.size} formats"
                )
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

    private fun applyAnalyzeResult(
        metadata: VideoMetadata,
        formats: List<FormatEntry>,
        settings: AppSettings,
        logMessage: String
    ) {
        update { state -> state.copy(metadata = metadata) }
        applyFormats(formats, settings, logMessage)
    }

    private fun applyFormats(
        formats: List<FormatEntry>,
        settings: AppSettings,
        logMessage: String
    ) {
        val defaultTab = when {
            formats.any { it.kind == FormatKind.VIDEO_AUDIO && it.matchesVideoCodec(settings.videoCodecPreference) } -> FormatKind.VIDEO_AUDIO
            formats.any { it.kind == FormatKind.VIDEO_ONLY && it.matchesVideoCodec(settings.videoCodecPreference) } -> FormatKind.VIDEO_ONLY
            formats.any { it.kind == FormatKind.AUDIO_ONLY } -> FormatKind.AUDIO_ONLY
            else -> FormatKind.VIDEO_AUDIO
        }
        val selected = pickBestFormatId(formats, defaultTab, settings.videoCodecPreference)
        val selectedVideoOnly = pickBestFormatId(formats, FormatKind.VIDEO_ONLY, settings.videoCodecPreference)
        val selectedAudioOnly = pickBestFormatId(formats, FormatKind.AUDIO_ONLY)
        update {
            it.copy(
                formats = formats,
                selectedFormatTab = defaultTab,
                selectedFormatId = selected,
                selectedVideoOnlyFormatId = selectedVideoOnly,
                selectedAudioOnlyFormatId = selectedAudioOnly,
                logs = (it.logs + logMessage).takeLast(400)
            )
        }
    }

    fun download() {
        val snapshot = state.value
        val ytDlpPath = analyzeYtDlpPath ?: tools?.ytDlpPath ?: return
        if (snapshot.isDownloading || snapshot.url.isBlank()) return

        val options = DownloadOptions(
            url = snapshot.url,
            playlistMode = snapshot.playlistMode,
            selectedFormatTab = snapshot.selectedFormatTab,
            outputDirectory = snapshot.settings.outputDirectory,
            fileNameTemplate = snapshot.settings.fileNameTemplate,
            selectedFormat = snapshot.selectedFormat,
            selectedVideoOnlyFormat = snapshot.selectedVideoOnlyFormat,
            selectedAudioOnlyFormat = snapshot.selectedAudioOnlyFormat,
            includeAutoSubs = snapshot.settings.includeAutoSubs,
            subLang = snapshot.settings.subLang,
            extractAudio = snapshot.settings.extractAudio,
            audioFormat = snapshot.settings.audioFormat,
            mergeOutputFormat = snapshot.settings.mergeOutputFormat,
            videoCodecPreference = snapshot.settings.videoCodecPreference
        )

        update {
            it.copy(
                isDownloading = true,
                lastError = null,
                progress = it.progress.copy(percent = null, speed = null, eta = null, currentFile = null)
            )
        }

        startDownloadProcess(
            ytDlpPath = ytDlpPath,
            options = options,
            finishLogPrefix = "Download finished",
            failurePrefix = "Download failed"
        )
    }

    fun quickDownload(urlOverride: String? = null) {
        val snapshot = state.value
        val effectiveUrl = (urlOverride ?: snapshot.url).trim()

        if (urlOverride != null && snapshot.url != effectiveUrl) {
            update { it.copy(url = effectiveUrl) }
        }

        if (snapshot.isDownloading) {
            update { it.copy(infoMessage = "Download is already in progress.") }
            return
        }
        if (effectiveUrl.isBlank()) {
            update { it.copy(infoMessage = "URL is empty. Paste a valid URL and try again.") }
            return
        }
        val ytDlpPath = analyzeYtDlpPath ?: tools?.ytDlpPath
        if (ytDlpPath == null) {
            update { it.copy(infoMessage = "Tools are not ready yet. Please wait a moment and try again.") }
            return
        }

        val options = DownloadOptions(
            url = effectiveUrl,
            playlistMode = snapshot.settings.quickPlaylistMode,
            selectedFormatTab = FormatKind.VIDEO_AUDIO,
            outputDirectory = snapshot.settings.outputDirectory,
            fileNameTemplate = snapshot.settings.fileNameTemplate,
            selectedFormat = null,
            selectedVideoOnlyFormat = null,
            selectedAudioOnlyFormat = null,
            quickFormatSelector = quickFormatSelector(
                snapshot.settings.quickQualityProfile,
                snapshot.settings.videoCodecPreference
            ),
            includeAutoSubs = snapshot.settings.includeAutoSubs,
            subLang = snapshot.settings.subLang,
            extractAudio = snapshot.settings.extractAudio,
            audioFormat = snapshot.settings.audioFormat,
            mergeOutputFormat = snapshot.settings.mergeOutputFormat,
            videoCodecPreference = snapshot.settings.videoCodecPreference
        )

        update {
            it.copy(
                isDownloading = true,
                lastError = null,
                progress = it.progress.copy(percent = null, speed = null, eta = null, currentFile = null)
            )
        }

        startDownloadProcess(
            ytDlpPath = ytDlpPath,
            options = options,
            finishLogPrefix = "Quick download finished",
            failurePrefix = "Quick download failed"
        )
    }

    fun cancelDownload() {
        downloadCancelRequested = true
        runningProcess?.cancel()
        runningProcess = null
        update {
            it.copy(
                isDownloading = false,
                logs = (it.logs + "Download cancelled").takeLast(400)
            )
        }
    }

    fun close() {
        runningProcess?.cancel()
        runningProcess = null
        scope.cancel()
    }

    private fun startDownloadProcess(
        ytDlpPath: String,
        options: DownloadOptions,
        finishLogPrefix: String,
        failurePrefix: String
    ) {
        scope.launch {
            try {
                val resolvedFfmpegPath = ensureFfmpegReady()
                if (downloadCancelRequested || !state.value.isDownloading) {
                    downloadCancelRequested = false
                    return@launch
                }
                downloadCancelRequested = false
                var lastProgressUpdateNanos = 0L
                runningProcess = ytDlpService.startDownload(
                    scope = scope,
                    ytDlpPath = ytDlpPath,
                    ffmpegPath = resolvedFfmpegPath,
                    options = options,
                    onStdoutLine = {},
                    onStderrLine = { addLog("[err] $it") },
                    onProgress = { progress ->
                        val now = System.nanoTime()
                        if (now - lastProgressUpdateNanos >= PROGRESS_UPDATE_INTERVAL_NANOS || progress.percent == 100.0) {
                            lastProgressUpdateNanos = now
                            update { it.copy(progress = progress) }
                        }
                    },
                    onExit = onExit@ { code ->
                        runningProcess = null
                        if (downloadCancelRequested) {
                            downloadCancelRequested = false
                            return@onExit
                        }
                        val dialogMessage = handleDownloadExit(code)
                        update {
                            it.copy(
                                isDownloading = false,
                                progress = if (code == 0) it.progress.copy(percent = 100.0, speed = null, eta = null) else it.progress,
                                logs = (it.logs + "$finishLogPrefix with code $code").takeLast(400),
                                lastError = if (code == 0) null else "$failurePrefix: exit code $code",
                                errorDialogMessage = dialogMessage
                            )
                        }
                    }
                )
            } catch (e: Throwable) {
                runningProcess = null
                update {
                    it.copy(
                        isDownloading = false,
                        lastError = e.message,
                        logs = (it.logs + "$failurePrefix: ${e.message}").takeLast(400),
                        errorDialogMessage = e.message
                    )
                }
            }
        }
    }

    private fun ensureTools() {
        scope.launch {
            supervisorScope {
                val ytDlpJob = launch { ensureYtDlpReady() }
                val ffmpegJob = launch {
                    runCatching {
                        ensureFfmpegReady()
                    }.onFailure { e ->
                        update {
                            it.copy(
                                ffmpegReady = false,
                                toolsReady = false,
                                toolStatus = "ffmpeg setup failed",
                                lastError = e.message,
                                logs = (it.logs + "ffmpeg setup failed: ${e.message}").takeLast(400)
                            )
                        }
                    }
                }
                joinAll(ytDlpJob, ffmpegJob)
            }
        }
    }

    private suspend fun ensureYtDlpReady(): String {
        analyzeYtDlpPath?.let { return it }
        return try {
            update { it.copy(toolStatus = "Checking yt-dlp...") }
            val ytDlp = toolManager.ensureYtDlp { status -> update { state -> state.copy(toolStatus = status) } }
            val resolvedYtDlpPath = ytDlp.toAbsolutePath().toString()
            analyzeYtDlpPath = resolvedYtDlpPath
            ytDlpManaged = ytDlp.startsWith(appHome.resolve("tools").resolve("bin"))
            val resolvedFfmpegPath = ffmpegPath
            val ytDlpVersion = withContext(Dispatchers.IO) { toolManager.getYtDlpVersion(resolvedYtDlpPath) }
            if (resolvedFfmpegPath != null) {
                tools = ToolPaths(
                    ytDlpPath = resolvedYtDlpPath,
                    ffmpegPath = resolvedFfmpegPath,
                    ytDlpManaged = ytDlpManaged,
                    ffmpegManaged = ffmpegManaged
                )
            }
            update {
                it.copy(
                    ytDlpReady = true,
                    toolsReady = resolvedFfmpegPath != null,
                    toolStatus = if (resolvedFfmpegPath != null) "Tools ready" else "yt-dlp ready",
                    ytDlpVersion = ytDlpVersion + if (ytDlpManaged) " (app)" else " (system)",
                    logs = (it.logs + "yt-dlp: $resolvedYtDlpPath").takeLast(400)
                )
            }
            resolvedYtDlpPath
        } catch (e: Throwable) {
            update {
                it.copy(
                    ytDlpReady = false,
                    toolsReady = false,
                    toolStatus = "Tool setup failed",
                    lastError = e.message,
                    logs = (it.logs + "Tool setup failed: ${e.message}").takeLast(400)
                )
            }
            throw e
        }
    }

    private suspend fun ensureFfmpegReady(): String {
        ffmpegPath?.let { return it }
        return ffmpegEnsureMutex.withLock {
            ffmpegPath?.let { return@withLock it }
            update { it.copy(toolStatus = "Checking ffmpeg...") }
            val ffmpeg = toolManager.ensureFfmpeg { status -> update { state -> state.copy(toolStatus = status) } }
            val resolvedFfmpegPath = ffmpeg.toAbsolutePath().toString()
            ffmpegPath = resolvedFfmpegPath
            ffmpegManaged = ffmpeg.startsWith(appHome.resolve("tools").resolve("bin"))
            val ytPath = analyzeYtDlpPath
            val ffmpegVersion = withContext(Dispatchers.IO) { toolManager.getFfmpegVersion(resolvedFfmpegPath) }
            if (ytPath != null) {
                tools = ToolPaths(
                    ytDlpPath = ytPath,
                    ffmpegPath = resolvedFfmpegPath,
                    ytDlpManaged = ytDlpManaged,
                    ffmpegManaged = ffmpegManaged
                )
            }
            update {
                it.copy(
                    ffmpegReady = true,
                    toolsReady = ytPath != null,
                    toolStatus = if (ytPath != null) "Tools ready" else "ffmpeg ready",
                    ffmpegVersion = ffmpegVersion + if (ffmpegManaged) " (app)" else " (system)",
                    logs = (it.logs + "ffmpeg: $resolvedFfmpegPath").takeLast(400)
                )
            }
            resolvedFfmpegPath
        }
    }

    private suspend fun refreshInstalledVersions() {
        val ytPath = analyzeYtDlpPath ?: tools?.ytDlpPath
        val ffPath = ffmpegPath ?: tools?.ffmpegPath
        val ytVersion = withContext(Dispatchers.IO) { ytPath?.let { toolManager.getYtDlpVersion(it) } }
        val ffVersion = withContext(Dispatchers.IO) { ffPath?.let { toolManager.getFfmpegVersion(it) } }
        update {
            it.copy(
                ytDlpVersion = ytVersion?.let { version -> version + if (ytDlpManaged) " (app)" else " (system)" } ?: it.ytDlpVersion,
                ffmpegVersion = ffVersion?.let { version -> version + if (ffmpegManaged) " (app)" else " (system)" } ?: it.ffmpegVersion
            )
        }
    }

    private fun update(block: (AppState) -> AppState) {
        _state.update(block)
    }

    private fun handleDownloadExit(code: Int): String? {
        val snapshot = state.value
        val s = uiStrings(snapshot.settings.language)
        val isFocused = isAppWindowFocused()

        if (code == 0) {
            if (snapshot.settings.notifyOnDownloadCompleteWhenInactive && !isFocused) {
                showDesktopNotification(
                    title = "yt-dlpk",
                    message = s.downloadCompleteNotification
                )
            }
            return null
        }

        val failureMessage = "${s.downloadFailedNotification} (exit code: $code)"
        if (isFocused) {
            return failureMessage
        }
        if (snapshot.settings.notifyOnDownloadCompleteWhenInactive) {
            showDesktopNotification(
                title = "yt-dlpk",
                message = failureMessage
            )
        }
        return null
    }

    private fun pickBestFormatId(
        formats: List<FormatEntry>,
        kind: FormatKind,
        videoCodecPreference: VideoCodecPreference = VideoCodecPreference.ALL
    ): String? {
        val candidates = when (kind) {
            FormatKind.VIDEO_AUDIO -> formats.filter {
                (it.kind == FormatKind.VIDEO_AUDIO || it.kind == FormatKind.VIDEO_ONLY) &&
                    it.matchesVideoCodec(videoCodecPreference)
            }
            FormatKind.VIDEO_ONLY -> formats.filter { it.kind == kind && it.matchesVideoCodec(videoCodecPreference) }
            else -> formats.filter { it.kind == kind }
        }
        if (candidates.isEmpty()) return null
        return when (kind) {
            FormatKind.VIDEO_AUDIO -> candidates
                .maxWithOrNull(
                    compareBy<FormatEntry> { resolutionMaxSide(it.resolution) }
                        .thenBy { resolutionMinSide(it.resolution) }
                        .thenBy { parseResolutionScore(it.resolution) }
                        .thenBy { it.tbrKbps ?: it.abrKbps ?: 0.0 }
                        .thenBy { it.fps ?: 0 }
                )
                ?.formatId
            else -> candidates.maxByOrNull { formatSortScore(it) }?.formatId
        }
    }

    private fun quickFormatSelector(profile: QuickQualityProfile, codecPreference: VideoCodecPreference): String {
        val codecFilter = codecSelectorFilter(codecPreference)
        return when (profile) {
            QuickQualityProfile.BEST -> "bestvideo+bestaudio/best"
            QuickQualityProfile.UP_TO_2160P -> "bestvideo${codecFilter}[height<=2160]+bestaudio/best[height<=2160]/best"
            QuickQualityProfile.UP_TO_1440P -> "bestvideo${codecFilter}[height<=1440]+bestaudio/best[height<=1440]/best"
            QuickQualityProfile.UP_TO_1080P -> "bestvideo${codecFilter}[height<=1080]+bestaudio/best[height<=1080]/best"
            QuickQualityProfile.UP_TO_720P -> "bestvideo${codecFilter}[height<=720]+bestaudio/best[height<=720]/best"
            QuickQualityProfile.AUDIO_ONLY -> "bestaudio/best"
        }.let {
            if (profile == QuickQualityProfile.BEST && codecFilter.isNotEmpty()) {
                "bestvideo$codecFilter+bestaudio/best"
            } else {
                it
            }
        }
    }

    private fun codecSelectorFilter(preference: VideoCodecPreference): String {
        return when (preference) {
            VideoCodecPreference.ALL -> ""
            VideoCodecPreference.H264 -> "[vcodec^=avc1]"
            VideoCodecPreference.H265 -> "[vcodec~='^(hev1|hvc1)']"
            VideoCodecPreference.AV1 -> "[vcodec^=av01]"
            VideoCodecPreference.VP9 -> "[vcodec~='^(vp9|vp09)']"
        }
    }

    private companion object {
        private const val PROGRESS_UPDATE_INTERVAL_NANOS = 150_000_000L
    }
}
