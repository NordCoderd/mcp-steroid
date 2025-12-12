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
class DockerCodexSession(
    containerId: String,
    workDir: File,
) : DockerSession(containerId, workDir) {

    override val logPrefix = "DOCKER-CODEX"

    /**
     * Run a codex command inside the Docker container.
     * Note: Codex doesn't support --verbose flag like Claude does.
     */
    fun run(vararg args: String, timeoutSeconds: Long = 120): ProcessResult {
        val codexArgs = buildList {
            add("codex")
            addAll(args.toList())
        }
        val extraEnvVars = mapOf("OPENAI_API_KEY" to File(System.getenv("HOME"), ".openai").readText().trim())
        return runInContainer(codexArgs, timeoutSeconds, enableDebugEnv = true, extraEnvVars = extraEnvVars)
    }

    /**
     * Run codex exec for non-interactive mode.
     */
    fun runExec(prompt: String, timeoutSeconds: Long = 120): ProcessResult {
        val codexArgs = listOf("codex", "exec", "--skip-git-repo-check", prompt)
        return runInContainer(codexArgs, timeoutSeconds, enableDebugEnv = true)
    }

    /**
     * Create the MCP config file with the given server configuration.
     * Codex uses TOML format for configuration at ~/.codex/config.toml
     */
    fun configureMcpServer(serverName: String, serverUrl: String): ProcessResult {
        val tomlConfig = """
[mcp_servers.$serverName]
url = "$serverUrl"
""".trim()
        return runRaw("bash", "-c", "mkdir -p ~/.codex && echo '$tomlConfig' >> ~/.codex/config.toml")
    }

    companion object {
        fun create(): DockerCodexSession {
            val tempDir = createTempDirectory("codex-test")
            println("[DOCKER-CODEX] Creating new session in temp dir: $tempDir")

            buildDockerImage(
                "codex-cli-test:latest",
                File("src/test/docker/codex-cli/Dockerfile"), "DOCKER-CODEX", timeoutSeconds = 600
            )
            val containerId = startContainer("codex-cli-test:latest", tempDir, "DOCKER-CODEX")

            println("[DOCKER-CODEX] Session created in container: $containerId")
            return DockerCodexSession(containerId, tempDir)
        }
    }
}
