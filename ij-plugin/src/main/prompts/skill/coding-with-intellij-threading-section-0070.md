
**When to use `ModalityState.any()`:**
- Enumerating open windows or dialogs while a modal is present
- Taking screenshots when a dialog is blocking the IDE
- Closing modal dialogs programmatically (e.g., `dialog.close(...)`)
- Any EDT work that must run regardless of modal state

**When NOT to use it:**
- Normal UI operations — use plain `Dispatchers.EDT` instead
- Read/write actions — use `readAction { }` / `writeAction { }` instead

**Detecting modal dialogs:**
