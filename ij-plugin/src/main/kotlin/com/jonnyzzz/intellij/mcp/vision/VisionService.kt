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
import java.awt.Container
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
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

//TODO: make the metadata object extensible, so each extension could provide
//TODO: it's own serializable data object with additional info and the filename
//TODO: basically the screenshot itself is yet another object in that collection, the first one
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

//TODO: This must be IntelliJ Component
object VisionService {
    private const val IMAGE_FILE = "screenshot.png"
    //TODO: the metadata should be provided via IntelliJ extension point, so move the
    //TODO: specific providers to additional Extension Point implementations
    //TODO: let extensions specify filenames
    private const val TREE_FILE = "screenshot-tree.md"
    private const val META_FILE = "screenshot-meta.json"


    //TODO: extensions design
    //TODO: we create a generic interface for extensions to provide the
    //TODO: screenshot and related metadata for a given context
    //TODO: the implementation works as follows:
    //TODO: we iterate over the all available providers
    //TODO: each provider can provide the metadata (image, component tree, etc)
    //TODO: so the provided metadata goes into the context
    //TODO: or the provider can return special answer to indicate it depends from other providers
    //TODO: we iterate over all providers which has not yet returned the answer
    //TODO: once provider returned answer -- it is not executed anymore
    //TODO: a provider may never return anything, there must be additional response (and exception) for such case
    //TODO: --- so our goal is to refactor the current system into extension point based approach where all 3 providers are added
    //TODO: --- next step is to support Compose controls and JCEF controls as additional tasks in the plan
    //TODO: ---- deploy the MCP plugin and try it

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    //TODO: Introduce the ScreenCaptureContext object, it will be better for the extensibility later
    //TODO: Wrap the whole method in coroutineScope { .. } to make sure all nested coroutines are properly cancelled/awaited
    suspend fun capture(project: Project, executionId: ExecutionId, windowId: String? = null): ScreenshotArtifacts {
        //TODO: make sure (really validate in the code, if that is executed with ModalityState.any()
        //TODO: otherwise the capture will not work for modal dialogs
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
        val treePath = storage.writeCodeExecutionData(executionId, TREE_FILE, capture.componentTree)

        val meta = ScreenshotMeta(
            system = "swing",
            imageFile = IMAGE_FILE,
            treeFiles = listOf(TREE_FILE),
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

        return ScreenshotArtifacts(
            imageBytes = pngBytes,
            imagePath = imagePath,
            treePath = treePath,
            metaPath = metaPath,
            meta = meta,
        )
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
        val componentTree: String,
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

        val tree = buildComponentTree(component)
        val location = runCatching { component.locationOnScreen }.getOrNull()
        val window = SwingUtilities.getWindowAncestor(component)
        val windowIdValue = WindowIdUtil.compute(window, component)
        val windowBounds = window?.bounds?.let { Rect(it.x, it.y, it.width, it.height) }
        val windowTitle = (window as? java.awt.Frame)?.title

        return CaptureInfo(
            image = image,
            componentTree = tree,
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
        return when (component) {
            is javax.swing.JLabel -> component.text
            is javax.swing.AbstractButton -> component.text
            is javax.swing.text.JTextComponent -> component.text
            else -> null
        }?.takeIf { it.isNotBlank() }?.take(120)
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
