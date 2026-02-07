FROM debian:bookworm-slim

# Install system dependencies: Xvfb, window manager, fonts, utils
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        xvfb \
        x11-utils \
        fluxbox \
        fonts-dejavu \
        ffmpeg \
        scrot \
        feh \
        imagemagick \
        curl \
        git \
        jq \
        ripgrep \
        unzip \
        rsync \
        procps \
        ca-certificates \
        gnupg \
        libxi6 \
        libxrender1 \
        libxtst6 \
        libxext6 \
        libx11-6 \
        libfreetype6 \
        libfontconfig1 \
        xdotool \
        xclip \
    && rm -rf /var/lib/apt/lists/*

# Install JDK 21 (Eclipse Temurin via Adoptium APT)
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-21-jdk && \
    rm -rf /var/lib/apt/lists/*

# Install Node.js 20 (NodeSource APT)
RUN mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

ARG CACHE_BUST=1

# Install Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

# Install Codex CLI
RUN npm install -g @openai/codex

# Install Gemini CLI
RUN npm install -g @google/gemini-cli

# Create non-root user
RUN useradd -m -s /bin/bash agent && \
    mkdir -p /home/agent/.local/bin && \
    mkdir -p /home/agent/.codex && \
    mkdir -p /home/agent/.gemini && \
    chown -R agent:agent /home/agent

# Copy and extract IntelliJ IDEA (pre-downloaded by Gradle)
COPY idea.tar.gz /tmp/idea.tar.gz
RUN mkdir -p /opt/idea && chmod a+rwx /opt && \
    tar -xzf /tmp/idea.tar.gz -C /opt/idea --strip-components=1 && \
    rm /tmp/idea.tar.gz

# Video streaming server (serves growing MP4 over HTTP for live preview)
COPY video-server.js /usr/local/bin/video-server.js

# Desktop wallpaper
COPY wallpaper.jpg /usr/share/images/mcp-steroid-wallpaper.jpg

# Switch to agent user
USER agent
WORKDIR /home/agent

ENV PATH="/home/agent/.local/bin:/opt/idea/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent

CMD ["sleep", "infinity"]
