/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// ⚠️ Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed
// ⚠️ CRITICAL: Spring Boot test output routinely exceeds 200k chars. NEVER print untruncated output.
// ⚠️ Do NOT use -q — Maven quiet mode suppresses "Tests run:" summary. Exit code 0 alone is NOT sufficient.
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | total output lines: ${lines.size}")
// ⚠️ Capture BOTH ends: Spring context / Testcontainers failures appear at the START;
// Maven BUILD FAILURE summary appears at the END. takeLast alone misses early errors.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
