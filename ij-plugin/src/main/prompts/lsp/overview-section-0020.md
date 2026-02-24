
If a language lacks reference providers, fall back to PSI traversal (`PsiNamedElement`),
Structure View (`LanguageStructureViewBuilder`), or inspection output.

## Important Notes

- `waitForSmartMode()` runs automatically before your script starts; call it again only after triggering indexing
- Use `readAction { }` for all PSI read operations
- Use `writeAction { }` for modifications
- The `project` variable is available in all scripts

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug workflows
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution

### LSP Examples
- Navigation: `go-to-definition`, `find-references`, `workspace-symbol`
- Code Intelligence: `hover`, `completion`, `signature-help`
- Document Operations: `document-symbols`, `formatting`
- Refactoring: `rename`, `code-action`

See `mcp-steroid://lsp/<id>` for specific examples (e.g., `mcp-steroid://lsp/go-to-definition`)

### Related Example Guides
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations beyond LSP
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [Test Examples](mcp-steroid://test/overview) - Test execution
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
