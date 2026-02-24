/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import com.intellij.openapi.command.WriteCommandAction

// Find
val ktClass = smartReadAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope()).firstOrNull()
}

// Modify
if (ktClass != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        ktClass.setName("MyNewClass")
    }
}
