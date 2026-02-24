
## Key APIs to use

**Run Configuration Management**
- `RunManager.getInstance(project)` - access all run configurations
- `RunManager.allSettings` - list of RunnerAndConfigurationSettings
- `RunManager.selectedConfiguration` - currently selected config

**Execution**
- `ProgramRunnerUtil.executeConfiguration(settings, executor)` - execute config
- `ExecutorRegistry.getExecutorById(DefaultRunExecutor.EXECUTOR_ID)` - get Run executor
- `ExecutorRegistry.getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)` - get Debug executor

**Accessing Results**
- `RunContentManager.getInstance(project).allDescriptors` - all run content
- `RunContentDescriptor.getExecutionConsole()` - get console (may be SMTRunnerConsoleView)
- `SMTRunnerConsoleView.getResultsViewer()` - get SMTestRunnerResultsForm
- `SMTestRunnerResultsForm.getTestsRootNode()` - get test tree root (SMRootTestProxy)

**Test Result Inspection**
- `SMTestProxy.isPassed()`, `isDefect()`, `isIgnored()`, `isInProgress()`
- `SMTestProxy.getChildren()` - navigate test tree
- `SMTestProxy.getErrorMessage()`, `getStacktrace()` - failure details
- `SMTestProxy.getDuration()` - execution time
- `SMTestProxy.getAllTests()` - flatten test tree

**Process Monitoring**
- `RunContentDescriptor.getProcessHandler()` - get ProcessHandler
- `ProcessHandler.isProcessTerminated()` - check if execution finished
- `ProcessHandler.exitCode` - get exit code (if available)

## Common pitfalls

- Test results are only available after execution starts (descriptor.executionConsole may be null initially).
- ExecutionConsole must be cast to SMTRunnerConsoleView for test-specific features.
- Test tree is populated asynchronously as tests run.
- Always check `isProcessTerminated()` before accessing final results.
- Execute configurations on EDT (use `withContext(Dispatchers.EDT)`).
- Wait for smart mode before accessing run configurations.

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Essential IntelliJ API patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug session management
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - This guide

### Test Execution Resources
- [Test Overview](mcp-steroid://test/overview) - Complete test execution workflow and patterns
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available test configs
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access and analyze test results
- [Test Tree Navigation](mcp-steroid://test/tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll for test execution status

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging and session management
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
