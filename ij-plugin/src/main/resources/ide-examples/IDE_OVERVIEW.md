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
| `intellij://ide/pull-up-members` | Pull Up Members | Move members to a base class |
| `intellij://ide/push-down-members` | Push Down Members | Move members to subclasses |
| `intellij://ide/extract-interface` | Extract Interface | Create an interface from a class |
| `intellij://ide/move-class` | Move Class / Package | Move classes between packages |

### Code Hygiene

| Resource | Operation | Description |
|----------|-----------|-------------|
| `intellij://ide/optimize-imports` | Optimize Imports | Remove unused imports and sort remaining ones |
| `intellij://ide/inspect-and-fix` | Inspection + Fix | Run an inspection and apply a quick fix |
| `intellij://ide/inspection-summary` | Inspection Summary | List enabled inspections in the project |

### Navigation and Generation

| Resource | Operation | Description |
|----------|-----------|-------------|
| `intellij://ide/generate-override` | Generate Overrides | Implement interface methods / override base methods |
| `intellij://ide/hierarchy-search` | Hierarchy Search | Find inheritors and overrides for a class/method |
| `intellij://ide/call-hierarchy` | Call Hierarchy | Find method callers |
| `intellij://ide/generate-constructor` | Generate Constructor | Create constructors from fields |

### Project Intelligence

| Resource | Operation | Description |
|----------|-----------|-------------|
| `intellij://ide/project-dependencies` | Project Dependencies | Summarize module dependencies |
| `intellij://ide/project-search` | Project Search (Index) | Search files by name or file type |
| `intellij://ide/run-configuration` | Run Configuration | List and execute run configs |

## Usage

1. Read a specific example resource to get the complete code snippet.
2. Adapt the code to your needs (file paths, positions, names).
3. Execute via `steroid_execute_code`.

## Notes

- Many examples support `dryRun` to preview changes safely.
- For write operations, use `dryRun=true` first, then set to `false` to apply.
- Always call `waitForSmartMode()` before using indices or PSI.
