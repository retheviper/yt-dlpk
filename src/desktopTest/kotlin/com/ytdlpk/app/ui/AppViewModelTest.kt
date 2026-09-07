package com.ytdlpk.app.ui

import com.ytdlpk.app.model.AppSettings
import com.ytdlpk.app.service.FormatService
import com.ytdlpk.app.service.ProcessRunner
import com.ytdlpk.app.service.ProgressParser
import com.ytdlpk.app.service.SettingsRepository
import com.ytdlpk.app.service.ToolManager
import com.ytdlpk.app.service.YtDlpCommandBuilder
import com.ytdlpk.app.service.YtDlpService
import com.ytdlpk.app.service.detectToolOs
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

private class ViewModelFixture : AutoCloseable {
    private val home = createTempDirectory("view-model-test-")
    private val bin = home.resolve("bin").createDirectories()
    val viewModel: AppViewModel

    init {
        fun executable(name: String, body: String) {
            bin.resolve(name).writeText("#!/bin/sh\n$body\n")
            bin.resolve(name).toFile().setExecutable(true)
        }
        executable("ffmpeg", "echo 'ffmpeg version 9.0.1'")
        executable("ffprobe", "echo 'ffprobe version 9.0.1'")
        executable("deno", "echo 'deno 2.9.6'")
        executable("yt-dlp", """
            analyze=false
            for arg in "${'$'}@"; do
                [ "${'$'}arg" = '--version' ] && { echo '2026.09.01'; exit 0; }
                [ "${'$'}arg" = '--dump-single-json' ] && analyze=true
                url="${'$'}arg"
            done
            if ${'$'}analyze; then
                echo '{"title":"fixture","formats":[{"format_id":"video-a","ext":"mp4","vcodec":"avc1","acodec":"mp4a"}]}'
            else
                echo "started ${'$'}url" >&2
                [ "${'$'}url" = 'https://example.com/slow' ] && /bin/sleep 30
                exit 0
            fi
        """.trimIndent())
        val runner = ProcessRunner(pathDirectories = listOf(bin, Path.of("/usr/bin"), Path.of("/bin")))
        val formats = FormatService(runner)
        val settings = SettingsRepository(home)
        settings.save(AppSettings(notifyOnDownloadCompleteWhenInactive = false))
        viewModel = AppViewModel(home, settings,
            ToolManager(home, os = "mac", searchDirectories = listOf(bin), resourceLoader = { "{}" }),
            YtDlpService(runner, formats, YtDlpCommandBuilder(formats), ProgressParser()))
    }

    suspend fun ready() = withTimeout(5_000) { viewModel.state.first { it.toolsReady } }

    override fun close() {
        viewModel.close()
        home.toFile().deleteRecursively()
    }
}

class AppViewModelTest : StringSpec(spec@{
    if (detectToolOs() == "windows") return@spec
    "changing URL clears formats from the previous video" {
        ViewModelFixture().use { fixture ->
            fixture.ready()
            val vm = fixture.viewModel
            vm.onUrlChange("https://example.com/a")
            vm.analyze()
            withTimeout(5_000) { vm.state.first { it.metadata != null && !it.isAnalyzing } }
            vm.onUrlChange("https://example.com/b")
            vm.state.value.metadata shouldBe null
            vm.state.value.formats shouldBe emptyList()
            vm.state.value.selectedFormatId shouldBe null
        }
    }

    "a new download completes immediately after cancelling the previous one" {
        ViewModelFixture().use { fixture ->
            fixture.ready()
            val vm = fixture.viewModel
            vm.quickDownload("https://example.com/slow")
            withTimeout(5_000) { vm.state.first { it.logs.any { line -> line.contains("started https://example.com/slow") } } }
            vm.cancelDownload()
            vm.quickDownload("https://example.com/next")
            withTimeout(5_000) { vm.state.first { !it.isDownloading } }
            vm.state.value.lastError shouldBe null
            vm.state.value.logs.any { it.contains("Quick download finished with code 0") } shouldBe true
        }
    }
})
