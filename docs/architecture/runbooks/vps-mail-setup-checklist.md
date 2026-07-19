# VPS Mail Setup — Operator Checklist

Step-by-step checklist for bringing up the self-hosted mail server on the production
VPS (`ossrv01`). Work top to bottom; each step says what to run and what you should
see. Background and troubleshooting: [self-hosted-mail.md](self-hosted-mail.md).

> Prerequisite: the branch containing the `mailserver` service (docker-compose.prod.yml),
> the MailConfig/application.yml changes, and this doc has been **merged and deployed**
> (new app image pulled). Until then, stop here.

---

## Phase 1 — Preflight (on the VPS, ~2 min)

- [ ] **1a. Outbound port 25 is open**

  ```bash
  timeout 7 bash -c 'exec 3<>/dev/tcp/gmail-smtp-in.l.google.com/25; head -1 <&3' \
    && echo "PORT 25 OPEN" || echo "PORT 25 BLOCKED"
  ```

  ✅ Expect: a `220 …` banner line then `PORT 25 OPEN`.
  ❌ `PORT 25 BLOCKED` → open a support ticket with the VPS provider requesting
  outbound port 25 ("transactional certificate-expiry alerts for our own SaaS,
  SPF/DKIM configured"). **Park this checklist until unblocked.**

- [ ] **1b. Confirm the VPS egress IP**

  ```bash
  curl -s ifconfig.me ; echo
  ```

  ✅ Expected: `62.197.32.196` (what cloud.oopsssl.co.uk resolves to). If it prints
  something different, use the printed value as `<VPS_IP>` everywhere below.

---

## Phase 2 — Start the mail server & harvest the DKIM key (~5 min)

- [ ] **2a. Start the mailserver container**

  ```bash
  cd /opt/certguard
  docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d mailserver
  ```

- [ ] **2b. Confirm DKIM key generation (first boot only)**

  ```bash
  docker logs certguard-mailserver 2>&1 | grep -i dkim | head -5
  ```

  ✅ Expect lines like `Auto-generating DKIM key for oopsssl.co.uk` and
  `DNS TXT record written to mail.txt`.

- [ ] **2c. Extract the DKIM DNS record** (you'll paste this into DNS in Phase 3)

  ```bash
  docker exec certguard-mailserver sh -c 'find /etc/opendkim -name "*.txt" -exec cat {} \;'
  ```

  Copy the whole output. The record name is `mail._domainkey` and the value is the
  quoted string starting `v=DKIM1; h=sha256; k=rsa; p=MIIB…` (the quoted chunks are
  one long string — most DNS UIs accept them pasted as-is).

  ⚠️ The key lives in the `mailserver_dkim` docker volume. Never delete that volume
  after publishing the record, or DNS and the key will no longer match.

---

## Phase 3 — DNS (at the oopsssl.co.uk DNS provider, ~10 min + propagation)

- [ ] **3a. MODIFY the existing SPF record** on `oopsssl.co.uk` (TXT):

  ```
  v=spf1 include:spf.protection.outlook.com ip4:<VPS_IP> -all
  ```

  Only ADD the `ip4:` term — keep the outlook include (your M365 mailboxes still send
  through Microsoft). There must be exactly ONE SPF record on the domain.

- [ ] **3b. ADD the DKIM record** — TXT, name `mail._domainkey` (some UIs want the full
  `mail._domainkey.oopsssl.co.uk`), value from step 2c.

- [ ] **3c. ADD an A record** — `mail.oopsssl.co.uk` → `<VPS_IP>`.

- [ ] **3d. Set reverse DNS (PTR)** — done at the **VPS provider's panel**, not the DNS
  host: `<VPS_IP>` → `mail.oopsssl.co.uk`.

- [ ] **3e. (Recommended) ADD DMARC** — TXT, name `_dmarc`, value:

  ```
  v=DMARC1; p=none; rua=mailto:postmaster@oopsssl.co.uk
  ```

- [ ] **3f. Verify propagation** (from the VPS or anywhere):

  ```bash
  dig +short TXT oopsssl.co.uk | grep spf1          # must show ip4:<VPS_IP>
  dig +short TXT mail._domainkey.oopsssl.co.uk      # must show v=DKIM1…
  dig +short mail.oopsssl.co.uk                     # must show <VPS_IP>
  dig +short -x <VPS_IP>                            # must show mail.oopsssl.co.uk
  ```

  All four must answer before Phase 4. TTLs can take up to an hour.

---

## Phase 4 — Point the app at the internal MTA (~2 min)

- [ ] **4a. Recreate the app** (compose literals carry the new mail settings — no
  `.env` edits needed or wanted):

  ```bash
  cd /opt/certguard
  docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --force-recreate --no-deps app
  ```

- [ ] **4b. Confirm the app is in internal-MTA mode**

  ```bash
  docker logs certguard-app --since 2m 2>&1 | grep -i "smtp auth disabled"
  ```

  ✅ Expect: `SMTP AUTH disabled — submitting unauthenticated to internal MTA at mailserver`.

---

## Phase 5 — Verify real delivery (~10 min)

- [ ] **5a. Watch the queued backlog drain** (all previously-failed expiry alerts
  retry automatically — nothing was lost):

  ```bash
  docker exec certguard-postgres psql -U certguard_user -d certguard -c \
   "select status, count(*) from notification_outbox group by status;"
  docker logs certguard-mailserver -f 2>&1 | grep -E "status=sent|status=bounced|status=deferred"
  ```

  ✅ Expect PENDING count falling and `status=sent` lines appearing.
  ℹ️ `status=deferred` on day one is normal greylisting — Postfix retries by itself.

- [ ] **5b. End-to-end inbox test** — force-scan a target with an expiring cert (or
  re-send the lost invite to suman.mummaneni@gmail.com from the Team page), then in
  Gmail open the message → ⋮ → **Show original** and confirm:

  ```
  SPF:   PASS
  DKIM:  PASS  (domain oopsssl.co.uk)
  ```

- [ ] **5c. UI banner clears** — the "email delivery degraded" banner disappears on
  its own once the outbox backlog is drained (within ~3 min of the last send).

---

## Done / Rollback

**Done** when 5b shows both PASS and 5a shows the queue at zero PENDING.

**Rollback**: point the app back at any relay by overriding the MAIL_* values and
`--force-recreate --no-deps app`. The outbox holds all alerts durably while switching —
no mail is lost in either direction.
