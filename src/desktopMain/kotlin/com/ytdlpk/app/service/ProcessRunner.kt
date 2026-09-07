package com.ytdlpk.app.service

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class ProcessResult(
    val exitCode: Int,
    val stdoutLines: List<String>,
    val stderrLines: List<String>
)

class RunningProcess(private val job: Job) {
    fun cancel() {
        job.cancel()
    }

    suspend fun join() = job.join()
}

class ProcessRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pathDirectories: List<Path> = systemToolDirectories()
) {
    suspend fun run(
        command: List<String>,
        workingDir: Path? = null,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
        captureOutput: Boolean = true
    ): ProcessResult = withContext(ioDispatcher) {
        val process = ProcessBuilder(command)
            .apply {
                if (workingDir != null) {
                    directory(workingDir.toFile())
                }
                environment()["PATH"] = pathDirectories.joinToString(File.pathSeparator)
            }
            .start()

        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        coroutineScope {
            try {
                val stdoutJob = launch {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (captureOutput) stdout += line
                            onStdoutLine(line)
                        }
                    }
                }
                val stderrJob = launch {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (captureOutput) stderr += line
                            onStderrLine(line)
                        }
                    }
                }

                runInterruptible { process.waitFor() }
                stdoutJob.join()
                stderrJob.join()
            } finally {
                // Terminate before coroutineScope waits for readers blocked on a silent child.
                terminateProcessTree(process)
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }
        ProcessResult(process.exitValue(), stdout, stderr)
    }

    fun runStreaming(
        scope: CoroutineScope,
        command: List<String>,
        workingDir: Path? = null,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
        onError: (Throwable) -> Unit = { throw it }
    ): RunningProcess {
        val job = scope.launch(ioDispatcher) {
            try {
                val result = run(command, workingDir, onStdoutLine, onStderrLine, captureOutput = false)
                onExit(result.exitCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
            }
        }
        return RunningProcess(job)
    }
}

internal fun terminateProcessTree(process: Process) {
    val root = process.toHandle()
    val descendants = root.descendants().use { stream ->
        stream.collect(Collectors.toList())
    }
    descendants.asReversed().forEach { killProcessHandle(it) }
    killProcessHandle(root)
}

private fun killProcessHandle(handle: ProcessHandle) {
    if (!handle.isAlive) return

    runCatching {
        handle.destroy()
        handle.onExit().toCompletableFuture().get(800, TimeUnit.MILLISECONDS)
    }

    if (!handle.isAlive) return

    runCatching {
        handle.destroyForcibly()
        handle.onExit().toCompletableFuture().get(1500, TimeUnit.MILLISECONDS)
    }

    if (!handle.isAlive) return

    if (System.getProperty("os.name").lowercase().contains("win")) {
        runCatching {
            ProcessBuilder("taskkill", "/PID", handle.pid().toString(), "/T", "/F")
                .redirectErrorStream(true)
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        }
    }
}
