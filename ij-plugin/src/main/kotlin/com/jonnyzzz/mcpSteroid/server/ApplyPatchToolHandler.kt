/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.vfs.LocalFileSystem
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchException
import com.jonnyzzz.mcpSteroid.execution.ApplyPatchHunk
import com.jonnyzzz.mcpSteroid.execution.executeApplyPatch
import com.jonnyzzz.mcpSteroid.execution.vfsRefreshService
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.serialization.json.*

/**
 * First-class MCP tool for atomic multi-site literal-text patching.
 *
 * Surfaces the same underlying [executeApplyPatch] engine that
 * [com.jonnyzzz.mcpSteroid.execution.McpScriptContextImpl.applyPatch] uses,
 * but without the kotlinc compile step — so Claude Code's hard-coded ~60s
 * MCP tool timeout (issue #16837) no longer truncates 5+ hunk patches.
 *
 * Input shape mirrors the DSL:
 * ```json
 * {
 *   "project_name": "project-home",
 *   "task_id": "feature-x",
 *   "reason": "rename logger name across services",
 *   "hunks": [
 *     {"path": "/abs/A.java", "old_string": "foo", "new_string": "bar"},
 *     {"path": "/abs/A.java", "old_string": "baz", "new_string": "qux"},
 *     {"path": "/abs/B.java", "old_string": "log1", "new_string": "log2"}
 *   ]
 * }
 * ```
 *
 * Semantics: pre-flight validates every `old_string` is present exactly once;
 * all edits land as a single undoable [WriteCommandAction], PSI committed in
 * the same action, VFS async-refreshed on completion.
 */
class ApplyPatchToolHandler : McpRegistrar {

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_apply_patch",
            description = toolDescription,
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_name") {
                        put("type", "string")
                        put("description", "Project name (from steroid_list_projects)")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "Your task identifier; reuse across related calls.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "One-line summary of what this patch changes.")
                    }
                    putJsonObject("hunks") {
                        put("type", "array")
                        put("description", "Literal-text hunks. Each hunk's old_string must occur exactly once in its file.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") {
                                    put("type", "string")
                                    put("description", "Absolute filesystem path.")
                                }
                                putJsonObject("old_string") {
                                    put("type", "string")
                                    put("description", "Literal text to replace (must occur exactly once).")
                                }
                                putJsonObject("new_string") {
                                    put("type", "string")
                                    put("description", "Replacement text.")
                                }
                            }
                            putJsonArray("required") {
                                add("path")
                                add("old_string")
                                add("new_string")
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    add("project_name")
                    add("task_id")
                    add("hunks")
                }
            },
            ::handle,
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments ?: return errorResult("Missing arguments")

        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_name")
        args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")

        val hunksJson = args["hunks"]?.jsonArray
            ?: return errorResult("Missing required parameter: hunks (array)")
        if (hunksJson.isEmpty()) return errorResult("hunks array is empty")

        val hunks = hunksJson.mapIndexed { i, el ->
            val o = (el as? JsonObject) ?: return errorResult("hunks[$i] is not an object")
            val path = o["path"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult("hunks[$i].path is required")
            val oldString = o["old_string"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult("hunks[$i].old_string is required")
            val newString = o["new_string"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult("hunks[$i].new_string is required")
            ApplyPatchHunk(filePath = path, oldString = oldString, newString = newString)
        }

        val (project, availableNames) = readAction {
            val openProjects = getInstance().openProjects
            openProjects.find { it.name == projectName } to openProjects.map { it.name }
        }
        if (project == null) {
            return errorResult("Project not found: \"$projectName\". Available projects: $availableNames")
        }

        val result = try {
            executeApplyPatch(project, hunks) { path ->
                LocalFileSystem.getInstance().findFileByPath(path)
            }
        } catch (e: ApplyPatchException) {
            runCatching {
                analyticsBeacon.capture(
                    event = "apply_patch",
                    project = project,
                    properties = mapOf("result" to "error"),
                )
            }
            return errorResult(e.message ?: "apply-patch failed with no message")
        }

        project.vfsRefreshService.scheduleAsyncRefresh()

        runCatching {
            analyticsBeacon.capture(
                event = "apply_patch",
                project = project,
                properties = mapOf(
                    "result" to "success",
                    "hunks" to result.hunkCount.toString(),
                    "files" to result.fileCount.toString(),
                ),
            )
        }

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = result.toString())),
            isError = false,
        )
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true,
    )

    private val toolDescription: String get() = """
        Atomic multi-site literal-text patch. Apply N `old_string → new_string`
        substitutions across one or more files in a single undoable command.

        Use this INSTEAD of chaining 2+ native `Edit` calls. Pre-flight
        validates every `old_string` is present exactly once per file; if any
        hunk fails validation, NO edits land (all-or-nothing). Multi-hunk
        edits in the same file apply in descending-offset order automatically
        so earlier edits don't shift later ones.

        Why this tool vs `steroid_execute_code` with `applyPatch { }`: this
        bypasses kotlinc compilation, so large patches (8+ hunks, 3k+ char
        payloads) complete in tens of ms instead of tens of seconds — matters
        for Claude Code CLI's 60s per-tool MCP timeout.

        Input shape:
        {
          "project_name": "project-home",
          "task_id": "my-task",
          "reason": "add @ComponentScan to each service Application",
          "hunks": [
            {"path": "/abs/path/A.java", "old_string": "old", "new_string": "new"},
            {"path": "/abs/path/B.java", "old_string": "other", "new_string": "replacement"}
          ]
        }

        Return: human-readable audit — `N hunks across M file(s) applied
        atomically` + per-hunk `path:line:col (oldLen→newLen chars)`.

        Same underlying engine as `steroid_execute_code`'s `applyPatch { hunk(…) }`
        DSL — identical semantics, no boilerplate, no compile overhead.
    """.trimIndent()
}
