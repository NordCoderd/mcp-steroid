
**⚠️ Use this BEFORE running `./gradlew test` after bulk file creation** — if any file has a package mismatch, typo, or missing import, this surfaces it in ~10s instead of waiting ~90s for the full test compile.

---

## ⚡ Fast Compile Check via runInspectionsDirectly (~5s vs ~90s for mvnw test-compile)

Run this BEFORE `./mvnw` to catch errors early:
