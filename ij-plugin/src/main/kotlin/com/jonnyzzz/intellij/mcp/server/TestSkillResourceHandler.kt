/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.intellij.mcp.mcp.McpServerCore

/**
 * Handler for the IntelliJ Test Runner skill guide resource.
 */
@Service(Service.Level.APP)
class TestSkillResourceHandler : McpRegistrar {

    companion object {
        private const val SKILL_RESOURCE_PATH = "/skill/TEST_SKILL.md"
    }

    private val resourceUri = "intellij://skill/test-runner-guide"
    private val resourceName = "IntelliJ Test Runner Skill Guide"
    private val resourceDescription = """
        Test execution and result inspection guide for running tests and analyzing results.

        Covers test configuration execution, result tree navigation, test status inspection,
        and accessing failure details with IntelliJ test runner APIs.
    """.trimIndent()

    /** Cached skill content - validated at load time */
    private val skillContent: String by lazy {
        javaClass.getResourceAsStream(SKILL_RESOURCE_PATH)
            ?.bufferedReader()
            ?.readText()
            ?: error("Test skill resource not found: $SKILL_RESOURCE_PATH")
    }

    override fun register(server: McpServerCore) {
        // Validate resource exists during registration (fail-fast)
        require(javaClass.getResource(SKILL_RESOURCE_PATH) != null) {
            "Test skill resource missing from JAR: $SKILL_RESOURCE_PATH"
        }

        server.resourceRegistry.registerResource(
            uri = resourceUri,
            name = resourceName,
            description = resourceDescription,
            mimeType = "text/markdown",
            contentProvider = ::loadSkillMd
        )
    }

    fun loadSkillMd(): String = skillContent
}

inline val testSkillResourceHandler: TestSkillResourceHandler get() = service()
