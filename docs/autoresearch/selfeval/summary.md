# Autoresearch iteration summary

Running tally of Claude's self-eval report across prompt-tuning iterations.

Metric legend:
- **(a)** tasks where MCP Steroid adds capability (§1 bullet count)
- **(b)** tasks where MCP Steroid applies but offers no clear improvement (§1 bullet count)
- **verdict**: count of recognizable `**Verdict**` lines the agent emitted
- **wall**: wall-clock of the `testSerenaSelfEvalPrompt` run (mm:ss)

| iter | commit   | (a) | (b) | verdict | wall  | edit made this round                                             |
|------|----------|-----|-----|---------|-------|------------------------------------------------------------------|
| 00   | 534c008c | 6   | 3   | 32      | 20:31 | — baseline                                                       |
| 01   | ac8a0182 | 5   | ~2  | 24      | 12:58 | route 1–3 line edits + single-file-private-rename to built-ins  |
| 02   | ed24e475 | 5   | 2   | 21      | 12:39 | replace lsp/rename.md regex recipe with semantic RenameProcessor |
| 03   | **reverted** | — | — | — | — | mcp-steroid-info.md scope split — policy reversal: keep "Use IntelliJ aggressively" messaging, do not steer agents toward built-in Edit. See iter-03' below. |
| 03'  | (next)   | —   | —   | —       | —     | Remove iter-01 Edit-routing rows; promote VfsUtil.saveText compact recipe as primary in-place-edit path |

Notes:
- iter-03 ba1dbaf3 attempted to reclassify small edits as "out of scope" for MCP Steroid
  via mcp-steroid-info.md. Reverted on policy: MCP Steroid should stay the default for all
  edits because the IDE VFS is authoritative; the right long-term fix is a multi-site
  apply-patch recipe (parked task #4), not steering the agent off toward built-in Edit.
- KtBlocks green through iter-02: lsp/rename RenameProcessor recipe compiles on all IDE targets.
- Test assertion loosened once at iter-01 start (accept `**Verdict (3.1):**` etc.).
