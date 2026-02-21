/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.process

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

//TODO: hide this class
data class ProcessResultValue(
    override val exitCode: Int,
    override val stdout: String,
    override val stderr: String,
) : ProcessResult


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
     * Filter secrets from text, replacing them with REDACTED.
     */
    private fun filterSecrets(text: String): String {
        var result = text
        for (pattern in secretPatterns) {
            if (pattern.isNotBlank()) {
                result = result.replace(pattern, "[REDACTED]")
            }
        }
        return result
    }

    /**
     * Run a process using the request configuration and waits for it to complete
     * This is the primary method for running processes.
     * Secrets are filtered from log output but preserved in returned ProcessResult.
     *
     * @param request The process run request with all configuration
     */
    fun runProcess(request: ProcessRunRequest): ProcessResult {
        val processInfo = startProcessImpl(request)

        val process = processInfo.process

        val completed = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            println("[$logPrefix] Timed out after ${request.timeoutSeconds}s")
            return ProcessResultValue(-1, processInfo.stdout, "Timeout\n${processInfo.stderr}")
        }

        processInfo.thread.forEach {
            it.join(1000)
            it.interrupt()
            it.join()
        }

        val exitCode = process.exitValue()
        println("[$logPrefix] Exit code: $exitCode")
        return ProcessResultValue(exitCode, processInfo.stdout, processInfo.stderr)
    }

    fun startProcess(request: ProcessRunRequest): StartedProcess = startProcessImpl(request)

    private fun startProcessImpl(request: ProcessRunRequest): StartedProcessImpl {
        // Filter secrets from command line and description for logging
        run {
            val filteredCommand = request.command.map { filterSecrets(it) }
            val filteredDescription = filterSecrets(request.description)
            println("[$logPrefix] $filteredDescription")
            println("[$logPrefix] $filteredCommand")
        }

        val processBuilder = ProcessBuilder(request.command)
        processBuilder.directory(request.workingDir)
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)

        val process = processBuilder.start()

        val messagesChannel = Collections.synchronizedList(mutableListOf<ProcessStreamLine>())

        fun readOutput(stream: InputStream, prefix: String, type: ProcessStreamType) {
            try {
                stream.reader().use { reader ->
                    while (process.isAlive) {
                        Thread.sleep(100)
                        reader.forEachLine { line ->
                            val filterSecrets = filterSecrets(line)
                            if (!request.quietly) {
                                println("[$prefix] $filterSecrets")
                            }
                            messagesChannel.add(ProcessStreamLine(type, line))
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore stream read errors
            }
        }

        // Thread for copying stdin to the process
        val stdinThread = Thread {
            try {
                request.stdin.use { input ->
                    process.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                if (!request.quietly) {
                    println("[$logPrefix] stdin copy error: ${e.message}")
                    messagesChannel.add(ProcessStreamLine(ProcessStreamType.INFO, "Failed to send STDIN: ${e.message}\n" + e.stackTraceToString()))
                }
            }
        }

        val outputThread = Thread {
            readOutput(process.inputStream, "$logPrefix OUT", ProcessStreamType.STDOUT)
        }

        val errorThread = Thread {
            readOutput(process.errorStream, "$logPrefix ERR", ProcessStreamType.STDERR)
        }

        stdinThread.start()
        outputThread.start()
        errorThread.start()

        return StartedProcessImpl(
            process,
            messagesChannel,
            listOf(stdinThread, outputThread, errorThread)
        )
    }

    private data class StartedProcessImpl(
        val process: Process,
        val messagesChannel: List<ProcessStreamLine>,
        val thread: List<Thread>,
    ) : StartedProcess {
        val pid: PID get() = process.PID()

        override fun destroyForcibly() {
            process.destroyForcibly()
        }

        override val exitCode: Int?
            get() = runCatching { process.exitValue() } .getOrNull()

        override val messagesFlow: Flow<ProcessStreamLine>
            get() = flow {
                var visited = 0
                while (process.isAlive) {
                    messagesChannel.toList().drop(visited).forEach {
                        visited++
                        emit(it)
                    }
                    //this is heavily inefficient
                    Thread.sleep(100)
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
    }
}
