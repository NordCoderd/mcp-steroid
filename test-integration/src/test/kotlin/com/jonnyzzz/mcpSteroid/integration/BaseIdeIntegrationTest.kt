/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration

import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.assertNoErrorsInOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Base class for IDE integration tests that run IntelliJ inside a Docker container.
 * Uses @TestInstance(PER_CLASS) to share the container across test methods,
 * since IDE startup takes several minutes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseIdeIntegrationTest {

    protected lateinit var ideSession: IdeDockerSession

    private val pluginZipPath: File
        get() {
            val path = System.getProperty("test.integration.plugin.zip")
                ?: error("test.integration.plugin.zip system property not set")
            return File(path).also {
                require(it.isFile) { "Plugin zip not found: $it" }
            }
        }

    private val ideaArchivePath: File
        get() {
            val path = System.getProperty("test.integration.idea.archive")
                ?: error("test.integration.idea.archive system property not set")
            return File(path).also {
                require(it.isFile) { "IDEA archive not found: $it" }
            }
        }

    private val testProjectDir: File
        get() {
            val url = javaClass.classLoader.getResource("test-project")
                ?: error("test-project resource not found")
            return File(url.toURI()).also {
                require(it.isDirectory) { "Test project not found: $it" }
            }
        }

    private val dockerDir: File
        get() {
            // Docker files are in test-integration/docker/ide-agent/
            val projectRoot = File(System.getProperty("user.dir")).parentFile
            return File(projectRoot, "test-integration/docker/ide-agent").also {
                if (!it.isDirectory) {
                    // Fallback: try relative to working directory
                    val fallback = File("docker/ide-agent")
                    require(fallback.isDirectory) { "Docker dir not found: $it and $fallback" }
                    return fallback
                }
            }
        }

    private val videoOutput: String?
        get() = System.getProperty("test.integration.video.output")

    @BeforeAll
    fun startIdeContainer() {
        println("[BASE-TEST] Starting IDE container...")
        ideSession = IdeDockerSession.start(
            pluginZipPath = pluginZipPath,
            ideaArchivePath = ideaArchivePath,
            testProjectDir = testProjectDir,
            dockerDir = dockerDir,
            videoOutput = videoOutput?.let { "/tmp/recording.mp4" },
        )

        println("[BASE-TEST] Waiting for IDE to be ready...")
        ideSession.waitForIdeReady(timeoutSeconds = 300)
        println("[BASE-TEST] IDE is ready")
    }

    @AfterAll
    fun stopIdeContainer() {
        if (::ideSession.isInitialized) {
            // Extract video if requested
            val videoPath = videoOutput
            if (videoPath != null) {
                try {
                    ideSession.extractVideo(File(videoPath))
                    println("[BASE-TEST] Video saved to: $videoPath")
                } catch (e: Exception) {
                    println("[BASE-TEST] Failed to extract video: ${e.message}")
                }
            }

            ideSession.close()
            println("[BASE-TEST] IDE container stopped")
        }
    }

    /**
     * Read the Anthropic API key from environment or ~/.anthropic file.
     */
    protected fun readAnthropicApiKey(): String {
        System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        val keyFile = File(System.getProperty("user.home"), ".anthropic")
        if (keyFile.exists()) {
            val content = keyFile.readText().trim()
            if (content.isNotBlank()) return content
        }
        error("ANTHROPIC_API_KEY is required (set env or ~/.anthropic)")
    }

    /**
     * Register the MCP server running inside the container with Claude CLI.
     * Since everything runs inside the same container, use localhost.
     */
    protected fun registerClaudeMcp(mcpUrl: String = "http://localhost:6315/mcp", mcpName: String = "intellij") {
        ideSession.runInContainer(
            "claude", "mcp", "add", "--transport", "http", mcpName, mcpUrl,
            timeoutSeconds = 30,
            extraEnvVars = mapOf("ANTHROPIC_API_KEY" to readAnthropicApiKey()),
        ).assertExitCode(0, "MCP registration")
            .assertNoErrorsInOutput("MCP registration")
    }

    /**
     * Run a Claude prompt inside the container.
     */
    protected fun runClaudePrompt(
        prompt: String,
        timeoutSeconds: Long = 600,
    ): ProcessResult {
        return ideSession.runInContainer(
            "claude",
            "--permission-mode", "bypassPermissions",
            "-p", prompt,
            timeoutSeconds = timeoutSeconds,
            extraEnvVars = mapOf("ANTHROPIC_API_KEY" to readAnthropicApiKey()),
        )
    }
}
