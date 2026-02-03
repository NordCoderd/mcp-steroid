/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

/**
 * Handler for the IntelliJ Test Runner skill guide resource.
 */
class TestSkillResourceHandler : McpRegistrar {
    private val descriptor = skillResources.test
    private val skillResourcePath = descriptor.resourcePath
    private val resourceName = descriptor.resourceName
    private val resourceDescription = """
        Test execution and result inspection guide for running tests and analyzing results.

        Covers test configuration execution, result tree navigation, test status inspection,
        and accessing failure details with IntelliJ test runner APIs.
    """.trimIndent()

    /** Cached skill content - validated at load time */
    private val skillContent: String by lazy {
        javaClass.getResourceAsStream(skillResourcePath)
            ?.bufferedReader()
            ?.readText()
            ?: error("Test skill resource not found: $skillResourcePath")
    }

    override fun register(server: McpServerCore) {
        // Validate resource exists during registration (fail-fast)
        require(javaClass.getResource(skillResourcePath) != null) {
            "Test skill resource missing from JAR: $skillResourcePath"
        }

        registerSkillResource(
            server = server,
            descriptor = descriptor,
            name = resourceName,
            description = resourceDescription,
            contentProvider = ::loadSkillMd
        )
    }

    fun loadSkillMd(): String = skillContent
}
