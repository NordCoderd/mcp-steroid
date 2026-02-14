# MCP Steroid NPX Proxy

This folder contains the NPX proxy implemented in TypeScript.

The proxy is an MCP stdio server that:
- discovers all running MCP Steroid plugin instances via `~/.<pid>.mcp-steroid`
- aggregates MCP metadata/tools/resources across IDE instances
- routes tool calls to the freshest matching IDE/plugin instance

## Runtime model

- Client-facing transport: stdio MCP only (`stdin`/`stdout`)
- Upstream transport: HTTP MCP to plugin `/mcp` endpoints discovered from marker files
- No local HTTP server is exposed by NPX

## TypeScript-only policy

- Source code is in `.ts` files only
- Tests are in `.ts` files only
- Generated `.js` exists only under build outputs (`dist/`, `dist-test/`)

## Quick start

```bash
npm --prefix npx install
npm --prefix npx run build
node npx/dist/index.js
```

Or via package binary once published:

```bash
npx mcp-steroid-proxy
```

## Configuration

Default config path:

```
~/.mcp-steroid/proxy.json
```

Example:

```json
{
  "scanIntervalMs": 2000,
  "allowHosts": ["127.0.0.1", "localhost"],
  "cache": { "enabled": false, "dir": "~/.mcp-steroid/proxy", "ttlSeconds": 5 },
  "trafficLog": { "enabled": false, "redactFields": ["code"] },
  "upstreamTimeoutMs": 5000
}
```

CLI flags:

```
--config <path>         Custom config file
--scan-interval <ms>    Marker scan interval override
--log-traffic           Enable traffic logging
-h, --help              Print help
```

## Compatibility

- Older NPX + newer plugin: must continue working using MCP baseline behavior.
- Newer NPX + older plugin: NPX must degrade gracefully to MCP-only discovery/routing.
- Bridge-specific metadata must be additive and feature-detected; no hard requirement for legacy plugin versions.

## Architecture direction

Current routing works through MCP API (`tools/list`, `tools/call`, `resources/list`, `resources/read`).

For NPX-internal control-plane concerns (fresh metadata snapshots, project/path mapping stability, timeout-safe progress heartbeats), the intended direction is a dedicated plugin bridge API served by Ktor, while keeping external MCP client behavior unchanged.

## Spec

See `npx/specs.md` for detailed protocol and design notes.
