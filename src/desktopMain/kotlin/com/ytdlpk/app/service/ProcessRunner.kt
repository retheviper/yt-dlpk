package com.ytdlpk.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

data class ProcessResult(
    val exitCode: Int,
    val stdoutLines: List<String>,
    val stderrLines: List<String>
)

class RunningProcess(private val job: Job, private val process: Process) {
    fun cancel() {
        terminateProcessTree(process)
        job.cancel()
    }
}

class ProcessRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun run(
        command: List<String>,
        workingDir: Path? = null,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {}
    ): ProcessResult = withContext(ioDispatcher) {
        val process = ProcessBuilder(command)
            .apply {
                if (workingDir != null) {
                    directory(workingDir.toFile())
                }
            }
            .start()

        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        try {
            coroutineScope {
                val stdoutJob = async {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stdout += line
                            onStdoutLine(line)
                        }
                    }
                }
                val stderrJob = async {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stderr += line
                            onStderrLine(line)
                        }
                    }
                }

                val waitJob = async { process.waitFor() }
                awaitAll(stdoutJob, stderrJob, waitJob)
            }

            ProcessResult(process.exitValue(), stdout, stderr)
        } catch (e: CancellationException) {
            terminateProcessTree(process)
            throw e
        }
    }

    fun runStreaming(
        scope: kotlinx.coroutines.CoroutineScope,
        command: List<String>,
        workingDir: Path? = null,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
        onExit: (Int) -> Unit = {}
    ): RunningProcess {
        val process = ProcessBuilder(command)
            .apply {
                if (workingDir != null) {
                    directory(workingDir.toFile())
                }
            }
            .start()

        val job = scope.launch(ioDispatcher) {
            val stdoutJob = launch {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(onStdoutLine)
                }
            }
            val stderrJob = launch {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach(onStderrLine)
                }
            }

            try {
                val code = process.waitFor()
                stdoutJob.join()
                stderrJob.join()
                withContext(scope.coroutineContext) { onExit(code) }
            } catch (_: CancellationException) {
                terminateProcessTree(process)
            }
        }
        return RunningProcess(job, process)
    }
}

private fun terminateProcessTree(process: Process) {
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
