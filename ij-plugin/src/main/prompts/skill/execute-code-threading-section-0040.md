
---

## ⚠️ Import-in-Strings Pitfall

Never put `import foo.Bar;` at the start of a line inside a triple-quoted Kotlin string. The script preprocessor extracts those lines as Kotlin imports, causing compile errors. Use `"import" + " foo.Bar;"` or `joinToString` to build the content, or use `java.io.File(path).writeText(content)` as an alternative.

---

## ⚠️ Generating Java Code Inline — `.class` and Dollar-Sign Pitfalls

Java code often contains `.class` references and dollar-sign characters. In double-quoted Kotlin strings, `.class)` can be mis-parsed and a bare dollar sign triggers string interpolation. Use `java.io.File(path).writeText()` with string concatenation to avoid both pitfalls:
