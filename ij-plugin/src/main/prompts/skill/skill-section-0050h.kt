/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val file = requireNotNull(findProjectFile("src/main/kotlin/MyClass.kt")) { "File not found" }

// Run inspections directly - works even when IDE is not focused
val problems = runInspectionsDirectly(file)

if (problems.isEmpty()) {
    println("No problems found!")
} else {
    problems.forEach { (inspectionId, descriptors) ->
        descriptors.forEach { problem ->
            val element = problem.psiElement
            val line = if (element != null) {
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
                doc?.getLineNumber(element.textOffset)?.plus(1) ?: "?"
            } else "?"
            println("[$inspectionId] Line $line: ${problem.descriptionTemplate}")
        }
    }
}
