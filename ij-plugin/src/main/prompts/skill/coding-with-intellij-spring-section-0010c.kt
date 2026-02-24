/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.vfs.VfsUtil

// Read → inject new dependencies → write via VFS (DO NOT use native Write tool)
val pomFile = findProjectFile("pom.xml")!!
val content = VfsUtil.loadText(pomFile)
val jjwtDeps = """
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>"""
val updated = content.replace("</dependencies>", "$jjwtDeps\n  </dependencies>")
check(updated != content) { "replace matched nothing — check pom.xml </dependencies> tag" }
writeAction { VfsUtil.saveText(pomFile, updated) }
println("pom.xml updated")
// Then trigger Maven sync (next step) before inspecting or compiling
