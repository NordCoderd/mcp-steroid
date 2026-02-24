
### Using Built-in Write Actions

```kotlin
// ✓ CORRECT - Use built-in writeAction (no import needed)
writeAction {
    document.setText("new content")
}

// For command-wrapped writes (shows in undo stack)
import com.intellij.openapi.command.WriteCommandAction
WriteCommandAction.runWriteCommandAction(project) {
    document.insertString(0, "// Added comment\n")
}
```
