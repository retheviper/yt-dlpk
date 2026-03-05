package com.ytdlpk.app.service

import com.ytdlpk.app.model.ToolPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

private data class ToolSources(
    val ytDlp: Map<String, String>,
    val ffmpeg: Map<String, String>
)

class ToolManager(
    private val appHome: Path,
    private val resourceLoader: (String) -> String
) {
    private val toolsRoot = appHome.resolve("tools")
    private val binDir = toolsRoot.resolve("bin")

    suspend fun ensureTools(onStatus: (String) -> Unit): ToolPaths {
        return resolveTools(onStatus, forceManagedYtDlp = false, forceManagedFfmpeg = false)
    }

    suspend fun updateManagedYtDlp(onStatus: (String) -> Unit): ToolPaths {
        return resolveTools(onStatus, forceManagedYtDlp = true, forceManagedFfmpeg = false)
    }

    suspend fun updateManagedFfmpeg(onStatus: (String) -> Unit): ToolPaths {
        return resolveTools(onStatus, forceManagedYtDlp = false, forceManagedFfmpeg = true)
    }

    fun getYtDlpVersion(path: String): String {
        return runCommandFirstLine(path, "--version") ?: "-"
    }

    fun getFfmpegVersion(path: String): String {
        return runCommandFirstLine(path, "-version")?.substringBefore(" Copyright") ?: "-"
    }

    suspend fun getLatestYtDlpVersion(): String = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        runCatching {
            val json = readTextFromUrl(url)
            Json.parseToJsonElement(json).jsonObject["tag_name"]?.jsonPrimitive?.content ?: "-"
        }.getOrDefault("-")
    }

    suspend fun getLatestFfmpegVersion(): String = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest"
        runCatching {
            val json = readTextFromUrl(url)
            Json.parseToJsonElement(json).jsonObject["tag_name"]?.jsonPrimitive?.content ?: "-"
        }.getOrDefault("-")
    }

    private suspend fun resolveTools(
        onStatus: (String) -> Unit,
        forceManagedYtDlp: Boolean,
        forceManagedFfmpeg: Boolean
    ): ToolPaths {
        binDir.createDirectories()
        val os = detectOs()
        val arch = detectArch()
        val sources = parseToolSources(resourceLoader("tool-sources.json"))

        val ytDlpName = if (os == "windows") "yt-dlp.exe" else "yt-dlp"
        val ffmpegName = if (os == "windows") "ffmpeg.exe" else "ffmpeg"

        val ytDlpPath = binDir.resolve(ytDlpName)
        val ffmpegPath = binDir.resolve(ffmpegName)

        val resolvedYtDlp = if (!forceManagedYtDlp) {
            resolveExistingBinary(ytDlpPath, ytDlpName, "--version")
        } else {
            null
        } ?: run {
            onStatus("Downloading yt-dlp...")
            val ytDlpCandidates = buildYtDlpCandidates(os, sources)
            downloadBinary(ytDlpCandidates, ytDlpPath)
            ytDlpPath.toFile().setExecutable(true)
            requireExecutable(ytDlpPath, "--version")
            ytDlpPath
        }

        val resolvedFfmpeg = if (!forceManagedFfmpeg) {
            resolveExistingBinary(ffmpegPath, ffmpegName, "-version")
        } else {
            null
        } ?: run {
            onStatus("Downloading ffmpeg...")
            val archivePath = toolsRoot.resolve(if (os == "linux") "ffmpeg.tar.xz" else "ffmpeg.zip")
            val ffmpegCandidates = buildFfmpegCandidates(os, arch, sources)
            downloadBinary(ffmpegCandidates, archivePath)
            onStatus("Extracting ffmpeg...")
            if (archivePath.name.endsWith(".zip")) {
                extractZip(archivePath, toolsRoot)
            } else {
                extractTarXz(archivePath, toolsRoot)
            }
            val found = findBinary(toolsRoot, ffmpegName)
                ?: error("ffmpeg binary not found after extraction")
            Files.copy(found, ffmpegPath, StandardCopyOption.REPLACE_EXISTING)
            ffmpegPath.toFile().setExecutable(true)
            requireExecutable(ffmpegPath, "-version")
            ffmpegPath
        }

        return ToolPaths(
            ytDlpPath = resolvedYtDlp.toAbsolutePath().toString(),
            ffmpegPath = resolvedFfmpeg.toAbsolutePath().toString(),
            ytDlpManaged = resolvedYtDlp.startsWith(binDir),
            ffmpegManaged = resolvedFfmpeg.startsWith(binDir)
        )
    }

    private fun runCommandFirstLine(binaryPath: String, arg: String): String? {
        return try {
            val process = ProcessBuilder(binaryPath, arg)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                return null
            }
            process.inputStream.bufferedReader().useLines { lines -> lines.firstOrNull() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun downloadBinary(urls: List<String>, target: Path) {
        target.parent.createDirectories()
        val errors = mutableListOf<String>()
        for (url in urls) {
            runCatching {
                URI.create(url).toURL().openStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }.onSuccess {
                return
            }.onFailure { e ->
                errors += "$url -> ${e.message}"
            }
        }
        error("Failed to download from all candidates:\n${errors.joinToString("\n")}")
    }

    private fun extractZip(archive: Path, destination: Path) {
        val destinationRoot = destination.toAbsolutePath().normalize()
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = destinationRoot.resolve(entry.name).normalize()
                    require(out.startsWith(destinationRoot)) {
                        "Zip entry escapes target directory: ${entry.name}"
                    }
                    out.parent?.createDirectories()
                    out.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun extractTarXz(archive: Path, destination: Path) {
        val destinationRoot = destination.toAbsolutePath().normalize()
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(FileInputStream(archive.toFile())))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry as? TarArchiveEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = destinationRoot.resolve(entry.name).normalize()
                    require(out.startsWith(destinationRoot)) {
                        "Tar entry escapes target directory: ${entry.name}"
                    }
                    out.parent?.createDirectories()
                    out.outputStream().use { output -> tar.copyTo(output) }
                }
                entry = tar.nextEntry as? TarArchiveEntry
            }
        }
    }

    private fun findBinary(root: Path, name: String): Path? {
        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .findFirst()
                .orElse(null)
        }
    }

    private fun resolveExistingBinary(appBinary: Path, commandName: String, checkArg: String): Path? {
        if (appBinary.exists() && isExecutable(appBinary, checkArg)) {
            return appBinary
        }
        val fromPath = findInPath(commandName)
        if (fromPath != null && isExecutable(fromPath, checkArg)) {
            return fromPath
        }
        return null
    }

    private fun requireExecutable(path: Path, checkArg: String) {
        if (!isExecutable(path, checkArg)) {
            error("Installed tool is not executable: $path")
        }
    }

    private fun isExecutable(path: Path, checkArg: String): Boolean {
        return try {
            val process = ProcessBuilder(path.toAbsolutePath().toString(), checkArg)
                .redirectErrorStream(true)
                .start()
            process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun findInPath(commandName: String): Path? {
        val locator = if (detectOs() == "windows") "where" else "which"
        return try {
            val process = ProcessBuilder(locator, commandName)
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.exitValue() != 0) return null
            val first = process.inputStream.bufferedReader().useLines { it.firstOrNull() } ?: return null
            Path.of(first.trim())
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildYtDlpCandidates(os: String, sources: ToolSources): List<String> {
        return listOfNotNull(
            sources.ytDlp[os],
            if (os == "windows") "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
            else "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
        ).distinct()
    }

    private fun buildFfmpegCandidates(os: String, arch: String, sources: ToolSources): List<String> {
        val configured = sources.ffmpeg[os]
        val fallback = when (os) {
            "mac" -> listOf("https://evermeet.cx/ffmpeg/getrelease/zip")
            "windows", "linux" -> listOfNotNull(fetchLatestBtbnAssetUrl(os, arch))
            else -> emptyList()
        }
        return listOfNotNull(configured).plus(fallback).distinct()
    }

    private fun fetchLatestBtbnAssetUrl(os: String, arch: String): String? {
        val api = "https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest"
        return runCatching {
            val json = readTextFromUrl(api)
            val assets = Json.parseToJsonElement(json).jsonObject["assets"]?.jsonArray ?: return null
            val suffix = when (os) {
                "windows" -> if (arch == "arm64") "-winarm64-gpl.zip" else "-win64-gpl.zip"
                "linux" -> if (arch == "arm64") "-linuxarm64-gpl.tar.xz" else "-linux64-gpl.tar.xz"
                else -> return null
            }
            assets
                .mapNotNull { item ->
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    name to url
                }
                .firstOrNull { (name, _) -> name.contains(suffix) }
                ?.second
        }.getOrNull()
    }

    private fun readTextFromUrl(url: String): String {
        return URI.create(url).toURL().openStream().bufferedReader().use { it.readText() }
    }

    private fun parseToolSources(jsonText: String): ToolSources {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        fun parseMap(key: String): Map<String, String> {
            val obj = root[key]?.jsonObject ?: emptyMap()
            return obj.mapValues { (_, value) -> value.jsonPrimitive.content }
        }
        return ToolSources(
            ytDlp = parseMap("ytDlp"),
            ffmpeg = parseMap("ffmpeg")
        )
    }

    private fun detectArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x64"
        }
    }

    private fun detectOs(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "mac"
            else -> "linux"
        }
    }
}
