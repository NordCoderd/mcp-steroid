/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// smartReadAction = waitForSmartMode() + readAction { } in one call
smartReadAction {
    // Use built-in projectScope() helper
    val classes = KotlinClassShortNameIndex.get("MyService", project, projectScope())

    if (classes.isEmpty()) {
        println("No class named 'MyService' found")
        return@smartReadAction
    }

    classes.forEach { ktClass ->
        println("Class: ${ktClass.fqName}")
        println("File: ${ktClass.containingFile.virtualFile.path}")

        // List all methods
        ktClass.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
            .forEach { method ->
                val params = method.valueParameters.joinToString { "${it.name}: ${it.typeReference?.text}" }
                val returnType = method.typeReference?.text ?: "Unit"
                println("  fun ${method.name}($params): $returnType")
            }
    }
}
