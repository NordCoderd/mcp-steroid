
### Script is a Coroutine

The script body runs as a **suspend function**. This means:

- Use coroutine APIs directly (no `runBlocking` needed)
- Call suspend functions without special wrappers
- Use `delay()` instead of `Thread.sleep()`
