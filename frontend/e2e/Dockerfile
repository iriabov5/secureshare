FROM node:26.13.0-bookworm AS node
FROM mcr.microsoft.com/playwright:v1.64.1-noble
COPY --from=node /usr/local/bin/node /usr/local/bin/node
RUN npm install --global npm@12.2.2 --no-audit --no-fund \
    && mkdir -p /workspace \
    && chown pwuser:pwuser /workspace
USER pwuser
WORKDIR /workspace
