# Release Notes Files

Release notes are stored as versioned files:

- `release/notes/<version>.md`

Examples:

- `release/notes/0.88.0.md`
- `release/notes/0.89.0.md`

`release/scripts/run-release.sh` generates/reviews this file via agent runs.
In non-dry-run mode, the notes file is committed as part of the release flow.
