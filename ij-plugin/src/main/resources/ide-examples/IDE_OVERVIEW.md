# IntelliJ IDE Power Operations (Non-LSP)

This collection provides example code snippets implementing advanced IDE operations
that go beyond LSP capabilities. Each example is a complete script for `steroid_execute_code`.

## Available Examples

### Refactorings

| Resource | Operation | Description |
|----------|-----------|-------------|
| `intellij://ide/extract-method` | Extract Method | Extract selected statements into a new method |
| `intellij://ide/introduce-variable` | Introduce Variable | Extract expression into a new local variable |
| `intellij://ide/inline-method` | Inline Method | Inline method body at call sites |
| `intellij://ide/change-signature` | Change Signature | Add/reorder parameters and update call sites |
| `intellij://ide/move-file` | Move File | Move a file to another directory and update references |
| `intellij://ide/safe-delete` | Safe Delete | Safely remove elements with usage analysis |

### Code Hygiene

| Resource | Operation | Description |
|----------|-----------|-------------|
| `intellij://ide/optimize-imports` | Optimize Imports | Remove unused imports and sort remaining ones |
| `intellij://ide/inspect-and-fix` | Inspection + Fix | Run an inspection and apply a quick fix |

### Navigation and Generation

| Resource | Operation | Description |
|----------|-----------|-------------|
| `intellij://ide/generate-override` | Generate Overrides | Implement interface methods / override base methods |
| `intellij://ide/hierarchy-search` | Hierarchy Search | Find inheritors and overrides for a class/method |

## Usage

1. Read a specific example resource to get the complete code snippet.
2. Adapt the code to your needs (file paths, positions, names).
3. Execute via `steroid_execute_code`.

## Notes

- Many examples support `dryRun` to preview changes safely.
- For write operations, use `dryRun=true` first, then set to `false` to apply.
- Always call `waitForSmartMode()` before using indices or PSI.
