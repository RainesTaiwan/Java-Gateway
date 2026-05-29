FROM node:20-alpine

WORKDIR /runner
COPY package.json ./
RUN npm install --omit=dev

COPY cursor-runner.mjs ./

WORKDIR /app
ENTRYPOINT ["node", "/runner/cursor-runner.mjs"]
