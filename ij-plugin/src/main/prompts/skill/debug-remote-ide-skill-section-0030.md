
**Important:** The IDE launch is asynchronous! Wait for startup before testing.

---

## Step 3: Set Breakpoints Programmatically

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Paths

// Set a breakpoint in your plugin code
val file = VfsUtil.findFile(
    Paths.get(
        project.basePath!!,
        "plugins/your-plugin/src/com/example/YourAction.kt"  // Replace with your path
    ),
    true
)

if (file != null) {
    val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
    val lineBreakpointType = XLineBreakpointType.EXTENSION_POINT_NAME
        .extensionList
        .firstOrNull { it is XLineBreakpointType }

    if (lineBreakpointType != null) {
        breakpointManager.addLineBreakpoint(
            lineBreakpointType,
            file.url,
            35, // line number - adjust as needed
            null // condition
        )
        println("Breakpoint set")
    }
}
```
