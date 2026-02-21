/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startContainerDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DockerCodexProgressTest {

    @Test
    fun `test Codex shows progress events`() = runWithCloseableStack { stack ->
        assumeTrue(
            hasCodexApiKey(),
            "Requires OPENAI_API_KEY or a key in ~/.openai"
        )

        val codexSession = createLightweightCodexSession(stack)
        val result = codexSession.runPrompt(
            prompt = "List files in current directory. You must use a tool call (for example, run a shell command) to get the result.",
            timeoutSeconds = 30
        )

        result.assertExitCode(0) { "Codex command should succeed" }

        val events = parseNdjsonEvents(result.rawOutput)
        assertTrue(events.isNotEmpty(), "Raw output should contain NDJSON events")
        assertTrue(events.size > 1, "Expected multiple NDJSON events, found ${events.size}")

        val eventTypes = events.mapNotNull { it.stringField("type") }
        assertTrue(eventTypes.contains("item.started"), "Raw output should contain item.started events: $eventTypes")
        assertTrue(eventTypes.contains("item.completed"), "Raw output should contain item.completed events: $eventTypes")

        assertNotEquals(
            result.rawOutput.trim(),
            result.stdout.trim(),
            "Processed output should not be identical to raw NDJSON"
        )
        assertTrue(result.stdout.contains(">> "), "Processed output should contain progress markers")
    }

    private fun createLightweightCodexSession(stack: CloseableStack): DockerCodexSession {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
        val dockerfile = projectHome.resolve("test-helper/src/main/docker/agents/codex/Dockerfile")
        require(dockerfile.isFile) { "Codex Dockerfile must exist: $dockerfile" }

        val workDir = createTempDirectory("codex-progress")
        stack.registerCleanupAction { workDir.deleteRecursively() }

        val driver = DockerDriver(workDir, "CODEX-PROGRESS")
        val imageId = driver.buildDockerImage(
            imageName = "codex-cli-progress-test",
            dockerfilePath = dockerfile,
            timeoutSeconds = 600
        )

        val container = startContainerDriver(
            lifetime = stack,
            scope = driver,
            imageName = imageId,
            autoRemove = true
        )
        return DockerCodexSession.create(container)
    }

    private fun hasCodexApiKey(): Boolean {
        if (!System.getenv("OPENAI_API_KEY").isNullOrBlank()) return true

        val home = System.getProperty("user.home")
        val keyFile = File(home, ".openai")
        return keyFile.isFile && keyFile.readText().trim().isNotBlank()
    }

    private fun parseNdjsonEvents(rawOutput: String): List<JsonObject> {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        return buildList {
            for (line in rawOutput.lineSequence()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("{")) continue

                val event = try {
                    json.parseToJsonElement(trimmed) as? JsonObject
                } catch (_: Exception) {
                    null
                }

                if (event != null) {
                    add(event)
                }
            }
        }
    }

    private fun JsonObject.stringField(fieldName: String): String? {
        val primitive = this[fieldName] as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }
}
