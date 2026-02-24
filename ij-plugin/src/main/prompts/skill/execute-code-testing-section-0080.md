
---

## Inspection Signal Semantics — Do NOT Misclassify

- **`[ConstantValue] Value ... is always 'null'`** on a DTO accessor in a test file → **CRITICAL BUG**: the DTO record is missing that component field. Do NOT dismiss as "pre-existing static analysis noise".
  - **`#ref` and `#loc` in ConstantValue output**: unresolved IntelliJ template placeholders. Look for DTO/record accessor calls in the test file that do NOT match any declared record component. Add the missing component.
- **`[ClassCanBeRecord]`** on a new DTO class → **REQUIRED**: convert to Java record (reference solution uses records).
- **`[ClassEscapesItsScope]`** on a `public` inner class inside Spring `@Service`/`@Repository` → **Expected**: safe to ignore.
- **`[GrazieInspectionRunner]`**, **`[DeprecatedIsStillUsed]`** → **Cosmetic**: low priority.

---

## Verification Gate — Run FAIL_TO_PASS Tests Before Marking Work Complete

```
./mvnw test -Dtest=ClassName -Dspotless.check.skip=true
# OR
./gradlew :module:test --tests "com.example.ClassName" --rerun-tasks --no-daemon
```

**⚠️ Compile success alone is NOT sufficient.**

**⚠️ FULL SUITE before `ARENA_FIX_APPLIED: yes`**: After FAIL_TO_PASS tests pass, ALSO run the complete test suite to catch regressions in other test classes.

**⚠️ Deprecation warnings ≠ errors**: Compiler output like `warning: 'getVirtualFilesByName(...)' is deprecated` is non-fatal — the script succeeded. Only retry on explicit `ERROR` responses with no `execution_id`.
