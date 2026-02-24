
This applies to ALL suspend context APIs: `readAction { }`, `writeAction { }`, `smartReadAction { }`, `waitForSmartMode()`, `runInspectionsDirectly()`, `findPsiFile()`, `findProjectPsiFile()`.

### Automatic Smart Mode

`waitForSmartMode()` is called **automatically before your script starts**. You only need to call it again if you trigger indexing mid-script.
