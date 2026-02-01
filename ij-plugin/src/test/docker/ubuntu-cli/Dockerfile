FROM ubuntu:22.04

RUN apt-get update && \
    apt-get install -y curl ca-certificates gnupg && \
    rm -rf /var/lib/apt/lists/*

ARG CACHE_BUST=1

# Default command - keep container running
CMD ["sleep", "infinity"]

