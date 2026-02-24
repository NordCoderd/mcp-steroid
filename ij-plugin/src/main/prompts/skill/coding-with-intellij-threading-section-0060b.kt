/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ✓ CORRECT - Runs on EDT even when a modal dialog is showing
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    // Enumerate windows, inspect dialogs, close dialogs, etc.
}
