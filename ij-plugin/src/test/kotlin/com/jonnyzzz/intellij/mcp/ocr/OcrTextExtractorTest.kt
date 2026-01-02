/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds

class OcrTextExtractorTest : BasePlatformTestCase() {

    fun testExtractsHelloOcrText(): Unit = timeoutRunBlocking(90.seconds) {
        val image = loadImage("hello-ocr.png")
        val result = OcrTextExtractor.extractText(image)
        assertContainsTokens(result, "HELLO", "OCR")
    }

    fun testExtractsMultiLineText(): Unit = timeoutRunBlocking(90.seconds) {
        val image = loadImage("multi-line.png")
        val result = OcrTextExtractor.extractText(image)
        assertContainsTokens(result, "FIRST", "LINE", "SECOND")
    }

    fun testExtractsNumbers(): Unit = timeoutRunBlocking(90.seconds) {
        val image = loadImage("numbers.png")
        val result = OcrTextExtractor.extractText(image)
        assertContainsTokens(result, "12345", "TEST")
    }

    private fun loadImage(name: String) = requireNotNull(javaClass.getResource("/ocr/$name")).let { url ->
        ImageIO.read(url) ?: error("Unable to read OCR test image: $name")
    }

    private fun assertContainsTokens(result: OcrResult, vararg tokens: String) {
        val text = result.blocks.joinToString(" ") { it.text }
        val normalized = normalize(text)
        for (token in tokens) {
            assertTrue("Expected OCR output to contain '$token' in: $normalized", normalized.contains(token))
        }
    }

    private fun normalize(text: String): String {
        val builder = StringBuilder()
        for (ch in text.uppercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) {
                builder.append(ch)
            } else {
                builder.append(' ')
            }
        }
        return builder.toString().replace("  ", " ").trim()
    }
}
