# RFC 0011 — Frontend Engineer Handover

You are building the React UI for RFC 0011: Network Discovery & TLS Sweep. This adds two things to the CertGuard UI: (1) an authenticated "Network Scans" section for org users, and (2) a public anonymous scan dashboard at `/scan/:viewToken` for unauthenticated marketing visitors. Read `docs/architecture/rfcs/0011-network-discovery-and-tls-sweep.md` for design rationale. This doc gives you every file path, component spec, API contract, and UX flow you need to start coding.

## Critical baseline: no React Router exists today

The app currently uses a **manual `phase`/`view` state machine** — there is no `react-router-dom` in the project. `App.jsx` switches on `phase` (login, onboarding, app), and `AppShell.jsx` switches on `view` (a string like `"dashboard"`, `"targets"`, etc.) to render panels. Navigation is done by calling `setView("targets")`, not by changing the URL.

**For RFC 0011, do not introduce React Router.** Add the new screens to the existing pattern first. A separate React Router migration has been scoped as RFC 0012. Building RFC 0011 on the existing pattern keeps the scope contained and avoids regression risk across the whole app.

---

## Existing code patterns to follow

Before writing any new code, read these files:

| File | What to learn |
|---|---|
| `ui/src/lib/api.js` | The `api` object — how every backend call is made (`api.call(method, path, body, token, opts)`) |
| `ui/src/layout/AppShell.jsx` | How `view` state drives which panel renders; how props (`token`, `org`, `me`, `toast`) flow down |
| `ui/src/layout/navGroups.js` | How sidebar nav items are declared (`id`, `icon`, `label`) |
| `ui/src/layout/Sidebar.jsx` | How nav items are rendered |
| `ui/src/panels/agents/index.jsx` | A good reference panel: polling, loading states, role-gated actions |
| `ui/src/components/Badge.jsx` | Existing badge/status component |
| `ui/src/components/Spinner.jsx` | Loading spinner |
| `ui/src/lib/helpers.js` | `fmtDate`, `fmtRelative` — use these for all date formatting |

---

## Part A: Authenticated Network Scans

### New files to create

```
ui/src/panels/network-scans/
  index.jsx                  ← NetworkScansView (scan list + "Scan Network" button)
  NetworkScanDetailView.jsx  ← progress + endpoints table for one scan
  NetworkScanModal.jsx       ← create-scan form (modal)
  DiscoveredEndpointTable.jsx← filterable endpoint results table
```

### Wire `NetworkScansView` into the app

**1. Add to `navGroups.js`** — insert a new item in the "Monitor" group:

```js
// ui/src/layout/navGroups.js
{ id: "network-scans", icon: "◮", label: "Network Scans" },
```

Place it after Certificates in the Monitor group.

**2. Add to `AppShell.jsx`** — two import lines and two render blocks:

```jsx
// imports (top of AppShell.jsx)
import { NetworkScansView }      from "@/panels/network-scans/index.jsx";
import { NetworkScanDetailView } from "@/panels/network-scans/NetworkScanDetailView.jsx";

// state (alongside selectedCertId)
const [selectedScanId, setSelectedScanId] = useState(null);
const [networkScans, setNetworkScans] = useState([]);
const [networkScansLoading, setNetworkScansLoading] = useState(false);

// load function (alongside loadCerts, loadAgents)
const loadNetworkScans = useCallback(async () => {
  setNetworkScansLoading(true);
  try { setNetworkScans(await api.networkScans.list(token, org.id)); }
  catch (e) { toast("Failed to load network scans: " + e.message, "error"); }
  finally { setNetworkScansLoading(false); }
}, [token, org?.id, toast]);

// useEffect (alongside others)
useEffect(() => {
  if (view === "network-scans" || view === "network-scan-detail") loadNetworkScans();
}, [view, loadNetworkScans]);

// render blocks (alongside existing view === "certs" block)
{view === "network-scans" && (
  <NetworkScansView
    scans={networkScans}
    loading={networkScansLoading}
    token={token}
    org={org}
    me={me}
    toast={toast}
    onRefresh={loadNetworkScans}
    onSelectScan={(scanId) => { setSelectedScanId(scanId); setView("network-scan-detail"); }}
  />
)}
{view === "network-scan-detail" && selectedScanId && (
  <NetworkScanDetailView
    scanId={selectedScanId}
    orgId={org.id}
    token={token}
    me={me}
    toast={toast}
    onBack={() => { setView("network-scans"); loadNetworkScans(); }}
  />
)}
```

