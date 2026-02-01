/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

abstract class VisionIntegrationReminderTest {
    fun reminderToAddVisionIntegrationTests() {
        // TODO: After 2026-01-17, add integration tests for Swing/JCEF/Compose input + screenshot flows.
        // See TODO.md entry: "Vision API integration tests (Swing/JCEF/Compose)".
        val enableAfter = LocalDate.of(2026, 1, 17)
        assumeTrue("Ignored until $enableAfter", LocalDate.now().isAfter(enableAfter))
        throw AssertionError("Vision API integration tests are overdue. Implement Swing/JCEF/Compose coverage.")
    }
}
