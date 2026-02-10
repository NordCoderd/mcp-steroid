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
 */
class ConsolePumpingContainerDriver(
    private val delegate: ContainerDriver,
    private val console: ConsoleDriver,
    private val agentName: String,
) : ContainerDriver {
    override val containerId: String get() = delegate.containerId
    private val counter = AtomicInteger(0)

    override fun mapContainerPortToHostPort(port: ContainerPort): Int =
        delegate.mapContainerPortToHostPort(port)

    override fun withGuestWorkDir(guestWorkDir: String): ContainerDriver =
        ConsolePumpingContainerDriver(delegate.withGuestWorkDir(guestWorkDir), console, agentName)

    override fun withSecretPattern(secretPattern: String): ContainerDriver =
        ConsolePumpingContainerDriver(delegate.withSecretPattern(secretPattern), console, agentName)

    override fun withEnv(key: String, value: String): ContainerDriver =
        ConsolePumpingContainerDriver(delegate.withEnv(key, value), console, agentName)

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
    ): ProcessResult {
        val idx = counter.incrementAndGet()
        val slug = agentName.lowercase().replace(" ", "-")
        val stdoutLog = "/tmp/agent-$slug-$idx-stdout.log"
        val stderrLog = "/tmp/agent-$slug-$idx-stderr.log"

        // Start pumps: stdout with agent prefix, stderr with red prefix
        val stdoutPump = console.startFilePump(stdoutLog, "[$agentName]", ConsoleDriver.CYAN)
        val stderrPump = console.startFilePump(stderrLog, "[$agentName]", ConsoleDriver.RED)

        try {
            // Wrap command to tee stdout and stderr to separate files.
            // Process substitution keeps the streams separate for pumping.
            val escaped = escapeForBash(args)
            val wrappedArgs = listOf(
                "bash", "-c",
                "$escaped > >(tee $stdoutLog) 2> >(tee $stderrLog >&2); exit \${PIPESTATUS[0]}",
            )
            return delegate.runInContainer(wrappedArgs, workingDir, timeoutSeconds, extraEnvVars)
        } finally {
            // Let pumps catch up with remaining output
            Thread.sleep(500)
            stdoutPump.stop()
            stderrPump.stop()
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
        private fun escapeForBash(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
        }
    }
}
