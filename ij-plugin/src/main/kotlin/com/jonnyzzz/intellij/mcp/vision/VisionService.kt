/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.vision

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.ImageUtil
import com.jonnyzzz.intellij.mcp.storage.ExecutionId
import com.jonnyzzz.intellij.mcp.storage.executionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Point
import java.awt.Dimension
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

@Serializable
data class ScreenshotMeta(
    val system: String,
    val imageFile: String,
    val treeFiles: List<String>,
    val metaFile: String,
    val componentClass: String,
    val componentName: String?,
    val componentSize: Size,
    val imageSize: Size,
    val locationOnScreen: PointInfo?,
    val windowId: String? = null,
    val windowTitle: String? = null,
    val windowBounds: Rect? = null,
    val projectName: String? = null,
    val projectPath: String? = null,
    val capturedAt: String,
)

@Serializable
data class Size(val width: Int, val height: Int)

@Serializable
data class PointInfo(val x: Int, val y: Int)

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

data class ScreenshotArtifacts(
    //TODO: replace with load method and handle in the custom way when serializing
    //TODO: include content-type
    val imageBytes: ByteArray,
    //TODO: use imports
    val imagePath: java.nio.file.Path,
    val treePath: java.nio.file.Path,
    val metaPath: java.nio.file.Path,
    val meta: ScreenshotMeta,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotArtifacts) return false
        return imageBytes.contentEquals(other.imageBytes) &&
                imagePath == other.imagePath &&
                treePath == other.treePath &&
                metaPath == other.metaPath &&
                meta == other.meta
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + imagePath.hashCode()
        result = 31 * result + treePath.hashCode()
        result = 31 * result + metaPath.hashCode()
        result = 31 * result + meta.hashCode()
        return result
    }
}

object VisionService {
    private const val IMAGE_FILE = "screenshot.png"
    private const val META_FILE = "screenshot-meta.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun capture(project: Project, executionId: ExecutionId, windowId: String? = null): ScreenshotArtifacts {
        val capture = withContext(Dispatchers.EDT) { captureOnEdt(project, windowId) }

        val pngBytes = withContext(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            val written = ImageIO.write(capture.image, "png", output)
            if (!written) {
                throw IllegalStateException("No PNG writer available for screenshot")
            }
            output.toByteArray()
        }

        val storage = project.executionStorage
        val imagePath = storage.writeBinaryExecutionData(executionId, IMAGE_FILE, pngBytes)
        val executionDir = storage.resolveExecutionDir(executionId)

        // Create context for metadata providers
        val context = ScreenCaptureContext(
            project = project,
            component = withContext(Dispatchers.EDT) { resolveComponent(project, windowId) },
            image = capture.image,
            executionDir = executionDir,
        )

        // Collect metadata from all providers
        val collectedMetadata = collectMetadataFromProviders(context)

        // Write metadata files to storage
        val treeFiles = mutableListOf<String>()
        for (metadata in collectedMetadata) {
            storage.writeCodeExecutionData(executionId, metadata.fileName, metadata.content)
            treeFiles.add(metadata.fileName)
        }

        val meta = ScreenshotMeta(
            system = "swing",
            imageFile = IMAGE_FILE,
            treeFiles = treeFiles,
            metaFile = META_FILE,
            componentClass = capture.componentClass,
            componentName = capture.componentName,
            componentSize = Size(capture.componentSize.width, capture.componentSize.height),
            imageSize = Size(capture.image.width, capture.image.height),
            locationOnScreen = capture.locationOnScreen?.let { PointInfo(it.x, it.y) },
            windowId = capture.windowId,
            windowTitle = capture.windowTitle,
            windowBounds = capture.windowBounds,
            projectName = capture.projectName,
            projectPath = capture.projectPath,
            capturedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        )

        val metaPath = storage.writeCodeExecutionData(executionId, META_FILE, json.encodeToString(ScreenshotMeta.serializer(), meta))

        // Use first tree file path if available (for backward compatibility)
        val treePath = if (treeFiles.isNotEmpty()) {
            storage.resolveExecutionPath(executionId, treeFiles.first())
        } else {
            storage.resolveExecutionPath(executionId, "screenshot-tree.md")
        }

