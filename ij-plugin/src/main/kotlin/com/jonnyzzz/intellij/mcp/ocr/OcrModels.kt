/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr

import kotlinx.serialization.Serializable

@Serializable
data class OcrRect(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class OcrTextBlock(
    val text: String,
    val bounds: OcrRect,
)

@Serializable
data class OcrResult(
    val blocks: List<OcrTextBlock>,
)

enum class OcrLevel {
    TEXT_LINE,
    WORD,
}
