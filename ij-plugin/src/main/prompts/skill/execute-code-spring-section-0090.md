
**⚠️ If `./gradlew test --tests ClassName` returns "No tests found"**: the class is in a submodule.
Add the subproject prefix (e.g. `:microservices:product-composite-service:test`).

---

## Maven Generated Sources — When a Class Exists in PSI But Has No Source File

In Maven projects with OpenAPI generator or annotation processors, DTO classes and API interfaces are generated into `target/generated-sources/`. They are **visible in IntelliJ's PSI index** but have **NO file in `src/`** — `FilenameIndex.getVirtualFilesByName("UserDto.java", ...)` returns empty.

**STOP after 2 failed filename lookups**: if the class is not found by filename, switch to PSI class lookup.
