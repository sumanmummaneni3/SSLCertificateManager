# RFC 0012 — Auto-discovered network scope (zero-CIDR enrollment)

**Status**: Draft  
**Author**: Architect (relayed via team-lead)  
**Relates to**: RFC 0011 (Network Discovery & TLS Sweep)

---

## TL;DR

The agent already knows how to enumerate its own NIC subnets (`agent/.../discovery/NicSubnetDiscovery.java`) and uses it in Part B. We extend that into Part A: the agent self-reports its real RFC1918 subnets at registration + heartbeat; the server stores them and enforces RFC1918 server-side; the admin never types a CIDR. `allowedCidrs` is demoted from a required input to an OPTIONAL manual override. We do NOT assume /24 — `NicSubnetDiscovery.toCidr()` reads the actual NIC prefix length, so we get the true mask per interface.

---

## Problem

When deploying a CertGuard agent for network scanning (Part A — authenticated), operators must manually specify CIDR ranges (e.g. `192.168.1.0/24`) during agent enrollment. This defeats the purpose of automatic network discovery — the operator already needs to know their subnets before the tool can discover them.

## Why the current gate is mostly theatre already

`AgentController.downloadConfig` (AgentController.java:99) hard-codes `certguard.agent.allowed-cidrs=192.168.0.0/16,10.0.0.0/8`. So in practice operators already ship a near-all-RFC1918 scope; the "gate" rarely constrains anything. The genuinely valuable security property — "never let an on-prem agent port-scan PUBLIC IP space" (legal/ToS/abuse risk) — must stay and is enforced server-side regardless of operator input.

## The two jobs `allowedCidrs` conflates

1. **SAFETY (abuse prevention)**: no scanning public space → keep ALWAYS, server-enforced RFC1918.
2. **SCOPING (least privilege)**: which subnets THIS agent may touch → this is the part that required operator knowledge. We replace it with agent self-report + optional admin override.

---

## Decision

**Option 1 (RECOMMENDED and adopted): self-report + server RFC1918 + discovered-subnet membership, no manual input, no approval gate.**

Lowest friction. Agent can physically only reach what it's plugged into; RFC1918 enforcement makes public scanning impossible even if the agent lies. Residual risk (scanning a private net the customer doesn't own) is inherent to any on-prem agent and unchanged from today.

`allowedCidrs` is retained as an **optional manual override** (covers coarse-scope use cases like "only 10.0.0.0/8"; full backward compatibility). Option 2 (admin approval gate) is available later behind an org-level `require_subnet_approval` flag if a compliance-sensitive customer requires it — NOT shipped by default.

### Options considered (not chosen)

| Option | Description | Decision |
|--------|-------------|----------|
| Option 2 | Agent reports → admin reviews/approves discovered subnets before first scan | Deferred; available as opt-in org setting `require_subnet_approval` |
| Option 3 | Admin sets coarse scope (e.g. `10.0.0.0/8`) instead of /24s | Covered: `allowedCidrs` retained as optional override |
| Option 4 | No gate, trust agent + RFC1918 only | Weaker UX — admin can't request a specific subnet; loses the UI picker |

---

## Effective-scope resolution (the new gate)

Implement in `NetworkScanService.createScan`:

1. If `agent.allowedCidrs` **non-empty** → today's behavior: requested cidr must be subset of allowedCidrs (`isCidrSubsetOfAllowed`, NetworkScanService.java:85). Backward compatible.
2. If `agent.allowedCidrs` **empty** (new default) → requested cidr must be (a) valid RFC1918 AND (b) subset of one of `agent.discoveredSubnets`.
3. **ALWAYS**, in both branches: reject any cidr that is not RFC1918 (new hard check — reuse `NicSubnetDiscovery.isPrivateCidr`).
4. If request `cidr` is **null/omitted** → "scan all discovered": server fans out one `NetworkScan` row per discovered RFC1918 subnet.

---

## CRITICAL compatibility catch

`AgentService.submitResult` calls `validateCidr(target.getHost(), agent.getAllowedCidrs())` (AgentService.java:216, 498) and **THROWS "Agent has no allowed CIDR ranges"** when `allowedCidrs` is empty. If we make `allowedCidrs` optional, every newly-enrolled agent's per-target cert scan (the existing RFC 0008 private-target flow) breaks.

**Fix**: when `allowedCidrs` is empty, `validateCidr` must fall back to enforcing `host ∈ (discoveredSubnets ∪ RFC1918)` instead of throwing. This is the **single highest-risk edit** — backend-engineer must address this explicitly.

---

## Change set (minimal)

### DB — new migration V40 (additive, safe)

