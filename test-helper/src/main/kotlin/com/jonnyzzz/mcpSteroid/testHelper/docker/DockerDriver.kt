/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.ProcessRunner
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofPattern

/**
 * Represents a process running inside a Docker container with output redirected to files.
 * Stdout, stderr, and PID are stored in [logDir] inside the container.
 * Use [readOutput], [readStderr] to fetch content via `docker exec`.
 * Use [kill] to terminate the process if it is still running.
 */
class RunningContainerProcess(
    private val driver: DockerDriver,
    private val containerId: String,
    /** Name/label for this background process */
    val name: String,
    /** Container-side directory holding stdout.log, stderr.log, and pid */
    val logDir: String,
) {
    val stdoutPath: String get() = "$logDir/stdout.log"
    val stderrPath: String get() = "$logDir/stderr.log"
    val pidPath: String get() = "$logDir/pid"

    /** Read current stdout content from the container. */
    fun readOutput(timeoutSeconds: Long = 10): String {
        val result = driver.runInContainer(
            containerId,
            listOf("cat", stdoutPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.output
    }

    /** Read current stderr content from the container. */
    fun readStderr(timeoutSeconds: Long = 10): String {
        val result = driver.runInContainer(
            containerId,
            listOf("cat", stderrPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.output
    }

    /** Read the PID of the background process. Returns null if not available. */
    fun readPid(timeoutSeconds: Long = 5): String? {
        val result = driver.runInContainer(
            containerId,
            listOf("cat", pidPath),
            timeoutSeconds = timeoutSeconds,
        )
        return result.output.trim().takeIf { it.isNotEmpty() && result.exitCode == 0 }
    }

    /** Kill the background process if it is still running. */
    fun kill(signal: String = "TERM", timeoutSeconds: Long = 5) {
        val pid = readPid() ?: return
        driver.runInContainer(
            containerId,
            listOf("kill", "-$signal", pid),
            timeoutSeconds = timeoutSeconds,
        )
    }

    /** Check if the process is still running. */
    fun isRunning(timeoutSeconds: Long = 5): Boolean {
        val pid = readPid() ?: return false
        val result = driver.runInContainer(
            containerId,
            listOf("kill", "-0", pid),
            timeoutSeconds = timeoutSeconds,
        )
        return result.exitCode == 0
    }

    override fun toString(): String = "RunningContainerProcess(name=$name, logDir=$logDir)"
}

class DockerDriver(
    val workDir: File,
    val logPrefix: String,
    val secretPatterns: List<String>,
) {
    fun withSecretPattern(secretPattern: String): DockerDriver {
        return DockerDriver(workDir, logPrefix, (secretPatterns + secretPattern).distinct())
    }

    val processRunner get() = ProcessRunner(logPrefix, secretPatterns.toList())

    fun buildDockerImage(
        imageName: String,
        dockerfilePath: File,
        timeoutSeconds: Long
    ) {
        require(dockerfilePath.exists() && dockerfilePath.isFile) { "File does not exist: $dockerfilePath" }


        val nowDate = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())
        val result = processRunner.run(
            listOf("docker", "build", "-t", imageName, "--build-arg", "CACHE_BUST=$nowDate", "."),
            description = "Build Docker image $imageName",
            workingDir = dockerfilePath.parentFile,
            timeoutSeconds = timeoutSeconds,
        )

        if (result.exitCode != 0) {
            throw IllegalStateException("Failed to build Docker image. Exit code: ${result.exitCode}\n${result.stderr}")
        }
        println("[$logPrefix] Docker image built successfully")
    }

    fun startContainer(
        imageName: String,
    ): String {
        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            add("--add-host=host.docker.internal:host-gateway")
            add(imageName)
        }

        val result = processRunner.run(
            command,
            description = "Start container from $imageName",
            workingDir = workDir,
            timeoutSeconds = 30,
        )

        val containerId = result.output.trim()
        if (result.exitCode != 0 || containerId.isEmpty()) {
            throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
        }

        println("[$logPrefix] Container started: $containerId")
        return containerId
    }

    fun killContainer(containerId: String) {
        processRunner.run(
            listOf("docker", "kill", containerId),
            description = "kill container",
            workingDir = workDir,
            timeoutSeconds = 10,
        )

        processRunner.run(
            listOf("docker", "rm", "-f", containerId),
            description = "Remove container",
            workingDir = workDir,
            timeoutSeconds = 5,
        )

        println("[$logPrefix] Container removed successfully")
    }

    fun copyToContainer(
        containerId: String,
        localPath: File,
        containerPath: String,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        require(localPath.exists()) { "Local path does not exist: $localPath" }
        return processRunner.run(
            listOf("docker", "cp", localPath.absolutePath, "$containerId:$containerPath"),
            description = "Copy ${localPath.name} to container:$containerPath",
            workingDir = workDir,
            timeoutSeconds = timeoutSeconds,
        )
    }

    fun copyFromContainer(
        containerId: String,
        containerPath: String,
        localPath: File,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        localPath.parentFile?.mkdirs()
        return processRunner.run(
            listOf("docker", "cp", "$containerId:$containerPath", localPath.absolutePath),
            description = "Copy container:$containerPath to ${localPath.name}",
            workingDir = workDir,
            timeoutSeconds = timeoutSeconds,
        )
    }

    /**
     * Write a text file inside the container at [containerPath].
     * Creates parent directories if needed.
     *
     * @param executable When true, marks the file as executable after writing.
     */
    fun writeFileInContainer(
        containerId: String,
        containerPath: String,
        executable: Boolean = false,
        content: String,
    ) {
        // Ensure parent directory exists
        val parentDir = containerPath.substringBeforeLast('/')
        if (parentDir.isNotEmpty()) {
            runInContainer(containerId, listOf("mkdir", "-p", parentDir), timeoutSeconds = 5)
        }

        runInContainer(
            containerId,
            listOf("bash", "-c", "cat > $containerPath << 'FILE_EOF'\n$content\nFILE_EOF"),
            timeoutSeconds = 5,
        )

        if (executable) {
            runInContainer(containerId, listOf("chmod", "+x", containerPath), timeoutSeconds = 5)
        }
    }

    /**
     * Run a command inside the container.
     *
     * @param detach When true, passes `--detach` to `docker exec` so the command
     *               runs in the background and this call returns immediately.
     */
    fun runInContainer(
        containerId: String,
        args: List<String>,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String> = emptyMap(),
        detach: Boolean = false,
    ): ProcessResult {
        val shellCommand = escapeShellArgs(args)

        val command = buildList {
            add("docker")
            add("exec")
            if (detach) add("--detach")
            extraEnvVars.forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            add(containerId)
            add("bash")
            add("-c")
            add(shellCommand)
        }

        return processRunner.run(
            command,
            description = "In container: ${args.joinToString(" ")}",
            workingDir = workDir,
            timeoutSeconds = timeoutSeconds,
        )
    }

    /**
     * Run a command inside the container in detached mode with output capture.
     *
     * Creates a wrapper bash script inside the container that:
     * - Redirects stdout/stderr to log files under `/tmp/run-<datetime>-<name>/`
     * - Records the PID of the actual process
     * - Runs via `docker exec --detach`
     *
     * Returns a [RunningContainerProcess] handle to read output, check status, or kill.
     *
     * @param name Label for this background process (used in log dir name and logging)
     */
    fun runInContainerDetached(
        containerId: String,
        args: List<String>,
        name: String,
        extraEnvVars: Map<String, String> = emptyMap(),
    ): RunningContainerProcess {
        val timestamp = ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now())
        val logDir = "/tmp/run-$timestamp-$name"

        // Build the wrapper script that runs the real command,
        // captures its PID, and redirects output to files
        val innerCommand = escapeShellArgs(args)
        val wrapperScript = buildString {
            appendLine("#!/bin/bash")
            appendLine("$innerCommand >$logDir/stdout.log 2>$logDir/stderr.log &")
            appendLine("echo \\$! > $logDir/pid")
            appendLine("wait")
        }

        // Write the wrapper script into the container
        val scriptPath = "$logDir/run.sh"
        writeFileInContainer(containerId, scriptPath, wrapperScript, executable = true)

        // Run the wrapper script detached
        val result = runInContainer(
            containerId,
            listOf("bash", scriptPath),
            timeoutSeconds = 10,
            extraEnvVars = extraEnvVars,
            detach = true,
        )

        if (result.exitCode != 0) {
            error("Failed to start detached process '$name': ${result.stderr}")
        }

        println("[$logPrefix] Detached process '$name' started, logs at $logDir")
        return RunningContainerProcess(this, containerId, name, logDir)
    }

    private fun escapeShellArgs(args: List<String>): String =
        args.joinToString(" ") { arg ->
            if (arg.contains(" ") || arg.contains("\"") || arg.contains("\n") || arg.contains("'")) {
                "'" + arg.replace("'", "'\\''") + "'"
            } else {
                arg
            }
        }
}