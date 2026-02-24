/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val process = ProcessBuilder("./mvnw", "test", "-Dtest=AuthControllerTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | lines: ${lines.size}")
println(lines.take(30).joinToString("\n"))   // Spring context / Testcontainers errors at top
println(lines.takeLast(30).joinToString("\n")) // Maven BUILD summary at bottom
