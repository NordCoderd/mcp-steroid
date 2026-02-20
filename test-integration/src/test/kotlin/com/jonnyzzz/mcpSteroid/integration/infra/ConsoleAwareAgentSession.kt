/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps an [AiAgentSession] to display agent activity in the [ConsoleDriver].
 *
 * Before running a prompt, the prompt text is shown in bright ANSI color.
 * After completion, a success/error summary is written.
 *
 * Real-time output pumping is handled by [ConsolePumpingContainerDriver]
 * which wraps the underlying container used by the agent session.
 */
class ConsoleAwareAgentSession(
    private val delegate: AiAgentSession,
    private val console: ConsoleDriver,
    private val agentName: String,
) : AiAgentSession {
    override val displayName: String
        get() = delegate.displayName

    override fun runPrompt(prompt: String, timeoutSeconds: Long): ProcessResult {
        console.writePrompt(agentName, prompt)
        console.writeInfo("Running $agentName...")

        val result = delegate.runPrompt(prompt, timeoutSeconds)

        if (result.exitCode == 0) {
            console.writeSuccess("$agentName finished (exit 0)")
        } else {
            console.writeError("$agentName finished (exit ${result.exitCode})")
        }

        return result
    }

    override fun registerHttpMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        return ConsoleAwareAgentSession(
            delegate.registerHttpMcp(mcpUrl, mcpName),
            console, agentName,
        )
    }

    override fun registerNpxMcp(npxCommand: StdioMcpCommand, mcpName: String) =
        ConsoleAwareAgentSession(delegate.registerNpxMcp(npxCommand, mcpName), console, agentName,)
}

private const val FILTER_BIN = "/opt/agent-output-filter/bin/agent-output-filter"

/**
 * A [ContainerDriver] decorator that tees command output to files in an
 * `agents/<run-id>/` directory and pumps filtered output to the [ConsoleDriver]
 * with a colored `[agentName]` prefix.
 *
 * When [filterType] and [agentsGuestDir] are both set, the in-container filter
 * pipeline is used:
 *   agent 2>&1 | tee raw.jsonl | agent-output-filter <type> | awk-tee filtered.log
 *
 * The filtered output is captured in [ProcessResult.output] for test assertions,
 * and also pumped to the xterm console via [ConsoleDriver.startFilePump].
 * The raw NDJSON is preserved in `raw.jsonl` for debugging.
 *
 * When [filterType] or [agentsGuestDir] are null, a simple combined-log tee is
 * used without filtering.
 */
class ConsolePumpingContainerDriver(
    delegate: ContainerDriver,
    private val console: ConsoleDriver,
    private val agentName: String,
    private val filterType: String? = null,
    private val agentsGuestDir: String? = null,
) : ContainerDriverDelegate<ConsolePumpingContainerDriver>(delegate) {
    private val counter = AtomicInteger(0)

    override fun createNewDriver(delegate: ContainerDriver) =
        ConsolePumpingContainerDriver(delegate, console, agentName, filterType, agentsGuestDir)

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
        quietly: Boolean,
    ): ProcessResult {
        require(!quietly) { "quietly mode is not supported for console runs" }

        val idx = counter.incrementAndGet()
        val slug = agentName.lowercase().replace(" ", "-")

        return if (filterType != null && agentsGuestDir != null) {
            runWithInContainerFilter(args, workingDir, timeoutSeconds, extraEnvVars, idx, slug, filterType, agentsGuestDir)
        } else {
            runWithCombinedLogTee(args, workingDir, timeoutSeconds, extraEnvVars, idx, slug)
        }
    }

    private fun runWithInContainerFilter(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
        idx: Int,
        slug: String,
        filterType: String,
        agentsGuestDir: String,
    ): ProcessResult {
        val runId = "$slug-$idx"
        val runDir = "$agentsGuestDir/$runId"
        val rawLog = "$runDir/raw.jsonl"
        val filteredLog = "$runDir/filtered.log"

        // Pump filtered log to xterm console in real-time
        val pump = console.startFilePump(filteredLog, "[$agentName]", ConsoleDriver.CYAN)

        try {
            val teeScript = "/tmp/agent-$slug-$idx-tee.sh"
            val escaped = escapeForBash(args)
            val scriptContent = buildString {
                appendLine("#!/bin/bash")
                appendLine("mkdir -p $runDir")
                // Pipeline: agent stdout+stderr → tee raw log → filter → tee filtered log to stdout
                appendLine("$escaped 2>&1 \\")
                appendLine("  | tee $rawLog \\")
                appendLine("  | $FILTER_BIN $filterType \\")
                appendLine("  | awk -v log=$filteredLog '{print; print >> log; fflush(); fflush(log)}'")
                appendLine("exit \${PIPESTATUS[0]}")
            }
            delegate.writeFileInContainer(teeScript, scriptContent, executable = true)

            // quietly=true: suppress Docker driver real-time echo; we print from result.output below
            val result = delegate.runInContainer(
                listOf("bash", teeScript), workingDir, timeoutSeconds, extraEnvVars, quietly = true,
            )

            // Print filtered output to JVM test-runner console
            for (line in result.output.lineSequence()) {
                if (line.isNotEmpty()) println("[$agentName] $line")
            }

            return result
        } finally {
            Thread.sleep(500)
            pump.stop()
        }
    }

    private fun runWithCombinedLogTee(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
        idx: Int,
        slug: String,
    ): ProcessResult {
        val combinedLog = "/tmp/agent-$slug-$idx-combined.log"

        // Pump raw output to xterm console for real-time visibility
        val pump = console.startFilePump(combinedLog, "[$agentName]", ConsoleDriver.CYAN)

        try {
            val teeScript = "/tmp/agent-$slug-$idx-tee.sh"
            val escaped = escapeForBash(args)
            val scriptContent = buildString {
                appendLine("#!/bin/bash")
                appendLine("$escaped 2>&1 | awk -v logfile=$combinedLog '{print; print >> logfile; fflush(); fflush(logfile)}'")
                appendLine("exit \${PIPESTATUS[0]}")
            }
            delegate.writeFileInContainer(teeScript, scriptContent, executable = true)

            return delegate.runInContainer(listOf("bash", teeScript), workingDir, timeoutSeconds, extraEnvVars, quietly = false)
        } finally {
            Thread.sleep(500)
            pump.stop()
        }
    }

    override fun runInContainerDetached(
        args: List<String>,
        workingDir: String?,
        extraEnvVars: Map<String, String>,
    ): RunningContainerProcess {
        val proc = delegate.runInContainerDetached(args, workingDir, extraEnvVars)

        // Pump the detached process stdout/stderr
        console.startFilePump(proc.stdoutPath, "[$agentName]", ConsoleDriver.CYAN)
        console.startFilePump(proc.stderrPath, "[$agentName]", ConsoleDriver.RED)

        return proc
    }

    override fun toString(): String = "ConsolePumping[$agentName]($delegate)"

    companion object {
        private fun escapeForBash(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
        }
    }
}
