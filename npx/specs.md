# NPX MCP Steroid Stdio Proxy Specification

Status: validated spec (bridge-aware update)
Last updated: 2026-02-14

## 1. Purpose
Create a local NPX-run stdio MCP proxy (TypeScript implementation) that discovers all running IntelliJ MCP Steroid servers on the machine and exposes them as a single MCP server to any agent. The proxy aggregates discovery, routes tool calls to the correct upstream server, and optionally records MCP traffic under the user's home directory.

## 2. Goals
- Provide a single stdio MCP endpoint that dynamically aggregates multiple running MCP Steroid servers.
- Offer the same tool methods as MCP Steroid without requiring proxy updates when new upstream tools appear.
- Aggregate `steroid_list_projects` and `steroid_list_windows` across all servers.
- Maintain dynamic mapping `<project name + path> -> IDE instance/port` and refresh on IDE restart/crash.
- Re-check for marker files and server health continuously; servers can join/leave at any time (IDE restarts included).
- Optional traffic log under `~/.mcp-steroid/`.
- Keep proxy metadata/instructions sourced from IDE/plugin data (no hardcoded IDE metadata in NPX).
- Keep NPX source and tests in TypeScript (`.ts`) only.

## 3. Non-goals
- No remote discovery or network scanning beyond explicit local configuration.
- No UI; this is a headless CLI/service.
- No NPX local HTTP endpoint for MCP clients (client-facing mode is stdio MCP).

## 4. RLM-informed design principles
- Use explicit decision trees for discovery, routing, and error handling.
- Partition work per server and fan-out concurrently (Map), then merge and validate (Reduce).
- Validation is a first-class output (explicit checklist, error reporting, and metrics).

## 5. Discovery
### 5.1 Primary discovery: user home marker files
MCP Steroid writes a marker file in the user's home directory on startup:
- File name pattern: `.<pid>.mcp-steroid`
- Content: **first line is the MCP server URL** (e.g., `http://localhost:<port>/mcp`).
- Remaining lines may include human-readable info and optional machine-readable key-value metadata.

Proxy requirements:
- Scan `~` for files matching `\\.(\\d+)\\.mcp-steroid`.
- Parse the **first line** as `serverUrl`.
- Optionally parse additional lines (`Key: Value`) for bridge URL/token/version and IDE/plugin hints.
- Treat the file as valid only if the PID is still running (same user).
- If PID is dead, ignore (do not delete; upstream already does cleanup).

### 5.2 Optional discovery: per-project file
Each IntelliJ project can contain `.idea/mcp-steroids.txt` with the same first-line URL. This is optional for the proxy; prefer the user home marker for global discovery.

### 5.3 Health validation
For each discovered `serverUrl`:
- Perform GET `/mcp` with `Accept: application/json`.
- Expect JSON containing `name`, `version`, and `status = available`.
- If unreachable, mark server as `offline` and retry on next scan (with backoff).

### 5.4 Refresh policy
- Default scan interval: 2 seconds (configurable).
- File watcher on `~` marker files to trigger immediate refresh if supported.
- Each scan updates the server registry and tool catalog atomically.

## 6. Transport and topology
- Client-facing transport: **stdio MCP server** (JSON-RPC over stdin/stdout).
- The proxy must never emit non-MCP output on stdout. Logs go to stderr.
- Upstream transport: HTTP MCP to each server URL from marker files.
- Support concurrent requests; preserve request IDs and tool call results.
- No proxy restart needed for new tools or server changes.

## 6.1 Control plane vs data plane
- MCP API remains the data plane for tool/resource operations.
- A dedicated plugin bridge API is allowed for NPX control-plane concerns (metadata snapshots, mapping freshness, heartbeat/progress stream wrapper, capability detection).
- NPX must feature-detect bridge support and fall back to MCP-only behavior.

## 6.2 Compatibility requirements
- Older NPX + newer plugin: continue to work via MCP baseline.
- Newer NPX + older plugin: bridge probe may fail; NPX must degrade gracefully and continue MCP-only.
- Bridge APIs/metadata are additive-only; existing MCP behavior is preserved.

## 7. Server registry model
Each upstream server is represented as:
- `serverId` (stable): default `pid:<pid>`; if PID reused, combine with URL hash.
- `pid`: process id from marker filename.
- `serverUrl`: full MCP URL, including `/mcp`.
- `baseUrl`: `serverUrl` without `/mcp`.
- `label`: derived from marker contents (IDE name/build) or user override.
- `status`: `online | degraded | offline | stale`.
- `lastSeenAt`: timestamp of last successful health check.
- `tools`: last cached `tools/list` result from that server.
- `resources`: last cached `resources/list` result from that server.
- `lastError`: last error string and timestamp.

## 8. Dynamic tool aggregation
### 8.1 Tool listing
`tools/list` returns the union of upstream tools plus proxy-native tools.

### 8.2 Collision handling
When multiple upstream servers provide the same tool name:
- Add an optional `server_id` property to the tool schema at the proxy level.
- Expose an alias for direct routing: `<serverId>.<toolName>`.
- If no routing hint is present and multiple servers support the tool, return an ambiguity error.

This avoids hard-coding tool lists and allows new upstream tools without proxy updates.

### 8.3 Resource aggregation (optional)
`resources/list` aggregates upstream resources.
- If a resource URI is identical across servers, keep a single entry (default server wins).
- For resources that exist on multiple servers, provide a namespaced alias:
  - `mcp-steroid+proxy://<serverId>/<url-encoded-original-uri>`
- `resources/read` resolves the alias to the correct upstream.

