FROM ubuntu:22.04

# Install dependencies required by Claude Code CLI:
# - curl, ca-certificates, gnupg: for downloading and package signing
# - git: required for repository operations
# - ripgrep: used by Claude's Search tool for fast file searching
# - jq: JSON processor (used by Claude internally)
# - Node.js 20+: Claude Code is a Node.js application (requires Node 18+)
RUN apt-get update && \
    apt-get install -y \
        curl \
        ca-certificates \
        gnupg \
        git \
        ripgrep \
        jq && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user for Claude CLI
RUN useradd -m -s /bin/bash claude && \
    mkdir -p /home/claude/.local/bin && \
    chown -R claude:claude /home/claude

# Switch to claude user
USER claude
WORKDIR /home/claude

# Install Claude CLI as the claude user
RUN curl -fsSL https://claude.ai/install.sh | bash

ENV PATH="/home/claude/.local/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/claude

# Create work directory
RUN mkdir -p /home/claude/work
WORKDIR /home/claude/work

# Default command - keep container running
CMD ["sleep", "infinity"]
