/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.GlobalSearchScope
val controllerClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.api.controllers.FeatureReactionController",
        GlobalSearchScope.projectScope(project)
    )
}
readAction {
    controllerClass?.methods?.forEach { method ->
        val ann = method.annotations.firstOrNull { a ->
            listOf("GetMapping","PostMapping","DeleteMapping","PutMapping","PatchMapping","RequestMapping")
                .any { a.qualifiedName?.endsWith(it) == true }
        }
        if (ann != null) {
            val path = ann.findAttributeValue("value")?.text ?: ann.findAttributeValue("path")?.text ?: "\"\""
            println("${method.name}: ${ann.qualifiedName?.substringAfterLast('.')} $path")
        }
    }
}
