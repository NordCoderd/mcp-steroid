/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vision

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Context passed to screenshot metadata providers during capture.
 *
 * @property project The IntelliJ project
 * @property component The captured AWT component
 * @property image The captured screenshot image
 * @property executionDir Directory where metadata files are stored
 * @property collectedMetadata Results from providers that have already completed
 */
data class ScreenCaptureContext(
    val project: Project,
    val component: Component,
    val image: BufferedImage,
    val executionDir: Path,
    val collectedMetadata: Map<String, ScreenshotMetadata> = emptyMap(),
) {
    /**
     * Get metadata from a specific provider type.
     */
    fun getMetadata(type: String): ScreenshotMetadata? = collectedMetadata[type]

    /**
     * Check if a specific provider has completed.
     */
    fun hasMetadata(type: String): Boolean = collectedMetadata.containsKey(type)

    /**
     * Create a new context with additional metadata.
     */
    fun withMetadata(metadata: ScreenshotMetadata): ScreenCaptureContext =
        copy(collectedMetadata = collectedMetadata + (metadata.type to metadata))
}

/**
 * Result from a metadata provider.
 */
sealed class ProviderResult {
    /**
     * Provider produced metadata.
     */
    data class Success(val metadata: ScreenshotMetadata) : ProviderResult()

    /**
     * Provider depends on other providers to run first.
     * Will be retried after other providers complete.
     */
    data object DependsOnOthers : ProviderResult()

    /**
     * Provider skipped (not applicable for this context).
     */
    data object Skip : ProviderResult()
}

/**
 * Metadata produced by a provider.
 */
data class ScreenshotMetadata(
    /** Unique identifier for this metadata type (e.g., "swing-tree", "ocr") */
    val type: String,
    /** File name for storing this metadata */
    val fileName: String,
    /** Content to write to the file */
    val content: String,
    /** MIME type of the content */
    val mimeType: String = "text/plain",
)

/**
 * Extension point for providing screenshot metadata.
 *
 * Providers are called iteratively:
 * 1. All providers are called once
 * 2. Providers returning DependsOnOthers are retried after others complete
 * 3. Each provider is called at most once per capture (unless it returns DependsOnOthers)
 * 4. Iteration continues until all providers have returned Success or Skip
 *
 * The context is updated with collected metadata after each provider completes,
 * allowing dependent providers to access results from earlier providers.
 */
interface ScreenshotMetadataProvider {
    /**
     * Unique identifier for this provider type.
     * Used to track which providers have completed and for dependency resolution.
     */
    val type: String

    /**
     * Provide metadata for the captured screenshot.
     *
     * @param context The capture context containing project, component, image, and collected metadata
     * @return Provider result indicating success, dependency, or skip
     */
    suspend fun provide(context: ScreenCaptureContext): ProviderResult

    companion object {
        val EP_NAME: ExtensionPointName<ScreenshotMetadataProvider> =
            ExtensionPointName.create("com.jonnyzzz.intellij.mcp-steroid.screenshotMetadataProvider")
    }
}
