# Implementation Plan

## Phase 1: MCP Server Infrastructure

### 1.1 Create MCP Server skeleton
- Add HTTP server dependency (Ktor or Netty via IntelliJ platform)
- Create `McpServer` class that starts on plugin initialization
- Bind to port 11993
- Implement JSON-RPC 2.0 message handling (MCP protocol)

### 1.2 Implement `list_projects` tool
- Query `ProjectManager.getInstance().openProjects`
- Return project name and base path for each

### 1.3 Create integration test infrastructure
- Set up test framework for IntelliJ plugin testing
- Create test that starts the plugin and connects to MCP server
- Verify server responds to basic requests

## Phase 2: Code Execution Engine

### 2.1 Implement `McpScriptContext` interface
- Define in a separate module/package that can be loaded into script classloader
- Include `project` property
- Include `registerCommand` / `unregisterCommand` methods
- Include `readSlot` / `writeSlot` methods

### 2.2 Create script classloader infrastructure
- Build classloader that parents IntelliJ platform classes
- Add mechanism to include additional plugin dependencies
- Ensure `McpScriptContext` is available in script classpath

### 2.3 Implement Kotlin compilation
- Use Kotlin compiler API (kotlin-compiler-embeddable)
- Compile submitted code to classes
- Handle compilation errors with line numbers

### 2.4 Implement Groovy compilation (optional, can defer)
- Use GroovyShell or GroovyClassLoader
- Similar error handling

### 2.5 Implement code execution
- Load compiled classes into script classloader
- Find and invoke `main(McpScriptContext)` entry point
- Capture stdout/stderr during execution
- Handle exceptions and return errors

### 2.6 Implement code storage
- Save submitted code to `.idea/mcp-run/<timestamp>-<hash>.kt`
- Maintain execution history

## Phase 3: Slots and Dynamic Commands

### 3.1 Implement slots storage
- Store slots in `.idea/mcp-run/slots/` as JSON files
- Implement `read_slot` and `write_slot` MCP tools
- Add slot access to `McpScriptContext`

### 3.2 Implement dynamic command registration
- Maintain registry of commands registered via `McpScriptContext.registerCommand()`
- Commands persist until plugin restart or explicit unregister
- Implement `list_commands` and `call_command` MCP tools

## Phase 4: Plugin Dependency Management

### 4.1 Implement `list_plugins` tool
- Return list of enabled plugins with IDs

### 4.2 Implement `add_plugin_dependency` tool
- Add plugin's classes to script classloader
- Validate plugin exists and is enabled

## Phase 5: IntelliJ Project Detection

### 5.1 Detect intellij-community projects
- Check for presence of `intellij.core` module or characteristic files
- Check for `.idea/modules.xml` patterns

### 5.2 Provide enhanced context
- Include additional documentation in tool descriptions
- Reference internal testing utilities
- List relevant extension points

## Phase 6: Testing and Polish

### 6.1 Integration tests
- Test `list_projects` with mock projects
- Test `execute_code` with simple Kotlin scripts
- Test `execute_code` with scripts that access Project APIs
- Test slot read/write
- Test dynamic command registration
- Test compilation error handling
- Test runtime exception handling

### 6.2 Error handling
- Graceful handling of port already in use
- Timeout for long-running scripts
- Memory limits for script execution

### 6.3 Documentation
- Update README with final API
- Add examples of common operations
- Document error codes and messages
