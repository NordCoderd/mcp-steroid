/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem

// ALWAYS use writeAction + VFS to create new files — NOT native Write tool.
// VFS creates index immediately; native Write bypasses it.
// Use triple-quoted strings for Java code with .class refs and $ signs:
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val secDir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/eval/sample/security")!!
    // Create JwtService.java
    val jwtService = secDir.findChild("JwtService.java") ?: secDir.createChildData(this, "JwtService.java")
    VfsUtil.saveText(jwtService, """
        package eval.sample.security;

        import io.jsonwebtoken.Jwts;
        import io.jsonwebtoken.security.Keys;
        import org.springframework.stereotype.Service;
        import java.util.Date;

        @Service
        public class JwtService {
            private static final String SECRET = "your-secret-key-here-must-be-at-least-256-bits";

            public String generateToken(String username) {
                return Jwts.builder()
                    .subject(username)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 86400000))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                    .compact();
            }
        }
    """.trimIndent())
    println("Created JwtService.java")
    // Create more files similarly...
}
// After creating files, trigger re-indexing for compile checks:
waitForSmartMode()
