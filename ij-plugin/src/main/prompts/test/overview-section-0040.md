
## Threading Considerations

- Execute run configurations on EDT: `withContext(Dispatchers.EDT) { ... }`
- Access PSI/VFS in read actions: `readAction { ... }`
- Poll completion status from background thread (no EDT blocking)
- Test tree navigation can be done on any thread after tests complete

## Stateful Execution

Remember that each `steroid_execute_code` call runs in the same IDE process; state persists between calls:

1. **Call 1**: Start test execution
2. **Call 2+**: Poll for completion (quick, non-blocking checks)
3. **Final call**: Inspect results after completion

This pattern avoids timeout issues and provides better feedback to the agent.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug workflows and session management
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Essential test execution knowledge

### Test Execution Examples
- [Test Overview](mcp-steroid://test/overview) - This document
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
- [Test Tree Navigation](mcp-steroid://test/tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
- [Demo Debug Test](mcp-steroid://test/demo-debug-test) - End-to-end debug flow for demo test

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