---

### `NetworkScansView` — `ui/src/panels/network-scans/index.jsx`

**Props:**
```js
{
  scans: NetworkScanResponse[],
  loading: boolean,
  token: string,
  org: { id: string, name: string },
  me: { permissions: { canWriteAgents: boolean } },
  toast: (msg, type) => void,
  onRefresh: () => void,
  onSelectScan: (scanId: string) => void,
}
```

**Renders:**
- Page header: "Network Scans" title + "Scan Network" button (ADMIN/ENGINEER only — check `me.permissions.canWriteAgents` as a proxy; or add `canCreateNetworkScan` to permissions server-side)
- If `loading`: `<Spinner lg />`
- If `scans.length === 0`: empty state ("No network scans yet. Click 'Scan Network' to discover TLS endpoints on your network.")
- Table of scans:

| Column | Source field | Notes |
|---|---|---|
| CIDR | `scan.cidr` | |
| Profile | `scan.portProfile` | badge: COMMON_TLS / EXTENDED / FULL / CUSTOM |
| Status | `scan.status` | colored badge (see status colors below) |
| TLS Found | `scan.tlsFoundCount` | |
| Hosts Scanned | `scan.hostsScanned` / `scan.hostsTotal` | `"12 / 254"` |
| Started | `scan.createdAt` | `fmtRelative(scan.createdAt)` |
| Agent | `scan.agentName` | |

- Row click → `onSelectScan(scan.id)`
- "Scan Network" button → open `<NetworkScanModal>`

**Status badge colors** (follow existing `agentStatusColor` pattern in `panels/agents/index.jsx`):
```js
const statusColor = {
  PENDING:     "pending",
  IN_PROGRESS: "pending",
  COMPLETE:    "active",
  FAILED:      "revoked",
  CANCELLED:   "revoked",
};
```

**Polling:** while any scan has `status === "PENDING"` or `status === "IN_PROGRESS"`, poll `onRefresh` every 5 seconds (same pattern as `AgentsView` lines 24-29):
```js
useEffect(() => {
  const needsPoll = scans.some(s => s.status === "PENDING" || s.status === "IN_PROGRESS");
  if (!needsPoll) return;
  const id = setInterval(onRefresh, 5000);
  return () => clearInterval(id);
}, [scans, onRefresh]);
```

---

### `NetworkScanModal` — `ui/src/panels/network-scans/NetworkScanModal.jsx`

**Props:**
```js
{
  token: string,
  orgId: string,
  agents: Agent[],      // pass from AppShell — already loaded
  onClose: () => void,
  onCreated: () => void,
  toast: (msg, type) => void,
}
```

**Form fields:**

| Field | Input type | Validation |
|---|---|---|
| Agent | `<select>` — list ACTIVE agents | required |
| CIDR | `<input type="text">` placeholder `192.168.1.0/24` | required; regex `/^(\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/`; must be RFC1918 |
| Port Profile | `<select>` | COMMON_TLS (default) / EXTENDED / CUSTOM |
| Custom Ports | `<input>` comma-separated (shown when profile=CUSTOM) | 1–65535 each; max 500 |

**Submit:**
```js
await api.networkScans.create(token, orgId, {
  agentId, cidr, portProfile, customPorts
});
toast("Network scan started", "success");
onCreated();
```

Error mapping — show in modal, not toast, for 4xx:
- `400 SCOPE_VIOLATION` → "CIDR is outside this agent's allowed network ranges."
- `409` → "A scan is already running for this agent."
- `402` → "Scans are blocked — subscription suspended."

---

### `NetworkScanDetailView` — `ui/src/panels/network-scans/NetworkScanDetailView.jsx`

**Props:**
```js
{
  scanId: string,
  orgId: string,
  token: string,
  me: object,
  toast: (msg, type) => void,
  onBack: () => void,
}
```

**Behavior:**
- On mount: `GET /api/v1/organizations/:orgId/network-scans/:scanId`
- While `status === "PENDING" | "IN_PROGRESS"`: poll every 5s
- Once `COMPLETE | FAILED`: stop polling, load endpoints

