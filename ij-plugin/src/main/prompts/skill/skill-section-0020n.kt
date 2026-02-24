/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

val path = Path.of("/path/to/project")
ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
