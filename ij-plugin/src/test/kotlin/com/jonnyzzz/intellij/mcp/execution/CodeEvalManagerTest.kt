/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.testExecParams
import org.junit.Assert
import kotlin.time.Duration.Companion.minutes

class CodeEvalManagerTest: BasePlatformTestCase()  {
    override fun runInDispatchThread(): Boolean = false

    fun testSmoke(): Unit = timeoutRunBlocking(5.minutes) {
        val code = """
            println("it works")
        """.trimIndent()
        val testExecParams = testExecParams(code)
        val execId = project.executionStorage.writeNewExecution(testExecParams)
        val result = TestResultBuilder()
        val data = project.codeEvalManager.evalCode(execId, code, result)
        Assert.assertNotNull(data)
    }

}