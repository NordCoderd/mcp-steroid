# Prompts Module — Writing & Editing Prompt Files

This guide explains the prompt file format, build-time processing, and IDE-conditional content system.

## File Format

All prompt `.md` files live under `prompts/src/main/prompts/`. They are organized into folders
that map to MCP resource URI prefixes (e.g., `ide/` -> `mcp-steroid://ide/...`).

### Article Format (New-Format `.md`)

Every article `.md` file follows this structure:

```
Title                       ← line 1: plain text, ≤80 chars, no # prefix
                            ← line 2: blank
Short description           ← line 3: plain text, ≤200 chars, no # prefix
                            ← line 4: blank
...body content...          ← lines 5+: markdown with ```kotlin``` blocks

# See also                  ← optional section (must be preceded by blank line)

- [Link](mcp-steroid://...)
```

**Validation rules** (enforced by `MarkdownArticleContractTest`):
- Title (line 1): non-empty, ≤80 chars, no `#` prefix
- Line 2: must be blank
- Description (line 3): non-empty, ≤200 chars, no `#` prefix
- Line 4: must be blank
- No bare Kotlin/Java code outside ` ```kotlin``` ` or ` ```text``` ` fences

### Skill Files

Skill files (in `skill/`) can have YAML frontmatter after the header:

```
Skill Title

Skill description

---
name: my-skill-name
description: Skill description for MCP prompt listing.
---

# Skill Content
...
```

## Build Pipeline

1. **prompt-generator** parses each `.md` file via `parseNewFormatArticleParts()`
2. Content is split into interleaved markdown/kotlin parts
3. Each part is encoded into a generated Kotlin class (content obfuscated with random factors)
4. A **payload class** assembles all parts at runtime via `readPromptInternal(context)`
5. An **article class** wraps payload + description + see-also
6. **Compilation tests** are generated for each ` ```kotlin``` ` block

The `prompts-api` module contains shared base types (`PromptBase`, `ArticleBase`, `IdeFilter`,
`PromptsContext`, `FilteredPart`) used by both prompt-generator and the runtime.

## Kotlin Code Blocks

Code inside ` ```kotlin``` ` fences is:
- Extracted at build time and compiled against the IDE classpath with `-Werror`
- Wrapped by `CodeButcher` with default imports (no need to import `readAction`, `writeAction`, `EDT`, etc.)
- Served to MCP clients as executable examples

If code doesn't compile (pseudo-code, undefined vars, plugin-specific APIs not on classpath),
use ` ```text``` ` fences instead — text blocks are treated as plain markdown and not compiled.

## IDE-Conditional Content

### Fence-Level Targeting

Annotate kotlin fences with product codes and/or version constraints:

```
 ```kotlin              ← no annotation: compiled against all IDEs (IDEA, Rider, CLion)
 ```kotlin[RD]          ← Rider only
 ```kotlin[IU,RD]       ← IDEA and Rider
 ```kotlin[RD;>=254]    ← Rider version 254+
 ```kotlin[;>=253]      ← all IDEs, version 253+
```

Valid product codes: `IU` (IDEA), `RD` (Rider), `CL` (CLion), `GO` (GoLand),
`PY` (PyCharm), `WS` (WebStorm), `RM` (RubyMine), `DB` (DataGrip).

Annotated blocks generate per-IDE compilation tests only for matching IDEs.
At runtime, annotated blocks are included only when the IDE matches.

### Conditional Sections

Use `###_IF_IDE[...]_###` directives for IDE-specific markdown sections:

```
###_IF_IDE[RD]_###
Rider-specific instructions here.
###_ELSE_###
Instructions for all other IDEs.
###_END_IF_###
```

Full syntax supports `###_ELSE_IF_IDE[...]_###`:

```
###_IF_IDE[RD]_###
Rider content
###_ELSE_IF_IDE[CL]_###
CLion content
###_ELSE_###
Everyone else
###_END_IF_###
```

Conditionals must be in the **body** (lines 5+), never in the title or description.

At build time, conditionals are parsed into `ContentPart` items with `IdeFilter` predicates.
The generated payload class checks each part's filter at runtime against `PromptsContext`.
When `context` is null (test reads, no-arg `readPrompt()`), all content is included.

### Migration Note

The old `###_IF_RIDER_###` syntax is still supported during migration but new files
should use the `###_IF_IDE[RD]_###` form.

## Special Directives

- `###_NO_AUTO_TOC_###` or `###_EXCLUDE_FROM_AUTO_TOC_###` — excludes the article from
  the auto-generated folder TOC and from sibling see-also links. Place on its own line in the body.

## Testing

Build-time tests (generated into `prompts/build/generated/kotlin-test/`):

| Test | What it verifies |
|------|-----------------|
| `{Stem}PromptTest` | `readPrompt()` output matches source file content |
| `{Stem}PromptArticleReadTest` | payload, description, seeAlso are non-empty and readable |
| `{Stem}KtBlocksCompilationTest` | each ` ```kotlin``` ` block compiles against IDE classpath |
| `MarkdownArticleContractTest` | title/description format, no bare code outside fences |

KtBlock compilation tests run against up to 3 IDE distributions:
- **IDEA** (`compileKtBlockOnIdea`) — system property `mcp.steroid.ide.home`
- **Rider** (`compileKtBlockOnRider`) — system property `mcp.steroid.rider.home`
- **CLion** (`compileKtBlockOnClion`) — system property `mcp.steroid.clion.home`

If an IDE distribution is not available, the corresponding test skips gracefully.

## Adding a New Prompt

1. Create `prompts/src/main/prompts/{folder}/{name}.md` following the article format
2. Add ` ```kotlin``` ` blocks for executable examples
3. Use ` ```text``` ` for non-compilable code
4. Use `###_IF_IDE[...]_###` for IDE-specific sections
5. Run `./gradlew :prompt-generator:test` to verify parsing
6. Run `./gradlew :prompts:compileKotlin` to verify generated code compiles
7. Run `./gradlew :prompts:test` for full test suite (requires IDE downloads)

## Key Source Files

| File | Purpose |
|------|---------|
| `prompt-generator/.../generateArticleClazz.kt` | Article parsing, payload class generation |
| `prompt-generator/.../generatePromptClazzTest.kt` | Test generation, `extractKotlinBlocks` |
| `prompt-generator/.../FenceMetadata.kt` | ` ```kotlin[...]` ` annotation parsing |
| `prompt-generator/.../ContentPart.kt` | Conditional directive parsing, `IdeFilter` mapping |
| `prompts-api/.../PromptFactory.kt` | `PromptBase`, `ArticleBase` base classes |
| `prompts-api/.../IdeFilter.kt` | Composable filter predicates |
| `prompts-api/.../PromptsContext.kt` | Runtime IDE context |
| `prompts/src/test/.../KtBlockCompilationTestBase.kt` | Per-IDE compilation test infrastructure |
| `prompts/src/test/.../MarkdownArticleContractTest.kt` | Format contract validation |
