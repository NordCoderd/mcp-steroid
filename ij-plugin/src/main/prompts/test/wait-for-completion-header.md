Test: Wait for Completion

This example polls the most recent test execution to check
if it has completed. Call this repeatedly until tests finish.

IntelliJ API used:
- RunContentManager - Access all run content descriptors
- ProcessHandler - Check process termination status

Output: Completion status and exit code
