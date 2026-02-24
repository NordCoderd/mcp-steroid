/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// ✓ CORRECT - This is your script
println("Hello from IntelliJ!")
val projectName = project.name
println("Project: $projectName")

// ✗ WRONG - Do not wrap in execute { }
execute {
    println("Hello")  // ERROR: execute is not defined
}
