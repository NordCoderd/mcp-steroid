**Reporting rule** (only when both conditions hold):
- `./mvnw test` failed with an explicit `DockerException` / `Could not find a valid Docker environment`
- `./mvnw test-compile` exits 0

→ Report: `ARENA_FIX_APPLIED: yes`
→ Note: `(tests blocked by Docker unavailability — compilation verified via test-compile)`

**Do NOT** apply this path when tests fail for any other reason (logic errors, missing methods,
Spring context startup failures). The Docker-unavailable path is ONLY valid when the error is
literally "cannot connect to Docker daemon."

---

**End of Guide**

For more examples, see the MCP resources:
- `mcp-steroid://lsp/overview` - LSP-like examples
- `mcp-steroid://ide/overview` - IDE power operations
- `mcp-steroid://debugger/overview` - Debugger examples

**IntelliJ Platform SDK**: https://plugins.jetbrains.com/docs/intellij/
**Source Code**: https://github.com/JetBrains/intellij-community
