
⚠️ **Common mistake**: `data.TEST_OBJECT = "class"` or `data.TEST_CLASS` as property on a supertype → compile error
`"unresolved reference 'TEST_CLASS'"`. Always use `JUnitConfiguration.TEST_CLASS` (the static constant).

**Alternative — run via Maven wrapper (simpler, no IDE runner needed):**
