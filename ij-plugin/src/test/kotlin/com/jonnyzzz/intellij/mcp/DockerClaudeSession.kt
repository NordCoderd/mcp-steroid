/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import java.io.File

/**
 * Manages a Claude CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Claude config.
 *
 * The API key is read from ~/.anthropic mounted into the container.
 */
class DockerClaudeSession(
    containerId: String,
    workDir: File,
) : DockerSession(containerId, workDir) {

    override val logPrefix = "DOCKER-CLAUDE"

    /**
     * Run a claude command inside the Docker container.
     * Debug mode is always enabled to see MCP connection details.
     */
    fun run(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val claudeArgs = buildList {
            add("claude")
            add("--debug")
            add("--mcp-debug")
            add("--verbose")
            addAll(args.toList())
        }
        return runInContainer(
            claudeArgs,
            timeoutSeconds,
            enableDebugEnv = true,
            extraEnvVars = mapOf("CLAUDE_CODE_DEBUG" to "1", "ANTHROPIC_API_KEY" to File(System.getenv("HOME"), ".anthropic").readText().trim())
        )
    }

    companion object {
        fun create(): DockerClaudeSession {
            val tempDir = createTempDirectory("claude-test")
            println("[DOCKER-CLAUDE] Creating new session in temp dir: $tempDir")

            buildDockerImage("claude-cli-test:latest", File("src/test/docker/claude-cli/Dockerfile"), "DOCKER-CLAUDE")

            val containerId = startContainer("claude-cli-test:latest", tempDir, "DOCKER-CLAUDE")

            println("[DOCKER-CLAUDE] Session created in container: $containerId")
            return DockerClaudeSession(containerId, tempDir)
        }
    }
}
