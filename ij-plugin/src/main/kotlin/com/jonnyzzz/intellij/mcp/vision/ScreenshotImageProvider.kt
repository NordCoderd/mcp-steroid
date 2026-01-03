/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vision

import com.intellij.openapi.application.EDT
import com.intellij.util.ui.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Provides the screenshot image capture.
 *
 * This is the primary provider that captures the AWT component as a PNG image.
 * Other providers can depend on this to access the captured image.
 */
class ScreenshotImageProvider : ScreenshotMetadataProvider {

    override val type: String = TYPE

    override suspend fun provide(context: ScreenCaptureContext): ProviderResult {
        // Capture the component on EDT
        val image = withContext(Dispatchers.EDT) {
            captureComponent(context.component)
        }

        // Encode as PNG
        val pngBytes = withContext(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            val written = ImageIO.write(image, "png", output)
            if (!written) {
                throw IllegalStateException("No PNG writer available for screenshot")
            }
            output.toByteArray()
        }

        return ProviderResult.Success(
            ScreenshotMetadata(
                type = TYPE,
                fileName = FILE_NAME,
                mimeType = MIME_TYPE,
                binaryContent = pngBytes,
            )
        )
    }

    private fun captureComponent(component: java.awt.Component): BufferedImage {
        val size = component.size
        val preferred = component.preferredSize
        val width = size.width.takeIf { it > 0 } ?: preferred.width.takeIf { it > 0 } ?: 1024
        val height = size.height.takeIf { it > 0 } ?: preferred.height.takeIf { it > 0 } ?: 768

        val image = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.printAll(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    companion object {
        const val TYPE = "screenshot"
        const val FILE_NAME = "screenshot.png"
        const val MIME_TYPE = "image/png"
    }
}
