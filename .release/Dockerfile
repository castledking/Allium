FROM node:22-bookworm-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

RUN npx playwright install --with-deps chromium

COPY src/ ./src/

ENTRYPOINT ["node", "src/spigot-release.js"]
