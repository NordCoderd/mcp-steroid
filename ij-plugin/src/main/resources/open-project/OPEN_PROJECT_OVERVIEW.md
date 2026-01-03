# Opening Projects in IntelliJ via MCP

This guide explains how to open projects in IntelliJ using the MCP Steroid plugin.

## Available Tool

### `steroid_open_project`

Opens a project in the IDE. This tool initiates the project opening process and returns quickly.

**Parameters:**
- `project_path` (required): Absolute path to the project directory
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project before opening (default: false)
- `force_new_frame` (optional): If true, always open in a new window (default: false)

## Workflows

### Quick Open (Trusted Project)

When you trust the project and want to skip dialogs:

```
1. steroid_open_project(project_path="/path/to/project", trust_project=true, ...)
2. Wait 2-5 seconds for project to load
3. steroid_list_projects() to verify project is open
```

Resource: `intellij://open-project/open-trusted`

### Interactive Open (With Dialog Handling)

When you need to see and interact with dialogs:

```
1. steroid_open_project(project_path="/path/to/project", trust_project=false, ...)
2. steroid_take_screenshot() to see current state
3. If dialog appears, use steroid_input() to click buttons
4. Repeat steps 2-3 until project is open
5. steroid_list_projects() to verify
```

Resource: `intellij://open-project/open-with-dialogs`

### Programmatic Open (Via Code)

For advanced scenarios using IntelliJ APIs directly:

```kotlin
execute {
    // Trust and open via APIs
    TrustedProjects.setProjectTrusted(path, true)
    ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
}
```

Resource: `intellij://open-project/open-via-code`

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
- Wait a few more seconds for indexing to complete
- Use `steroid_take_screenshot()` to see if there's a dialog waiting
- Check IDE logs for errors

### Trust dialog keeps appearing
- Make sure to set `trust_project=true` in the tool call
- Or use `steroid_input()` to click the "Trust Project" button

### Project opens but is empty/broken
- The project may need additional configuration
- Check if all required plugins are installed via `steroid_capabilities()`
- Some projects require specific SDKs to be configured

## Available Resources

| Resource URI | Description |
|-------------|-------------|
| `intellij://open-project/overview` | This overview document |
| `intellij://open-project/open-trusted` | Example: Open with automatic trust |
| `intellij://open-project/open-with-dialogs` | Example: Open with dialog handling |
| `intellij://open-project/open-via-code` | Example: Open via IntelliJ APIs |
