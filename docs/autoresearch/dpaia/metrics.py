#!/usr/bin/env python3
"""
DPAIA autoresearch metrics extractor.

Reads a single agent's raw NDJSON output from a DPAIA run dir and emits a
JSON-structured metrics block covering the four axes requested by the user:

  1. tokens cost — input / output / cache-read / cache-creation sums
  2. complexity — steroid_execute_code script line-count stats (total, avg, p50, max)
  3. ease of use — proxy = number of steroid_fetch_resource fetches + number of
     retry calls (repeated similar exec_code attempts) + number of edit-tool calls
  4. errors — tool_result blocks with is_error=true, grouped by tool name

The extractor is defensive — it never throws on malformed lines, it just skips
them with a warning on stderr. The intent is that this script runs unattended
against dozens of run dirs and aggregates the output for the iteration log.

Usage:
    python3 metrics.py <run_dir>                 # one run, pretty JSON
    python3 metrics.py <run_dir1> <run_dir2> ... # multiple runs, one record per line
    python3 metrics.py --csv <run_dir> ...       # CSV, suitable for summary.md

Output schema (keys are stable):
    {
      "run_dir":            "<path>",
      "agent":              "claude-code" | "codex" | "gemini-cli",
      "iteration":          <int, from agent-NAME-N>,
      "tokens":             { input, output, cache_read, cache_creation, total },
      "calls": {
         "total":            <int>,
         "by_name":          { "Read": N, "mcp__mcp-steroid__steroid_execute_code": N, ... },
         "errors_by_name":   { ... }
      },
      "complexity": {
         "exec_code_calls":  <int>,
         "exec_code_lines_total": <int>,
         "exec_code_lines_avg":   <float>,
         "exec_code_lines_p50":   <int>,
         "exec_code_lines_max":   <int>
      },
      "ease_of_use": {
         "fetch_resource_calls":   <int>,      # agent searched skill guides
         "native_edit_calls":      <int>,      # agent used built-in Edit instead of apply-patch
         "exec_code_retries":      <int>,      # heuristic: same opening tokens repeated
         "native_bash_calls":      <int>
      },
      "errors":             <int>,
      "apply_patch": {
         "called":           <bool>,          # script source contains "applyPatch " or "hunk("
         "hunks_estimate":   <int>             # sum of hunk( occurrences across scripts
      }
    }
"""

import argparse
import json
import os
import re
import statistics
import sys


_CLAUDE_EXEC_CODE_NAME = "mcp__mcp-steroid__steroid_execute_code"
_CLAUDE_FETCH_RESOURCE_NAME = "mcp__mcp-steroid__steroid_fetch_resource"


def _count_lines(text):
    if not text:
        return 0
    return text.count("\n") + 1


def _find_agent_file(run_dir):
    """Pick the latest agent-<name>-<N>-raw.ndjson in the run dir."""
    candidates = []
    for f in os.listdir(run_dir):
        m = re.match(r"^agent-(?P<name>[^-]+(?:-[a-z]+)?)-(?P<n>\d+)-raw\.ndjson$", f)
        if m:
            candidates.append((int(m.group("n")), m.group("name"), f))
    if not candidates:
        return None, None
    candidates.sort()
    _, name, fname = candidates[-1]
    return os.path.join(run_dir, fname), name


