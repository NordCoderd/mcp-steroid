/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps an [AiAgentSession] to display agent activity in the [ConsoleDriver].
 *
 * Before running a prompt, the prompt text is shown in bright ANSI color.
 * After completion, a success/error summary is written.
 *
 * Real-time output pumping is handled by [ConsolePumpingContainerDriver]
 * which wraps the underlying container used by the agent session.
 */
class ConsoleAwareAgentSession(
    private val delegate: AiAgentSession,
    private val console: ConsoleDriver,
    private val agentName: String,
) : AiAgentSession {

    override fun runPrompt(prompt: String, timeoutSeconds: Long): ProcessResult {
        console.writePrompt(agentName, prompt)
        console.writeInfo("Running $agentName...")

        val result = delegate.runPrompt(prompt, timeoutSeconds)

        if (result.exitCode == 0) {
            console.writeSuccess("$agentName finished (exit 0)")
        } else {
            console.writeError("$agentName finished (exit ${result.exitCode})")
        }

        return result
    }

    override fun registerMcp(mcpUrl: String, mcpName: String): AiAgentSession {
        return ConsoleAwareAgentSession(
            delegate.registerMcp(mcpUrl, mcpName),
            console, agentName,
        )
    }
}

/**
 * A [ContainerDriver] decorator that tees command output to a file
 * and pumps it to the [ConsoleDriver] with a colored `[agentName]` prefix.
 *
 * stdout is pumped with cyan prefix; stderr is pumped with red prefix.
 * The tee approach ensures output is both captured in [ProcessResult]
 * for assertions and visible in the console xterm window in real-time.
 *
 * When [consoleFilterScript] is set (a path to a Python filter inside the
 * container), the pump pipes raw output through the filter before displaying.
 * This is used to convert NDJSON (stream-json) to human-readable text.
 */
