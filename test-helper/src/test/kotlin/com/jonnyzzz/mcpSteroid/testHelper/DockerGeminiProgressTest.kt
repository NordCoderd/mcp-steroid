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

class DockerGeminiProgressTest {

    @Test
    fun `test Gemini shows progress events with stream-json`() = runWithCloseableStack { stack ->
        assumeTrue(
            hasGeminiApiKey(),
            "Requires GEMINI_API_KEY/GOOGLE_API_KEY or a key in ~/.vertes or ~/.vertex"
        )

        val geminiSession = createLightweightGeminiSession(stack)
        val result = geminiSession.runPrompt(
            prompt = """
                List files in current directory.
                You must use a tool call (for example, a shell command) to get the result.
            """.trimIndent(),
            timeoutSeconds = 30
        )

        result.assertExitCode(0) { "Gemini command should succeed" }

        val events = parseNdjsonEvents(result.rawOutput)
        assertTrue(events.isNotEmpty(), "Raw output should contain stream-json NDJSON events")
        assertTrue(events.size > 1, "Expected multiple stream-json events, found ${events.size}")

        val eventTypes = events.mapNotNull { it.stringField("type") }
        assertTrue(eventTypes.contains("tool_use"), "Raw output should contain tool_use events: $eventTypes")
        assertTrue(eventTypes.contains("tool_result"), "Raw output should contain tool_result events: $eventTypes")
        assertTrue(eventTypes.contains("result"), "Raw output should contain result events: $eventTypes")

        assertNotEquals(
            result.rawOutput.trim(),
            result.stdout.trim(),
            "Processed output should not be identical to raw NDJSON"
        )
        assertTrue(result.stdout.contains(">> "), "Processed output should contain start progress markers")
        assertTrue(result.stdout.contains("<< "), "Processed output should contain completion progress markers")
    }

    private fun createLightweightGeminiSession(stack: CloseableStack): DockerGeminiSession {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
        val dockerfile = projectHome.resolve("test-helper/src/main/docker/agents/gemini/Dockerfile")
        require(dockerfile.isFile) { "Gemini Dockerfile must exist: $dockerfile" }

        val workDir = createTempDirectory("gemini-progress")
        stack.registerCleanupAction { workDir.deleteRecursively() }

        val driver = DockerDriver(workDir, "GEMINI-PROGRESS")
        val imageId = driver.buildDockerImage(
            imageName = "gemini-cli-progress-test",
            dockerfilePath = dockerfile,
            timeoutSeconds = 600
        )

        val container = startContainerDriver(
            lifetime = stack,
            scope = driver,
            imageName = imageId,
            autoRemove = true
        )
        return DockerGeminiSession.create(container)
    }

    private fun hasGeminiApiKey(): Boolean {
        if (!System.getenv("GEMINI_API_KEY").isNullOrBlank()) return true
        if (!System.getenv("GOOGLE_API_KEY").isNullOrBlank()) return true

        val home = System.getProperty("user.home")
        for (filename in listOf(".vertes", ".vertex")) {
            val keyFile = File(home, filename)
            if (keyFile.isFile && keyFile.readText().trim().isNotBlank()) {
                return true
            }
        }

        return false
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
