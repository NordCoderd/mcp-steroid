
**⚠️ Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only checks the single file you pass. After modifying a widely-used class (DTO, command, entity), also check dependent files or run `./mvnw test-compile` for project-wide verification.

**⚠️ Inspect MODIFIED files too** — not just newly created ones. After adding methods to an existing file (e.g., `findByFeature_Code` to a Spring Data repository), run `runInspectionsDirectly` on that file immediately. Spring Data JPA derived query names throw `QueryCreationException` at Spring context startup — the Spring Data plugin inspection catches these in ~5s, before `./mvnw test` (~90s).

**`runInspectionsDirectly` also catches Spring issues**: Duplicate `@Bean` definitions, missing `@Component` annotations, unresolved `@Autowired` dependencies. Run it on your `@Configuration` classes BEFORE `./mvnw test` to catch Spring bean override exceptions early.

---

## ⭐ PRIMARY: Maven IDE Runner — Structured Pass/Fail, No Token Overflow

Use this for Maven test execution when pom.xml was NOT modified in this session. Always pass `dialog_killer: true` on the `steroid_execute_code` call to auto-dismiss modals:

```kotlin
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
val mavenResult = CompletableDeferred<Boolean>()
project.messageBus.connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(suite: SMTestProxy.SMRootTestProxy) { mavenResult.complete(suite.isPassed) }
    override fun onTestingStarted(s: SMTestProxy.SMRootTestProxy) {}
    override fun onTestNodeAdded(r: com.intellij.execution.testframework.sm.runner.SMTestRunnerResultsForm, t: SMTestProxy) {}
    override fun onTestingFinished(r: com.intellij.execution.testframework.sm.runner.SMTestRunnerResultsForm) {}
    override fun onCustomProgressTestsCategory(c: String?, n: Int) {}
    override fun onCustomProgressTestStarted() {}; override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(t: SMTestProxy) {}; override fun onSuiteTreeStarted(s: SMTestProxy) {}
})
MavenRunConfigurationType.runConfiguration(project,
    MavenRunnerParameters(true, project.basePath!!, "pom.xml",
        listOf("test", "-Dtest=FeatureReactionServiceTest", "-Dspotless.check.skip=true"), emptyList()),
    null, null) {}
val mvnPassed = withTimeout(5.minutes) { mavenResult.await() }
println("Maven IDE runner: passed=$mvnPassed")
// If modal dialog blocks: steroid_take_screenshot → steroid_input dismiss → retry. Do NOT fall back to ProcessBuilder.
```
