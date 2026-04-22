# iter-09 plan (final)

## iter-08 signals

- Self-eval 23:19 ✅ + validation 3:37 ✅.
- §1 (a) = 6 items (iter-07's 8-item expansion contracted; items more cohesive now).
- §1 (b) = 2 items (small text edits, single-file unique rename).
- §9 still routes small edits to Edit — the iter-08 payload-accounting clarification
  shifted the agent's token math but not the ergonomic verdict. The agent now frames
  it as "Edit is more direct / lower-overhead" rather than "cheaper per token".
- §1 (a) item 6: "Batched multi-edit: Multiple edits within one file (or across
  files) can be executed in a single `steroid_execute_code` call." — this item
  was asserted but never quantified with a concrete amortization count.

## Final-iteration gap

Small-edit ergonomic parity is structural: writing Kotlin to do a 1-line edit has a
unavoidable syntactic floor that `Edit(old, new)` doesn't have. The right lever is
**amortization over N substitutions** — where one `steroid_execute_code` with chained
`.replace(...)` calls beats N rounds of `Edit`. iter-08 §1 (a) named "batched
multi-edit" as a capability but neither §4 nor §9 quantified it, because the agent
didn't have a concrete recipe anchoring the claim.

## iter-09 edit

Add a "Batch multiple edits into one call" paragraph plus recipe to
`skill/execute-code-tool-description.md`:

```kotlin
val vf = findProjectFile("…")!!
val content = String(vf.contentsToByteArray(), vf.charset)
val updated = content
    .replace("old_import_1", "new_import_1")
    .replace("legacyMethodName(", "newMethodName(")
    .replace("DEPRECATED_CONST", "CURRENT_CONST")
    .let { Regex("""logger\.warn\(""").replace(it, "logger.info(") }
check(updated != content) { "no substitutions matched — verify patterns" }
writeAction { VfsUtil.saveText(vf, updated) }
```

Names the explicit arithmetic: "5 changes via native Edit = 5 round-trips ×
(pre-Read + Edit) ≈ 10 tool calls. The recipe above is still one call." Plus a
multi-file variant (N files in 1 call vs 2N).

## Expected iter-09 report change

- §1 (a) "Batched multi-edit" item should promote to a top-tier bullet with a
  concrete 10× amortization number.
- §4 Token-efficiency table should grow a row for "5 edits in one file" with a
  ~10× advantage for MCP Steroid.
- §9 decision rule may finally admit an MCP Steroid-preferred row for
  "≥2 edits in the same file" even when the individual edits are small.
- §1 (b) "Small text edits" survives as a single-edit case only — narrower scope.
