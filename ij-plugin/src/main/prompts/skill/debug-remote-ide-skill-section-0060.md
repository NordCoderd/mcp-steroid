
---

## Observing the Debugged IDE

### Check Process Status

```bash
# Find target IDE process
ps aux | grep "DevMainKt" | grep -v grep

# Extract debug port
ps aux | grep "DevMainKt" | grep -o "address=[^,]*"
# Output: address=127.0.0.1:60228
```

### Monitor Logs

```bash
# Logs directory (adjust path for your IDE)
ls -la ~/Library/Logs/JetBrains/TARGET_IDE/

# Main log file
tail -f ~/Library/Logs/JetBrains/TARGET_IDE/idea.log

# Search for plugin activity
grep -i "your-plugin" idea.log | tail -20

# Look for errors
grep -i "error\|exception" idea.log | tail -10
```

### Check Window Visibility (macOS)

```bash
osascript -e 'tell application "System Events" to get count of windows of (first process whose name is "java")'
# Output: 0 = headless mode
```

---

## Common Patterns

### Pattern 1: Verify Plugin Loaded
