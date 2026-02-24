/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.vfs.LocalFileSystem

val dir = LocalFileSystem.getInstance().findFileByPath("/path/to/directory")

if (dir != null && dir.isDirectory) {
    dir.children.forEach { child ->
        val type = if (child.isDirectory) "DIR" else "FILE"
        println("[$type] ${child.name}")
    }
}
