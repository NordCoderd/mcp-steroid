
## Git-Specific Notes

- Git operations are provided by the Git4Idea plugin
- Most operations require the file to be in a Git repository
- Some operations (like annotations) may need to fetch data from Git

## Threading Considerations

- VCS operations can be slow (especially for large files or remote repos)
- Many VCS operations should NOT be called from EDT
- Use background threads with `runBackgroundableTask` for long operations
- Read actions may still be needed for PSI access

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://skill/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://skill/debugger-skill) - Debug workflows
- [Test Runner Skill Guide](mcp-steroid://skill/test-skill) - Test execution

### VCS Examples
- [Git Annotations](mcp-steroid://vcs/git-annotations) - Get blame/annotations for files
- [Git History](mcp-steroid://vcs/git-history) - Get commit history

### Related Example Guides
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation and intelligence
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [Test Examples](mcp-steroid://test/overview) - Test execution
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
