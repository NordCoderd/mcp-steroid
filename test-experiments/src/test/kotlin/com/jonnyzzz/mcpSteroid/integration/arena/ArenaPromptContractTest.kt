/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArenaPromptContractTest {

    @Test
    fun `mcp prompt keeps apply patch schema and verification rerun guardrails`() {
        val prompt = ArenaTestRunner(
            container = ContainerDriver(
                logPrefix = "prompt-test",
                containerId = "unused",
                startRequest = StartContainerRequest(),
            ),
            projectGuestDir = "/workspace",
        ).buildPrompt(testCase = sampleMavenTestCase(), projectDir = "/home/agent/project-home", withMcp = true)

        assertTrue(
            prompt.contains("steroid_apply_patch"),
            "MCP prompt should keep the dedicated apply-patch tool as the multi-site edit path",
        )
        assertTrue(
            prompt.contains("\"file_path\""),
            "steroid_apply_patch examples must use ApplyPatchToolHandler's file_path schema",
        )
        assertFalse(
            prompt.contains("\"path\":"),
            "steroid_apply_patch examples must not drift back to the invalid path schema",
        )
        assertTrue(
            prompt.contains("NOT the older `applyPatch {}` DSL"),
            "The older applyPatch DSL may be mentioned only as the path to avoid",
        )
        assertFalse(
            prompt.contains("Run each Maven/Gradle verification target at most once"),
            "Verification guidance must not forbid legitimate reruns after fixes or skipped tests",
        )
        assertTrue(
            prompt.contains("Do not rerun Maven/Gradle just to recover hidden output"),
            "Prompt should keep the measured duplicate-verification guardrail",
        )
        assertTrue(
            prompt.contains("Rerun when you changed code, saw a real failure, got an incomplete run, or Gradle skipped tests"),
            "Prompt should explicitly preserve legitimate rerun cases",
        )
        assertTrue(
            prompt.contains("Before outputting `ARENA_FIX_APPLIED: yes`, the full suite must exit 0"),
            "Prompt must not weaken the DPAIA full-suite success requirement",
        )
    }

    private fun sampleMavenTestCase() = DpaiaTestCase(
        instanceId = "dpaia__sample",
        issueNumbers = listOf("1"),
        tags = listOf("Spring", "Maven"),
        repo = "dpaia/sample.git",
        patch = "",
        testPatch = """
            diff --git a/src/test/java/SampleTest.java b/src/test/java/SampleTest.java
            +class SampleTest {}
        """.trimIndent(),
        failToPass = listOf("com.example.SampleTest"),
        passToPass = emptyList(),
        createdAt = "2026-04-26T00:00:00Z",
        baseCommit = "0000000000000000000000000000000000000000",
        problemStatement = "Add the missing endpoint.",
        version = "1",
        isMaven = true,
        buildSystem = "maven",
        testArgs = "",
    )
}