        return ScreenshotArtifacts(
            imageBytes = pngBytes,
            imagePath = imagePath,
            treePath = treePath,
            metaPath = metaPath,
            meta = meta,
        )
    }

    /**
     * Collects metadata from all registered providers.
     * Providers are called iteratively until all return Success or Skip.
     * Providers returning DependsOnOthers are retried after others complete.
     * The context is updated with collected metadata after each provider completes.
     */
    private suspend fun collectMetadataFromProviders(initialContext: ScreenCaptureContext): List<ScreenshotMetadata> {
        val providers = ScreenshotMetadataProvider.EP_NAME.extensionList
        if (providers.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<ScreenshotMetadata>()
        val pending = providers.toMutableList()
        var previousPendingCount = pending.size + 1
        var context = initialContext

        // Iterate until all providers complete or no progress is made
        while (pending.isNotEmpty() && pending.size < previousPendingCount) {
            previousPendingCount = pending.size
            val iterator = pending.iterator()

            while (iterator.hasNext()) {
                val provider = iterator.next()
                when (val result = provider.provide(context)) {
                    is ProviderResult.Success -> {
                        results.add(result.metadata)
                        // Update context with the new metadata for subsequent providers
                        context = context.withMetadata(result.metadata)
                        iterator.remove()
                    }
                    is ProviderResult.Skip -> {
                        iterator.remove()
                    }
                    is ProviderResult.DependsOnOthers -> {
                        // Keep in pending list for next iteration
                    }
                }
            }
        }

        return results
    }

    suspend fun executeInput(
        meta: ScreenshotMeta,
        steps: List<InputStep>,
    ) {
        val executor = SwingInputExecutor(meta)
        executor.execute(steps)
    }

    suspend fun loadScreenshotMeta(project: Project, executionId: ExecutionId): ScreenshotMeta {
        val metaPath = project.executionStorage.resolveExecutionPath(executionId, META_FILE)
        val content = withContext(Dispatchers.IO) { java.nio.file.Files.readString(metaPath) }
        return json.decodeFromString(ScreenshotMeta.serializer(), content)
    }

    private data class CaptureInfo(
        val image: BufferedImage,
        val componentClass: String,
        val componentName: String?,
        val componentSize: Dimension,
        val locationOnScreen: Point?,
        val windowId: String,
        val windowTitle: String?,
        val windowBounds: Rect?,
        val projectName: String?,
        val projectPath: String?,
    )

    private fun captureOnEdt(project: Project, windowId: String?): CaptureInfo {
        val component = resolveComponent(project, windowId)

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

        val location = runCatching { component.locationOnScreen }.getOrNull()
        val window = SwingUtilities.getWindowAncestor(component)
        val windowIdValue = WindowIdUtil.compute(window, component)
        val windowBounds = window?.bounds?.let { Rect(it.x, it.y, it.width, it.height) }
        val windowTitle = (window as? java.awt.Frame)?.title

        return CaptureInfo(
            image = image,
            componentClass = component.javaClass.name,
            componentName = component.name,
            componentSize = Dimension(component.width, component.height),
            locationOnScreen = location,
            windowId = windowIdValue,
            windowTitle = windowTitle,
            windowBounds = windowBounds,
            projectName = project.name,
            projectPath = project.basePath,
        )
    }

    private fun resolveComponent(project: Project, windowId: String?): Component {
        if (windowId != null) {
            return findComponentByWindowId(windowId)
                ?: throw IllegalStateException("Window not found for window_id: $windowId")
        }

        return WindowManager.getInstance().getIdeFrame(project)?.component
            ?: FileEditorManager.getInstance(project).selectedTextEditor?.component
            ?: throw IllegalStateException("No IDE frame or editor component available for screenshot")
    }

    /**
     * Find a component by window ID. Searches both project frames and all displayable windows.
     * @return The component if found, null otherwise
     */
    private fun findComponentByWindowId(windowId: String): Component? {
        // Search project frames first
        for (frame in WindowManager.getInstance().allProjectFrames) {
            val component = frame.component
            val window = SwingUtilities.getWindowAncestor(component)
            if (WindowIdUtil.compute(window, component) == windowId) {
                return component
            }
        }
        // Fall back to all displayable windows
        for (window in Window.getWindows()) {
            if (!window.isDisplayable) continue
            if (WindowIdUtil.compute(window, window) == windowId) {
                return window
            }
        }
        return null
    }

    private class SwingInputExecutor(
        private val meta: ScreenshotMeta,
    ) {
        private val stuckKeys = LinkedHashSet<Int>()

        suspend fun execute(steps: List<InputStep>) {
            val rootComponent = withContext(Dispatchers.EDT) {
                resolveComponentForInput()
            }

            try {
                withContext(Dispatchers.EDT) {
                    ensureFocus(rootComponent)
                }
                for (step in steps) {
                    when (step) {
                        is InputStep.Delay -> delay(step.ms)
                        is InputStep.StickKey -> withContext(Dispatchers.EDT) { stickKey(rootComponent, step) }
                        is InputStep.PressKey -> withContext(Dispatchers.EDT) { pressKey(rootComponent, step) }
                        is InputStep.TypeText -> withContext(Dispatchers.EDT) { typeText(rootComponent, step) }
                        is InputStep.Click -> withContext(Dispatchers.EDT) { click(rootComponent, step) }
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) {
                    releaseAll(rootComponent)
                }
            }
        }

        private fun resolveComponentForInput(): Component {
            val windowId = meta.windowId ?: throw IllegalStateException("Screenshot metadata missing windowId")
            return findComponentByWindowId(windowId)
                ?: throw IllegalStateException("No IDE window found for windowId: $windowId")
        }

        private fun stickKey(component: Component, step: InputStep.StickKey) {
            ensureFocus(component)
            if (stuckKeys.add(step.keyCode)) {
                dispatchKey(component, KeyEvent.KEY_PRESSED, step.keyCode, '\u0000', currentModifiers())
            }
        }

        private fun pressKey(component: Component, step: InputStep.PressKey) {
            ensureFocus(component)
            val tempModifiers = step.modifiers.mapNotNull { modifierKeyCode(it) }
                .filterNot { stuckKeys.contains(it) }
            tempModifiers.forEach { dispatchKey(component, KeyEvent.KEY_PRESSED, it, '\u0000', currentModifiers()) }

            dispatchKey(component, KeyEvent.KEY_PRESSED, step.keyCode, '\u0000', currentModifiers(step.modifiers))
            dispatchKey(component, KeyEvent.KEY_RELEASED, step.keyCode, '\u0000', currentModifiers(step.modifiers))

            tempModifiers.reversed().forEach { dispatchKey(component, KeyEvent.KEY_RELEASED, it, '\u0000', currentModifiers()) }
        }

        private fun typeText(component: Component, step: InputStep.TypeText) {
            val focus = focusOwner(component)
            ensureFocus(focus)
            focus.requestFocusInWindow()
            step.text.forEach { ch ->
                dispatchKey(focus, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ch, currentModifiers())
            }
        }

        private fun click(component: Component, step: InputStep.Click) {
            val targetComponent = when (val target = step.target) {
                is InputTarget.ScreenshotPixel -> {
                    val point = mapScreenshotPoint(component, target.x, target.y)
                    SwingUtilities.getDeepestComponentAt(component, point.x, point.y) ?: component
                }
                is InputTarget.ScreenPixel -> {
                    val point = Point(target.x, target.y)
                    SwingUtilities.convertPointFromScreen(point, component)
                    SwingUtilities.getDeepestComponentAt(component, point.x, point.y) ?: component
                }
                is InputTarget.Unsupported -> throw IllegalStateException("Unsupported target: ${target.raw}")
            }

            val point = when (val target = step.target) {
                is InputTarget.ScreenshotPixel -> mapScreenshotPoint(component, target.x, target.y)
                is InputTarget.ScreenPixel -> Point(target.x, target.y).also {
                    SwingUtilities.convertPointFromScreen(it, component)
                }
                is InputTarget.Unsupported -> throw IllegalStateException("Unsupported target: ${target.raw}")
            }

            ensureFocus(targetComponent)
            targetComponent.requestFocusInWindow()

            val modifiers = currentModifiers(step.modifiers)
            val button = when (step.button) {
                MouseButton.LEFT -> MouseEvent.BUTTON1
                MouseButton.RIGHT -> MouseEvent.BUTTON3
                MouseButton.MIDDLE -> MouseEvent.BUTTON2
            }

            dispatchMouse(targetComponent, MouseEvent.MOUSE_PRESSED, point, button, modifiers)
            dispatchMouse(targetComponent, MouseEvent.MOUSE_RELEASED, point, button, modifiers)
            dispatchMouse(targetComponent, MouseEvent.MOUSE_CLICKED, point, button, modifiers)
        }

        private fun mapScreenshotPoint(component: Component, x: Int, y: Int): Point {
            val imageSize = meta.imageSize
            require(imageSize.width > 0 && imageSize.height > 0) {
                "Invalid screenshot metadata: image size is empty"
            }
            require(component.width > 0 && component.height > 0) {
                "Target component has empty size"
            }
            val widthScale = component.width.toDouble() / imageSize.width.toDouble()
            val heightScale = component.height.toDouble() / imageSize.height.toDouble()
            val localX = (x.toDouble() * widthScale).roundToInt()
            val localY = (y.toDouble() * heightScale).roundToInt()
            return Point(localX.coerceIn(0, component.width - 1), localY.coerceIn(0, component.height - 1))
        }

        private fun focusOwner(component: Component): Component {
            val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            return focus ?: component
        }

        private fun ensureFocus(component: Component) {
            val window = component as? Window ?: SwingUtilities.getWindowAncestor(component)
            if (window != null) {
                if (!window.isActive) {
                    window.toFront()
                    window.requestFocus()
                }
            }

            IdeFocusManager.findInstanceByComponent(component).requestFocus(component, true)
        }

        private fun releaseAll(component: Component) {
            stuckKeys.reversed().forEach { code ->
                dispatchKey(component, KeyEvent.KEY_RELEASED, code, '\u0000', currentModifiers())
            }
            stuckKeys.clear()
        }

        private fun currentModifiers(extra: Set<InputModifier> = emptySet()): Int {
            val all = stuckKeys.mapNotNull { modifierFromKeyCode(it) }.toSet() + extra
            var mask = 0
            if (InputModifier.SHIFT in all) mask = mask or InputEvent.SHIFT_DOWN_MASK
            if (InputModifier.CTRL in all) mask = mask or InputEvent.CTRL_DOWN_MASK
            if (InputModifier.ALT in all) mask = mask or InputEvent.ALT_DOWN_MASK
            if (InputModifier.META in all) mask = mask or InputEvent.META_DOWN_MASK
            return mask
        }

        private fun modifierFromKeyCode(code: Int): InputModifier? {
            return when (code) {
                KeyEvent.VK_SHIFT -> InputModifier.SHIFT
                KeyEvent.VK_CONTROL -> InputModifier.CTRL
                KeyEvent.VK_ALT -> InputModifier.ALT
                KeyEvent.VK_META -> InputModifier.META
                else -> null
            }
        }

        private fun modifierKeyCode(modifier: InputModifier): Int? {
            return when (modifier) {
                InputModifier.SHIFT -> KeyEvent.VK_SHIFT
                InputModifier.CTRL -> KeyEvent.VK_CONTROL
                InputModifier.ALT -> KeyEvent.VK_ALT
                InputModifier.META -> KeyEvent.VK_META
            }
        }

        private fun dispatchKey(component: Component, id: Int, keyCode: Int, char: Char, modifiers: Int) {
            val event = KeyEvent(
                component,
                id,
                System.currentTimeMillis(),
                modifiers,
                keyCode,
                char
            )
            component.dispatchEvent(event)
        }

        private fun dispatchMouse(component: Component, id: Int, point: Point, button: Int, modifiers: Int) {
            val event = MouseEvent(
                component,
                id,
                System.currentTimeMillis(),
                modifiers,
                point.x,
                point.y,
                1,
                button == MouseEvent.BUTTON3,
                button
            )
            component.dispatchEvent(event)
        }
    }
}
