package com.ytdlpk.app.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/** Opt-in release check: downloads real tools into an isolated, caller-supplied directory. */
class ManagedToolsSmokeTest : StringSpec({
    "all managed tools work without system installations".config(
        enabled = !System.getenv("YTDLPK_TOOL_SMOKE_HOME").isNullOrBlank()
    ) {
        val home = Path.of(System.getenv("YTDLPK_TOOL_SMOKE_HOME"))
        val manager = ToolManager(home, searchDirectories = emptyList()) { resource ->
            javaClass.classLoader.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }
        }
        val tools = manager.ensureTools(onStatus = ::println)
        tools.ytDlpManaged shouldBe true
        tools.ffmpegManaged shouldBe true
        val ffprobeName = if (detectToolOs() == "windows") "ffprobe.exe" else "ffprobe"
        Path.of(tools.ffmpegPath).resolveSibling(ffprobeName).exists() shouldBe true
        manager.denoPath!!.exists() shouldBe true
        manager.getYtDlpVersion(tools.ytDlpPath).also(::println).isNotBlank() shouldBe true
        manager.getFfmpegVersion(tools.ffmpegPath).also(::println).isNotBlank() shouldBe true
        Files.list(home.resolve("tools")).use { paths ->
            paths.noneMatch { it.fileName.toString().startsWith("install-") } shouldBe true
        }
    }
})
