# Stage 1: build
FROM node:20-alpine AS builder

WORKDIR /app

# Install dependencies first (layer-cached separately from source)
COPY package.json package-lock.json ./
RUN npm ci

# Copy source and build
COPY . .
ENV VITE_DEV_MODE=false
RUN npm run build

# Stage 2: serve
FROM nginx:alpine

# Copy built assets from stage 1
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
