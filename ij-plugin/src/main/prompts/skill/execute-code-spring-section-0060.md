
**⚠️ After editing pom.xml (with native Edit tool or writeAction): ALWAYS run the Maven sync above BEFORE running `runInspectionsDirectly` or `./mvnw`** — without sync, newly added imports show "cannot resolve symbol" false-positive errors from undownloaded deps.

---

## ClassCanBeRecord — Convert to Java Record (NOT Optional)

When a `ClassCanBeRecord` inspection fires on a newly created DTO, you MUST convert it:
