/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// ⚠️ ALWAYS verify package structure BEFORE creating new files
// Step 1: List all content source roots to understand the module layout
// ⚠️ contentSourceRoots accesses the project model — MUST be inside readAction { }
import com.intellij.openapi.roots.ProjectRootManager
readAction { ProjectRootManager.getInstance(project).contentSourceRoots }.forEach { println(it.path) }
// Step 2: Check if the target package actually exists in the project model
val pkg = readAction { JavaPsiFacade.getInstance(project).findPackage("shop.api.core") }
println("shop.api.core exists: ${pkg != null}")
// If the package doesn't exist, list top-level packages to find the real one:
val topPkg = readAction { JavaPsiFacade.getInstance(project).findPackage("") }
topPkg?.subPackages?.forEach { println(it.qualifiedName) }
