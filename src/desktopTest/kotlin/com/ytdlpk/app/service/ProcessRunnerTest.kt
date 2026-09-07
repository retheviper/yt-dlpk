package com.ytdlpk.app.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.File
import java.nio.file.Path

internal object ProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "environment") {
            println(System.getenv("PATH"))
            return
        }
        println(ProcessHandle.current().pid())
        System.out.flush()
        if (args.firstOrNull() == "tree") {
            ProcessBuilder(fixtureCommand()).inheritIO().start().waitFor()
            return
        }
        Thread.sleep(30_000)
    }
}

internal fun fixtureCommand(): List<String> = listOf(
    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    "-cp", listOf(ProcessFixture::class.java, Unit::class.java)
        .joinToString(File.pathSeparator) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() },
    ProcessFixture::class.java.name
)

class ProcessRunnerTest : StringSpec({
    "cancelling a silent process promptly stops it and completes the caller" {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pid = CompletableDeferred<Long>()
        val job = scope.launch { ProcessRunner().run(fixtureCommand(), onStdoutLine = { pid.complete(it.toLong()) }) }
        val handle = ProcessHandle.of(withTimeout(5_000) { pid.await() }).orElseThrow()
        try {
            job.cancel()
            withTimeoutOrNull(2_000) { job.join(); true } shouldBe true
            handle.isAlive shouldBe false
        } finally {
            handle.destroyForcibly()
            scope.cancel()
        }
    }

    "cancellation terminates child processes as well as the parent" {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = CompletableDeferred<List<Long>>()
        val pids = mutableListOf<Long>()
        val job = scope.launch {
            ProcessRunner().run(fixtureCommand() + "tree", onStdoutLine = {
                pids.add(it.toLong())
                if (pids.size == 2) ready.complete(pids.toList())
            })
        }
        val handles = withTimeout(5_000) { ready.await() }.map { ProcessHandle.of(it).orElseThrow() }
        try {
            job.cancel()
            withTimeout(3_000) { job.join() }
            handles.any { it.isAlive } shouldBe false
        } finally {
            handles.forEach { it.destroyForcibly() }
            scope.cancel()
        }
    }

    "a failing output callback cannot leave its process running" {
        var handle: ProcessHandle? = null
        try {
            shouldThrow<IllegalStateException> {
                withTimeout(3_000) {
                    ProcessRunner().run(fixtureCommand(), onStdoutLine = {
                        handle = ProcessHandle.of(it.toLong()).orElseThrow()
                        error("callback failed")
                    })
                }
            }
            handle?.isAlive shouldBe false
        } finally { handle?.destroyForcibly() }
    }

    "subprocesses receive the tool search paths even outside a terminal" {
        val paths = listOf(Path.of("/fixture/tools"), Path.of("/fixture/system"))
        val result = ProcessRunner(pathDirectories = paths).run(fixtureCommand() + "environment")
        result.exitCode shouldBe 0
        result.stdoutLines shouldBe listOf(paths.joinToString(File.pathSeparator))
    }

    "streaming startup errors are delivered to the owner" {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val error = CompletableDeferred<Throwable>()
        try {
            ProcessRunner().runStreaming(scope, listOf("/missing-ytdlpk-test-tool"), onError = { error.complete(it) })
            (withTimeout(3_000) { error.await() } is IOException) shouldBe true
        } finally { scope.cancel() }
    }
})
