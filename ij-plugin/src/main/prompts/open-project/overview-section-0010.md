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

## Important: Project Opening is Asynchronous

Project opening is **asynchronous** - the `steroid_open_project` tool returns immediately after
initiating the open operation. You **MUST poll** to verify the project is fully ready.

## Verification Workflow (Required)

After calling `steroid_open_project`, follow this workflow:

```
1. Call steroid_open_project(project_path="/path/to/project", trust_project=true, ...)
2. Poll steroid_list_windows() every 2-3 seconds until:
   - The project appears in the windows list
   - modalDialogShowing is false (no dialogs blocking)
   - indexingInProgress is false (indexing complete)
   - projectInitialized is true
3. If modalDialogShowing is true:
   - Call steroid_take_screenshot() to see the dialog
   - Use steroid_input() to interact with the dialog
4. Use steroid_take_screenshot() to visually confirm project is loaded
5. Verify with steroid_list_projects() that the project appears
```

## Window Info Fields for Polling

`steroid_list_windows` returns these fields for each window:

| Field | Description |
|-------|-------------|
| `projectName` | Project name (null if not a project window) |
| `projectPath` | Project base path |
| `modalDialogShowing` | True if any modal dialog is showing in IDE |
| `indexingInProgress` | True if project is indexing (dumb mode) |
| `projectInitialized` | True if project is fully initialized |

## Workflows

### Quick Open (Trusted Project)

When you trust the project and want to skip dialogs:

```
1. steroid_open_project(project_path="/path/to/project", trust_project=true, ...)
2. Poll steroid_list_windows() until indexingInProgress=false and projectInitialized=true
3. steroid_list_projects() to verify project is open
```

Resource: `mcp-steroid://open-project/open-trusted`

### Interactive Open (With Dialog Handling)

When you need to see and interact with dialogs:

```
1. steroid_open_project(project_path="/path/to/project", trust_project=false, ...)
2. Poll steroid_list_windows() - check modalDialogShowing
3. If modalDialogShowing=true:
   a. steroid_take_screenshot() to see current state
   b. steroid_input() to click dialog buttons
4. Continue polling until indexingInProgress=false and projectInitialized=true
5. steroid_list_projects() to verify
```

Resource: `mcp-steroid://open-project/open-with-dialogs`

### Programmatic Open (Via Code)

For advanced scenarios using IntelliJ APIs directly:

```kotlin
// Trust and open via APIs
TrustedProjects.setProjectTrusted(path, true)
ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
```
