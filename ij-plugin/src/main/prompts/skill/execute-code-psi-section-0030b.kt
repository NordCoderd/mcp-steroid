/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.searches.ReferencesSearch
val cmdClass = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.CreateReleaseCommand", projectScope())
}
if (cmdClass != null) {
    ReferencesSearch.search(cmdClass, projectScope()).findAll().forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
        println("$file → " + ref.element.parent.text.take(100))
    }
} else println("class not found")
