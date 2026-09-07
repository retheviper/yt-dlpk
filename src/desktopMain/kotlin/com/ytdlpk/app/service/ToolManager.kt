package com.ytdlpk.app.service

import com.ytdlpk.app.model.ToolPaths
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class ToolSources(
    val ytDlp: Map<String, String>,
    val ffmpeg: Map<String, String>,
    val ffprobe: Map<String, String>,
    val deno: Map<String, String>
)

private data class ExecProbe(
    val ok: Boolean,
    val details: String
)

data class SystemToolUpdateResult(
    val updated: Boolean,
    val message: String
)

class ToolManager(
    private val appHome: Path,
    private val os: String = detectToolOs(),
    private val arch: String = detectToolArch(),
    private val searchDirectories: List<Path> = systemToolDirectories(os),
    private val download: ((String, Path) -> Unit)? = null,
    private val resourceLoader: (String) -> String
) {
    private val toolProbeTimeoutSeconds = 10L
    private val networkTimeout = Duration.ofSeconds(30)
    private val downloadTimeout = Duration.ofMinutes(5)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    private val toolsRoot = appHome.resolve("tools")
    private val binDir = toolsRoot.resolve("bin")
    @Volatile var denoPath: Path? = null
        private set

    private val ytDlpMutex = Mutex()
    private val ffmpegMutex = Mutex()
    private val denoMutex = Mutex()
    private val installer = ManagedToolInstaller(toolsRoot, ::downloadToPath) { path, arg ->
        makeExecutable(path, os)
        requireExecutable(path, arg)
    }

    suspend fun ensureYtDlp(onStatus: (String) -> Unit): Path = ytDlpMutex.withLock {
        runInterruptible(Dispatchers.IO) { resolveYtDlp(onStatus, force = false) }
    }

    suspend fun ensureFfmpeg(onStatus: (String) -> Unit): Path = ffmpegMutex.withLock {
        runInterruptible(Dispatchers.IO) { resolveFfmpeg(onStatus, force = false) }
    }

    suspend fun ensureDeno(onStatus: (String) -> Unit): Path = denoMutex.withLock {
        runInterruptible(Dispatchers.IO) {
            val target = binDir.resolve(executableName("deno"))
            val existing = (searchDirectories.map { it.resolve(target.fileName) } + listOf(target)).firstOrNull { path ->
                if (!Files.isRegularFile(path)) return@firstOrNull false
                val probe = probeExecutable(path, "--version")
                probe.ok && (compareVersions(probe.details, "2.3.0") ?: -1) >= 0
            }
            (existing ?: run {
                onStatus("Downloading Deno...")
                val sources = parseToolSources(resourceLoader("tool-sources.json"))
                installer.install(mapOf(target to listOf(sourceFor(sources.deno))), "--version")
                target
            }).also { denoPath = it }
        }
    }

    private fun executableName(name: String): String = if (os == "windows") "$name.exe" else name

    private fun resolveYtDlp(onStatus: (String) -> Unit, force: Boolean): Path {
        val target = binDir.resolve(executableName("yt-dlp"))
        if (!force) resolveExistingBinary(target, target.fileName.toString(), "--version")?.let { return it }
        onStatus("Downloading yt-dlp...")
        val sources = parseToolSources(resourceLoader("tool-sources.json"))
        installer.install(mapOf(target to listOf(sourceFor(sources.ytDlp))), "--version")
        return target
    }

    private fun resolveFfmpeg(onStatus: (String) -> Unit, force: Boolean): Path {
        val target = binDir.resolve(executableName("ffmpeg"))
        val probeName = executableName("ffprobe")
        if (!force) {
            (searchDirectories.map { it.resolve(target.fileName) } + listOf(target)).firstOrNull { candidate ->
                isExecutable(candidate, "-version") && isExecutable(candidate.resolveSibling(probeName), "-version")
            }?.let { return it }
        }
        onStatus("Downloading ffmpeg and ffprobe...")
        val sources = parseToolSources(resourceLoader("tool-sources.json"))
        val ffmpegUrl = sourceFor(sources.ffmpeg)
        val ffprobeUrl = sources.ffprobe["$os-$arch"] ?: sources.ffprobe[os] ?: ffmpegUrl
        installer.install(linkedMapOf(target to listOf(ffmpegUrl), target.resolveSibling(probeName) to listOf(ffprobeUrl)), "-version")
        return target
    }

    private fun sourceFor(sources: Map<String, String>): String =
        sources["$os-$arch"] ?: sources[os] ?: error("No tool download configured for $os/$arch")

    suspend fun ensureTools(
        onStatus: (String) -> Unit,
        onYtDlpReady: (Path) -> Unit = {}
    ): ToolPaths {
        val ytDlp = ensureYtDlp(onStatus)
        ensureDeno(onStatus)
        onYtDlpReady(ytDlp)
        val ffmpeg = ensureFfmpeg(onStatus)
        return ToolPaths(
            ytDlpPath = ytDlp.toAbsolutePath().toString(),
            ffmpegPath = ffmpeg.toAbsolutePath().toString(),
            ytDlpManaged = ytDlp.startsWith(binDir),
            ffmpegManaged = ffmpeg.startsWith(binDir)
        )
    }

    suspend fun updateManagedYtDlp(onStatus: (String) -> Unit): Path = ytDlpMutex.withLock {
        runInterruptible(Dispatchers.IO) { resolveYtDlp(onStatus, force = true) }
    }

    suspend fun updateManagedFfmpeg(onStatus: (String) -> Unit): Path = ffmpegMutex.withLock {
        runInterruptible(Dispatchers.IO) { resolveFfmpeg(onStatus, force = true) }
    }

    fun getYtDlpVersion(path: String): String {
        return runCommandFirstLine(path, "--version") ?: "-"
    }

    fun getFfmpegVersion(path: String): String {
        return runCommandFirstLine(path, "-version")?.substringBefore(" Copyright") ?: "-"
    }

    suspend fun getLatestYtDlpVersion(): String = runInterruptible(Dispatchers.IO) {
        val url = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        runCatching {
            val json = readTextFromUrl(url)
            Json.parseToJsonElement(json).jsonObject["tag_name"]?.jsonPrimitive?.content ?: "-"
        }.getOrDefault("-")
    }

    suspend fun getLatestFfmpegVersion(): String = runInterruptible(Dispatchers.IO) {
        runCatching {
            val json = readTextFromUrl("https://evermeet.cx/ffmpeg/info/ffmpeg/release")
            Json.parseToJsonElement(json).jsonObject["version"]?.jsonPrimitive?.content ?: "-"
        }.getOrElse {
            runCatching {
                val json = readTextFromUrl("https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest")
                Json.parseToJsonElement(json).jsonObject["tag_name"]?.jsonPrimitive?.content ?: "-"
            }.getOrDefault("-")
        }
    }

    fun compareVersions(current: String, latest: String): Int? {
        val currentParts = versionParts(current)
        val latestParts = versionParts(latest)
        if (currentParts.isEmpty() || latestParts.isEmpty()) return null

        val maxSize = maxOf(currentParts.size, latestParts.size)
        for (index in 0 until maxSize) {
            val currentPart = currentParts.getOrElse(index) { 0 }
            val latestPart = latestParts.getOrElse(index) { 0 }
            if (currentPart != latestPart) return currentPart.compareTo(latestPart)
        }
        return 0
    }

    suspend fun updateSystemYtDlp(path: String, onStatus: (String) -> Unit): SystemToolUpdateResult {
        return updateSystemTool(
            toolName = "yt-dlp",
            binaryPath = path,
            selfUpdateArgs = listOf(path, "-U"),
            homebrewFormula = "yt-dlp",
            wingetPackage = "yt-dlp.yt-dlp",
            chocoPackage = "yt-dlp",
            scoopPackage = "yt-dlp",
            onStatus = onStatus
        )
    }

    suspend fun updateSystemFfmpeg(path: String, onStatus: (String) -> Unit): SystemToolUpdateResult {
        return updateSystemTool(
            toolName = "ffmpeg",
            binaryPath = path,
            selfUpdateArgs = null,
            homebrewFormula = "ffmpeg",
            wingetPackage = "Gyan.FFmpeg",
            chocoPackage = "ffmpeg",
            scoopPackage = "ffmpeg",
            onStatus = onStatus
        )
    }

    private fun runCommandFirstLine(binaryPath: String, arg: String): String? {
        val result = runCommand(listOf(binaryPath, arg), toolProbeTimeoutSeconds)
        return result.output.lineSequence().firstOrNull()?.takeIf { result.exitCode == 0 }
    }

    private suspend fun updateSystemTool(
        toolName: String,
        binaryPath: String,
        selfUpdateArgs: List<String>?,
        homebrewFormula: String,
        wingetPackage: String,
        chocoPackage: String,
        scoopPackage: String,
        onStatus: (String) -> Unit
    ): SystemToolUpdateResult = runInterruptible(Dispatchers.IO) {
        val commands = buildList {
            val brew = findInPath("brew")
            if (brew != null && isLikelyHomebrewBinary(binaryPath)) {
                add(listOf(brew.toString(), "upgrade", homebrewFormula))
            }
            if (os == "windows") {
                findInPath("winget")?.let {
                    add(listOf(it.toString(), "upgrade", "--id", wingetPackage, "--silent", "--accept-package-agreements", "--accept-source-agreements"))
                }
                findInPath("choco")?.let { add(listOf(it.toString(), "upgrade", chocoPackage, "-y")) }
                findInPath("scoop")?.let { add(listOf(it.toString(), "update", scoopPackage)) }
            }
            if (selfUpdateArgs != null) {
                add(selfUpdateArgs)
            }
        }

        if (commands.isEmpty()) {
            return@runInterruptible SystemToolUpdateResult(
                updated = false,
                message = systemUpdateInstructions(toolName)
            )
        }

        val errors = mutableListOf<String>()
        for (command in commands) {
            onStatus("Updating $toolName...")
            val result = runCommand(command, timeoutSeconds = 600)
            if (result.exitCode == 0) {
                return@runInterruptible SystemToolUpdateResult(
                    updated = true,
                    message = result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "$toolName update finished."
                )
            }
            errors += "${command.joinToString(" ")} -> ${result.output.take(600)}"
        }

        SystemToolUpdateResult(
            updated = false,
            message = buildString {
                append(systemUpdateInstructions(toolName))
                if (errors.isNotEmpty()) append("\n\nLast failure:\n${errors.last()}")
            }
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )

    private fun runCommand(command: List<String>, timeoutSeconds: Long): CommandResult {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).apply {
                environment()["PATH"] = (searchDirectories + listOf(binDir)).joinToString(File.pathSeparator)
            }.start()
        } catch (e: Exception) {
            return CommandResult(-1, e.message.orEmpty())
        }
        val output = StringBuilder()
        val collector = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) {
                        if (output.length < 65_536) output.appendLine(line.take(4_096))
                    } }
                }
            }
        }.apply { isDaemon = true; start() }
        return try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                CommandResult(-1, "timeout")
            } else {
                collector.join(2_000)
                CommandResult(process.exitValue(), synchronized(output) { output.toString().trim() })
            }
        } finally {
            terminateProcessTree(process)
            process.inputStream.close()
            process.outputStream.close()
        }
    }

    private fun versionParts(value: String): List<Int> {
        val version = Regex("""^(?:ffmpeg version |deno )?v?(\d+(?:\.\d+)*)(?:\b|$)""")
            .find(value.trim())?.groupValues?.get(1) ?: return emptyList()
        return version.split('.').mapNotNull(String::toIntOrNull)
    }

    private fun isLikelyHomebrewBinary(path: String): Boolean {
        return path.startsWith("/opt/homebrew/") ||
            path.startsWith("/usr/local/") ||
            path.contains("/linuxbrew/")
    }

    private fun systemUpdateInstructions(toolName: String): String {
        return when (os) {
            "mac" -> "Could not update system $toolName automatically. If it was installed with Homebrew, run: brew upgrade $toolName"
            "windows" -> "Could not update system $toolName automatically. Try winget, Chocolatey, or Scoop for your installation source."
            else -> "Could not update system $toolName automatically. Update it with your distro package manager or install the app-managed tool."
        }
    }

    private fun resolveExistingBinary(appBinary: Path, commandName: String, checkArg: String): Path? {
        searchDirectories.asSequence().map { it.resolve(commandName) }
            .firstOrNull { isExecutable(it, checkArg) }?.let { return it }
        if (appBinary.exists()) {
            if (isExecutable(appBinary, checkArg)) return appBinary
            makeExecutable(appBinary, os)
            if (isExecutable(appBinary, checkArg)) return appBinary
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
        if (os != "windows") {
            check(path.toFile().setExecutable(true, false)) { "Cannot make tool executable: $path" }
        }
    }

    private fun isExecutable(path: Path, checkArg: String): Boolean {
        return probeExecutable(path, checkArg).ok
    }

    private fun probeExecutable(path: Path, checkArg: String): ExecProbe {
        if (!Files.isRegularFile(path)) return ExecProbe(false, "missing file")
        val result = runCommand(listOf(path.toAbsolutePath().toString(), checkArg), toolProbeTimeoutSeconds)
        return ExecProbe(result.exitCode == 0, result.output.lineSequence().firstOrNull().orEmpty())
    }

    private fun findInPath(commandName: String): Path? {
        return searchDirectories.asSequence()
            .map { it.resolve(commandName) }
            .firstOrNull { Files.isRegularFile(it) }
    }

    private fun readTextFromUrl(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(networkTimeout)
            .header("User-Agent", "yt-dlpk")
            .GET()
            .build()
        val response = httpClient.send(request, BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} from $url" }
        return response.body()
    }

    private fun downloadToPath(url: String, target: Path) {
        download?.let { it(url, target); return }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(downloadTimeout)
            .header("User-Agent", "yt-dlpk")
            .GET()
            .build()
        val response = httpClient.send(request, BodyHandlers.ofFile(target))
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(target)
            error("HTTP ${response.statusCode()} from $url")
        }
    }

    private fun parseToolSources(jsonText: String): ToolSources {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        fun parseMap(key: String): Map<String, String> {
            val obj = root[key]?.jsonObject ?: emptyMap()
            return obj.mapValues { (_, value) -> value.jsonPrimitive.content }
        }
        return ToolSources(
            ytDlp = parseMap("ytDlp"),
            ffmpeg = parseMap("ffmpeg"),
            ffprobe = parseMap("ffprobe"),
            deno = parseMap("deno")
        )
    }

}
