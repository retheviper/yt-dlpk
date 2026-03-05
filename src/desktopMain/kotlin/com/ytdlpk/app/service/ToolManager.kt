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
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes

private data class ToolSources(
    val ytDlp: Map<String, String>,
    val ffmpeg: Map<String, String>
)

private data class ExecProbe(
    val ok: Boolean,
    val details: String
)

class ToolManager(
    private val appHome: Path,
    private val resourceLoader: (String) -> String
) {
    private val toolProbeTimeoutSeconds = 60L

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
            downloadExecutableBinary(ytDlpCandidates, ytDlpPath, "--version", os)
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
            makeExecutable(ffmpegPath, os)
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
            val finished = process.waitFor(toolProbeTimeoutSeconds, TimeUnit.SECONDS)
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

    private fun downloadExecutableBinary(urls: List<String>, target: Path, checkArg: String, os: String) {
        target.parent.createDirectories()
        val errors = mutableListOf<String>()
        val temp = target.resolveSibling("${target.fileName}.download.bin")
        val payload = target.resolveSibling("${target.fileName}.download.payload")
        for (url in urls) {
            runCatching {
                Files.deleteIfExists(temp)
                Files.deleteIfExists(payload)
                URI.create(url).toURL().openStream().use { input ->
                    payload.outputStream().use { output -> input.copyTo(output) }
                }
                if (url.endsWith(".zip") && os == "mac") {
                    installMacYtDlpFromZip(payload, target)
                    val probe = probeExecutable(target, checkArg)
                    check(probe.ok) { "downloaded file failed executable check: ${probe.details}" }
                    return
                } else if (url.endsWith(".zip")) {
                    extractBinaryFromZip(payload, temp, target.fileName.toString())
                } else {
                    Files.copy(payload, temp, StandardCopyOption.REPLACE_EXISTING)
                }
                makeExecutable(temp, os)
                val probe = probeExecutable(temp, checkArg)
                check(probe.ok) { "downloaded file failed executable check: ${probe.details}" }
                runCatching {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }.onSuccess {
                return
            }.onFailure { e ->
                errors += "$url -> ${e.message}"
            }.also {
                runCatching { Files.deleteIfExists(temp) }
                runCatching { Files.deleteIfExists(payload) }
            }
        }
        error("Failed to install executable from all candidates:\n${errors.joinToString("\n")}")
    }

    private fun installMacYtDlpFromZip(zipPath: Path, target: Path) {
        val parent = target.parent ?: error("invalid target path: $target")
        val internalDir = parent.resolve("_internal")
        runCatching { Files.walk(internalDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }

        extractZip(zipPath, parent)

        val extracted = findBinary(parent, "yt-dlp_macos") ?: findBinary(parent, "yt-dlp")
            ?: error("yt-dlp executable not found in mac zip")
        Files.copy(extracted, target, StandardCopyOption.REPLACE_EXISTING)
        makeExecutable(target, "mac")
    }

    private fun extractBinaryFromZip(zipPath: Path, outBinary: Path, expectedName: String) {
        val found = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(BufferedInputStream(zipPath.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name.substringAfterLast("/")
                    if (entryName == expectedName || entryName == "yt-dlp_macos" || entryName == "yt-dlp") {
                        found += entryName to zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val bytes = found.firstOrNull()?.second ?: error("executable entry not found in zip")
        outBinary.writeBytes(bytes)
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
        val os = detectOs()
        if (appBinary.exists()) {
            if (isExecutable(appBinary, checkArg)) return appBinary
            makeExecutable(appBinary, os)
            if (isExecutable(appBinary, checkArg)) return appBinary
        }
        val fromPath = findInPath(commandName)
        if (fromPath != null && isExecutable(fromPath, checkArg)) {
            return fromPath
        }
        return null
    }

    private fun requireExecutable(path: Path, checkArg: String) {
        val probe = probeExecutable(path, checkArg)
        if (!probe.ok) {
            error("Installed tool is not executable: $path (${probe.details})")
        }
    }

    private fun makeExecutable(path: Path, os: String) {
        if (os == "windows" || !Files.exists(path)) return

        runCatching {
            val perms = runCatching { Files.getPosixFilePermissions(path) }.getOrDefault(emptySet())
            val updated = perms.toMutableSet().apply {
                add(PosixFilePermission.OWNER_EXECUTE)
                add(PosixFilePermission.GROUP_EXECUTE)
                add(PosixFilePermission.OTHERS_EXECUTE)
                add(PosixFilePermission.OWNER_READ)
            }
            Files.setPosixFilePermissions(path, updated)
        }
        runCatching { path.toFile().setExecutable(true, false) }
        runCatching {
            ProcessBuilder("chmod", "755", path.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        }

        if (os == "mac") {
            runCatching {
                ProcessBuilder("xattr", "-d", "com.apple.quarantine", path.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun isExecutable(path: Path, checkArg: String): Boolean {
        return probeExecutable(path, checkArg).ok
    }

    private fun probeExecutable(path: Path, checkArg: String): ExecProbe {
        return try {
            val process = ProcessBuilder(path.toAbsolutePath().toString(), checkArg)
                .redirectErrorStream(true)
                .start()
            // Drain output while running to avoid pipe backpressure causing false timeouts.
            val outputBuffer = StringBuilder()
            val outputCollector = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { outputBuffer.append(it.readText()) }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(toolProbeTimeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                return ExecProbe(false, "timeout")
            }
            outputCollector.join(2000)
            val output = outputBuffer.toString().trim()
            if (process.exitValue() == 0) {
                ExecProbe(true, output.lineSequence().firstOrNull().orEmpty())
            } else {
                val first = output.lineSequence().firstOrNull().orEmpty()
                ExecProbe(false, "exit=${process.exitValue()} ${if (first.isBlank()) "(no output)" else first}")
            }
        } catch (t: Throwable) {
            ExecProbe(false, t.message ?: t::class.simpleName.orEmpty())
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
        return when (os) {
            "windows" -> listOfNotNull(
                sources.ytDlp[os],
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
            ).distinct()
            "mac" -> listOfNotNull(
                sources.ytDlp[os],
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos",
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos.zip"
            ).distinct()
            else -> listOfNotNull(
                sources.ytDlp[os],
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
            ).distinct()
        }
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
