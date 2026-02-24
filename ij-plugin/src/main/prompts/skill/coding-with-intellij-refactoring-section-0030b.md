---

## Refactoring: Find All Call Sites Before Adding Parameters

When adding a new field or parameter to a record, command, or DTO class, use `ReferencesSearch`
to locate every call site **before** editing. This prevents compile errors from undiscovered
constructors or factory calls in other files.
