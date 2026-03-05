package com.ytdlpk.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ytdlpk.app.service.FormatService
import com.ytdlpk.app.service.ProcessRunner
import com.ytdlpk.app.service.ProgressParser
import com.ytdlpk.app.service.SettingsRepository
import com.ytdlpk.app.service.ToolManager
import com.ytdlpk.app.service.YtDlpCommandBuilder
import com.ytdlpk.app.service.YtDlpService
import com.ytdlpk.app.ui.App
import com.ytdlpk.app.ui.AppViewModel
import com.ytdlpk.app.util.updateAppIconProgress
import java.awt.Dimension
import java.nio.file.Paths

fun main() = application {
    val appHome = Paths.get(System.getProperty("user.home"), ".yt-dlpk")
    val processRunner = ProcessRunner()
    val formatService = FormatService(processRunner)
    val settingsRepository = SettingsRepository(appHome)
    val toolManager = ToolManager(appHome) { resourceName ->
        object {}.javaClass.classLoader.getResourceAsStream(resourceName)
            ?.bufferedReader()
            ?.readText()
            ?: error("Missing resource: $resourceName")
    }
    val commandBuilder = YtDlpCommandBuilder(formatService)
    val ytDlpService = YtDlpService(
        processRunner = processRunner,
        formatService = formatService,
        commandBuilder = commandBuilder,
        progressParser = ProgressParser()
    )

    val viewModel = AppViewModel(
        appHome = appHome,
        settingsRepository = settingsRepository,
        toolManager = toolManager,
        ytDlpService = ytDlpService
    )

    val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "yt-dlpk",
        state = windowState
    ) {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1180, 800)
        }
        LaunchedEffect(state.isDownloading, state.progress.percent) {
            updateAppIconProgress(
                window = window,
                isDownloading = state.isDownloading,
                percent = state.progress.percent
            )
        }
        App(viewModel)
    }
}
