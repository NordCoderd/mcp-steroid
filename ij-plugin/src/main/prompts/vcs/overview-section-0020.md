
### Check if File is Under VCS

```kotlin
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val isVersioned = vcsManager.getVcsFor(virtualFile) != null
```
