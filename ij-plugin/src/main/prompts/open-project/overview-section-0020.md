
Resource: `mcp-steroid://open-project/open-via-code`

## Trust Project Dialog

When opening an untrusted project, IntelliJ shows a "Trust Project" dialog asking:
- **Trust Project**: Opens with full functionality
- **Preview in Safe Mode**: Opens with limited functionality (no build/run)
- **Don't Open**: Cancels the operation

Using `trust_project=true` in `steroid_open_project` automatically trusts the project,
skipping this dialog entirely.

## Common Scenarios

### Opening a New Project

```
steroid_open_project(
    project_path="/Users/me/projects/new-project",
    task_id="open-new-project",
    reason="Opening project to work on feature X",
    trust_project=true
)
```

### Opening in New Window

```
steroid_open_project(
    project_path="/Users/me/projects/another-project",
    task_id="open-in-new-window",
    reason="Opening second project for comparison",
    trust_project=true,
    force_new_frame=true
)
```

### Checking if Project is Already Open

Before opening, you can check if the project is already open:

```
steroid_list_projects()
```

If the project path matches an already-open project, `steroid_open_project` will
return immediately with a message indicating the project is already open.

## Troubleshooting

### Project not appearing in list
- Poll `steroid_list_windows()` - check if `indexingInProgress` is still true
- Wait for indexing to complete (can take several minutes for large projects)
- Use `steroid_take_screenshot()` to see if there's a dialog waiting
- Check IDE logs for errors

### Trust dialog keeps appearing
- Make sure to set `trust_project=true` in the tool call
- Or use `steroid_input()` to click the "Trust Project" button

### Project opens but is empty/broken
- Check `steroid_list_windows()` for `projectInitialized` status
- Wait for `indexingInProgress` to become false
- The project may need additional configuration
- Check if all required plugins are installed via `steroid_capabilities()`
- Some projects require specific SDKs to be configured

### Modal dialog is blocking
- Check `modalDialogShowing` in `steroid_list_windows()` response
- Use `steroid_take_screenshot()` to see the dialog
- Use `steroid_input()` to interact with the dialog

## Available Resources

| Resource URI | Description |
|-------------|-------------|
| `mcp-steroid://open-project/overview` | This overview document |
| `mcp-steroid://open-project/open-trusted` | Example: Open with automatic trust |
| `mcp-steroid://open-project/open-with-dialogs` | Example: Open with dialog handling |
| `mcp-steroid://open-project/open-via-code` | Example: Open via IntelliJ APIs |

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug workflows
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution

### Project Opening Examples
- [Open Project Overview](mcp-steroid://open-project/overview) - This document
- [Open Trusted](mcp-steroid://open-project/open-trusted) - Auto-trust project opening
- [Open with Dialogs](mcp-steroid://open-project/open-with-dialogs) - Interactive dialog handling
- [Open via Code](mcp-steroid://open-project/open-via-code) - Programmatic opening

### Related Example Guides
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [Test Examples](mcp-steroid://test/overview) - Test execution
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations
