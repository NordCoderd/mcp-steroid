
### Get VCS Root

```kotlin
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val root = vcsManager.getVcsRootFor(virtualFile)
```
