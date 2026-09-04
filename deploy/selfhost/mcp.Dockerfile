FROM node:22-slim

ENV NODE_ENV=production

WORKDIR /app

RUN corepack enable

COPY --chown=node:node mcp/package.json mcp/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --prod

COPY --chown=node:node mcp/server.js ./server.js

USER node

EXPOSE 8514

CMD ["node", "server.js"]