> **Note**: V38 = network_scan_tables (RFC 0011 Part A), V39 = anon_scan_tables (RFC 0011 Part B). Next available version is V40.

```sql
-- V40__agent_discovered_subnets.sql
ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS discovered_subnets jsonb NOT NULL DEFAULT '[]'::jsonb;

-- Also verify allowed_cidrs defaults to '[]' for new rows (should already be set in V38)
-- If V38 declared it NOT NULL without a default, add:
-- ALTER TABLE agents ALTER COLUMN allowed_cidrs SET DEFAULT '[]'::jsonb;
```

Option 2 columns (deferred — only if `require_subnet_approval` org flag is requested):
```sql
ALTER TABLE organizations ADD COLUMN require_subnet_approval boolean NOT NULL DEFAULT false;
ALTER TABLE agents ADD COLUMN subnets_approved_at timestamptz;
```

### Server API

| File | Change |
|------|--------|
| `dto/request/AgentRegisterRequest.java:9` | Add optional `List<String> discoveredSubnets`; relax `allowedCidrs` from `@NotNull @Size(min=1)` to optional |
| `dto/request/CreateAgentRequest.java:24` | Relax `allowedCidrs` `@NotNull @Size(min=1)` → optional, default empty |
| `dto/response/AgentResponse.java` | Add `discoveredSubnets` field so UI can render subnet picker |
| `dto/request/NetworkScanCreateRequest.java:20` | Make `cidr` optional (null = scan all discovered); keep `@Pattern` only when present |
| `service/AgentService.java:119` | Persist `discoveredSubnets` on `register()`; fix `validateCidr` fallback (see critical catch above) |
| `service/NetworkScanService.java:85,91` | New effective-scope resolution (rules 1–4 above); relax concurrency guard from "one active scan per agent" to "one active per (agent, cidr)" to support fan-out |
| `controller/AgentController.java:149` | Heartbeat: accept optional body `{ discoveredSubnets: [...] }` and update column; tolerate empty/no body for old agents |
| `controller/AgentController.java:99` | `downloadConfig`: drop hard-coded `allowed-cidrs` line (or comment as optional override hint) |

### Agent

| File | Change |
|------|--------|
| `http/ServerApiClient.java:55` | `register()`: include `discoveredSubnets` from `new NicSubnetDiscovery().discoverSubnets()`; stop requiring `allowed-cidrs` |
| `http/ServerApiClient.java:79` | `heartbeat()`: periodically include discovered subnets (every heartbeat; cheap) |
| `config/AgentConfig.java:226` | `certguard.agent.allowed-cidrs` becomes fully optional |
| `AgentMain` / `PollLoop.java:95` | No change — server fans out per-subnet; agent stays one-cidr-per-NETWORK_SCAN job |

### UI

- **Enrollment**: REMOVE the CIDR input field entirely.
- **Scan launch**: show discovered subnets (from `AgentResponse.discoveredSubnets`) as checkboxes, default "Scan all"; advanced toggle allows typing a custom CIDR, validated client-side as RFC1918 + within discovered set.
- **Pending state**: if a brand-new agent hasn't reported yet (no heartbeat, empty `discoveredSubnets`), show "Waiting for agent to report subnets…".

---

## Part B (anonymous) — NO CHANGE

Part B already uses `NicSubnetDiscovery`, has no `allowedCidrs` gate, and is privacy-preserving (no IPs stored). This design makes Part A converge on Part B's auto-discovery model — good architectural coherence. The intentional difference remains: Part A stores IPs (authenticated, customer's own network) while Part B does not.

---

## Rollout ordering

**Deploy SERVER FIRST, then ship the updated agent.**

Rationale: a new agent sending empty `allowedCidrs` against an OLD server would be rejected by the old `@Size(min=1)` validation. The reverse (old agent + new server) works fine via the pinned-scope backward-compat path.

---

## Security summary

| Threat | Mitigation |
|--------|------------|
| Agent scans public IP space | RFC1918 enforced server-side on reported set AND on scan cidr — independent of agent behavior (defense in depth) |
| Agent lies about discovered subnets | Only RFC1918 subnets are trusted; server filters on ingest; public scanning remains impossible |
| Agent scans a private net it doesn't own | Inherent to any on-prem agent; unchanged from today's allowedCidrs model |

---

## Open question for the user

> **Do compliance-sensitive tenants need the Option 2 admin-approval gate** (admin must approve discovered subnets before the first scan runs), or is the zero-friction Option 1 acceptable for everyone?

**Architect recommendation**: Ship Option 1 now. Add Option 2 later behind an org-level `require_subnet_approval` flag only if a customer requires it. V40 does NOT include the Option 2 columns — they are deferred.

Please confirm this decision so implementation can begin.
