/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// ✓ CORRECT - Direct coroutine usage
delay(1000)
progress("Step 1 complete")

// ✗ WRONG - Never use runBlocking
runBlocking {  // ERROR: Causes deadlocks!
    delay(1000)
}
