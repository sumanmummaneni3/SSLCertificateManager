# VPS Setup Runbook — CertGuard Production

Run every command below on the VPS unless a section is labelled **"on your local machine"** or **"on GitHub"**.

---

## 1. Install Docker (Ubuntu / Debian)

```bash
# Install Docker Engine + Compose plugin in one shot
curl -fsSL https://get.docker.com | sh

# Verify
docker --version
docker compose version
```

---

## 2. Create a dedicated deploy user

Never run the stack as root. A `deploy` user with Docker access is the minimum.

```bash
sudo useradd -m -s /bin/bash deploy
sudo usermod -aG docker deploy

# Verify docker access (should print server version, no sudo needed)
sudo -u deploy docker info --format '{{.ServerVersion}}'
```

---

## 3. Clone the repo

```bash
sudo mkdir -p /opt/certguard
sudo chown deploy:deploy /opt/certguard

# Replace with your actual repo URL
sudo -u deploy git clone https://github.com/sumanmummaneni3/SSLCertificateManager.git /opt/certguard

ls /opt/certguard        # should show docker-compose.yml, server/, ui/, etc.
```

---

## 4. Create the `.env` file

The `.env` is never in the repo — create it on the VPS by hand. Start from the example in `server/.env` and change every value marked below.

```bash
sudo -u deploy nano /opt/certguard/.env
```

Minimum required contents (replace ALL angle-bracket values):

```dotenv
# PostgreSQL
POSTGRES_DB=certguard
POSTGRES_USER=certguard_user
POSTGRES_PASSWORD=<strong-random-password>

# RabbitMQ
RABBITMQ_DEFAULT_USER=certguard_rmq
RABBITMQ_DEFAULT_PASS=<strong-random-password>
RABBITMQ_VHOST=/certguard

# JWT — must be ≥ 64 characters, random
JWT_SECRET=<64-char-random-string>

# Google OAuth — real credentials from console.cloud.google.com
GOOGLE_CLIENT_ID=<your-google-client-id>
GOOGLE_CLIENT_SECRET=<your-google-client-secret>

# Public URL of the server (used in agent bundle download URLs)
APP_BASE_URL=https://<your-domain>:8443

# Set to false in production
APP_DEV_MODE=false

# Gateway CORS — your production frontend domain
GATEWAY_CORS_ALLOWED_ORIGINS=https://<your-domain>

# SSL keystore password (must match the keystore file in server/certs/)
SSL_KEYSTORE_PASSWORD=<strong-random-password>

# Alerts
ALERT_THRESHOLD_WARNING_DAYS=30
ALERT_THRESHOLD_CRITICAL_DAYS=7

# Grafana
GRAFANA_PASSWORD=<strong-random-password>

# Mail (optional — leave blank to disable)
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=noreply@<your-domain>

# Platform admin(s) — comma-separated emails
PLATFORM_ADMIN_EMAILS=<your-email>

# GHCR image tag to deploy — updated by vps-deploy.sh automatically
GITHUB_REPOSITORY_OWNER=sumanmummaneni3
APP_IMAGE_TAG=latest

# Agent artifact download (set after first tagged release)
# AGENT_ARTIFACT_URL_TEMPLATE=https://github.com/sumanmummaneni3/SSLCertificateManager/releases/download/%s/certguard-agent.jar
# APP_RELEASE_TAG=v1.2.0
```

---

## 5. Generate TLS certificates for nginx

### Option A — Let's Encrypt (recommended for production)

```bash
sudo apt install -y certbot

# Stop anything using port 80 first, then:
sudo certbot certonly --standalone -d <your-domain>

# Copy certs into the location docker-compose.yml expects
sudo cp /etc/letsencrypt/live/<your-domain>/fullchain.pem /opt/certguard/server/certs/nginx.crt
sudo cp /etc/letsencrypt/live/<your-domain>/privkey.pem   /opt/certguard/server/certs/nginx.key
sudo chown deploy:deploy /opt/certguard/server/certs/nginx.*
```

