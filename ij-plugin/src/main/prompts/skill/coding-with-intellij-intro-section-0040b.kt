/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Smart mode already waited - safe to use indices immediately
val classes = readAction {
    JavaPsiFacade.getInstance(project)
        .findClass("com.example.MyClass", allScope())
}

// Only call again if you trigger re-indexing
// (rare - most operations don't trigger indexing)
