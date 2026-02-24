/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.progress.ProcessCanceledException

try {
    // some operation
} catch (e: ProcessCanceledException) {
    // ✗ WRONG - Never catch this!
    throw e  // Always rethrow
}
