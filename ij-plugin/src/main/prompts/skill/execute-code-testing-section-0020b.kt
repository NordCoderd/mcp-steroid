/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val vf = findProjectFile("src/main/java/com/example/NewClass.java")!!
val problems = runInspectionsDirectly(vf)
if (problems.isEmpty()) println("OK: no compile errors")
else problems.forEach { (id, descs) -> descs.forEach { println("[$id] ${it.descriptionTemplate}") } }
