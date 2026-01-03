/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.ocr.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.Word
import org.bytedeco.javacpp.Loader
import org.bytedeco.leptonica.global.leptonica
import org.bytedeco.tesseract.global.tesseract
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.system.exitProcess

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

private data class OcrOptions(
    val imagePath: Path,
    val language: String,
    val level: OcrLevel,
)

private val json = Json { encodeDefaults = true }

fun main(args: Array<String>) {
    val options = parseArgs(args) ?: return
    try {
        val result = extractText(options)
        println(json.encodeToString(result))
    } catch (e: Throwable) {
        System.err.println("OCR failed: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun parseArgs(args: Array<String>): OcrOptions? {
    if (args.isEmpty() || args.contains("--help")) {
        printUsage()
        return null
    }

    var imagePath: Path? = null
    var language = "eng"
    var level = OcrLevel.TEXT_LINE

    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--image" -> {
                imagePath = args.getOrNull(++index)?.let { Paths.get(it) }
            }
            "--lang" -> {
                language = args.getOrNull(++index) ?: language
            }
            "--level" -> {
                val raw = args.getOrNull(++index)
                if (raw != null) {
                    level = parseLevel(raw)
                }
            }
            else -> {
                System.err.println("Unknown argument: $arg")
                printUsage()
                exitProcess(2)
            }
        }
        index++
    }

    if (imagePath == null) {
        System.err.println("Missing --image argument")
        printUsage()
        exitProcess(2)
    }

    return OcrOptions(imagePath, language, level)
}

private fun parseLevel(raw: String): OcrLevel {
    val normalized = raw.replace('-', '_').uppercase()
    return try {
        OcrLevel.valueOf(normalized)
    } catch (e: IllegalArgumentException) {
        System.err.println("Unknown OCR level: $raw")
        printUsage()
        exitProcess(2)
    }
}

private fun printUsage() {
    System.err.println(
        """
        |Usage: ocr-tesseract --image <path> [--lang <lang>] [--level text_line|word]
        |
        |Outputs OCR JSON to stdout.
        """.trimMargin()
    )
}

private fun extractText(options: OcrOptions): OcrResult {
    val image = readImage(options.imagePath)
    ensureNativeLibraries()
    val tesseract = Tesseract()
    tesseract.setDatapath(ensureTessdataDir().toAbsolutePath().toString())
    tesseract.setLanguage(options.language)
    tesseract.setVariable("user_defined_dpi", "300")
    tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO)

    val words = tesseract.getWords(listOf(image), toTessLevel(options.level))
    val blocks = words.mapNotNull(::toTextBlock)
    return OcrResult(blocks)
}

private fun readImage(path: Path): BufferedImage {
    val image = ImageIO.read(path.toFile())
    return image ?: error("Unable to read OCR image: $path")
}

private fun toTessLevel(level: OcrLevel): Int {
    return when (level) {
        OcrLevel.TEXT_LINE -> ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE
        OcrLevel.WORD -> ITessAPI.TessPageIteratorLevel.RIL_WORD
    }
}

private fun toTextBlock(word: Word): OcrTextBlock? {
    val text = word.text?.trim().orEmpty()
    if (text.isBlank()) return null

    val box = word.boundingBox
    return OcrTextBlock(
        text = text,
        bounds = OcrRect(box.x, box.y, box.width, box.height)
    )
}

private fun ensureTessdataDir(): Path {
    //TODO: Make sure you copy that during build time in Gradle build
    //TODO: The created application in that module must have these resources prepared
    //TODO: Build must never put files under the user home folder!
    val root = Path.of(System.getProperty("user.home"), ".mcp-ocr", "tessdata")
    Files.createDirectories(root)
    copyResource("tessdata/eng.traineddata", root.resolve("eng.traineddata"))
    copyResource("tessdata/osd.traineddata", root.resolve("osd.traineddata"))
    return root
}

private fun ensureNativeLibraries() {
    Loader.load(leptonica::class.java)
    Loader.load(tesseract::class.java)

    val cacheDir = Loader.getCacheDir().absolutePath
    val existing = System.getProperty("jna.library.path").orEmpty()
    val updated = if (existing.isBlank()) cacheDir else existing + File.pathSeparator + cacheDir
    System.setProperty("jna.library.path", updated)
}

private fun copyResource(resource: String, target: Path) {
    if (Files.exists(target) && Files.size(target) > 0) return

    val stream = OcrResult::class.java.classLoader.getResourceAsStream(resource)
        ?: error("OCR resource not found: $resource")
    stream.use { input ->
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