Set up auto-renewal (Let's Encrypt certs expire every 90 days):

```bash
# Test renewal works
sudo certbot renew --dry-run

# Add a cron job to copy renewed certs and reload nginx
sudo crontab -e
# Add this line:
# 0 3 * * * certbot renew --quiet && cp /etc/letsencrypt/live/<your-domain>/fullchain.pem /opt/certguard/server/certs/nginx.crt && cp /etc/letsencrypt/live/<your-domain>/privkey.pem /opt/certguard/server/certs/nginx.key && docker exec certguard-nginx nginx -s reload
```

### Option B — Self-signed (local testing only)

```bash
cd /opt/certguard/server/certs
openssl req -x509 -newkey rsa:4096 -keyout nginx.key -out nginx.crt \
  -days 365 -nodes -subj "/CN=localhost"
chown deploy:deploy nginx.key nginx.crt
```

---

## 6. Generate the Spring Boot SSL keystore (first time only)

The app needs a PKCS12 keystore at `server/certs/certguard.p12` for its HTTPS listener.

```bash
cd /opt/certguard/server/certs

# Use the nginx cert you already generated, or generate a separate one
openssl pkcs12 -export \
  -in nginx.crt -inkey nginx.key \
  -out certguard.p12 \
  -name certguard \
  -passout pass:<SSL_KEYSTORE_PASSWORD-from-env>

chown deploy:deploy certguard.p12
chmod 600 certguard.p12
```

---

## 7. Make deploy scripts executable

```bash
chmod +x /opt/certguard/scripts/vps-deploy.sh
chmod +x /opt/certguard/scripts/vps-rollback.sh
```

---

## 8. Set up GHCR authentication (pull images)

The VPS needs a GitHub Personal Access Token (PAT) to pull private images from GHCR.

### Create the PAT (on GitHub)

1. Go to **GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)**
2. Click **Generate new token (classic)**
3. Name: `certguard-vps-pull`
4. Scopes: check **`read:packages`** only
5. Copy the token — you'll only see it once

### Log in to GHCR on the VPS

```bash
# As the deploy user
sudo -u deploy bash -c \
  'echo "<YOUR_PAT>" | docker login ghcr.io -u sumanmummaneni3 --password-stdin'

# Verify (should print "Login Succeeded")
sudo -u deploy docker pull ghcr.io/sumanmummaneni3/certguard-app:latest
```

The credentials are saved to `/home/deploy/.docker/config.json` and persist across reboots.

---

## 9. First deploy

Run the deploy script on the VPS whenever you are ready. GitHub Actions builds and pushes the images to GHCR automatically on every push to `main` — the VPS just pulls them.

```bash
sudo -u deploy bash /opt/certguard/scripts/vps-deploy.sh latest
```

### Verify the stack is healthy

```bash
sudo -u deploy docker compose -f /opt/certguard/docker-compose.yml \
  -f /opt/certguard/docker-compose.prod.yml ps
```

All services should show `healthy` within 2 minutes.

---

## 10. Smoke test

```bash
# Health check through nginx → gateway → app
curl -k https://localhost/api/v1/health        # or use your domain

# Actuator (internal)
curl -k https://localhost:8443/actuator/health
```

Expected: `{"status":"UP",...}`

---

## Quick reference — useful commands after setup

```bash
# View logs (all services)
sudo -u deploy docker compose -f /opt/certguard/docker-compose.yml \
  -f /opt/certguard/docker-compose.prod.yml logs -f

# View logs (single service)
sudo -u deploy docker compose -f /opt/certguard/docker-compose.yml \
  -f /opt/certguard/docker-compose.prod.yml logs -f app

# Deploy a specific image tag manually
sudo -u deploy bash /opt/certguard/scripts/vps-deploy.sh <sha-or-tag>

# Roll back to a previous tag
sudo -u deploy bash /opt/certguard/scripts/vps-rollback.sh <previous-sha>

# Restart a single service (e.g. after .env change)
sudo -u deploy docker compose -f /opt/certguard/docker-compose.yml \
  -f /opt/certguard/docker-compose.prod.yml restart app

# Update repo files only (no image pull)
cd /opt/certguard && sudo -u deploy git pull --ff-only
```
