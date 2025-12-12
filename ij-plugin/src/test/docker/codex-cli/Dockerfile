FROM ubuntu:22.04

# Install dependencies including Node.js 20+ (required for Codex)
RUN apt-get update && \
    apt-get install -y curl ca-certificates gnupg && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Codex CLI globally as root (before creating user)
RUN npm install -g @openai/codex

# Create a non-root user for Codex CLI
RUN useradd -m -s /bin/bash codex && \
    mkdir -p /home/codex/.local/bin && \
    mkdir -p /home/codex/.codex && \
    chown -R codex:codex /home/codex

# Switch to codex user
USER codex
WORKDIR /home/codex

ENV PATH="/home/codex/.local/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/codex

# Create work directory
RUN mkdir -p /home/codex/work
WORKDIR /home/codex/work

# Default command - keep container running
CMD ["sleep", "infinity"]
