/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Wrong: filename search fails for generated classes
// val vfs = readAction { FilenameIndex.getVirtualFilesByName("UserDto.java", scope) }  // returns []

// Correct: PSI class lookup finds generated classes too
// Use allScope() — not projectScope() — to include generated sources:
import com.intellij.psi.search.GlobalSearchScope
val generatedClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "org.springframework.samples.petclinic.dto.UserDto",
        GlobalSearchScope.allScope(project)  // allScope() searches generated sources
    )
}
println(if (generatedClass != null) "Found: " + generatedClass.containingFile?.virtualFile?.path
        else "Not in PSI — class not yet generated or wrong FQN")

// Find where a generated class is USED (no source file needed):
import com.intellij.psi.search.PsiSearchHelper
val scope = GlobalSearchScope.projectScope(project)
val usageFiles = mutableListOf<String>()
readAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("UserDto", scope, { psiFile ->
        usageFiles.add(psiFile.virtualFile.path); true
    }, true)
}
println("Files referencing UserDto:\n" + usageFiles.joinToString("\n"))

// Check if target/generated-sources exists at all:
val genSources = findProjectFile("target/generated-sources")
println("Generated sources dir: " + (genSources?.path ?: "NOT FOUND — run mvnw generate-sources first"))
