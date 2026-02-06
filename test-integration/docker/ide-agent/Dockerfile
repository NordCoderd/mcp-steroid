FROM debian:bookworm-slim

# Install system dependencies: Xvfb, window manager, fonts, utils
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        xvfb \
        fluxbox \
        fonts-dejavu \
        ffmpeg \
        scrot \
        imagemagick \
        curl \
        git \
        jq \
        unzip \
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

# Create non-root user
RUN useradd -m -s /bin/bash agent && \
    mkdir -p /home/agent/.local/bin && \
    chown -R agent:agent /home/agent

# Copy and extract IntelliJ IDEA (pre-downloaded by Gradle)
COPY idea.tar.gz /tmp/idea.tar.gz
RUN mkdir -p /opt/idea && \
    tar -xzf /tmp/idea.tar.gz -C /opt/idea --strip-components=1 && \
    rm /tmp/idea.tar.gz

# Copy plugin zip and install it
COPY plugin.zip /tmp/plugin.zip
RUN mkdir -p /home/agent/.config/JetBrains/IntelliJIdea2025.3/plugins && \
    cd /home/agent/.config/JetBrains/IntelliJIdea2025.3/plugins && \
    unzip /tmp/plugin.zip && \
    rm /tmp/plugin.zip && \
    chown -R agent:agent /home/agent/.config

# Copy test project
COPY test-project/ /home/agent/project/
RUN chown -R agent:agent /home/agent/project

# Copy IDE VM options
COPY idea.vmoptions /home/agent/.config/JetBrains/IntelliJIdea2025.3/idea64.vmoptions
RUN chown agent:agent /home/agent/.config/JetBrains/IntelliJIdea2025.3/idea64.vmoptions

# Copy entrypoint
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Accept IntelliJ EULA
RUN mkdir -p /home/agent/.config/JetBrains/consentOptions && \
    echo -n "rsch.send.usage.stat:1.1:0:$(date +%s)000" > /home/agent/.config/JetBrains/consentOptions/accepted && \
    mkdir -p /home/agent/.java/.userPrefs/jetbrains && \
    chown -R agent:agent /home/agent/.config /home/agent/.java

# Switch to agent user
USER agent
WORKDIR /home/agent

ENV DISPLAY=:99
ENV PATH="/home/agent/.local/bin:/opt/idea/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent
ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64

EXPOSE 6315

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
