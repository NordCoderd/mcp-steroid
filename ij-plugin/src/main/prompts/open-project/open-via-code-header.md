Open Project via IntelliJ APIs

This script demonstrates how to open a project programmatically using
IntelliJ Platform APIs. Use this for advanced scenarios where you need
more control over the project opening process.

IMPORTANT: This code runs in a background coroutine. The project opening
is asynchronous, so the script will return before the project is fully loaded.

To use: Adapt the projectPath variable to your project location.