## 9. Routing decision tree (explicit)
Resolve the upstream server in this order:
1. `server_id` argument (if provided).
2. Namespaced tool alias (`<serverId>.<toolName>`).
3. Mapping by `projectId` or `projectName` (from aggregated project list).
4. Mapping by `windowId` (from aggregated windows list).
5. If only one server is online, use it.
6. If a `defaultServerId` is configured, use it.
7. Otherwise return an error: ambiguous routing.

If the target server does not support the tool, return an error indicating tool absence and list the servers that do support it.

## 10. Aggregated list_projects and list_windows (map/reduce)
### 10.1 `steroid_list_projects` (aggregated)
Fan-out to all online servers (Map) and return a merged result (Reduce):
- Each project includes `serverId`, `serverLabel`, `serverUrl`.
- Add `projectId = <serverId>::<path>` (fallback to `name` if path missing).
- Include `errors` for per-server failures.
- Optional `projectsGroupedByPath` for dedupe view only (do not drop per-server entries).

### 10.2 `steroid_list_windows` (aggregated)
Fan-out to all online servers (Map) and return a merged result (Reduce):
- Each window includes `serverId`, `serverLabel`, `serverUrl`.
- `windowId` must be namespaced: `<serverId>::<rawWindowId>`.
- Preserve raw id as `serverWindowId`.
- Include `backgroundTasks` with server identity.
- Include `errors` for per-server failures.

### 10.3 Proxy-native introspection tools
- `proxy_list_servers`: registry entries (id, label, url, status, lastSeenAt, toolCount, resourceCount).
- `proxy_list_projects` and `proxy_list_windows`: same as aggregated calls plus `servers` block and timing metadata.

## 11. Caching and traffic recording
### 11.1 Directory layout
Default: `~/.mcp-steroid/`
- `~/.mcp-steroid/proxy-cache/` (tool/resource lists, aggregated list results)
- `~/.mcp-steroid/traffic/` (JSONL traffic log)

### 11.2 Traffic log (optional)
When enabled, append JSONL records for every MCP message:
```
{"ts":"...","direction":"in","session":"...","method":"tools/call","serverId":null,"requestId":"...","payload":{...}}
{"ts":"...","direction":"out","session":"...","serverId":"pid:123","requestId":"...","payload":{...}}
```
- Redact fields by default (e.g., `code`, `content`).
- Traffic logging is not result caching.

### 11.3 Cache policy
- Cache `tools/list`, `resources/list`, `steroid_list_projects`, `steroid_list_windows` per server with TTL.
- Do not cache mutating tools by default.
- Allow per-tool cache override in config.

## 12. Configuration
Default config file: `~/.mcp-steroid/proxy.json`

Suggested structure:
```
{
  "scanIntervalMs": 2000,
  "defaultServerId": null,
  "allowHosts": ["127.0.0.1", "localhost"],
  "toolListTtlMs": 5000,
  "listProjectsTtlMs": 2000,
  "listWindowsTtlMs": 2000,
  "cache": {
    "enabled": false,
    "dir": "~/.mcp-steroid/proxy-cache",
    "ttlMs": 5000
  },
  "trafficLog": {
    "enabled": false,
    "dir": "~/.mcp-steroid/traffic",
    "redactFields": ["code", "content"]
  },
  "concurrency": {
    "maxFanout": 4,
    "requestTimeoutMs": 15000,
    "backoffMs": 2000
  }
}
```

CLI flags (minimum):
```
--config <path>      Custom config file
--scan-interval <ms> Override scan interval
--log-traffic        Enable traffic logging
```

## 13. Validation checklist
- Marker file appears for a live PID -> proxy lists server within `scanIntervalMs + 1s`.
- Marker file for dead PID is ignored.
- `tools/list` includes new upstream tools without proxy code changes.
- `steroid_list_projects` and `steroid_list_windows` return merged results with server identity.
- `windowId` is namespaced and collision-safe.
- Routing decision tree resolves with `server_id` and errors on ambiguity.
- Servers can join/leave without proxy restart.
- Traffic log writes under `~/.mcp-steroid/traffic` when enabled.
- Proxy never writes to stdout except valid MCP responses.
- Newer NPX falls back to MCP-only mode when bridge endpoints are absent.
- Older NPX remains functional against newer plugin versions.

## 14. Upstream improvements (optional, tracked separately)
These are not required for the proxy to work but would simplify and harden it:
- Add dedicated bridge endpoints (Ktor handlers) for NPX control plane, for example:
  - `GET /npx/v1/summary`
  - `POST /npx/v1/tools/call/stream`
- Publish a stable discovery document (e.g., `/.well-known/mcp.json`) with transports, URL, auth, version, and tools hash.
- Add a server info tool/endpoint returning plugin/build version, IDE/platform version, and feature flags.
- Define deterministic error codes plus retryable flag for proxy classification.
- Extend tool metadata with idempotency/side effects, rate limits, default timeouts, and minimum IDE version.
- Add schema versioning with ETag/Last-Modified for caching and revalidation.
- Provide health/ready checks reporting indexing state, queue depth, and uptime.
- Include tools digest in init plus optional toolsChanged event for live refresh.
- Guarantee stdio-safe output (no unsolicited stdout, logs on stderr, optional length framing).
- Add session keepalive/heartbeat and graceful reconnect semantics.
- Include input/output examples in tool schemas for proxy validation.
- Add a CLI-friendly print-connection-info command (URL/port/protocol) for auto-config.
- Introduce capability negotiation/version field in init to avoid mismatches.
