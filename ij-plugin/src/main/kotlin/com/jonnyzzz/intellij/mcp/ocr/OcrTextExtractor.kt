/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr

import com.intellij.openapi.diagnostic.Logger
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import javax.imageio.ImageIO

data class OcrRect(val x: Int, val y: Int, val width: Int, val height: Int)

data class OcrTextBlock(
    val text: String,
    val bounds: OcrRect,
)

data class OcrResult(
    val blocks: List<OcrTextBlock>,
)

enum class OcrLevel {
    TEXT_LINE,
    WORD,
}

object OcrTextExtractor {
    private val log = Logger.getInstance(OcrTextExtractor::class.java)

    fun extractText(imagePath: Path, language: String = "eng", level: OcrLevel = OcrLevel.TEXT_LINE): OcrResult {
        val image = ImageIO.read(imagePath.toFile())
            ?: throw IllegalArgumentException("Unable to read OCR image: $imagePath")
        return extractText(image, language, level)
    }

    fun extractText(image: BufferedImage, language: String = "eng", level: OcrLevel = OcrLevel.TEXT_LINE): OcrResult {
        val loader = OcrNativeLoader.ensureLoaded()
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            val tesseract = newTesseract(loader, language)
            val words = invokeGetWords(loader, tesseract, image, level)
            val blocks = words.mapNotNull { word ->
                val block = toTextBlock(loader, word)
                if (block.text.isBlank()) null else block
            }
            return OcrResult(blocks)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            log.warn("OCR extraction failed: ${cause.message}", cause)
            throw IllegalStateException("OCR extraction failed: ${cause.message}", cause)
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun newTesseract(loader: ClassLoader, language: String): Any {
        val tesseractClass = loader.loadClass("net.sourceforge.tess4j.Tesseract")
        val tesseract = tesseractClass.getConstructor().newInstance()

        val tessdataDir = OcrNativeLoader.ensureTessdataDir(loader)
        tesseractClass.getMethod("setDatapath", String::class.java)
            .invoke(tesseract, tessdataDir.toAbsolutePath().toString())
        tesseractClass.getMethod("setLanguage", String::class.java)
            .invoke(tesseract, language)
        tesseractClass.getMethod("setVariable", String::class.java, String::class.java)
            .invoke(tesseract, "user_defined_dpi", "300")

        val pageSegMode = loadIntConstant(
            loader,
            "net.sourceforge.tess4j.ITessAPI\$TessPageSegMode",
            "PSM_AUTO",
        )
        tesseractClass.getMethod("setPageSegMode", Int::class.javaPrimitiveType)
            .invoke(tesseract, pageSegMode)

        return tesseract
    }

    private fun invokeGetWords(
        loader: ClassLoader,
        tesseract: Any,
        image: BufferedImage,
        level: OcrLevel,
    ): List<Any> {
        val levelValue = loadIntConstant(
            loader,
            "net.sourceforge.tess4j.ITessAPI\$TessPageIteratorLevel",
            when (level) {
                OcrLevel.TEXT_LINE -> "RIL_TEXTLINE"
                OcrLevel.WORD -> "RIL_WORD"
            }
        )

        val tesseractClass = tesseract.javaClass
        val getWords = tesseractClass.getMethod("getWords", java.util.List::class.java, Int::class.javaPrimitiveType)
        @Suppress("UNCHECKED_CAST")
        return getWords.invoke(tesseract, listOf(image), levelValue) as? List<Any> ?: emptyList()
    }

    private fun loadIntConstant(loader: ClassLoader, className: String, fieldName: String): Int {
        val clazz = loader.loadClass(className)
        return clazz.getField(fieldName).getInt(null)
    }

    private fun toTextBlock(loader: ClassLoader, word: Any): OcrTextBlock {
        val wordClass = loader.loadClass("net.sourceforge.tess4j.Word")
        val text = wordClass.getMethod("getText").invoke(word) as? String ?: ""
        val rectangle = wordClass.getMethod("getBoundingBox").invoke(word) as Rectangle
        return OcrTextBlock(
            text = text.trim(),
            bounds = OcrRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height)
        )
    }
}
