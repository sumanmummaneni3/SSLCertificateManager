# Self-Hosted Mail Server — Setup & DNS Runbook

CertGuard production sends notification email through its **own mail server**: the
`mailserver` service in `server/docker-compose.prod.yml` (send-only Postfix + OpenDKIM,
image `boky/postfix`). The app submits unauthenticated on the private docker network;
the MTA DKIM-signs and delivers **directly to recipient MX hosts over port 25**. No
external relay is involved, and the `MAIL_*` entries in `.env` are **inert in prod** —
the app's mail settings are literals in the compose override.

> **A mail server that runs is not a mail server that delivers.** Delivery depends on
> the DNS/provider steps below. Until they are done, recipients (Gmail especially) will
> reject or junk everything. Do them in order; none are optional.

## 1. One-time prerequisites

### 1.1 Outbound port 25 must be open from the VPS

```bash
timeout 7 bash -c 'exec 3<>/dev/tcp/gmail-smtp-in.l.google.com/25; head -1 <&3' \
  && echo "PORT 25 OPEN" || echo "PORT 25 BLOCKED"
```

If blocked, request an unblock from the VPS provider (usually a support ticket; they
ask what you're sending — "transactional certificate-expiry alerts for our own SaaS,
SPF/DKIM configured" is the honest answer). Nothing else in this runbook matters until
this says OPEN.

### 1.2 Confirm the VPS's public egress IP

```bash
curl -s ifconfig.me
```

Use this IP (not an assumed one) in the DNS records below. Referred to as `<VPS_IP>`.

## 2. DNS records (at the `oopsssl.co.uk` DNS provider)

| # | Type | Name | Value | Why |
|---|------|------|-------|-----|
| 1 | TXT (SPF) — **modify existing** | `oopsssl.co.uk` | `v=spf1 include:spf.protection.outlook.com ip4:<VPS_IP> -all` | The current record authorizes ONLY Microsoft (`-all` = hard-fail everyone else). Without adding `ip4:<VPS_IP>`, every recipient is instructed to reject our mail. Keep the outlook include — inbound/mailbox mail still flows through M365. |
| 2 | TXT (DKIM) — **new** | `mail._domainkey.oopsssl.co.uk` | From the container — see §3.2. Starts `v=DKIM1; h=sha256; k=rsa; p=MIIB…` | Cryptographic proof the mail came from us. Gmail effectively requires it. |
| 3 | A — **new** | `mail.oopsssl.co.uk` | `<VPS_IP>` | The MTA's HELO identity must resolve. |
| 4 | PTR (reverse DNS) — at the **VPS provider**, not the DNS host | `<VPS_IP>` | `mail.oopsssl.co.uk` | Most large receivers score/reject on missing or mismatched rDNS. Set in the VPS control panel. |
| 5 | TXT (DMARC) — **new, recommended** | `_dmarc.oopsssl.co.uk` | `v=DMARC1; p=none; rua=mailto:postmaster@oopsssl.co.uk` | Start at `p=none` (monitor); tighten to `quarantine` after a clean week of reports. |

## 3. Bring-up on the VPS

### 3.1 Start (arrives via normal deploy — compose files are git-pulled by `vps-deploy.sh`)

```bash
cd /opt/certguard
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d mailserver
docker logs certguard-mailserver 2>&1 | grep -i dkim   # confirm key generation on first boot
```

The DKIM key persists in the `mailserver_dkim` volume — it is generated once and
survives recreates. **Do not delete the volume** after publishing the DNS record, or
you'll have to publish a new key.

### 3.2 Extract the DKIM DNS record

```bash
docker exec certguard-mailserver sh -c 'find /etc/opendkim -name "*.txt" -exec cat {} \;'
```

Publish the printed value as DNS record #2. The quoted chunks are one long string —
most DNS providers accept them pasted as-is; if not, concatenate the `p=` parts.

### 3.3 Recreate the app so it targets the internal MTA

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --force-recreate --no-deps app
docker logs certguard-app --since 2m 2>&1 | grep -i "SMTP AUTH disabled"
# expect: "SMTP AUTH disabled — submitting unauthenticated to internal MTA at mailserver"
```

## 4. Verify end-to-end

```bash
# 1. Queued outbox alerts should start draining (they retry automatically):
docker exec certguard-postgres psql -U certguard_user -d certguard -c \
 "select status, count(*) from notification_outbox group by status;"

# 2. Watch the MTA hand mail to real MX hosts:
docker logs certguard-mailserver -f 2>&1 | grep -E "status=sent|status=bounced|status=deferred"

# 3. Send yourself a test (force-scan a target with an expiring cert, or re-send an invite)
#    and check the received headers show:  DKIM: PASS, SPF: PASS  (Gmail: "Show original").
```

`status=sent` + SPF/DKIM PASS in Gmail's "Show original" = done. The UI degraded
banner clears itself once the backlog drains.

## 5. Operational notes

- **Not an open relay**: the MTA publishes no host ports; only containers on
  `certguard-net` can reach it, and it only accepts envelope senders under
  `oopsssl.co.uk` (`ALLOWED_SENDER_DOMAINS`).
- **Reputation is earned slowly.** A fresh IP sending low, steady transactional volume
  is normally fine; a burst (e.g. a huge queued backlog draining at once) can trip
  greylisting/deferrals — `status=deferred` with retries is Postfix handling that
  normally; the outbox on top retries the app layer. Don't panic on first-day deferrals.
- **Rollback**: revert the app's mail env by removing the compose override literals
  (or setting them back to a relay) and `--force-recreate` the app. The outbox keeps
  everything queued in the interim; nothing is lost while switching transports.
- **Monitoring**: the delivery-status banner covers app-level failures. MTA-level
  deferrals/bounces are visible only in `certguard-mailserver` logs (see GAPS R20 —
  no metrics yet).
