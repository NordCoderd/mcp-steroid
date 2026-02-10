/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
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

    override fun registerMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        return ConsoleAwareAgentSession(
            delegate.registerMcp(mcpUrl, mcpName),
            console, agentName,
        )
    }
}

/**
 * A [ContainerDriver] decorator that tees command output to a file
 * and pumps it to the [ConsoleDriver] with a colored `[agentName]` prefix.
 *
 * stdout is pumped with cyan prefix; stderr is pumped with red prefix.
 * The tee approach ensures output is both captured in [ProcessResult]
 * for assertions and visible in the console xterm window in real-time.
 *
 * When [consoleFilterScript] is set (a path to a Python filter inside the
 * container), the pump pipes raw output through the filter before displaying.
 * This is used to convert NDJSON (stream-json) to human-readable text.
 */
class ConsolePumpingContainerDriver(
    private val delegate: ContainerDriver,
    private val console: ConsoleDriver,
    private val agentName: String,
    private val consoleFilterScript: String? = null,
) : ContainerDriver {
    override val containerId: String get() = delegate.containerId
    private val counter = AtomicInteger(0)

    override fun mapContainerPortToHostPort(port: ContainerPort): Int =
        delegate.mapContainerPortToHostPort(port)

    override fun withGuestWorkDir(guestWorkDir: String): ContainerDriver =
        ConsolePumpingContainerDriver(delegate.withGuestWorkDir(guestWorkDir), console, agentName, consoleFilterScript)

    override fun withSecretPattern(secretPattern: String): ContainerDriver =
        ConsolePumpingContainerDriver(delegate.withSecretPattern(secretPattern), console, agentName, consoleFilterScript)

    override fun withEnv(key: String, value: String): ContainerDriver =
        ConsolePumpingContainerDriver(delegate.withEnv(key, value), console, agentName, consoleFilterScript)

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
    ): ProcessResult {
        val idx = counter.incrementAndGet()
        val slug = agentName.lowercase().replace(" ", "-")
        val combinedLog = "/tmp/agent-$slug-$idx-combined.log"

        // Single pump for combined output, with optional filter for readable display
        val pump = console.startFilePump(
            combinedLog, "[$agentName]", ConsoleDriver.CYAN,
            filterScript = consoleFilterScript,
        )

        try {
            // Write a tee-wrapper script that copies each line to BOTH stdout
            // (captured by docker exec for ProcessResult) and the log file
            // (tailed by the pump for console display).
            //
            // awk with explicit fflush() ensures both stdout and the log file
            // are flushed after every line, giving real-time streaming.
            val teeScript = "/tmp/agent-$slug-$idx-tee.sh"
            val escaped = escapeForBash(args)
            val scriptContent = buildString {
                appendLine("#!/bin/bash")
                appendLine("$escaped 2>&1 | awk -v logfile=$combinedLog '{print; print >> logfile; fflush(); fflush(logfile)}'")
                appendLine("exit \${PIPESTATUS[0]}")
            }
            delegate.writeFileInContainer(teeScript, scriptContent, executable = true)
            return delegate.runInContainer(listOf("bash", teeScript), workingDir, timeoutSeconds, extraEnvVars)
        } finally {
            // Let pump catch up with remaining output
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

    override fun writeFileInContainer(containerPath: String, content: String, executable: Boolean) =
        delegate.writeFileInContainer(containerPath, content, executable)

    override fun copyFromContainer(containerPath: String, localPath: File) =
        delegate.copyFromContainer(containerPath, localPath)

    override fun copyToContainer(localPath: File, containerPath: String) =
        delegate.copyToContainer(localPath, containerPath)

    override fun mapGuestPathToHostPath(path: String): File =
        delegate.mapGuestPathToHostPath(path)

    override fun toString(): String = "ConsolePumping[$agentName]($delegate)"

    companion object {
        /** Container path where the stream-json filter is deployed. */
        const val STREAM_JSON_FILTER_PATH = "/tmp/stream-json-filter.py"

        /**
         * Deploy a Python filter script that converts Claude's stream-json NDJSON
         * output to human-readable console text.
         *
         * Extracts:
         * - Assistant text from content_block_delta events (streamed incrementally)
         * - Tool use names (e.g. ">> steroid_execute_code")
         * - Skips raw JSON, system events, and full tool results
         */
        fun deployStreamJsonFilter(container: ContainerDriver) {
            val filterScript = """
                #!/usr/bin/env python3
                import sys, json

                for line in sys.stdin:
                    line = line.rstrip('\n\r')
                    if not line:
                        continue
                    if not line.lstrip().startswith('{'):
                        print(line, flush=True)
                        continue
                    try:
                        obj = json.loads(line)
                        t = obj.get('type', '')
                        if t == 'content_block_delta':
                            text = obj.get('delta', {}).get('text', '')
                            if text:
                                for part in text.split('\n'):
                                    part = part.rstrip()
                                    if part:
                                        print(part, flush=True)
                        elif t == 'tool_use':
                            name = obj.get('name', '?')
                            inp = obj.get('input', {})
                            detail = ''
                            if name == 'steroid_execute_code':
                                reason = inp.get('reason', '')
                                if reason:
                                    detail = f' ({reason})'
                            elif name == 'read_mcp_resource':
                                uri = inp.get('uri', '')
                                if uri:
                                    detail = f' ({uri})'
                            print(f'>> {name}{detail}', flush=True)
                    except (json.JSONDecodeError, KeyError, TypeError):
                        pass
            """.trimIndent()
            container.writeFileInContainer(STREAM_JSON_FILTER_PATH, filterScript)
        }

        private fun escapeForBash(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
        }
    }
}
