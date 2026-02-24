
---

## ARCHITECTURE: When the Problem Names a Specific Class — CREATE It as a Separate @Component

Do NOT inline the logic into an existing service. The arena evaluates structural conformance:
- **NEW @Component file** → arena PASS (matches reference patch)
- **Inlined private method** → FAIL_TO_PASS tests may pass but arena exits code 1

Check test imports to confirm: if a test imports `PasswordValidator`, a separate class is required:
