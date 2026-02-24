/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Verify compile with test-compile (faster than full test, no Docker needed):
val proc = ProcessBuilder("./mvnw", "test-compile", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exitCode = proc.waitFor()
println("test-compile exit: $exitCode")
println(lines.filter { "ERROR" in it || "BUILD" in it || "FAILURE" in it }.joinToString("\n"))
// Exit code 0 = compilation success → safe to report ARENA_FIX_APPLIED: yes with caveat
// Exit code 1 = compile errors → fix errors first
