/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp

import java.io.File

/**
 * Manages a Codex CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Codex config.
 *
 * The API key is read from ~/.openai mounted into the container.
 */
class DockerCodexSession private constructor(
    containerId: String,
    workDir: File,
    private val apiKey: String
) : DockerSession(containerId, workDir) {

    override val logPrefix = "DOCKER-CODEX"

    init {
        // Register API key for filtering in logs
        processRunner.addSecretFilter(apiKey)
    }

    /**
     * Run a codex command inside the Docker container.
     * Note: Codex doesn't support --verbose flag like Claude does.
     */
    fun run(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val codexArgs = buildList {
            add("codex")
            addAll(args.toList())
        }
        val extraEnvVars = buildMap {
            put("OPENAI_API_KEY", apiKey)
            put("CODEX_DEBUG", "1")
            put("MCP_DEBUG", "1")
        }
        return runInContainer(codexArgs, timeoutSeconds, enableDebugEnv = true, extraEnvVars = extraEnvVars)
    }

    /**
     * Run codex exec for non-interactive mode.
     * @param model Optional model override (e.g., "gpt-5-codex", "o4-mini")
     */
    fun runExec(prompt: String, timeoutSeconds: Long = 120, model: String? = null): ProcessResult {
        val codexArgs = buildList {
            add("codex")
            add("exec")
            add("--skip-git-repo-check")
            if (model != null) {
                add("--model")
                add(model)
            }
            add(prompt)
        }
        // Set both OPENAI_API_KEY and CODEX_API_KEY for compatibility
        val extraEnvVars = mapOf(
            "OPENAI_API_KEY" to apiKey,
            "CODEX_API_KEY" to apiKey,
            "CODEX_DEBUG" to "1",
            "MCP_DEBUG" to "1"
        )
        return runInContainer(codexArgs, timeoutSeconds, enableDebugEnv = true, extraEnvVars = extraEnvVars)
    }

    /**
     * Create the MCP config file with the given server configuration.
     * Codex uses TOML format for configuration at ~/.codex/config.toml
     *
     * The [features] rmcp_client = true is needed for HTTP-based MCP servers.
     * The preferred_auth_method = "apikey" is needed for API key authentication.
     */
    fun configureMcpServer(serverName: String, serverUrl: String): ProcessResult {
        val tomlConfig = """
# Use API key authentication (required for non-interactive mode)
preferred_auth_method = "apikey"

[features]
rmcp_client = true

[mcp_servers.$serverName]
url = "$serverUrl"
""".trim()
        val script = """
set -e
mkdir -p ~/.codex
cat > ~/.codex/config.toml <<'EOF'
$tomlConfig
EOF
""".trimIndent()
        return runRaw("bash", "-c", script)
    }

    companion object {
        private fun readOpenAiApiKey(): String {
            System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".openai")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            error("OPENAI_API_KEY is required for Codex CLI tests (set env or ~/.openai)")
        }

        fun create(): DockerCodexSession {
            val tempDir = createTempDirectory("codex-test")
            println("[DOCKER-CODEX] Creating new session in temp dir: $tempDir")

            buildDockerImage(
                "codex-cli-test:latest",
                File("src/test/docker/codex-cli/Dockerfile"), "DOCKER-CODEX", timeoutSeconds = 600
            )
            val containerId = startContainer("codex-cli-test:latest", tempDir, "DOCKER-CODEX")

            val apiKey = readOpenAiApiKey()

            println("[DOCKER-CODEX] Session created in container: $containerId")
            return DockerCodexSession(containerId, tempDir, apiKey)
        }
    }
}
