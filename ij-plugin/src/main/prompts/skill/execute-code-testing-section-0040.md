
---

## ⚠️ FALLBACK: ProcessBuilder("./mvnw") — Use ONLY When pom.xml Was Just Modified

After pom.xml changes, IntelliJ triggers a Maven re-import dialog that blocks the IDE runner latch for up to 600s:
