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
        ripgrep \
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

# Copy and extract IntelliJ IDEA Community Edition (pre-downloaded by Gradle)
COPY idea.tar.gz /tmp/idea.tar.gz
RUN mkdir -p /opt/idea && \
    tar -xzf /tmp/idea.tar.gz -C /opt/idea --strip-components=1 && \
    rm /tmp/idea.tar.gz

# IDE directories — redirected via vmoptions to explicit paths
ENV IDE_CONFIG_DIR=/home/agent/ide-config
ENV IDE_PLUGINS_DIR=/home/agent/ide-plugins

# Copy plugin zip and install it
COPY plugin.zip /tmp/plugin.zip
RUN mkdir -p ${IDE_PLUGINS_DIR} && \
    cd ${IDE_PLUGINS_DIR} && \
    unzip /tmp/plugin.zip && \
    rm /tmp/plugin.zip && \
    chown -R agent:agent ${IDE_PLUGINS_DIR}

# Copy test project
COPY test-project/ /home/agent/project/
RUN chown -R agent:agent /home/agent/project

# VM options are generated and written by IdeContainerSession during container run

# Copy entrypoint
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Accept ALL IntelliJ EULA variants and data sharing consent via Java Preferences
# Keys from JetBrains test infrastructure (IDERunContext.kt): JetBrains.privacy_policy.*_accepted_version
RUN mkdir -p /home/agent/.java/.userPrefs/jetbrains/privacy_policy && \
    printf '<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">\n<map MAP_XML_VERSION="1.0">\n  <entry key="accepted_version" value="999.999"/>\n  <entry key="privacy_policy_accepted_version" value="999.999"/>\n  <entry key="eua_accepted_version" value="999.999"/>\n  <entry key="euaCommunity_accepted_version" value="999.999"/>\n  <entry key="ij_euaEap_accepted_version" value="999.999"/>\n</map>\n' \
        > /home/agent/.java/.userPrefs/jetbrains/privacy_policy/prefs.xml && \
    mkdir -p /home/agent/.config/JetBrains/consentOptions && \
    echo -n "rsch.send.usage.stat:1.1:0:$(date +%s)000" > /home/agent/.config/JetBrains/consentOptions/accepted && \
    chown -R agent:agent /home/agent/.config /home/agent/.java

# Pre-trust the project directory (skip "Trust and Open Project?" dialog)
# From JetBrains test infrastructure (IDETestContext.addProjectToTrustedLocations)
RUN mkdir -p ${IDE_CONFIG_DIR}/options && \
    printf '<application>\n  <component name="Trusted.Paths">\n    <option name="TRUSTED_PROJECT_PATHS">\n      <map>\n        <entry key="/home/agent/project" value="true" />\n      </map>\n    </option>\n  </component>\n  <component name="Trusted.Paths.Settings">\n    <option name="TRUSTED_PATHS">\n      <list>\n        <option value="/home/agent" />\n      </list>\n    </option>\n  </component>\n</application>\n' \
        > ${IDE_CONFIG_DIR}/options/trusted-paths.xml && \
    chown -R agent:agent ${IDE_CONFIG_DIR}/options

# Switch to agent user
USER agent
WORKDIR /home/agent

ENV DISPLAY=:99
ENV PATH="/home/agent/.local/bin:/opt/idea/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent
# JAVA_HOME is set dynamically in entrypoint since path varies by arch

EXPOSE 6315

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
