Test: Interact with Running Docker Test Container

Use this when an integration test is running (or paused) inside Docker and you need

```kotlin
import java.io.File

// Locate the most recent test session written by the integration test factory.
// Sessions are stored under test-integration/build/test-logs/test/<run-dir>/session-info.txt
val sessionsRoot = File(
    System.getProperty("test.integration.testOutput", "test-integration/build/test-logs/test")
)

fun parseSessionInfo(dir: File): Map<String, String>? {
    val infoFile = File(dir, "session-info.txt")
    if (!infoFile.exists()) return null
    return infoFile.readLines()
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}

// ❌ DO NOT use GeneralCommandLine("docker", ...) or ProcessBuilder("docker", ...) inside steroid_execute_code.
//    These spawn a child process inside IntelliJ's JVM — same banned pattern as ProcessBuilder("./mvnw").
// ✅ For docker inspect / docker exec: use the Bash tool OUTSIDE steroid_execute_code.
// ✅ Docker socket availability check (no process spawn needed):
val dockerAvailable = java.io.File("/var/run/docker.sock").exists()
println("Docker socket available: $dockerAvailable")

// Find the latest session with a session-info.txt file
val session = sessionsRoot.listFiles()
    ?.filter { it.isDirectory && File(it, "session-info.txt").exists() }
    ?.sortedByDescending { it.lastModified() }
    ?.firstNotNullOfOrNull { dir ->
        val props = parseSessionInfo(dir) ?: return@firstNotNullOfOrNull null
        val containerId = props["CONTAINER_ID"] ?: return@firstNotNullOfOrNull null
        if (containerId.isBlank()) return@firstNotNullOfOrNull null
        dir to props
    }
    ?: error("No test session found. Start an integration test first.\nLooked in: $sessionsRoot")

val (runDir, props) = session
val containerId = props["CONTAINER_ID"]!!
val display     = props["DISPLAY"] ?: ":99"
val mcpUrl      = props["MCP_STEROID"] ?: ""
val videoUrl    = props["VIDEO_DASHBOARD"] ?: ""

println("Session  : $runDir")
println("Container: $containerId")
println("Display  : $display")
println("MCP      : $mcpUrl")
println("Video    : $videoUrl")
println()
println("=== Use the Bash tool for Docker operations ===")
println("Check running : docker inspect --format='{{.State.Running}}' $containerId")
println("Screenshot    : docker exec $containerId bash -c 'DISPLAY=$display scrot /mcp-run-dir/screenshot/debug-\$(date +%s).png 2>&1'")
println("Keyboard input: docker exec $containerId bash -c 'DISPLAY=$display xdotool key ctrl+shift+a'")
println("Mouse click   : docker exec $containerId bash -c 'DISPLAY=$display xdotool mousemove --sync 800 400 && xdotool click 1'")
println()
println("Screenshot files land in: $runDir/screenshot/")
println("(Use the Read tool to view PNG images)")
```

# See also

Related test operations:
- [Find Recent Test Run](mcp-steroid://test/find-recent-test-run) - Find test executions in the IDE
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Read test result XML/reports

Skill guides:
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Essential test knowledge
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debugging workflows
