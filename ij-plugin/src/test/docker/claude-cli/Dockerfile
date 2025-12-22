FROM ubuntu:22.04

ARG CACHE_BUST=1

# Install dependencies including Node.js 20+ (required for Claude Code CLI)
# Following the same pattern as Codex CLI Dockerfile for consistency
RUN apt-get update && \
    apt-get install -y curl ca-certificates gnupg git ripgrep jq && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Claude Code CLI globally as root (before creating user)
# This follows the same pattern as Codex CLI installation
RUN npm install -g @anthropic-ai/claude-code

# Create a non-root user for Claude CLI
RUN useradd -m -s /bin/bash claude && \
    mkdir -p /home/claude/.local/bin && \
    chown -R claude:claude /home/claude

# Switch to claude user
USER claude
WORKDIR /home/claude

ENV PATH="/home/claude/.local/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/claude

# Create work directory
RUN mkdir -p /home/claude/work
WORKDIR /home/claude/work

# Default command - keep container running
CMD ["sleep", "infinity"]
