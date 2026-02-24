/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
val text = VfsUtil.loadText(findProjectFile("src/main/resources/application.properties")!!)
println(text)
