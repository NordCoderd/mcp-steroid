/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import java.io.File

/**
 * Manages a Claude CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Claude config.
 *
 * The API key is read from ~/.anthropic file.
 */
class DockerClaudeSession private constructor(
    containerId: String,
    workDir: File,
    private val apiKey: String
) : DockerSession(containerId, workDir) {

    override val logPrefix = "DOCKER-CLAUDE"

    init {
        // Register API key for filtering in logs
        processRunner.addSecretFilter(apiKey)
    }

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
            extraEnvVars = mapOf(
                "CLAUDE_CODE_DEBUG" to "1",
                "ANTHROPIC_API_KEY" to apiKey
            )
        )
    }

    /**
     * Run claude in non-interactive mode with a prompt.
     *
     * @param prompt The prompt to send to Claude
     * @param timeoutSeconds Maximum time to wait for the command
     * @param allowedTools Optional list of tools to allow (e.g., "mcp__serverName__*")
     */
    fun runPrompt(
        prompt: String,
        timeoutSeconds: Long = 120,
        allowedTools: List<String>? = null
    ): ProcessResult {
        val claudeArgs = buildList {
            add("claude")
            add("--debug")
            add("--mcp-debug")
            add("--verbose")
            // Allow MCP tools in print mode (required since v0.2.54)
            if (allowedTools != null && allowedTools.isNotEmpty()) {
                add("--allowedTools")
                add(allowedTools.joinToString(","))
            }
            add("-p")
            add(prompt)
        }
        return runInContainer(
            claudeArgs,
            timeoutSeconds,
            enableDebugEnv = true,
            extraEnvVars = mapOf(
                "CLAUDE_CODE_DEBUG" to "1",
                "ANTHROPIC_API_KEY" to apiKey
            )
        )
    }

    /**
     * Configure MCP server in .mcp.json
     */
    fun configureMcpServer(serverName: String, serverUrl: String): ProcessResult {
        val mcpConfig = """
{
    "mcpServers": {
        "$serverName": {
            "url": "$serverUrl"
        }
    }
}
""".trim()
        val script = """
set -e
mkdir -p ~/work
cat > ~/work/.mcp.json <<'EOF'
$mcpConfig
EOF
""".trimIndent()
        return runRaw("bash", "-c", script)
    }

    companion object {
        private fun readAnthropicApiKey(): String {
            System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".anthropic")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("ANTHROPIC_API_KEY is required for Claude CLI tests (set env or ~/.anthropic)")
        }

        fun create(): DockerClaudeSession {
            val tempDir = createTempDirectory("claude-test")
            println("[DOCKER-CLAUDE] Creating new session in temp dir: $tempDir")

            buildDockerImage("claude-cli-test:latest", File("src/test/docker/claude-cli/Dockerfile"), "DOCKER-CLAUDE")

            val containerId = startContainer("claude-cli-test:latest", tempDir, "DOCKER-CLAUDE")

            val apiKey = readAnthropicApiKey()

            println("[DOCKER-CLAUDE] Session created in container: $containerId")
            return DockerClaudeSession(containerId, tempDir, apiKey)
        }
    }
}
