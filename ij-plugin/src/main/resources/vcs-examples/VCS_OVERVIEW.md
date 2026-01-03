# VCS Operations in IntelliJ Platform

This collection provides example code snippets for version control operations using IntelliJ Platform APIs.
Each example is a complete script for `steroid_execute_code`.

## Available Examples

### Git Annotations (Blame)

| Resource | Description |
|----------|-------------|
| `intellij://vcs/git-annotations` | Get blame/annotations for a file (who changed what, when) |

### Git History

| Resource | Description |
|----------|-------------|
| `intellij://vcs/git-history` | Get commit history for a file |
| `intellij://vcs/git-log` | Get detailed commit log with messages and diffs |

## Usage

1. Read a specific example resource to get the complete code snippet
2. Adapt the code to your needs (file paths, etc.)
3. Execute via `steroid_execute_code`

## Key IntelliJ VCS Concepts

- **ProjectLevelVcsManager**: Entry point for VCS operations on a project
- **VcsRoot**: A directory mapped to a VCS (e.g., Git repository root)
- **AbstractVcs**: Base class for VCS implementations (Git, SVN, etc.)
- **AnnotationProvider**: Interface for getting line-by-line blame information
- **FileAnnotation**: Contains blame data for each line (revision, author, date)
- **VcsHistoryProvider**: Interface for getting file history
- **VcsHistorySession**: Contains list of historical revisions

## Common Patterns

### Get VCS for a File
```kotlin
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val vcs = vcsManager.getVcsFor(virtualFile)
```

### Check if File is Under VCS
```kotlin
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val isVersioned = vcsManager.getVcsFor(virtualFile) != null
```

### Get VCS Root
```kotlin
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val root = vcsManager.getVcsRootFor(virtualFile)
```

## Git-Specific Notes

- Git operations are provided by the Git4Idea plugin
- Most operations require the file to be in a Git repository
- Some operations (like annotations) may need to fetch data from Git

## Threading Considerations

- VCS operations can be slow (especially for large files or remote repos)
- Many VCS operations should NOT be called from EDT
- Use background threads with `runBackgroundableTask` for long operations
- Read actions may still be needed for PSI access
