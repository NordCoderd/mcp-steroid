/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

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

    override fun registerNpxMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        return ConsoleAwareAgentSession(
            delegate.registerNpxMcp(mcpUrl, mcpName),
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
 * When [consoleFilterCommand] is set, the pump pipes raw output through
 * the filter before displaying. This is used to convert NDJSON to
 * human-readable text via the agent-output-filter JAR.
 */
class ConsolePumpingContainerDriver(
    delegate: ContainerDriver,
    private val console: ConsoleDriver,
    private val agentName: String,
    private val consoleFilterCommand: String? = null,
) : ContainerDriverDelegate<ConsolePumpingContainerDriver>(delegate) {
    private val counter = AtomicInteger(0)

    override fun createNewDriver(delegate: ContainerDriver) = ConsolePumpingContainerDriver(delegate, console, agentName)

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
        val combinedLog = "/tmp/agent-$slug-$idx-combined.log"

        // Single pump for combined output, with optional filter for readable display
        val pump = console.startFilePump(
            combinedLog, "[$agentName]", ConsoleDriver.CYAN,
            filterCommand = consoleFilterCommand,
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

    override fun toString(): String = "ConsolePumping[$agentName]($delegate)"

    companion object {
        /** Container path where the agent-output-filter JAR is deployed. */
        const val FILTER_JAR_PATH = "/tmp/agent-output-filter.jar"

        /**
         * Deploy the agent-output-filter JAR to the container.
         * The JAR is built by the :agent-output-filter Gradle module and
         * resolved via the `agentOutputFilterJar` configuration.
         */
        fun deployFilterJar(container: ContainerDriver) {
            container.copyToContainer(IdeTestFolders.agentOutputFilterJar, FILTER_JAR_PATH)
        }

        /**
         * Build a filter command for the given filter type.
         * The command uses `java -jar` to invoke the agent-output-filter JAR.
         *
         * @param filterType one of: "claude", "codex", "gemini"
         */
        fun filterCommand(filterType: String): String {
            return "java -jar $FILTER_JAR_PATH $filterType"
        }

        private fun escapeForBash(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
        }
    }
}
