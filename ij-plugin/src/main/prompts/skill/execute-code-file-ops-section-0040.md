
**⚠️ `findProjectFile()` pitfall for resource files**: requires the **FULL relative path** from the project root (e.g., `"src/main/resources/application.properties"`). Calling it with just a filename **always returns null** — causing NPE on `!!`. For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName()` which searches by filename:
