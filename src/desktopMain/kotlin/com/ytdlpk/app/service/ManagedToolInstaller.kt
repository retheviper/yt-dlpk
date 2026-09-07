package com.ytdlpk.app.service

import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/** Stage and validate the entire tool set before replacing any installed executable. */
internal class ManagedToolInstaller(
    private val toolsRoot: Path,
    private val download: (String, Path) -> Unit,
    private val validate: (Path, String) -> Unit
) {
    fun install(candidates: Map<Path, List<String>>, checkArg: String) {
        toolsRoot.createDirectories()
        val staging = Files.createTempDirectory(toolsRoot, "install-")
        var preserveBackups = false
        try {
            val payloads = mutableMapOf<String, Path>()
            val staged = candidates.map { (target, urls) ->
                val binary = staging.resolve(target.fileName)
                val errors = mutableListOf<String>()
                val installed = urls.any { url ->
                    try {
                        val payload = payloads.getOrPut(url) {
                            Files.createTempFile(staging, "payload-", ".tmp").also { download(url, it) }
                        }
                        extractExecutable(url, payload, binary)
                        validate(binary, checkArg)
                        true
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        errors += "$url -> ${e.message}"
                        Files.deleteIfExists(binary)
                        false
                    }
                }
                check(installed) { "Failed to install ${target.fileName}:\n${errors.joinToString("\n")}" }
                target to binary
            }
            val backups = staged.associate { (target, _) ->
                target to target.takeIf(Files::exists)?.let {
                    Files.copy(it, staging.resolve("${it.fileName}.backup"), REPLACE_EXISTING)
                }
            }
            val replaced = mutableListOf<Path>()
            try {
                staged.forEach { (target, binary) ->
                    target.parent.createDirectories()
                    replaceFile(binary, target)
                    replaced.add(target)
                }
            } catch (e: Exception) {
                replaced.asReversed().forEach { target ->
                    runCatching {
                        backups[target]?.let { replaceFile(it, target) } ?: Files.deleteIfExists(target)
                    }.onFailure { failure ->
                        preserveBackups = true
                        e.addSuppressed(IllegalStateException("Previous tool backup retained in $staging", failure))
                    }
                }
                throw e
            }
        } finally {
            if (!preserveBackups) {
                Files.walk(staging).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun extractExecutable(url: String, payload: Path, binary: Path) {
        val path = URI.create(url).path
        when {
            path.endsWith(".zip") || path.endsWith("/zip") -> {
                ZipInputStream(payload.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        validateEntry(entry.name)
                        if (!entry.isDirectory && entry.name.substringAfterLast('/') == binary.fileName.toString()) {
                            binary.outputStream().use(zip::copyTo)
                            return
                        }
                        entry = zip.nextEntry
                    }
                }
                error("${binary.fileName} is missing from archive")
            }
            path.endsWith(".tar.xz") -> {
                TarArchiveInputStream(XZCompressorInputStream(payload.inputStream().buffered())).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        validateEntry(entry.name)
                        if (entry.isFile && entry.name.substringAfterLast('/') == binary.fileName.toString()) {
                            binary.outputStream().use(tar::copyTo)
                            return
                        }
                        entry = tar.nextEntry
                    }
                }
                error("${binary.fileName} is missing from archive")
            }
            else -> Files.copy(payload, binary, REPLACE_EXISTING)
        }
    }

    private fun validateEntry(name: String) {
        val path = Path.of(name.replace('\\', '/')).normalize()
        require(!path.isAbsolute && !path.startsWith("..")) { "Archive entry escapes target directory: $name" }
    }

    private fun replaceFile(source: Path, target: Path) {
        try {
            Files.move(source, target, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }
}
