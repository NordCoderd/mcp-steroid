---
title: "Getting Started"
description: "Install and set up MCP Steroid with your AI agent"
weight: 1
---

## Installation

MCP Steroid is distributed as an IntelliJ plugin. Currently in early access - [message Eugene Petrenko on LinkedIn](https://linkedin.com/in/jonnyzzz) to get access.

### Requirements

- IntelliJ IDEA 2025.3+ (or any IntelliJ-based IDE: Rider, Android Studio, GoLand, WebStorm, PyCharm, etc.)
- An MCP-compatible AI agent (Claude, GPT, Gemini, Cursor, or any MCP client)

### Install the Plugin

1. Download the plugin ZIP file (provided via LinkedIn)
2. In IntelliJ, go to **Settings > Plugins > Gear icon > Install Plugin from Disk**
3. Select the downloaded ZIP file
4. Restart IntelliJ

## Connecting Your AI Agent

When the plugin starts, it automatically creates a description file at `.idea/mcp-steroid.md` in each open project. This file contains the MCP server URL.

### Finding the Server URL

The MCP server URL is written to `.idea/mcp-steroid.md`. The first line contains the URL:

```
http://127.0.0.1:6315/mcp
```

### Claude Code CLI

```bash
claude mcp add --transport sse mcp-steroid http://127.0.0.1:6315/mcp
```

### Cursor

Add to your MCP configuration:

```json
{
  "mcpServers": {
    "mcp-steroid": {
      "url": "http://127.0.0.1:6315/mcp"
    }
  }
}
```

### Other MCP Clients

Any MCP-compatible client can connect using the HTTP/SSE transport at the server URL.

## Human Review Mode

By default, MCP Steroid requires human approval for all code execution (**ALWAYS** mode). When your AI agent sends code:

1. A notification appears in IntelliJ
2. You can review, edit, or reject the code
3. Only approved code gets executed

This ensures you maintain full control over what runs in your IDE.

## Next Steps

- [Configuration Options](/docs/configuration/) - Customize server settings, timeouts, and more
- [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues) - Report bugs or request features
