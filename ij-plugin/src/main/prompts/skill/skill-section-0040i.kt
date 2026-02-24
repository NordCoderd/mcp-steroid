/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
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
