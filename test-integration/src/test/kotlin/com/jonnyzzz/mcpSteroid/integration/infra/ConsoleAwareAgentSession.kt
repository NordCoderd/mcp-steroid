/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import java.io.File
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
 * A [ContainerDriver] decorator that saves agent output to per-run log directories
 * and pumps filtered output to the [ConsoleDriver] in real-time.
 *
 * When [filterType] and [agentsGuestDir] are both set, the in-container filter
 * pipeline is used per run:
 *   - `agents/<run-id>/raw.jsonl`   — raw NDJSON from the agent
 *   - `agents/<run-id>/filtered.log` — human-readable text via agent-output-filter
 *
 * The pipeline is:  `agent 2>&1 | tee raw.jsonl | agent-output-filter <type> > filtered.log`
 *
 * [ProcessResult.output] returns the raw NDJSON so test assertions are backward-compatible.
 * The filtered log is pumped to the xterm console via [ConsoleDriver.startFilePump].
 *
 * The agents dir is volume-mounted, so log files are directly accessible on the host
 * via [mapGuestPathToHostPath].
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
        val guestRunDir = "$agentsGuestDir/$runId"
        val rawLog = "$guestRunDir/raw.jsonl"
        val filteredLog = "$guestRunDir/filtered.log"

        // Resolve the host-side path via the volume mount so we can read files directly
        val hostRunDir = File(mapGuestPathToHostPath(agentsGuestDir), runId)
        val hostRawLog = File(hostRunDir, "raw.jsonl")
        val hostFilteredLog = File(hostRunDir, "filtered.log")

        // Pump filtered log to xterm console in real-time
        val pump = console.startFilePump(filteredLog, "[$agentName]", ConsoleDriver.CYAN)

        try {
            val teeScript = "/tmp/agent-$slug-$idx-tee.sh"
            val escaped = escapeForBash(args)
            val scriptContent = buildString {
                appendLine("#!/bin/bash")
                appendLine("mkdir -p $guestRunDir")
                // Pipeline: stdout+stderr → raw.jsonl (tee) → filter → filtered.log
                // stdout of the script is intentionally empty; filter output goes to file only.
                appendLine("$escaped 2>&1 | tee $rawLog | $FILTER_BIN $filterType > $filteredLog")
                appendLine("exit \${PIPESTATUS[0]}")
            }
            delegate.writeFileInContainer(teeScript, scriptContent, executable = true)

            val result = delegate.runInContainer(
                listOf("bash", teeScript), workingDir, timeoutSeconds, extraEnvVars, quietly = true,
            )

            // Print filtered output to JVM test-runner console (volume-mounted, accessible on host)
            if (hostFilteredLog.exists()) {
                for (line in hostFilteredLog.readLines()) {
                    if (line.isNotEmpty()) println("[$agentName] $line")
                }
            }

            // Return raw NDJSON in result.output for backward-compatible test assertions.
            // Falls back to whatever docker captured (empty, since quietly=true) if not readable.
            val rawOutput = if (hostRawLog.exists()) hostRawLog.readText() else result.output
            return ProcessResultValue(
                exitCode = result.exitCode ?: -1,
                output = rawOutput,
                stderr = result.stderr,
            )
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
