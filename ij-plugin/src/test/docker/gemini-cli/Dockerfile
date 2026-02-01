FROM ubuntu:22.04

# Install dependencies including Node.js 20+
RUN apt-get update && \
    apt-get install -y curl ca-certificates gnupg git jq && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ARG CACHE_BUST=1

# Install Gemini CLI globally
RUN npm install -g @google/gemini-cli

# Create non-root user
RUN useradd -m -s /bin/bash gemini && \
    mkdir -p /home/gemini/.gemini && \
    chown -R gemini:gemini /home/gemini

USER gemini
WORKDIR /home/gemini

ENV PATH="/home/gemini/.local/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/gemini

RUN mkdir -p /home/gemini/work
WORKDIR /home/gemini/work

RUN rm -f /home/gemini/work/.gemini/settings.json

CMD ["sleep", "infinity"]
