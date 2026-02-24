## Indexing and Search

### Search Files by Name

```kotlin
import com.intellij.psi.search.FilenameIndex

// smartReadAction = waitForSmartMode() + readAction
smartReadAction {
    // Find files by exact name using built-in projectScope()
    val files = FilenameIndex.getFilesByName(project, "build.gradle.kts", projectScope())

    files.forEach { file ->
        println("Found: ${file.virtualFile.path}")
    }
}
```

### Search Files by Extension

```kotlin
import com.intellij.psi.search.FileTypeIndex
import org.jetbrains.kotlin.idea.KotlinFileType

smartReadAction {
    // Find all Kotlin files using built-in projectScope()
    val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, projectScope())

    println("Found ${kotlinFiles.size} Kotlin files")
    kotlinFiles.take(20).forEach { vf ->
        println("  ${vf.path}")
    }
}
```

### Find Methods by Name (Stub Index)

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.impl.java.stubs.index.JavaMethodNameIndex
import com.intellij.psi.PsiMethod


readAction {
    val methods = StubIndex.getElements(
        JavaMethodNameIndex.getInstance().key,
        "toString",
        project,
        GlobalSearchScope.projectScope(project),
        PsiMethod::class.java
    )

    println("Found ${methods.size} methods named 'toString'")
    methods.take(10).forEach { method ->
        println("  ${method.containingClass?.qualifiedName}.${method.name}")
    }
}
```

---

## Document and Editor Operations

### Read Document Content

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val document = FileDocumentManager.getInstance().getDocument(vf!!)

    if (document != null) {
        println("Lines: ${document.lineCount}")
        println("Length: ${document.textLength}")

        // Get specific line
        val lineNum = 5
        if (lineNum < document.lineCount) {
            val startOffset = document.getLineStartOffset(lineNum)
            val endOffset = document.getLineEndOffset(lineNum)
            println("Line $lineNum: ${document.getText().substring(startOffset, endOffset)}")
        }
    }
}
```

### Modify Document (CAUTION: modifies file)

```kotlin
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
val document = FileDocumentManager.getInstance().getDocument(vf!!)

if (document != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        // Insert at position
        document.insertString(0, "// Added comment\n")

        // Or replace text
        // document.replaceString(startOffset, endOffset, "new text")

        // Or delete
        // document.deleteString(startOffset, endOffset)
    }
    println("Document modified")
}
```

### Access Current Editor

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager

readAction {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor

    if (editor != null) {
        val document = editor.document
        val caretModel = editor.caretModel
        val selectionModel = editor.selectionModel

        println("Current file: ${editor.virtualFile?.name}")
        println("Caret offset: ${caretModel.offset}")
        println("Caret line: ${caretModel.logicalPosition.line}")

        if (selectionModel.hasSelection()) {
            println("Selected: ${selectionModel.selectedText}")
        }
    } else {
        println("No editor open")
    }
}
```

---

## VFS (Virtual File System) Operations

### Read File Content

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.txt")

if (vf != null && !vf.isDirectory) {
    val content = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
    println("File content (${content.length} chars):")
    println(content.take(500))
}
```

### Refresh a Specific File (Optional)

Use this only when you know a file changed outside the IDE or VFS looks stale. Prefer refreshing the exact file or directory you need.

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

val path = "/path/to/file.txt"
val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
if (vf != null) {
    VfsUtil.markDirtyAndRefresh(false, false, false, vf)
}
```

### List Directory Contents

```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

val dir = LocalFileSystem.getInstance().findFileByPath("/path/to/directory")

if (dir != null && dir.isDirectory) {
    dir.children.forEach { child ->
        val type = if (child.isDirectory) "DIR" else "FILE"
        println("[$type] ${child.name}")
    }
}
```

### Create File (CAUTION: modifies filesystem)

```kotlin
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.vfs.LocalFileSystem

writeAction {
    val parentDir = LocalFileSystem.getInstance().findFileByPath("/path/to/dir")
    if (parentDir != null) {
        val newFile = parentDir.createChildData(this, "newfile.txt")
        newFile.setBinaryContent("Hello, World!".toByteArray())
        println("Created: ${newFile.path}")
    }
}
```

---
