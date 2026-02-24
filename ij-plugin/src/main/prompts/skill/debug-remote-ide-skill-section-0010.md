# How to Debug Another IDE Instance (for AI Agents)

> **This guide was written entirely by AI agents** using MCP Steroid while working on the IntelliJ Platform codebase.
>
> **Note:** Specific plugin names, paths, and internal details use generic placeholders. Replace `YOUR_PLUGIN_ID`, `YourAction`, and example paths with your actual values.

**Guide for AI Agents:** Debugging IntelliJ-based IDEs (CLion, IDEA, Rider, etc.) using IntelliJ's debugger

---

## Overview

This guide explains how an AI agent can debug an IntelliJ-based IDE (like CLion) by:
1. Launching the IDE in debug mode from IntelliJ IDEA
2. Using MCP Steroid to interact with the debugged IDE
3. Taking screenshots to observe UI state
4. Using the debugger to inject code and inspect runtime state
5. Testing plugin functionality programmatically

**Use Case:** Validating a plugin in the target IDE while having full debugger control

---

## Architecture: Two IDEs Working Together

```
┌─────────────────────────────────────┐
│  IntelliJ IDEA (Debugger Host)      │
│                                     │
│  - intellij project open            │
│  - Run Configurations available     │
│  - Debugger UI active               │
│  - MCP Steroid connected            │
│  - Can execute Kotlin code          │
│  - Can set breakpoints              │
└────────────┬────────────────────────┘
             │ JDWP Debug Connection
             │ (port 60228, etc.)
             ▼
┌─────────────────────────────────────┐
│  Target IDE (Debugged)              │
│                                     │
│  - Running with -agentlib:jdwp      │
│  - Plugin under test loaded         │
│  - UI may be visible or headless    │
│  - Fully controllable via debugger  │
│  - State inspectable                │
└─────────────────────────────────────┘
```

**Key Insight:** IntelliJ IDEA becomes your "control center" for debugging any target IDE

---

## Step 1: Identify Available Run Configurations
