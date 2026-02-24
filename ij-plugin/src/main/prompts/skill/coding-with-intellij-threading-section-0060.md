
**Good news**: `waitForSmartMode()` is called automatically before your script starts!

### Modal Dialogs and ModalityState

When a modal dialog is open in the IDE, the default EDT dispatcher (`Dispatchers.EDT`) will
**not execute** your code — it waits until the dialog is dismissed. To interact with the IDE
while a modal dialog is present, use `ModalityState.any()`:
