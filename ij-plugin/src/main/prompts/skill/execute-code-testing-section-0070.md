
---

## Verify @ControllerAdvice / @ExceptionHandler Exists

CRITICAL before writing controllers that throw custom exceptions — if no global handler exists, the API returns 500 instead of 404, breaking tests:
