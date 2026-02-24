/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

val path = "/path/to/file.txt"
val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
if (vf != null) {
    VfsUtil.markDirtyAndRefresh(false, false, false, vf)
}
