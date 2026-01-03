/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vision

import com.jonnyzzz.intellij.mcp.ocr.OcrLevel
import com.jonnyzzz.intellij.mcp.ocr.OcrProcessClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides OCR text extraction metadata for screenshots.
 *
 * Uses the bundled Tesseract OCR engine to extract text from the screenshot.
 * Outputs a markdown file with detected text blocks and their positions.
 */
class OcrMetadataProvider : ScreenshotMetadataProvider {

    override val type: String = TYPE

    override suspend fun provide(context: ScreenCaptureContext): ProviderResult {
        val ocrClient = OcrProcessClient.getInstance()

        // Check if OCR is available
        if (!ocrClient.isAvailable()) {
            return ProviderResult.Skip
        }

        // OCR needs the image file - construct path from execution dir
        val imagePath = context.executionDir.resolve(IMAGE_FILE_NAME)
        if (!java.nio.file.Files.exists(imagePath)) {
            // Image not yet written - depend on other providers
            return ProviderResult.DependsOnOthers
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                ocrClient.extractText(imagePath, language = "eng", level = OcrLevel.TEXT_LINE)
            }

            val content = buildOcrMarkdown(result)
            ProviderResult.Success(
                ScreenshotMetadata(
                    type = TYPE,
                    fileName = FILE_NAME,
                    content = content,
                    mimeType = "text/markdown",
                )
            )
        } catch (e: Exception) {
            // OCR failed - skip rather than fail the entire capture
            ProviderResult.Skip
        }
    }

    private fun buildOcrMarkdown(result: com.jonnyzzz.intellij.mcp.ocr.OcrResult): String {
        val builder = StringBuilder()
        builder.appendLine("# OCR Results")
        builder.appendLine()

        if (result.blocks.isEmpty()) {
            builder.appendLine("No text detected in the screenshot.")
            return builder.toString()
        }

        builder.appendLine("Detected ${result.blocks.size} text block(s):")
        builder.appendLine()

        for ((index, block) in result.blocks.withIndex()) {
            val bounds = block.bounds
            builder.appendLine("## Block ${index + 1}")
            builder.appendLine("- Position: (${bounds.x}, ${bounds.y})")
            builder.appendLine("- Size: ${bounds.width}x${bounds.height}")
            builder.appendLine("- Text: \"${block.text}\"")
            builder.appendLine()
        }

        return builder.toString()
    }

    companion object {
        const val TYPE = "ocr"
        const val FILE_NAME = "screenshot-ocr.md"
        const val IMAGE_FILE_NAME = "screenshot.png"
    }
}