**Layout:**
```
← Back button
[Scan header: CIDR / Profile / Status badge / Timestamps]

Progress bar (hostsScanned / hostsTotal) — only while IN_PROGRESS/PENDING
  "Scanning 192.168.1.0/24 — 42 of 254 hosts scanned"

Summary chips (once IN_PROGRESS or COMPLETE):
  [Open TLS Ports: 14]  [Open No-TLS: 6]  [Routers: 1]  [Servers: 8]

<DiscoveredEndpointTable scanId orgId token />
```

---

### `DiscoveredEndpointTable` — `ui/src/panels/network-scans/DiscoveredEndpointTable.jsx`

**Props:**
```js
{
  scanId: string,
  orgId: string,
  token: string,
}
```

**API call:** `GET /api/v1/organizations/:orgId/network-scans/:scanId/endpoints?state=&deviceClass=&page=0&size=50`

**Filters (above table):**
- State: All / Open TLS / Open No-TLS
- Device Class: All / Router / Switch / Server / Workstation / Unknown

**Table columns:**

| Column | Source | Notes |
|---|---|---|
| IP | `endpoint.ip` | monospace |
| Port | `endpoint.port` | |
| State | `endpoint.state` | badge: `OPEN_TLS` → green "TLS", `OPEN_NO_TLS` → yellow "Open", `CLOSED_OR_FILTERED` → grey |
| Device | `endpoint.deviceClass` | icon + label (see icons below) |
| TLS Subject | `endpoint.tlsSubjectCn` | only for OPEN_TLS rows |
| Expires | `endpoint.tlsNotAfter` | `fmtRelative(endpoint.tlsNotAfter)` — color-code red if < 30 days |
| Cert Status | `endpoint.tlsCertStatus` | reuse existing cert status badge from `CertsView` |
| Banners | `endpoint.banners` | collapsed tooltip / expand icon |

**Device class icons** (use text/emoji consistent with existing nav icons):
```js
const deviceIcon = {
  ROUTER:      "⬡",
  SWITCH:      "◫",
  SERVER:      "⊞",
  WORKSTATION: "◎",
  UNKNOWN:     "?",
};
```

**Pagination:** "Load more" button or page controls (backend returns a Page object: `{ content, totalElements, totalPages }`).

---

### New API methods — add to `ui/src/lib/api.js`

```js
// Network scan endpoints (authenticated)
networkScans: {
  create: (token, orgId, body) =>
    api.call("POST", `/api/v1/organizations/${orgId}/network-scans`, body, token),

  list: (token, orgId, page = 0, size = 20) =>
    api.call("GET", `/api/v1/organizations/${orgId}/network-scans?page=${page}&size=${size}`, null, token),

  get: (token, orgId, scanId) =>
    api.call("GET", `/api/v1/organizations/${orgId}/network-scans/${scanId}`, null, token),

  listEndpoints: (token, orgId, scanId, { state, deviceClass, page = 0, size = 50 } = {}) => {
    const params = new URLSearchParams({ page, size });
    if (state)       params.set("state", state);
    if (deviceClass) params.set("deviceClass", deviceClass);
    return api.call("GET",
      `/api/v1/organizations/${orgId}/network-scans/${scanId}/endpoints?${params}`,
      null, token);
  },

  cancel: (token, orgId, scanId) =>
    api.call("DELETE", `/api/v1/organizations/${orgId}/network-scans/${scanId}`, null, token),
},
```

---

## Part B: Anonymous Scan Dashboard

This is a **fully public, standalone page** — no login, no sidebar, no token in localStorage. It's served at `/scan/:viewToken`. Because there is no React Router, implement this as a **separate `phase` in `App.jsx`**, triggered when the app loads with `/scan/...` in the URL.

### How to trigger the anon phase

In `App.jsx`, in the mount effect (`useEffect(() => { ... }, [])` at line 156), add detection before the existing checks:

```js
// Detect anonymous scan dashboard: /scan/:viewToken
if (pathname.startsWith("/scan/")) {
  const viewToken = pathname.replace("/scan/", "").split("/")[0];
  if (viewToken) {
    setAnonViewToken(viewToken);
    setPhase("anon-scan");
    return;
  }
}
```

Add state: `const [anonViewToken, setAnonViewToken] = useState(null);`

Add render block (alongside other phase blocks):
```jsx
{phase === "anon-scan" && anonViewToken && (
  <AnonScanDashboard
    viewToken={anonViewToken}
    onSignUp={() => setPhase("launch")}
  />
)}
```

