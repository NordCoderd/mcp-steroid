
---

## ⚠️ Do NOT Configure JDKs/SDKs or Install Plugins

This environment is pre-configured with the correct JDK and all required plugins. Do NOT call `ProjectJdkTable`, search hardcoded JVM paths, or attempt any JDK/SDK configuration — these calls either throw at runtime or silently do nothing, wasting a turn. Do NOT attempt `PluginsAdvertiser`, `PluginInstaller`, `PluginManagerCore` write APIs, or reflection-based plugin installation.

To check if a plugin is already installed (without installing it):
