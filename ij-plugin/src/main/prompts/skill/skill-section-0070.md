## Services and Components

### Access Project Services

```kotlin
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager

// Project root manager
val rootManager = ProjectRootManager.getInstance(project)
println("SDK: ${rootManager.projectSdk?.name}")

// Module manager
val moduleManager = ModuleManager.getInstance(project)
moduleManager.modules.forEach { module ->
    println("Module: ${module.name}")
}

// Content roots
rootManager.contentRoots.forEach { root ->
    println("Content root: ${root.path}")
}

// Source roots
rootManager.contentSourceRoots.forEach { src ->
    println("Source root: ${src.path}")
}
```

### Check File Type in Project

```kotlin
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")

if (vf != null) {
    val fileIndex = ProjectFileIndex.getInstance(project)

    println("Is in project: ${fileIndex.isInProject(vf)}")
    println("Is in source: ${fileIndex.isInSource(vf)}")
    println("Is in test source: ${fileIndex.isInTestSourceContent(vf)}")
    println("Is in library: ${fileIndex.isInLibraryClasses(vf)}")

    val module = fileIndex.getModuleForFile(vf)
    println("Module: ${module?.name}")
}
```

---

## Search Scopes

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.module.ModuleManager

// Project scope (all project files)
val projectScope = GlobalSearchScope.projectScope(project)

// All scope (project + libraries)
val allScope = GlobalSearchScope.allScope(project)

// Module scope
val module = ModuleManager.getInstance(project).modules.firstOrNull()
if (module != null) {
    val moduleScope = GlobalSearchScope.moduleScope(module)
    println("Module scope: ${module.name}")
}

// File scope
val vf = // ... get virtual file
val fileScope = GlobalSearchScope.fileScope(project, vf)

// Multiple files scope
val filesScope = GlobalSearchScope.filesScope(project, listOf(vf1, vf2))
```