### New files to create

```
ui/src/screens/AnonScanDashboard.jsx   ← standalone public dashboard
ui/src/panels/network-scans/SubnetCard.jsx
ui/src/panels/network-scans/DeviceList.jsx
```

---

### `AnonScanDashboard` — `ui/src/screens/AnonScanDashboard.jsx`

**Props:**
```js
{
  viewToken: string,
  onSignUp: () => void,   // navigates to login/register flow
}
```

**API call:** `GET /api/v1/anon/sessions/:viewToken` — **no `Authorization` header**, no token arg.

Add to `api.js`:
```js
// Anonymous scan endpoints (no auth)
anon: {
  getSession: (viewToken) =>
    api.call("GET", `/api/v1/anon/sessions/${viewToken}`),   // no token arg

  claimSession: (viewToken, token) =>
    api.call("POST", `/api/v1/anon/sessions/${viewToken}/claim`, null, token),

  deleteSession: (viewToken) =>
    api.call("DELETE", `/api/v1/anon/sessions/${viewToken}`),
},
```

**Behavior:**
- On mount: fetch `api.anon.getSession(viewToken)`
- While `status === "ACTIVE"`: poll every 5s (agent is still running)
- Once `SCAN_COMPLETE`: stop polling, render full results
- If `status === "EXPIRED"` or 404: show "This scan has expired." message

**Layout (three states):**

**State 1 — ACTIVE (agent running):**
```
[CertGuard logo]  [Sign Up Free →]

  ╔════════════════════════════════════════╗
  ║  Your network scan is running...       ║
  ║  [animated spinner]                    ║
  ║  We're discovering subnets and         ║
  ║  checking for SSL certificates.        ║
  ╚════════════════════════════════════════╝
```

**State 2 — SCAN_COMPLETE (results):**
```
[CertGuard logo]  [Sign Up Free →]

  🔍 Network Scan Results

  ┌──────────┬──────────┬───────────┬────────────┐
  │ Subnets  │ Devices  │ TLS Found │ Routers    │
  │    2     │   14     │     5     │     1      │
  └──────────┴──────────┴───────────┴────────────┘

  Discovered Subnets:
  [SubnetCard: 192.168.1.0/24 — 8 devices, 3 TLS]
  [SubnetCard: 10.0.0.0/24   — 6 devices, 2 TLS]

  Devices Found:
  [DeviceList]

  ╔═══════════════════════════════════════════════╗
  ║  Want to see full details & run a deep scan?  ║
  ║  Sign up free — takes 30 seconds.             ║
  ║            [Create Free Account]              ║
  ║  Already have an account? [Sign In]           ║
  ╚═══════════════════════════════════════════════╝

  [Delete this scan]  (small link, bottom of page)
```

**State 3 — EXPIRED / not found:**
```
[CertGuard logo]

  This scan link has expired or doesn't exist.
  [Download CertGuard free →]
```

**"Create Free Account" / "Sign In" flow:**
- Both call `onSignUp()` — which sets `phase = "launch"` in `App.jsx`
- After successful login, the user lands in the `app` phase. The `viewToken` is already in the URL — `LaunchScreen` / post-login handler should detect it and call `api.anon.claimSession(viewToken, newToken)` to claim the scan.
- After claim, navigate to the network scans list: `setView("network-scans")` (or show a toast prompting "Your scan has been claimed. Run a full scan to see details.").

**Claim wiring in `App.jsx`:**
In `handleToken()` (line 104), after loading `org` and `me`, check if there's a pending `anonViewToken` and auto-claim:
```js
if (anonViewToken) {
  try {
    await api.anon.claimSession(anonViewToken, t);
    toast("Scan claimed — run a full network scan to see details.", "success");
    setAnonViewToken(null);
  } catch { /* non-fatal; session may already be claimed */ }
}
```

---

### `SubnetCard` — `ui/src/panels/network-scans/SubnetCard.jsx`

**Props:**
```js
{
  subnet: {
    id: string,
    cidr: string,
    deviceCount: number,
    tlsCount: number,
  }
}
```

**Renders:** a card showing the CIDR, device count, TLS cert count. No click action in anon mode.

