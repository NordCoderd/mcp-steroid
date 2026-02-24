
**⚠️ PREFER VFS write over native Edit tool for pom.xml**: using `writeAction { VfsUtil.saveText(vf, ...) }` triggers IntelliJ's file change notification immediately, making the Maven auto-import more reliable than the native Edit tool (which writes directly to disk, bypassing VFS).

---

## Trigger Maven Re-Import After Editing pom.xml AND Await Completion

```kotlin
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec  // ← correct package for IU-253+; NOT .project.MavenSyncSpec
import com.intellij.platform.backend.observation.Observation
val manager = MavenProjectsManager.getInstance(project)
manager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("post-pom-edit", false))
Observation.awaitConfiguration(project)  // suspends until sync + indexing fully complete
println("Maven sync complete — new deps resolved, safe to compile/inspect")
// ⚠️ If MavenSyncSpec cannot be resolved, fall back to:
//   ProcessBuilder("./mvnw", "dependency:resolve").directory(java.io.File(project.basePath!!)).start().waitFor()
```
