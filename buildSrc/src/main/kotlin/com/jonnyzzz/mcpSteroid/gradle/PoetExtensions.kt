/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import com.squareup.kotlinpoet.CodeBlock

fun CodeBlock.Builder.controlFlow(
    controlFlow: String,
    vararg args: Any?,
    ƒ: CodeBlock.Builder.() -> Unit
): CodeBlock.Builder {
    beginControlFlow(controlFlow, *args)
    ƒ()
    endControlFlow()
    return this
}


