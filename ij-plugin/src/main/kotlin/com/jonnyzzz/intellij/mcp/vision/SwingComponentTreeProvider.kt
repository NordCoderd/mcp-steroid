/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vision

import java.awt.Component
import java.awt.Container

/**
 * Provides Swing component tree metadata for screenshots.
 */
class SwingComponentTreeProvider : ScreenshotMetadataProvider {

    override val type: String = TYPE

    override suspend fun provide(context: ScreenCaptureContext): ProviderResult {
        val tree = buildComponentTree(context.component)
        return ProviderResult.Success(
            ScreenshotMetadata(
                type = TYPE,
                fileName = FILE_NAME,
                content = tree,
                mimeType = "text/markdown",
            )
        )
    }

    companion object {
        const val TYPE = "swing-tree"
        const val FILE_NAME = "screenshot-tree.md"
    }

    private fun buildComponentTree(component: Component, indent: String = "", depth: Int = 0): String {
        val builder = StringBuilder()
        val bounds = component.bounds
        builder.append(indent).append("- ")
        builder.append(component.javaClass.simpleName)
        component.name?.let { builder.append("(name=").append(it).append(")") }
        builder.append(" [").append(bounds.width).append("x").append(bounds.height).append("]")
        if (!component.isVisible) builder.append(" hidden")

        val text = extractText(component)
        if (text != null) {
            builder.append(" \"").append(text).append("\"")
        }
        builder.append("\n")

        if (component is Container && depth < 64) {
            for (child in component.components) {
                builder.append(buildComponentTree(child, indent + "  ", depth + 1))
            }
        } else if (depth >= 64) {
            builder.append(indent).append("  ").append("... depth limit reached\n")
        }

        return builder.toString()
    }

    private fun extractText(component: Component): String? {
        val raw = when (component) {
            is javax.swing.JLabel -> component.text
            is javax.swing.AbstractButton -> component.text
            is javax.swing.text.JTextComponent -> component.text
            else -> null
        } ?: return null

        // Sanitize: collapse whitespace (newlines, tabs, multiple spaces) into single space
        val sanitized = raw
            .replace(Regex("\\s+"), " ")
            .trim()

        return sanitized.takeIf { it.isNotBlank() }?.take(120)
    }
}
