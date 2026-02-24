
**⚠️ AVOID range-based VFS writes**: Never use hardcoded byte ranges when writing files (e.g., `setBinaryContent(bytes, 0, 2000)` when the file may be shorter). This causes `StringIndexOutOfBoundsException` when the range exceeds file length. Always use `VfsUtil.saveText(file, content)` for full-file replacement — it atomically replaces the entire content regardless of existing file size.

**⚠️ Import-in-strings pitfall**: The script preprocessor extracts `import foo.Bar;` lines from the top level of your script — including lines inside triple-quoted strings. This causes compilation failures (e.g., `unresolved reference 'jakarta'`) when you embed Java source in a `"""..."""` literal.

**⚠️ Char-literal pitfall in string-assembled Java**: When building Java source via Kotlin `joinToString()`, char literals like `'\''` cause silent escaping errors. The Kotlin string `"'\\''"` produces Java text `'\''` which is a Java syntax error (empty char literal followed by spurious `'`). For Java code containing char literals (e.g., `toString()` with `', '` separators), prefer `java.io.File.writeText()` with triple-quoted raw strings, or use `PsiFileFactory.createFileFromText()`:
