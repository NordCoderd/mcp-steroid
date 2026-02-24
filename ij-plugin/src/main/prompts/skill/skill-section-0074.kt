/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.actionSystem.ActionManager

ActionManager.getInstance().getActionIdList("")
    .filter { it.contains("Goto", ignoreCase = true) }
    .take(20)
    .forEach { println(it) }
