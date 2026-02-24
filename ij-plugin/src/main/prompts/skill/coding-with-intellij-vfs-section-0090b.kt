/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.vfs.VfsUtil
// Refresh entire project tree so VFS picks up externally created files:
VfsUtil.markDirtyAndRefresh(false, true, true,
    LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
)
