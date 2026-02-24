/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager

// ⚠️ ProjectRootManager accesses the project model — must be inside readAction { }
val (sdkName, contentRootPaths, sourceRootPaths) = readAction {
    val rm = ProjectRootManager.getInstance(project)
    Triple(rm.projectSdk?.name, rm.contentRoots.map { it.path }, rm.contentSourceRoots.map { it.path })
}
println("SDK: $sdkName")

// Module manager
val moduleManager = ModuleManager.getInstance(project)
moduleManager.modules.forEach { module ->
    println("Module: ${module.name}")
}

// Content roots
contentRootPaths.forEach { println("Content root: $it") }

// Source roots
sourceRootPaths.forEach { println("Source root: $it") }
