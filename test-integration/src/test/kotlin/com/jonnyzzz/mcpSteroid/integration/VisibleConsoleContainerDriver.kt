/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ContainerDriver] decorator that shows command output in a visible
 * xterm window on the X11 display. The output appears in the video
 * recording while still being captured in [ProcessResult] for assertions.
 *
 * For synchronous [runInContainer] calls: the command runs normally and its
 * output is tee'd to an xterm window via `tail -f`.
 *
 * For [runInContainerDetached] calls: an xterm window is opened that tails
 * the detached process's stdout log.
 */
class VisibleConsoleContainerDriver(
    private val delegate: ContainerDriver,
    private val xcvb: XcvbDriver,
    private val consoleTitle: String = "Agent",
    private val geometry: String = "200x50+0+0",
) : ContainerDriver {
    private val counter = AtomicInteger(0)

    override fun mapContainerPortToHostPort(port: ContainerPort): Int =
        delegate.mapContainerPortToHostPort(port)

    override fun withGuestWorkDir(guestWorkDir: String): ContainerDriver =
        VisibleConsoleContainerDriver(delegate.withGuestWorkDir(guestWorkDir), xcvb, consoleTitle, geometry)

    override fun withSecretPattern(secretPattern: String): ContainerDriver =
        VisibleConsoleContainerDriver(delegate.withSecretPattern(secretPattern), xcvb, consoleTitle, geometry)

    override fun withEnv(key: String, value: String): ContainerDriver =
        VisibleConsoleContainerDriver(delegate.withEnv(key, value), xcvb, consoleTitle, geometry)

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
    ): ProcessResult {
        val idx = counter.incrementAndGet()
        val logFile = "/tmp/visible-console-$idx.log"

        // Start a tail -f in an xterm before the command runs
        val tailProc = xcvb.runInVisibleConsole(
            args = listOf("bash", "-c", "touch $logFile && tail -f $logFile"),
            title = "$consoleTitle #$idx: ${args.firstOrNull() ?: "cmd"}",
            geometry = geometry,
        )

        try {
            // Run the actual command, tee-ing output to the log file for the xterm
            val wrappedArgs = listOf(
                "bash", "-c",
                "${escapeForBash(args)} 2>&1 | tee -a $logFile; exit \${PIPESTATUS[0]}",
            )
            return delegate.runInContainer(wrappedArgs, workingDir, timeoutSeconds, extraEnvVars)
        } finally {
            // Give xterm a moment to display the final output
            Thread.sleep(500)
            tailProc.kill("TERM")
        }
    }

    override fun runInContainerDetached(
        args: List<String>,
        workingDir: String?,
        extraEnvVars: Map<String, String>,
    ): RunningContainerProcess {
        val proc = delegate.runInContainerDetached(args, workingDir, extraEnvVars)

        // Open an xterm that tails the detached process stdout
        xcvb.runInVisibleConsole(
            args = listOf("bash", "-c", "tail -f ${proc.stdoutPath} 2>/dev/null || sleep 3600"),
            title = "$consoleTitle: ${proc.name}",
            geometry = geometry,
        )

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

    override fun toString(): String =
        "VisibleConsole($delegate)"

    companion object {
        private fun escapeForBash(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
        }
    }
}
