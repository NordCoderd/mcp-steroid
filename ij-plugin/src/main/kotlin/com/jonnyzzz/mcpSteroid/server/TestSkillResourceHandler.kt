/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.TestSkillPrompt

/**
 * Handler for the IntelliJ Test Runner skill guide resource.
 */
class TestSkillResourceHandler : McpRegistrar {
    private val descriptor = skillResources.test
    private val resourceName = descriptor.resourceName
    private val resourceDescription = """
        Test execution and result inspection guide for running tests and analyzing results.

        Covers test configuration execution, result tree navigation, test status inspection,
        and accessing failure details with IntelliJ test runner APIs.
    """.trimIndent()

    override fun register(server: McpServerCore) {
        registerSkillResource(
            server = server,
            descriptor = descriptor,
            name = resourceName,
            description = resourceDescription,
            contentProvider = {
                TestSkillPrompt().readPrompt()
            }
        )
    }
}
