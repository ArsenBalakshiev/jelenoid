# -------- Stage 1: build --------
FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci --frozen-lockfile

COPY . .
RUN npm run build

FROM nginx:1.25-alpine
ENV PORT=80

WORKDIR /usr/share/nginx/html
COPY --from=builder /app/dist ./
COPY --from=builder /app/dist/vite-envs.sh ./vite-envs.sh

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
RUN chmod +x ./vite-envs.sh

ENTRYPOINT ["sh","-c","./vite-envs.sh && exec nginx -g 'daemon off;'"]

EXPOSE $PORT
