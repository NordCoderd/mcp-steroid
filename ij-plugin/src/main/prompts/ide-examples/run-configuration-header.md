IDE: Run Configuration

This example lists available run configurations and can optionally
execute one via a chosen executor.

IntelliJ API used:
- RunManager - Access run configurations
- ProgramRunnerUtil - Execute configurations
- ExecutorRegistry - Select executor

Parameters to customize:
- runConfigName: Exact configuration name (empty = list only)
- executorId: Executor ID (DefaultRunExecutor.EXECUTOR_ID for Run)
- dryRun: Set false to execute

Output: List of configurations and optional execution status