```jsx
<div className="card">
  <div className="card-title">{subnet.cidr}</div>
  <div style={{ display: "flex", gap: "1rem" }}>
    <span>◎ {subnet.deviceCount} devices</span>
    <span>⊞ {subnet.tlsCount} TLS</span>
  </div>
</div>
```

---

### `DeviceList` — `ui/src/panels/network-scans/DeviceList.jsx`

**Props:**
```js
{
  devices: AnonDeviceDto[],   // from session response
}
```

Renders a simple table or card list — one row per device. No IP is shown (not stored). Show:
- Device class icon + label
- Open ports as chips: `[443]  [80]  [22]`
- TLS subjects (if any): e.g. `router.local`
- Earliest expiry: `fmtRelative(device.tlsExpiryMin)` — red if < 30 days
- Banners: `http_title` or `ssh_version` in a secondary line

```jsx
<div className="card">
  <span>{deviceIcon[device.deviceClass]} {device.deviceClass}</span>
  <div>{device.openPorts.map(p => <span key={p} className="port-chip">{p}</span>)}</div>
  {device.tlsSubjects?.length > 0 && (
    <div className="text-muted">{device.tlsSubjects.join(", ")}</div>
  )}
  {device.tlsExpiryMin && (
    <div className={isExpiringSoon(device.tlsExpiryMin) ? "text-danger" : ""}>
      Expires: {fmtRelative(device.tlsExpiryMin)}
    </div>
  )}
</div>
```

---

## API response shapes (reference)

### `NetworkScanResponse` (from `GET .../network-scans/:id`)
```json
{
  "id": "uuid",
  "orgId": "uuid",
  "agentId": "uuid",
  "agentName": "My Agent",
  "cidr": "192.168.1.0/24",
  "portProfile": "COMMON_TLS",
  "status": "IN_PROGRESS",
  "hostsTotal": 254,
  "hostsScanned": 42,
  "openPortCount": 18,
  "tlsFoundCount": 5,
  "errorMessage": null,
  "createdAt": "2026-06-28T12:00:00Z",
  "updatedAt": "2026-06-28T12:01:30Z"
}
```

### `Page<DiscoveredEndpointResponse>` (from `GET .../endpoints`)
```json
{
  "content": [
    {
      "id": "uuid",
      "networkScanId": "uuid",
      "ip": "192.168.1.10",
      "port": 443,
      "state": "OPEN_TLS",
      "deviceClass": "SERVER",
      "banners": { "http_server": "nginx/1.24.0", "tls_cn": "myapp.internal" },
      "certRecordId": "uuid",
      "tlsSubjectCn": "myapp.internal",
      "tlsNotAfter": "2027-03-15T00:00:00Z",
      "tlsCertStatus": "VALID",
      "createdAt": "2026-06-28T12:01:00Z"
    }
  ],
  "totalElements": 18,
  "totalPages": 1,
  "number": 0,
  "size": 50
}
```

### `AnonSessionDashboardResponse` (from `GET /api/v1/anon/sessions/:viewToken`)
```json
{
  "status": "SCAN_COMPLETE",
  "scanExpiresAt": "2026-06-28T13:00:00Z",
  "viewExpiresAt": "2026-07-05T12:00:00Z",
  "summary": {
    "subnetCount": 2,
    "deviceCount": 14,
    "tlsFoundCount": 5,
    "routerCount": 1,
    "serverCount": 8
  },
  "subnets": [
    { "id": "uuid", "cidr": "192.168.1.0/24", "deviceCount": 8, "tlsCount": 3 }
  ],
  "devices": [
    {
      "id": "uuid",
      "subnetCidr": "192.168.1.0/24",
      "deviceClass": "ROUTER",
      "openPorts": [22, 80, 443, 161],
      "banners": { "http_title": "RouterOS", "tls_cn": "router.local" },
      "tlsSubjects": ["router.local"],
      "tlsExpiryMin": "2027-01-15T00:00:00Z"
    }
  ]
}
```

Note: no IP addresses in the anon response — the server never stores them.

---

## UX flows

### Flow 1: Authenticated user creates a network scan

