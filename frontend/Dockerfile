# BUILD
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY index.html vite.config.ts tsconfig.json tsconfig.app.json tsconfig.node.json ./
COPY public ./public
COPY src ./src

ARG VITE_API_URL=/api/v1
ARG VITE_WS_URL=/ws
ENV VITE_API_URL=$VITE_API_URL \
  VITE_WS_URL=$VITE_WS_URL

RUN npm run build

# RUN
FROM nginx:alpine

# Upgrade base OS packages, then let the built-in unprivileged "nginx" user (uid 101) run the server:
# PID file moved to /tmp, cache dir made writable, "user" directive dropped (only valid as root)
RUN apk upgrade --no-cache && \
  sed -i -e '/^user /d' -e 's#^pid .*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf && \
  chown -R nginx:nginx /var/cache/nginx

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
