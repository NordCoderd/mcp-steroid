
### Check Plugin Installed (Before Using Plugin APIs)

> **⚠️ Do NOT call `PluginsAdvertiser.installAndEnable` or any programmatic plugin installer.**
> These APIs change signatures between IntelliJ versions and throw `IllegalArgumentException` /
> `IllegalAccessError` at runtime (2025.x+). Always check first; if not installed, use `required_plugins`
> parameter instead and let the tool system handle it.
