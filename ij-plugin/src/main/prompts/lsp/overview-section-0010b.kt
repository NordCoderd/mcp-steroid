/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.lang.Language

val actionIds = ActionManager.getInstance().getActionIdList("").toSet()
println("Has Java actions: " + actionIds.contains("NewJavaSpecialFile"))
println("Has Kotlin actions: " + actionIds.contains("Kotlin.NewFile"))
println("Languages: " + Language.getRegisteredLanguages().map { it.id }.sorted())
