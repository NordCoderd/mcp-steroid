/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Run this at the start of your task to detect files already created/modified by parallel agents
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "Clean slate — no prior agent changes" else "FILES ALREADY MODIFIED:\n" + changes.joinToString("\n"))
// If files are listed above: read them first before writing, to avoid overwriting work
