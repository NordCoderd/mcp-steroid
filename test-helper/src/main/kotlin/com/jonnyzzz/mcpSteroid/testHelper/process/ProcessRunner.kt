/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

import com.jonnyzzz.mcpSteroid.testHelper.truncate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

//TODO: hide this class
data class ProcessResultValue(
    override val exitCode: Int,
    override val stdout: String,
    override val stderr: String,
) : ProcessResult


@ConsistentCopyVisibility
data class RunProcessRequest private constructor(
    val workingDir: File? = null,
    val args: List<String> = listOf(),

    val logPrefix: String? = null,
    val description: String? = null,
    val quietly: Boolean = false,

    val timeout: Duration = Duration.ofSeconds(30),

    val stdin: Flow<ByteArray> = emptyFlow(),

    val secretPatterns: List<String> = listOf(),
) {
    companion object {
        operator fun invoke() : RunProcessRequest = RunProcessRequest()
    }

    fun withWorkingDir(workingDir: File) = copy(workingDir = workingDir)
    fun withArgs(args: List<String>) = copy(args = args)

    fun withLogPrefix(logPrefix: String) = copy(logPrefix = logPrefix)
    fun withDescription(description: String) = copy(description = description)
    fun withTimeout(timeout: Duration) = copy(timeout = timeout)
    fun withQuietly(quietly: Boolean) = copy(quietly = quietly)
    fun withStdin(stdin: Flow<ByteArray>) = copy(stdin = stdin)

    fun withSecretPatterns(secretPatterns: List<String>) = copy(secretPatterns = secretPatterns)
    fun addSecretPatterns(secretPatterns: List<String>) = copy(secretPatterns = (this.secretPatterns + secretPatterns).distinct())
}

fun RunProcessRequest.workdir(workingDir: File) = withWorkingDir(workingDir)
fun RunProcessRequest.command(command: List<String>) = withArgs(command)
fun RunProcessRequest.command(builder: MutableList<String>.() -> Unit) = command(buildList(builder))
fun RunProcessRequest.command(vararg command: String) = command(command.toList())
fun RunProcessRequest.description(description: String) = withDescription(description)
fun RunProcessRequest.timeoutSeconds(timeoutSeconds: Long) = withTimeout(Duration.ofSeconds(timeoutSeconds))
fun RunProcessRequest.quietly(quietly: Boolean) = withQuietly(quietly)
fun RunProcessRequest.quietly() = quietly(true)
fun RunProcessRequest.stdin(stdin: ByteArray) = withStdin(flowOf(stdin))
fun RunProcessRequest.stdin(stdin: String) = stdin(stdin.toByteArray())


fun ProcessRunRequest.toRunProcessRequest() = RunProcessRequest()
    .withWorkingDir(this.workingDir)
    .withArgs(this.command)
    .withDescription(this.description)
    .withTimeout(Duration.ofSeconds(this.timeoutSeconds))
    .withQuietly(this.quietly)
    .withStdin(this.stdin)


private fun RunProcessRequest.withDefaultLogPrefix(prefix: String) = if (this.logPrefix.isNullOrEmpty()) this else withLogPrefix(prefix)

/**
 * Utility for running processes with consistent logging.
 * All output is logged to stdout with prefixes for easy debugging.
 * Supports filtering secrets from log output.
 */
class ProcessRunner(
    private val logPrefix: String,
    private val secretPatterns: List<String>,
) {
    /**
     * Run a process using the request configuration and waits for it to complete
     * This is the primary method for running processes.
     * Secrets are filtered from log output but preserved in returned ProcessResult.
     *
     * @param request The process run request with all configuration
     */
    fun startProcess(request: RunProcessRequest): StartedProcess {
        return startProcessImpl(request.addSecretPatterns(secretPatterns).withDefaultLogPrefix(logPrefix))
    }
}


/**
 * Filter secrets from text, replacing them with REDACTED.
 */
private fun RunProcessRequest.filterSecrets(text: String): String {
    var result = text
    for (pattern in secretPatterns) {
        if (pattern.isNotBlank()) {
            result = result.replace(pattern, "[REDACTED]")
        }
    }
    return result
}