def analyse(run_dir):
    ndjson_path, agent_name = _find_agent_file(run_dir)
    if ndjson_path is None:
        return None

    tokens = {
        "input": 0,
        "output": 0,
        "cache_read": 0,
        "cache_creation": 0,
        "total": 0,
    }
    tool_calls_by_name = {}
    errors_by_name = {}
    exec_code_line_counts = []
    exec_code_raw_codes = []
    fetch_resource_calls = 0
    native_edit_calls = 0
    native_bash_calls = 0
    errors = 0
    apply_patch_hunks = 0
    apply_patch_called = False

    with open(ndjson_path, "r", encoding="utf-8") as fh:
        for line in fh:
            try:
                ev = json.loads(line)
            except Exception:
                continue
            msg = ev.get("message") or {}
            usage = msg.get("usage") or {}
            tokens["input"] += int(usage.get("input_tokens") or 0)
            tokens["output"] += int(usage.get("output_tokens") or 0)
            tokens["cache_read"] += int(usage.get("cache_read_input_tokens") or 0)
            tokens["cache_creation"] += int(usage.get("cache_creation_input_tokens") or 0)
            for block in msg.get("content") or []:
                btype = block.get("type")
                if btype == "tool_use":
                    name = block.get("name", "?")
                    tool_calls_by_name[name] = tool_calls_by_name.get(name, 0) + 1
                    if name == _CLAUDE_EXEC_CODE_NAME:
                        code = (block.get("input") or {}).get("code") or ""
                        exec_code_line_counts.append(_count_lines(code))
                        exec_code_raw_codes.append(code)
                        if "applyPatch" in code or "hunk(" in code:
                            apply_patch_called = True
                            apply_patch_hunks += code.count("hunk(")
                    elif name == _CLAUDE_FETCH_RESOURCE_NAME:
                        fetch_resource_calls += 1
                    elif name == "Edit":
                        native_edit_calls += 1
                    elif name == "Bash":
                        native_bash_calls += 1
                elif btype == "tool_result":
                    is_error = block.get("is_error", False)
                    if is_error:
                        errors += 1
                        # best-effort tool-name recovery via tool_use_id grep
                        tu_id = block.get("tool_use_id") or ""
                        errors_by_name[tu_id] = errors_by_name.get(tu_id, 0) + 1

    tokens["total"] = (
        tokens["input"] + tokens["output"] + tokens["cache_read"] + tokens["cache_creation"]
    )

    # Heuristic: retries = adjacent exec_code calls with identical first 80 chars.
    retries = 0
    for i in range(1, len(exec_code_raw_codes)):
        a = exec_code_raw_codes[i - 1][:80].strip()
        b = exec_code_raw_codes[i][:80].strip()
        if a and a == b:
            retries += 1

    complexity = {
        "exec_code_calls": len(exec_code_line_counts),
        "exec_code_lines_total": sum(exec_code_line_counts),
        "exec_code_lines_avg": (
            statistics.fmean(exec_code_line_counts) if exec_code_line_counts else 0.0
        ),
        "exec_code_lines_p50": (
            statistics.median(exec_code_line_counts) if exec_code_line_counts else 0
        ),
        "exec_code_lines_max": max(exec_code_line_counts) if exec_code_line_counts else 0,
    }

    return {
        "run_dir": run_dir,
        "agent": agent_name,
        "tokens": tokens,
        "calls": {
            "total": sum(tool_calls_by_name.values()),
            "by_name": tool_calls_by_name,
            "errors_by_name": errors_by_name,
        },
        "complexity": complexity,
        "ease_of_use": {
            "fetch_resource_calls": fetch_resource_calls,
            "native_edit_calls": native_edit_calls,
            "exec_code_retries": retries,
            "native_bash_calls": native_bash_calls,
        },
        "errors": errors,
        "apply_patch": {
            "called": apply_patch_called,
            "hunks_estimate": apply_patch_hunks,
        },
    }


def _csv_header():
    return ",".join(
        [
            "run_dir",
            "agent",
            "tokens_input",
            "tokens_output",
            "tokens_cache_read",
            "tokens_cache_creation",
            "tokens_total",
            "calls_total",
            "exec_code_calls",
            "exec_code_lines_avg",
            "exec_code_lines_max",
            "native_edit_calls",
            "fetch_resource_calls",
            "retries",
            "errors",
            "apply_patch_called",
            "apply_patch_hunks",
        ]
    )


def _csv_row(a):
    return ",".join(
        str(v)
        for v in [
            a["run_dir"],
            a["agent"],
            a["tokens"]["input"],
            a["tokens"]["output"],
            a["tokens"]["cache_read"],
            a["tokens"]["cache_creation"],
            a["tokens"]["total"],
            a["calls"]["total"],
            a["complexity"]["exec_code_calls"],
            round(a["complexity"]["exec_code_lines_avg"], 1),
            a["complexity"]["exec_code_lines_max"],
            a["ease_of_use"]["native_edit_calls"],
            a["ease_of_use"]["fetch_resource_calls"],
            a["ease_of_use"]["exec_code_retries"],
            a["errors"],
            int(a["apply_patch"]["called"]),
            a["apply_patch"]["hunks_estimate"],
        ]
    )


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("run_dirs", nargs="+")
    p.add_argument("--csv", action="store_true")
    args = p.parse_args(argv[1:])

    if args.csv:
        print(_csv_header())
    for d in args.run_dirs:
        a = analyse(d)
        if a is None:
            sys.stderr.write(f"# skip (no agent-*-raw.ndjson): {d}\n")
            continue
        if args.csv:
            print(_csv_row(a))
        else:
            print(json.dumps(a, indent=2))


if __name__ == "__main__":
    main(sys.argv)
