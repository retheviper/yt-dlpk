package com.ytdlpk.app.service

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ToolManagerTest : StringSpec(spec@{
    if (detectToolOs() == "windows") return@spec
    fun sources() = """{"ytDlp":{"mac":"https://fixture/yt-dlp"},"ffmpeg":{"mac":"https://fixture/ffmpeg.zip"}}"""

    fun executable(path: Path, version: String) {
        path.parent.createDirectories()
        path.writeText("#!/bin/sh\necho '$version'\n")
        path.toFile().setExecutable(true)
    }

    fun archive(path: Path, valid: Boolean = true) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            listOf("ffmpeg", "ffprobe").forEach { name ->
                zip.putNextEntry(ZipEntry("package/bin/$name"))
                zip.write((if (valid) "#!/bin/sh\necho '$name version 9.0.1'\n" else "broken binary").toByteArray())
                zip.closeEntry()
            }
        }
    }

    "managed ffmpeg installs ffprobe beside it" {
        val home = createTempDirectory("tools-test-")
        try {
            val manager = ToolManager(home, os = "mac", arch = "x64", searchDirectories = emptyList(),
                download = { _, target -> archive(target) }, resourceLoader = { sources() })
            val ffmpeg = manager.ensureFfmpeg {}
            ffmpeg.resolveSibling("ffprobe").exists() shouldBe true
        } finally { home.toFile().deleteRecursively() }
    }

    "failed managed ffmpeg update preserves the working executable" {
        val home = createTempDirectory("tools-test-")
        try {
            val bin = home.resolve("tools/bin")
            executable(bin.resolve("yt-dlp"), "2026.09.01")
            executable(bin.resolve("ffmpeg"), "ffmpeg version 8.0")
            executable(bin.resolve("ffprobe"), "ffprobe version 8.0")
            val original = bin.resolve("ffmpeg").readText()
            val manager = ToolManager(home, os = "mac", arch = "x64", searchDirectories = emptyList(),
                download = { _, target -> archive(target, valid = false) }, resourceLoader = { sources() })
            shouldThrowAny { manager.updateManagedFfmpeg {} }
            bin.resolve("ffmpeg").readText() shouldBe original
        } finally { home.toFile().deleteRecursively() }
    }

    "successful yt-dlp install removes downloaded payloads" {
        val home = createTempDirectory("tools-test-")
        try {
            val manager = ToolManager(home, os = "mac", arch = "x64", searchDirectories = emptyList(),
                download = { _, target -> executable(target, "2026.09.01") }, resourceLoader = { sources() })
            manager.ensureYtDlp {}
            Files.walk(home).use { paths ->
                paths.anyMatch { it.fileName.toString().contains(".download") } shouldBe false
            }
        } finally { home.toFile().deleteRecursively() }
    }

    "concurrent ffmpeg setup downloads a combined archive only once" {
        val home = createTempDirectory("tools-test-")
        try {
            var downloads = 0
            val manager = ToolManager(home, os = "mac", arch = "x64", searchDirectories = emptyList(),
                download = { _, target -> downloads++; archive(target) }, resourceLoader = { sources() })
            coroutineScope { List(4) { async { manager.ensureFfmpeg {} } }.awaitAll() }.distinct().size shouldBe 1
            downloads shouldBe 1
        } finally { home.toFile().deleteRecursively() }
    }

    "an invalid first PATH entry does not hide a valid system yt-dlp" {
        val home = createTempDirectory("tools-test-")
        try {
            val invalid = home.resolve("invalid").createDirectories()
            invalid.resolve("yt-dlp").writeText("broken")
            val valid = home.resolve("valid").createDirectories()
            executable(valid.resolve("yt-dlp"), "2026.09.01")
            val manager = ToolManager(home, searchDirectories = listOf(invalid, valid),
                download = { _, _ -> error("must use the existing tool") }, resourceLoader = { sources() })
            manager.ensureYtDlp {} shouldBe valid.resolve("yt-dlp")
        } finally { home.toFile().deleteRecursively() }
    }

    "Deno setup selects the native architecture and records the actual runtime" {
        val home = createTempDirectory("tools-test-")
        try {
            val system = home.resolve("system").createDirectories()
            executable(system.resolve("deno"), "deno 1.46.0")
            var downloadedUrl = ""
            val manager = ToolManager(home, os = "mac", arch = "arm64", searchDirectories = listOf(system),
                download = { url, target -> downloadedUrl = url; executable(target, "deno 2.9.6") },
                resourceLoader = { """{"deno":{"mac-arm64":"https://fixture/arm64","mac-x64":"https://fixture/x64"}}""" })
            val runtime = manager.ensureDeno {}
            downloadedUrl shouldBe "https://fixture/arm64"
            manager.denoPath shouldBe runtime
            runtime shouldBe home.resolve("tools/bin/deno")
        } finally { home.toFile().deleteRecursively() }
    }

    "ffmpeg version comparison ignores build and copyright numbers" {
        val home = createTempDirectory("tools-test-")
        try {
            val manager = ToolManager(home, resourceLoader = { sources() })
            manager.compareVersions("ffmpeg version 9.0.1-static Copyright 2000-2026", "9.0.1") shouldBe 0
            manager.compareVersions("ffmpeg version N-12345-gabcdef", "9.0.1") shouldBe null
        } finally { home.toFile().deleteRecursively() }
    }
})