class ConsolePumpingContainerDriver(
    delegate: ContainerDriver,
    private val console: ConsoleDriver,
    private val agentName: String,
    private val consoleFilterScript: String? = null,
) : ContainerDriverDelegate<ConsolePumpingContainerDriver>(delegate) {
    private val counter = AtomicInteger(0)

    override fun createNewDriver(delegate: ContainerDriver) = ConsolePumpingContainerDriver(delegate, console, agentName)

    override fun runInContainer(
        args: List<String>,
        workingDir: String?,
        timeoutSeconds: Long,
        extraEnvVars: Map<String, String>,
        quietly: Boolean,
    ): ProcessResult {
        require(!quietly) { "quietly mode is not supported for console runs" }

        val idx = counter.incrementAndGet()
        val slug = agentName.lowercase().replace(" ", "-")
        val combinedLog = "/tmp/agent-$slug-$idx-combined.log"

        // Single pump for combined output, with optional filter for readable display
        val pump = console.startFilePump(
            combinedLog, "[$agentName]", ConsoleDriver.CYAN,
            filterScript = consoleFilterScript,
        )

        try {
            // Write a tee-wrapper script that copies each line to BOTH stdout
            // (captured by docker exec for ProcessResult) and the log file
            // (tailed by the pump for console display).
            //
            // awk with explicit fflush() ensures both stdout and the log file
            // are flushed after every line, giving real-time streaming.
            val teeScript = "/tmp/agent-$slug-$idx-tee.sh"
            val escaped = escapeForBash(args)
            val scriptContent = buildString {
                appendLine("#!/bin/bash")
                appendLine("$escaped 2>&1 | awk -v logfile=$combinedLog '{print; print >> logfile; fflush(); fflush(logfile)}'")
                appendLine("exit \${PIPESTATUS[0]}")
            }
            delegate.writeFileInContainer(teeScript, scriptContent, executable = true)
            return delegate.runInContainer(listOf("bash", teeScript), workingDir, timeoutSeconds, extraEnvVars)
        } finally {
            // Let pump catch up with remaining output
            Thread.sleep(500)
            pump.stop()
        }
    }

    override fun runInContainerDetached(
        args: List<String>,
        workingDir: String?,
        extraEnvVars: Map<String, String>,
    ): RunningContainerProcess {
        val proc = delegate.runInContainerDetached(args, workingDir, extraEnvVars)

        // Pump the detached process stdout/stderr
        console.startFilePump(proc.stdoutPath, "[$agentName]", ConsoleDriver.CYAN)
        console.startFilePump(proc.stderrPath, "[$agentName]", ConsoleDriver.RED)

        return proc
    }

    override fun toString(): String = "ConsolePumping[$agentName]($delegate)"

    companion object {
        /** Container path where the stream-json filter is deployed. */
        const val STREAM_JSON_FILTER_PATH = "/tmp/stream-json-filter.py"

        /**
         * Deploy a Python filter script that converts Claude's stream-json NDJSON
         * output to human-readable console text.
         *
         * Handles these Claude stream-json event types:
         * - `message_start`: shows model name at start of session
         * - `content_block_start`: shows tool name when a tool_use block begins
         * - `content_block_delta` (text_delta): streams assistant text incrementally
         * - `content_block_delta` (input_json_delta): silently skipped (partial JSON)
         * - `content_block_stop`: no-op (block boundary marker)
         * - `message_delta`: shows non-standard stop reasons
         * - `message_stop`: no-op (message boundary marker)
         * - `tool_use`: shows tool name with detail (fallback for older formats)
         * - `tool_result`: shows tool execution result summary (success/failure)
         * - `result`: shows cost, duration, total cost, and turn count summary
         * - `error`: surfaces error messages visibly with [ERROR] prefix
         * - `system`: shows system messages
         * - `ping`: silently skipped (keep-alive)
         *
         * Non-JSON lines (e.g. Claude verbose stderr mixed in) pass through unchanged.
         * All recognized JSON lines are consumed so no raw JSON leaks to the console.
         */
        fun deployStreamJsonFilter(container: ContainerDriver) {
            val filterScript = buildStreamJsonFilterScript()
            container.writeFileInContainer(STREAM_JSON_FILTER_PATH, filterScript, executable = true)
        }

        internal fun buildStreamJsonFilterScript(): String = buildString {
            appendLine("#!/usr/bin/env python3")
            appendLine("import sys, json")
            appendLine("")
            appendLine("def tool_detail(name, inp):")
            appendLine("    '''Extract a short detail string for known tool names.'''")
            appendLine("    if name == 'steroid_execute_code':")
            appendLine("        reason = inp.get('reason', '')")
            appendLine("        if reason:")
            appendLine("            if len(reason) > 80: reason = reason[:77] + '...'")
            appendLine("            return ' (' + reason + ')'")
            appendLine("    elif name == 'read_mcp_resource':")
            appendLine("        uri = inp.get('uri', '')")
            appendLine("        if uri:")
            appendLine("            return ' (' + uri + ')'")
            appendLine("    elif name in ('Bash', 'bash'):")
            appendLine("        cmd = inp.get('command', '')")
            appendLine("        if cmd:")
            appendLine("            if len(cmd) > 60: cmd = cmd[:57] + '...'")
            appendLine("            return ' (' + cmd + ')'")
            appendLine("    elif name in ('Read', 'read'):")
            appendLine("        path = inp.get('file_path', '')")
            appendLine("        if path: return ' (' + path + ')'")
            appendLine("    elif name in ('Edit', 'edit', 'Write', 'write'):")
            appendLine("        path = inp.get('file_path', '')")
            appendLine("        if path: return ' (' + path + ')'")
            appendLine("    elif name in ('Grep', 'grep'):")
            appendLine("        pat = inp.get('pattern', '')")
            appendLine("        if pat: return ' (' + pat + ')'")
            appendLine("    elif name in ('Glob', 'glob'):")
            appendLine("        pat = inp.get('pattern', '')")
            appendLine("        if pat: return ' (' + pat + ')'")
            appendLine("    return ''")
            appendLine("")
            appendLine("def tool_result_summary(obj):")
            appendLine("    '''Extract a short summary from a tool_result event.'''")
            appendLine("    content = obj.get('content', '')")
            appendLine("    if isinstance(content, str):")
            appendLine("        for line in content.split('\\n'):")
            appendLine("            line = line.strip()")
            appendLine("            if line:")
            appendLine("                return line[:100] + '...' if len(line) > 100 else line")
            appendLine("    elif isinstance(content, list):")
            appendLine("        for block in content:")
            appendLine("            if isinstance(block, dict) and block.get('type') == 'text':")
            appendLine("                for line in block.get('text', '').split('\\n'):")
            appendLine("                    line = line.strip()")
            appendLine("                    if line:")
            appendLine("                        return line[:100] + '...' if len(line) > 100 else line")
            appendLine("    return ''")
            appendLine("")
            appendLine("for line in sys.stdin:")
            appendLine("    line = line.rstrip('\\n\\r')")
            appendLine("    if not line:")
            appendLine("        continue")
            appendLine("    if not line.lstrip().startswith('{'):")
            appendLine("        # Non-JSON line (e.g. verbose stderr, plain text) -- pass through")
            appendLine("        print(line, flush=True)")
            appendLine("        continue")
            appendLine("    try:")
            appendLine("        obj = json.loads(line)")
            appendLine("    except (json.JSONDecodeError, ValueError):")
            appendLine("        # Malformed JSON -- pass through so it is visible for debugging")
            appendLine("        print(line, flush=True)")
            appendLine("        continue")
            appendLine("    try:")
            appendLine("        t = obj.get('type', '')")
            appendLine("        if t == 'content_block_delta':")
            appendLine("            delta = obj.get('delta', {})")
            appendLine("            dt = delta.get('type', '')")
            appendLine("            if dt == 'text_delta':")
            appendLine("                text = delta.get('text', '')")
            appendLine("                if text:")
            appendLine("                    sys.stdout.write(text)")
            appendLine("                    sys.stdout.flush()")
            appendLine("            # input_json_delta carries partial tool input -- skip (noisy JSON fragments)")
            appendLine("        elif t == 'content_block_start':")
            appendLine("            cb = obj.get('content_block', {})")
            appendLine("            if cb.get('type') == 'tool_use':")
            appendLine("                name = cb.get('name', '?')")
            appendLine("                inp = cb.get('input', {})")
            appendLine("                detail = tool_detail(name, inp)")
            appendLine("                print('\\n>> ' + name + detail, flush=True)")
            appendLine("            elif cb.get('type') == 'text':")
            appendLine("                pass  # text block start, content comes via deltas")
            appendLine("        elif t == 'tool_use':")
            appendLine("            # Fallback for older stream-json format with standalone tool_use events")
            appendLine("            name = obj.get('name', '?')")
            appendLine("            inp = obj.get('input', {})")
            appendLine("            detail = tool_detail(name, inp)")
            appendLine("            print('>> ' + name + detail, flush=True)")
            appendLine("        elif t == 'tool_result':")
            appendLine("            # Tool execution result -- show success/failure and summary")
            appendLine("            is_error = obj.get('is_error', False)")
            appendLine("            summary = tool_result_summary(obj)")
            appendLine("            prefix = '<< ERROR' if is_error else '<<'")
            appendLine("            parts = [prefix]")
            appendLine("            if summary: parts.append(summary)")
            appendLine("            print(' '.join(parts), flush=True)")
            appendLine("        elif t == 'message_start':")
            appendLine("            msg = obj.get('message', {})")
            appendLine("            model = msg.get('model', '')")
            appendLine("            if model:")
            appendLine("                print('[model] ' + model, flush=True)")
            appendLine("        elif t == 'message_delta':")
            appendLine("            delta = obj.get('delta', {})")
            appendLine("            sr = delta.get('stop_reason', '')")
            appendLine("            if sr and sr != 'end_turn':")
            appendLine("                print('[stop] ' + sr, flush=True)")
            appendLine("        elif t == 'result':")
            appendLine("            cost = obj.get('cost_usd', 0)")
            appendLine("            duration = obj.get('duration_ms', 0)")
            appendLine("            dur_s = duration / 1000.0 if duration else 0")
            appendLine("            total_cost = obj.get('total_cost_usd', 0)")
            appendLine("            turns = obj.get('num_turns', 0)")
            appendLine("            parts = []")
            appendLine("            if cost:")
            appendLine("                parts.append('cost=' + chr(36) + format(cost, '.4f'))")
            appendLine("            if total_cost and total_cost != cost:")
            appendLine("                parts.append('total=' + chr(36) + format(total_cost, '.4f'))")
            appendLine("            if dur_s:")
            appendLine("                parts.append('time=' + format(dur_s, '.1f') + 's')")
            appendLine("            if turns:")
            appendLine("                parts.append('turns=' + str(turns))")
            appendLine("            if parts:")
            appendLine("                print('[done] ' + ' '.join(parts), flush=True)")
            appendLine("            else:")
            appendLine("                print('[done]', flush=True)")
            appendLine("        elif t == 'error':")
            appendLine("            error = obj.get('error', {})")
            appendLine("            if isinstance(error, dict):")
            appendLine("                msg = error.get('message', str(error))")
            appendLine("                etype = error.get('type', '')")
            appendLine("                if etype:")
            appendLine("                    print('[ERROR ' + etype + '] ' + msg, flush=True)")
            appendLine("                else:")
            appendLine("                    print('[ERROR] ' + msg, flush=True)")
            appendLine("            else:")
            appendLine("                print('[ERROR] ' + str(error), flush=True)")
            appendLine("        elif t == 'system':")
            appendLine("            msg_text = obj.get('message', '')")
            appendLine("            if msg_text:")
            appendLine("                print('[system] ' + str(msg_text), flush=True)")
            appendLine("        # Silently skip: ping, content_block_stop, message_stop")
            appendLine("        # These are protocol bookkeeping and add no useful info for console display")
            appendLine("    except (KeyError, TypeError, AttributeError):")
            appendLine("        pass")
        }

        /** Container path where the Codex JSON filter is deployed. */
        const val CODEX_JSON_FILTER_PATH = "/tmp/codex-json-filter.py"

        /**
         * Deploy a Python filter script that converts Codex's NDJSON output
         * to human-readable console text.
         *
         * Codex `--json` emits newline-delimited JSON events. The filter handles:
         * - `item.completed` (agent_message): shows the agent's response text
         * - `item.completed` (command_execution): shows command output and exit code
         * - `item.completed` (tool_call/function_call/mcp_tool_call): shows tool result summary
         * - `item.started` (command_execution): shows ">> command" for shell commands
         * - `item.started` (tool_call/function_call/mcp_tool_call): shows ">> tool (detail)"
         * - `turn.completed`: shows token usage summary [turn] in=N out=N
         * - `error`: surfaces error messages visibly with [ERROR] prefix
         * - Non-JSON lines pass through unchanged
         * - All other JSON events (thread.started, turn.started, etc.) are silently consumed
         */
        fun deployCodexJsonFilter(container: ContainerDriver) {
            val filterScript = buildCodexJsonFilterScript()
            container.writeFileInContainer(CODEX_JSON_FILTER_PATH, filterScript, executable = true)
        }

        internal fun buildCodexJsonFilterScript(): String = buildString {
            appendLine("#!/usr/bin/env python3")
            appendLine("import sys, json")
            appendLine("")
            appendLine("def tool_detail(name, inp):")
            appendLine("    '''Extract a short detail string for known tool names.'''")
            appendLine("    if name == 'steroid_execute_code':")
            appendLine("        reason = inp.get('reason', '')")
            appendLine("        if reason:")
            appendLine("            if len(reason) > 80: reason = reason[:77] + '...'")
            appendLine("            return ' (' + reason + ')'")
            appendLine("    elif name == 'read_mcp_resource':")
            appendLine("        uri = inp.get('uri', '')")
            appendLine("        if uri:")
            appendLine("            return ' (' + uri + ')'")
            appendLine("    elif name in ('Bash', 'bash'):")
            appendLine("        cmd = inp.get('command', '')")
            appendLine("        if cmd:")
            appendLine("            if len(cmd) > 60: cmd = cmd[:57] + '...'")
            appendLine("            return ' (' + cmd + ')'")
            appendLine("    elif name in ('Read', 'read'):")
            appendLine("        path = inp.get('file_path', '')")
            appendLine("        if path: return ' (' + path + ')'")
            appendLine("    elif name in ('Edit', 'edit', 'Write', 'write'):")
            appendLine("        path = inp.get('file_path', '')")
            appendLine("        if path: return ' (' + path + ')'")
            appendLine("    elif name in ('Grep', 'grep'):")
            appendLine("        pat = inp.get('pattern', '')")
            appendLine("        if pat: return ' (' + pat + ')'")
            appendLine("    elif name in ('Glob', 'glob'):")
            appendLine("        pat = inp.get('pattern', '')")
            appendLine("        if pat: return ' (' + pat + ')'")
            appendLine("    return ''")
            appendLine("")
            appendLine("def truncate(text, max_len=200):")
            appendLine("    return text[:max_len] + '...' if len(text) > max_len else text")
            appendLine("")
            appendLine("for line in sys.stdin:")
            appendLine("    line = line.rstrip('\\n\\r')")
            appendLine("    if not line:")
            appendLine("        continue")
            appendLine("    if not line.lstrip().startswith('{'):")
            appendLine("        print(line, flush=True)")
            appendLine("        continue")
            appendLine("    try:")
            appendLine("        obj = json.loads(line)")
            appendLine("    except (json.JSONDecodeError, ValueError):")
            appendLine("        print(line, flush=True)")
            appendLine("        continue")
            appendLine("    try:")
            appendLine("        t = obj.get('type', '')")
            appendLine("        item = obj.get('item', {})")
            appendLine("        item_type = item.get('type', '') if isinstance(item, dict) else ''")
            appendLine("        # item.completed with agent_message: show the agent's response text")
            appendLine("        if t == 'item.completed' and item_type == 'agent_message':")
            appendLine("            text = item.get('text', '')")
            appendLine("            if text:")
            appendLine("                for part in text.split('\\n'):")
            appendLine("                    part = part.rstrip()")
            appendLine("                    if part:")
            appendLine("                        print(part, flush=True)")
            appendLine("        # item.completed with command_execution: show command output and exit code")
            appendLine("        elif t == 'item.completed' and item_type == 'command_execution':")
            appendLine("            output = item.get('output', '')")
            appendLine("            if output:")
            appendLine("                for part in output.split('\\n'):")
            appendLine("                    part = part.rstrip()")
            appendLine("                    if part:")
            appendLine("                        print('  ' + truncate(part), flush=True)")
            appendLine("            ec = item.get('exit_code')")
            appendLine("            if ec is not None and ec != 0:")
            appendLine("                cmd = item.get('command', '')")
            appendLine("                label = '>> exit ' + str(ec)")
            appendLine("                if cmd:")
            appendLine("                    label += ' (' + truncate(cmd, 80) + ')'")
            appendLine("                print(label, flush=True)")
            appendLine("        # item.completed with tool_call: show tool result summary with execution ID")
            appendLine("        elif t == 'item.completed' and item_type in ('tool_call', 'function_call', 'mcp_tool_call'):")
            appendLine("            name = item.get('name', item.get('function', {}).get('name', '?'))")
            appendLine("            output = item.get('output', item.get('result', ''))")
            appendLine("            exec_id = item.get('id', '')")
            appendLine("            label = '<< ' + name")
            appendLine("            if exec_id:")
            appendLine("                label += ' [' + exec_id + ']'")
            appendLine("            if output:")
            appendLine("                summary = truncate(str(output).replace('\\n', ' '), 120)")
            appendLine("                label += ': ' + summary")
            appendLine("            print(label, flush=True)")
            appendLine("        # item.started with command_execution: show the command being run")
            appendLine("        elif t == 'item.started' and item_type == 'command_execution':")
            appendLine("            cmd = item.get('command', '')")
            appendLine("            if cmd:")
            appendLine("                print('>> ' + cmd, flush=True)")
            appendLine("        # item.started with tool_call: show tool name with details")
            appendLine("        elif t == 'item.started' and item_type in ('tool_call', 'function_call', 'mcp_tool_call'):")
            appendLine("            name = item.get('name', item.get('function', {}).get('name', '?'))")
            appendLine("            inp = item.get('input', item.get('arguments', {}))")
            appendLine("            if isinstance(inp, str):")
            appendLine("                try:")
            appendLine("                    inp = json.loads(inp)")
            appendLine("                except Exception:")
            appendLine("                    inp = {}")
            appendLine("            detail = tool_detail(name, inp) if isinstance(inp, dict) else ''")
            appendLine("            print('>> ' + name + detail, flush=True)")
            appendLine("        # turn.completed: show token usage summary")
            appendLine("        elif t == 'turn.completed':")
            appendLine("            usage = obj.get('usage', {})")
            appendLine("            inp_tok = usage.get('input_tokens', 0)")
            appendLine("            out_tok = usage.get('output_tokens', 0)")
            appendLine("            if inp_tok or out_tok:")
            appendLine("                print('[turn] in=' + str(inp_tok) + ' out=' + str(out_tok), flush=True)")
            appendLine("        # error events: surface error messages visibly")
            appendLine("        elif t == 'error':")
            appendLine("            error = obj.get('error', obj.get('message', ''))")
            appendLine("            if isinstance(error, dict):")
            appendLine("                msg = error.get('message', str(error))")
            appendLine("                etype = error.get('type', error.get('code', ''))")
            appendLine("                if etype:")
            appendLine("                    print('[ERROR ' + str(etype) + '] ' + msg, flush=True)")
            appendLine("                else:")
            appendLine("                    print('[ERROR] ' + msg, flush=True)")
            appendLine("            else:")
            appendLine("                print('[ERROR] ' + str(error), flush=True)")
            appendLine("        # Silently skip: thread.started, turn.started, item.started/agent_message, etc.")
            appendLine("    except (KeyError, TypeError, AttributeError):")
            appendLine("        pass")
        }

        /** Container path where the Gemini text filter is deployed. */
        const val GEMINI_FILTER_PATH = "/tmp/gemini-filter.py"

        /**
         * Deploy a Python filter script that cleans Gemini CLI text output
         * for readable console display.
         *
         * Gemini CLI with `--screen-reader true` outputs text-based progress
         * with ANSI escape codes (colors, cursor positioning, etc.). Unlike
         * Claude's stream-json and Codex's --json, Gemini produces human-readable
         * text rather than structured events.
         *
         * The filter processes line-by-line:
         * - **ANSI stripping**: Removes CSI sequences (colors, cursor), OSC sequences
         *   (terminal title), character set selection, and DEC private modes
         * - **Noise suppression**: Filters blank lines, decorative separators,
         *   spinner dots, and carriage returns (progress overwrites)
         * - **Deduplication**: Collapses consecutive identical lines (e.g. repeated
         *   progress messages)
         * - **Tool highlighting**: Adds `>>` prefix to lines indicating tool/MCP
         *   activity (tool calls, execution IDs, file operations, command execution)
         * - **Pass-through**: All other meaningful text passes unchanged
         *
         * Edge cases handled:
         * - Lines with embedded CR (\r) are filtered (progress overwrites)
         * - Non-ASCII and Unicode content passes through (after ANSI removal)
         * - Tool result markers (`<<`) are highlighted when present
         * - Execution IDs (eid_...) are highlighted for correlation with logs
         */
        fun deployGeminiFilter(container: ContainerDriver) {
            val filterScript = buildGeminiFilterScript()
            container.writeFileInContainer(GEMINI_FILTER_PATH, filterScript, executable = true)
        }

        internal fun buildGeminiFilterScript(): String = buildString {
            appendLine("#!/usr/bin/env python3")
            appendLine("import sys, re")
            appendLine("")
            appendLine("# Regex to strip ANSI escape sequences")
            appendLine("# Covers: CSI (colors, cursor), OSC (terminal title), charset selection, DEC modes, erase commands")
            appendLine("ANSI_RE = re.compile(r'\\x1b\\[[0-9;]*[a-zA-Z]|\\x1b\\][^\\x07]*\\x07|\\x1b[()][AB012]|\\x1b\\[\\?[0-9;]*[hl]')")
            appendLine("")
            appendLine("# Patterns for noisy lines to suppress")
            appendLine("NOISE_PATTERNS = [")
            appendLine("    re.compile(r'^\\s*$'),")
            appendLine("    re.compile(r'^[\\s\\-=_*]+$'),  # Decorative separators")
            appendLine("    re.compile(r'^\\s*\\.+\\s*$'),  # Spinner dots (one or more)")
            appendLine("    re.compile(r'\\x0d'),  # Carriage return anywhere (progress overwrite)")
            appendLine("]")
            appendLine("")
            appendLine("# Patterns that indicate tool/MCP activity (highlight these)")
            appendLine("TOOL_PATTERNS = [")
            appendLine("    re.compile(r'(?:calling|using|executing|running)\\s+(?:tool|function|mcp)', re.IGNORECASE),")
            appendLine("    re.compile(r'steroid_execute_code', re.IGNORECASE),")
            appendLine("    re.compile(r'read_mcp_resource', re.IGNORECASE),")
            appendLine("    re.compile(r'mcp_tool_call', re.IGNORECASE),")
            appendLine("    re.compile(r'(?:Execution ID|execution_id):\\s*eid_[A-Za-z0-9_-]+', re.IGNORECASE),")
            appendLine("    re.compile(r'^\\s*(?:>>|<<)\\s+\\w+'),  # Tool call (>>) or result (<<) prefix")
            appendLine("    re.compile(r'(?:Tool|Function)\\s+(?:result|output|completed)', re.IGNORECASE),")
            appendLine("    re.compile(r'(?:Reading|Writing|Editing)\\s+(?:file|resource):', re.IGNORECASE),")
            appendLine("    re.compile(r'(?:Bash|Command)\\s+(?:execution|output):', re.IGNORECASE),")
            appendLine("]")
            appendLine("")
            appendLine("prev_line = None")
            appendLine("")
            appendLine("for raw_line in sys.stdin:")
            appendLine("    # Strip ANSI escape codes")
            appendLine("    line = ANSI_RE.sub('', raw_line).rstrip()")
            appendLine("")
            appendLine("    # Skip noise")
            appendLine("    if any(p.match(line) for p in NOISE_PATTERNS):")
            appendLine("        continue")
            appendLine("")
            appendLine("    # Deduplicate identical consecutive lines (e.g. repeated progress)")
            appendLine("    if line == prev_line:")
            appendLine("        continue")
            appendLine("    prev_line = line")
            appendLine("")
            appendLine("    # Highlight tool calls with >> prefix for consistency with Claude/Codex filters")
            appendLine("    if any(p.search(line) for p in TOOL_PATTERNS):")
            appendLine("        print('>> ' + line, flush=True)")
            appendLine("    else:")
            appendLine("        print(line, flush=True)")
        }

        private fun escapeForBash(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                "'" + arg.replace("'", "'\\''") + "'"
            }
        }
    }
}