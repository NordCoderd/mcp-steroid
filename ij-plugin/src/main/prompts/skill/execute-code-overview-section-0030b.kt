/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.psi.search.FilenameIndex        // getVirtualFilesByName, getAllFilesByExt
import com.intellij.psi.search.GlobalSearchScope    // projectScope(), allScope()
import com.intellij.openapi.roots.ProjectRootManager // contentSourceRoots
import com.intellij.openapi.vfs.VfsUtil              // loadText(), saveText(), createDirectoryIfMissing()
import com.intellij.psi.search.PsiShortNamesCache   // allClassNames
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ReferencesSearch
