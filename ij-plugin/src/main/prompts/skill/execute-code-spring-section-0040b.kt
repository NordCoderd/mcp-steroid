/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val pomFile = findProjectFile("pom.xml")!!
val content = VfsUtil.loadText(pomFile)
val newDep = "\n    <dependency>" +
    "\n        <groupId>io.jsonwebtoken</groupId>" +
    "\n        <artifactId>jjwt-api</artifactId>" +
    "\n        <version>0.12.6</version>" +
    "\n    </dependency>"
val updated = content.replace("</dependencies>", "$newDep\n  </dependencies>")
check(updated != content) { "replace matched nothing — check pom.xml structure (missing </dependencies>?)" }
writeAction { VfsUtil.saveText(pomFile, updated) }
println("pom.xml updated — run Maven sync next")