private fun startProcessImpl(request: RunProcessRequest): StartedProcessImpl {
    // Filter secrets from command line and description for logging
    val logPrefix = request.logPrefix

    run {
        val filteredCommand = request.args.map { request.filterSecrets(it) }
        val filteredDescription = request.filterSecrets(request.description ?: request.args.joinToString(" ") { it.truncate(20) })
        println("[$logPrefix] $filteredDescription")
        println("[$logPrefix] $filteredCommand")
    }

    val processBuilder = ProcessBuilder(request.args)
    processBuilder.directory(request.workingDir ?: error("Working directory is not set for $request"))
    processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)

    val process = processBuilder.start()

    val messagesChannel = Collections.synchronizedList(mutableListOf<ProcessStreamLine>())

    fun readOutput(stream: InputStream, prefix: String, type: ProcessStreamType) {
        runCatching {
            stream.reader().use { reader ->
                while (process.isAlive) {
                    Thread.sleep(100)
                    reader.forEachLine { line ->
                        val filterSecrets = request.filterSecrets(line)
                        if (!request.quietly) {
                            println("[$prefix] $filterSecrets")
                        }
                        messagesChannel.add(ProcessStreamLine(type, line))
                    }
                }
            }
        }
    }

    // Thread for copying stdin to the process
    val stdinThread = thread(start = false, name = "$logPrefix-stdin-reader") {
        try {
            process.outputStream.use { output ->
                runBlocking(CoroutineName("$logPrefix-stdin")) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    request.stdin.collect {
                        output.write(it)
                        output.flush()
                    }
                }
            }
        } catch (e: Exception) {
            println("[$logPrefix] stdin copy error: ${e.message}")
            messagesChannel.add(
                ProcessStreamLine(
                    ProcessStreamType.INFO,
                    "Failed to send STDIN: ${e.message}\n" + e.stackTraceToString()
                )
            )
        }
    }

    val outputThread = thread(start = false, name = "$logPrefix-stdout") {
        readOutput(process.inputStream, "$logPrefix OUT", ProcessStreamType.STDOUT)
    }

    val errorThread = thread(start = false, name = "$logPrefix-stderr") {
        readOutput(process.errorStream, "$logPrefix ERR", ProcessStreamType.STDERR)
    }

    stdinThread.start()
    outputThread.start()
    errorThread.start()

    return StartedProcessImpl(
        request,
        process,
        messagesChannel,
        listOf(stdinThread, outputThread, errorThread)
    )
}

private class StartedProcessImpl(
    val request: RunProcessRequest,
    val process: Process,
    val messagesChannel: List<ProcessStreamLine>,
    val thread: List<Thread>,
) : StartedProcess {
    val pid: PID get() = process.PID()

    val logPrefix by request::logPrefix

    override fun destroyForcibly() {
        process.destroyForcibly()
    }

    override val exitCode: Int?
        get() = runCatching { process.exitValue() } .getOrNull()

    override val messagesFlow: Flow<ProcessStreamLine>
        get() = flow {
            var offset = 0
            while (true) {
                messagesChannel.drop(offset).forEach {
                    offset++
                    emit(it)
                }

                @Suppress("BlockingMethodInNonBlockingContext")
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    return@flow
                }
            }
        }

    private fun builder(type: ProcessStreamType) : String {
        return messagesChannel
            .filter { it.type == type }
            .joinToString(separator = "\n") { it.line }
    }

    override val stdout: String
        get() = builder(ProcessStreamType.STDOUT)

    override val stderr: String
        get() = builder(ProcessStreamType.STDERR)

    override fun toString(): String {
        return "StartedProcessImpl(pid=$pid, exitCode=$exitCode, output='$stdout', stderr='$stderr')"
    }

    private fun waitForThreads() {
        thread.forEach {
            runCatching {
                it.join(3_000)
                if (it.isAlive) {
                    println("[$logPrefix] Waiting for process thread ${it.name}")
                    it.interrupt()
                    it.join()
                }
            }
        }
    }

    override fun awaitForProcessFinish() : ProcessResult {
        val completed = process.waitFor(request.timeout.toMillis(), TimeUnit.MILLISECONDS)

        if (!completed) {
            process.destroyForcibly()
            waitForThreads()

            println("[${logPrefix}] Process is terminated by timeout after ${request.timeout}")
            return ProcessResultValue(-1, stdout, "Terminated by timeout\n${stderr}\n\n ERROR: Terminated by timeout")
        } else {
            waitForThreads()
            val exitCode = process.exitValue()
            println("[$logPrefix] Process exited with code: $exitCode")
            return ProcessResultValue(exitCode, stdout, stderr)
        }
    }
}
