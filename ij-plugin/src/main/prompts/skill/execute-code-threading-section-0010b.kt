/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val vf = findProjectFile("src/main/java/com/example/Foo.java")!!
val content = VfsUtil.loadText(vf)               // read OUTSIDE writeAction

// ⚠️ BEFORE content.replace() — ALWAYS print the excerpt BEFORE THE FIRST ATTEMPT:
// Do NOT print it only after a failure — that costs an extra turn.
val idx = content.indexOf("methodName")
println("EXCERPT:\n" + content.substring(idx, (idx + 250).coerceAtMost(content.length)))

// Only then do the replace, verifying the result is different:
val updated = content.replace("oldString", "newString")
check(updated != content) { "content.replace matched nothing — whitespace mismatch!" }
writeAction { VfsUtil.saveText(vf, updated) }    // write INSIDE — no suspend calls allowed

// After bulk VFS edits, flush to disk before running git/shell subprocesses:
LocalFileSystem.getInstance().refresh(false)     // ensures git diff sees the changes
