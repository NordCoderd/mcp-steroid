/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
// Safe pattern for Java source with .class refs and dollar signs:
java.io.File("${project.basePath}/src/main/java/com/example/SecurityConfig.java").writeText(
    "package com.example;\n" +
    "import" + " org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n" +
    "public class SecurityConfig {\n" +
    "    public void configure(HttpSecurity http) throws Exception {\n" +
    "        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);\n" +
    "    }\n" +
    "}"
)
// For dollar signs in Java string literals: use "${'\$'}Bearer" (produces literal dollar-Bearer in the output)
