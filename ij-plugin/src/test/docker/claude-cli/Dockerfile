FROM ubuntu:22.04

# Install dependencies
RUN apt-get update && \
    apt-get install -y curl ca-certificates && \
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