```
1. User clicks "Network Scans" in sidebar → NetworkScansView loads
2. No scans yet → empty state with "Scan Network" button
3. Click "Scan Network" → NetworkScanModal opens
4. Select agent, enter CIDR (e.g. 192.168.1.0/24), choose COMMON_TLS
5. Click "Start Scan" → POST /api/v1/organizations/:orgId/network-scans
6. Success → modal closes, toast "Network scan started", scan list refreshes
7. New scan row appears with status "PENDING" → polling starts (5s)
8. Status changes to "IN_PROGRESS" → progress bar appears
9. Click scan row → NetworkScanDetailView with live progress bar
10. Status changes to "COMPLETE" → DiscoveredEndpointTable loads
11. User can filter by state (OPEN_TLS) or device class (ROUTER)
```

### Flow 2: Anonymous visitor runs a free scan

```
1. Visitor on certguard.io clicks "Free Network Scan"
2. Clicks "Download Agent" → personalised ZIP downloads (contains scanToken + server fingerprint)
3. Runs agent: java -jar certguard-agent.jar
4. Agent discovers local subnets, fingerprints devices, pushes results to server
5. Visitor opens dashboard URL from the download page or browser: /scan/<viewToken>
6. App.jsx mount effect detects /scan/... → phase = "anon-scan"
7. AnonScanDashboard renders, polls GET /api/v1/anon/sessions/:viewToken every 5s
8. Status transitions ACTIVE → SCAN_COMPLETE
9. Dashboard shows: 2 subnets, 14 devices, 5 TLS certs found
10. "Create Free Account" CTA displayed prominently
```

### Flow 3: Visitor signs up and claims their scan

```
1. Visitor on AnonScanDashboard clicks "Create Free Account"
2. App.jsx: onSignUp() → setPhase("launch")
3. LaunchScreen shown (app is now at phase "launch" but viewToken is remembered in state)
4. User registers (email or Google OAuth)
5. handleToken() fires after login
6. Detects anonViewToken is set → calls api.anon.claimSession(viewToken, newToken)
7. Claim succeeds → toast "Scan claimed — start a full network scan to see details."
8. anonViewToken cleared, app proceeds to normal "app" phase
9. User lands in AppShell at dashboard view
10. Can navigate to "Network Scans" to run a full authenticated NETWORK_SCAN
    using the claimed subnets as pre-populated CIDR input
```

### Flow 4: Visitor deletes their anonymous scan (GDPR)

```
1. On AnonScanDashboard, visitor clicks "Delete this scan" (small link, bottom)
2. Confirmation: "This will permanently delete your scan results. Are you sure?"
3. Confirm → DELETE /api/v1/anon/sessions/:viewToken
4. Response 204 → show "Your scan data has been deleted." message
5. AnonScanDashboard shows State 3 (expired/not found)
```

---

## Implementation sequence

Build in this order:

**Part A (authenticated):**
1. Add `networkScans.*` to `api.js`
2. Create `NetworkScansView/index.jsx` — scan list + empty state + polling
3. Create `NetworkScanModal.jsx` — create form
4. Wire `view === "network-scans"` into `AppShell.jsx`
5. Add nav item to `navGroups.js`
6. Create `DiscoveredEndpointTable.jsx`
7. Create `NetworkScanDetailView.jsx` — progress + endpoint table

**Part B (anonymous):**
8. Add `anon.*` to `api.js`
9. Create `SubnetCard.jsx` and `DeviceList.jsx`
10. Create `AnonScanDashboard.jsx`
11. Wire `phase === "anon-scan"` into `App.jsx` mount effect
12. Wire claim flow into `handleToken()` in `App.jsx`

Part A can be reviewed and merged first; Part B is independent.

---

## CSS / styling notes

Follow the existing pattern — use the CSS classes from `ui/src/styles/global.css` and `ui/src/index.css`:
- `.page-header`, `.page-title`, `.page-sub` — page headers
- `.btn`, `.btn-primary`, `.btn-secondary`, `.btn-sm`, `.btn-danger` — buttons
- `.card` — content cards
- `.modal-bg`, `.modal`, `.modal-title`, `.modal-sub`, `.modal-actions` — modals
- `.loading-center` + `<Spinner lg />` — full-panel loading state
- `.alert`, `.alert-warning` — alert banners
- `text-danger` — red text for expiring certs

For `AnonScanDashboard`, the page is bare (no `.app` shell), so wrap content in a centered container similar to `LaunchScreen`. Match the visual language but don't try to import `.app` layout classes.

For port chips (`[443]  [80]  [22]`), add a minimal new class:
```css
.port-chip {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 0.8rem;
  background: var(--surface-2, #2a2a3a);
  margin: 0 2px;
}
```
