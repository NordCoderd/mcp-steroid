
### No Visible Window

Target IDE may be running headless. Use programmatic approaches:
- Monitor logs
- Use state file manipulation
- Query via debugger code injection

---

## Best Practices for AI Agents

1. **Always use async launch** - `invokeLater` prevents blocking forever
2. **Wait for initialization** - Monitor logs for "Loaded bundled plugins"
3. **Check logs first** - Before UI automation, verify IDE started correctly
4. **Use multiple evidence sources** - Process status + debug connection + logs + state files
5. **Document everything** - PID, debug port, log excerpts, screenshots

### Evidence Checklist

```
Process running (ps aux)
Debug connection active (IntelliJ shows it)
Logs show plugin loaded
No errors in logs
State files updated
= High confidence plugin works
```

---

## Summary

**As an AI Agent, you can:**

1. Launch IDEs in debug mode programmatically
2. Use MCP Steroid to execute code in the host IDE
3. Inject code into target IDE via debugger "Evaluate Expression"
4. Set breakpoints and inspect runtime state
5. Take screenshots for visual verification
6. Monitor logs in real-time
7. Test plugins without UI automation
8. Modify behavior during debugging

**Workflow:**
```
Launch IDE -> Wait for startup -> Set breakpoints ->
Trigger functionality -> Inspect via debugger ->
Monitor logs -> Collect evidence -> Document results
```

**Remember:**
- Debugging is async - wait for readiness
- Headless mode is common - adapt your approach
- Logs are your best friend
- Multiple evidence sources = high confidence
- Document everything for reproducibility
