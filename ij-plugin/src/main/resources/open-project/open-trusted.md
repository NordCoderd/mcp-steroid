# Open Project (Trusted)

Open a project with automatic trust, skipping the trust dialog.

## Workflow

### Step 1: Open the Project

Call `steroid_open_project` with `trust_project=true`:

```json
{
  "tool": "steroid_open_project",
  "arguments": {
    "project_path": "/absolute/path/to/your/project",
    "task_id": "open-my-project",
    "reason": "Opening project to implement feature X",
    "trust_project": true
  }
}
```

The tool will:
1. Mark the project path as trusted (skips trust dialog)
2. Initiate project opening in the background
3. Return immediately with next steps

### Step 2: Wait for Project to Load

Wait 2-5 seconds for the project to initialize. The time depends on:
- Project size
- Number of files to index
- Installed plugins

### Step 3: Verify Project is Open

Call `steroid_list_projects` to verify:

```json
{
  "tool": "steroid_list_projects",
  "arguments": {}
}
```

Expected response includes your project:

```json
{
  "projects": [
    {"name": "your-project", "path": "/absolute/path/to/your/project"}
  ]
}
```

### Step 4: Start Working

Now you can use `steroid_execute_code` with the project:

```json
{
  "tool": "steroid_execute_code",
  "arguments": {
    "project_name": "your-project",
    "code": "println(\"Project: ${project.name}\")",
    "task_id": "verify-project",
    "reason": "Verifying project is accessible"
  }
}
```

## Complete Example Session

```
→ steroid_open_project(project_path="/Users/me/projects/my-app", task_id="open-app", reason="Opening to add feature", trust_project=true)
← Project opening initiated...

→ [wait 3 seconds]

→ steroid_list_projects()
← {"projects":[{"name":"my-app","path":"/Users/me/projects/my-app"}]}

→ steroid_execute_code(project_name="my-app", code="println(project.basePath)", ...)
← /Users/me/projects/my-app
```

## When to Use This Approach

- You trust the project and its build scripts
- You want the fastest way to open a project
- You're automating project operations

## Alternatives

If you need to review the trust dialog first, use the "open-with-dialogs" workflow instead.

## See Also

Related project opening examples:
- [Open Project Overview](mcp-steroid://open-project/overview) - Complete opening guide
- [Open with Dialogs](mcp-steroid://open-project/open-with-dialogs) - Interactive dialog handling
- [Open via Code](mcp-steroid://open-project/open-via-code) - Programmatic opening

Related MCP tools:
- `steroid_open_project` - Tool for opening projects via MCP
- `steroid_list_projects` - List all open projects
- `steroid_list_windows` - Check project initialization status

Overview resources:
- [Open Project Examples Overview](mcp-steroid://open-project/overview) - All project opening workflows
- [IntelliJ API Power User Guide](mcp-steroid://skill/intellij-api-poweruser-guide) - Core API patterns
