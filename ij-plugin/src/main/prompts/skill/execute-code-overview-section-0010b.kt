/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val vf = findProjectFile("src/main/java/com/example/MyClass.java")!!
val content = VfsUtil.loadText(vf)  // read OUTSIDE writeAction
val updated = content.replace("oldMethod", "newMethod")
check(updated != content) { "replace matched nothing — check whitespace" }
writeAction { VfsUtil.saveText(vf, updated) }  // write INSIDE writeAction
// ↑ This replaces both Read + Edit tools in a single exec_code call
