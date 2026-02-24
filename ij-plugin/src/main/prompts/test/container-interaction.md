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

fun isContainerRunning(containerId: String): Boolean {
    val proc = ProcessBuilder("docker", "inspect", "--format={{.State.Running}}", containerId)
        .redirectErrorStream(true).start()
    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    return proc.inputStream.bufferedReader().readText().trim() == "true"
}

fun dockerExec(containerId: String, display: String, cmd: String) {
    ProcessBuilder("docker", "exec", containerId, "bash", "-c", "DISPLAY=$display $cmd")
        .redirectErrorStream(true).start()
        .waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
}

// Find the latest running session
val session = sessionsRoot.listFiles()
    ?.filter { it.isDirectory && File(it, "session-info.txt").exists() }
    ?.sortedByDescending { it.lastModified() }
    ?.firstNotNullOfOrNull { dir ->
        val props = parseSessionInfo(dir) ?: return@firstNotNullOfOrNull null
        val containerId = props["CONTAINER_ID"] ?: return@firstNotNullOfOrNull null
        if (!isContainerRunning(containerId)) return@firstNotNullOfOrNull null
        dir to props
    }
    ?: error("No running test session found. Start an integration test first.\nLooked in: $sessionsRoot")

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

// Take a screenshot — saved to the mounted run directory, readable via the Read tool
val screenshotDir = File(runDir, "screenshot").also { it.mkdirs() }
val screenshotName = "debug-${System.currentTimeMillis()}.png"
val containerPath = "/mcp-run-dir/screenshot/$screenshotName"
val scrotResult = ProcessBuilder(
    "docker", "exec", containerId, "bash", "-c",
    "DISPLAY=$display scrot $containerPath 2>&1"
).redirectErrorStream(true).start()
scrotResult.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
val scrotOut = scrotResult.inputStream.bufferedReader().readText()
if (scrotResult.exitValue() != 0) println("scrot warning: $scrotOut")
val hostScreenshot = File(runDir, "screenshot/$screenshotName")
println("Screenshot: $hostScreenshot")
println("(Use the Read tool to view the image)")
println()

// ── Keyboard / mouse input examples ──────────────────────────────────
// Uncomment the lines you need:

// Open Find Action dialog (Ctrl+Shift+A)
// dockerExec(containerId, display, "xdotool key ctrl+shift+a")
// Thread.sleep(500)
// dockerExec(containerId, display, "xdotool type --delay 50 -- 'steroid_execute_code'")
// dockerExec(containerId, display, "xdotool key Return")

// Click at display coordinates
// dockerExec(containerId, display, "xdotool mousemove --sync 800 400 && xdotool click 1")

// Show all sessions (including stopped ones)
// sessionsRoot.listFiles()
//     ?.filter { it.isDirectory && File(it, "session-info.txt").exists() }
//     ?.sortedByDescending { it.lastModified() }
//     ?.forEach { dir ->
//         val p = parseSessionInfo(dir) ?: return@forEach
//         println("${dir.name}  container=${p["CONTAINER_ID"]}")
//     }
```
