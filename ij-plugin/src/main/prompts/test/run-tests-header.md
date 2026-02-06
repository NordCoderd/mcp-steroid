Test: Run Tests

This example executes a test run configuration.
Use RunContentManager afterwards to locate the RunContentDescriptor.

IntelliJ API used:
- RunManager - Access run configurations
- ProgramRunnerUtil - Execute configurations
- ExecutorRegistry - Get executor (Run or Debug)
- DefaultRunExecutor - Standard "Run" executor

Parameters to customize:
- configurationName: Name of the run configuration to execute
- executorId: DefaultRunExecutor.EXECUTOR_ID or DefaultDebugExecutor.EXECUTOR_ID

Output: Execution started confirmation
