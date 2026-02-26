ARG BASE_IMAGE=mcp-steroid-ide-base-test
FROM ${BASE_IMAGE}

USER root

# Install .NET 8 SDK via Microsoft's install script
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl libicu-dev && \
    curl -sSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --channel 8.0 --install-dir /usr/share/dotnet && \
    rm /tmp/dotnet-install.sh && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV DOTNET_ROOT=/usr/share/dotnet
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1

# Copy and extract Rider archive (pre-downloaded by Gradle)
COPY ide.tar.gz /tmp/ide.tar.gz
RUN mkdir -p /opt/idea && chmod a+rwx /opt && \
    tar -xzf /tmp/ide.tar.gz -C /opt/idea --strip-components=1 && \
    rm /tmp/ide.tar.gz

USER agent
WORKDIR /home/agent

ENV PATH="/home/agent/.local/bin:/usr/share/dotnet:/opt/idea/bin:/usr/local/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent

CMD ["sleep", "infinity"]
