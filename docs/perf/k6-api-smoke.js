/**
 * CertGuard API — k6 smoke test
 *
 * Targets:
 *   - GET  /api/v1/targets          (list targets, paginated)
 *   - POST /api/v1/targets/{id}/scan (trigger scan on first found target)
 *
 * Thresholds (smoke — raise for soak/stress):
 *   - http_req_duration p95 < 500 ms
 *   - http_req_failed   < 1 %
 *
 * Usage:
 *   BASE_URL=https://localhost:8443 JWT_TOKEN=<token> k6 run k6-api-smoke.js
 *
 * To obtain a JWT in dev-mode:
 *   curl -sk -X POST 'https://localhost:8443/api/v1/auth/dev-token?email=perf@certguard.local&role=ADMIN' | jq -r .token
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const errorRate    = new Rate('error_rate');
const listLatency  = new Trend('list_targets_duration',  true);
const scanLatency  = new Trend('trigger_scan_duration',  true);

// ── Options ───────────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    smoke: {
      executor:    'constant-vus',
      vus:         5,
      duration:    '30s',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_duration:    ['p(95)<500'],  // 95th percentile under 500 ms
    http_req_failed:      ['rate<0.01'],  // less than 1 % errors
    list_targets_duration: ['p(95)<400'],
    trigger_scan_duration: ['p(95)<800'], // scan is heavier
  },
  insecureSkipTLSVerify: true,
};

// ── Config (override via env vars) ────────────────────────────────────────────
const BASE_URL  = __ENV.BASE_URL  || 'https://localhost:8443';
const JWT_TOKEN = __ENV.JWT_TOKEN || 'REPLACE_ME_WITH_A_VALID_JWT';

const HEADERS = {
  'Authorization': `Bearer ${JWT_TOKEN}`,
  'Content-Type':  'application/json',
  'Accept':        'application/json',
};

// ── Helpers ───────────────────────────────────────────────────────────────────
function listTargets() {
  const res = http.get(`${BASE_URL}/api/v1/targets?page=0&size=20`, { headers: HEADERS });
  listLatency.add(res.timings.duration);

  const ok = check(res, {
    'list-targets: status 200':        (r) => r.status === 200,
    'list-targets: has content array': (r) => {
      try { return Array.isArray(JSON.parse(r.body).content); }
      catch { return false; }
    },
  });
  errorRate.add(!ok);
  return res;
}

function triggerScan(targetId) {
  const res = http.post(
    `${BASE_URL}/api/v1/targets/${targetId}/scan`,
    null,
    { headers: HEADERS }
  );
  scanLatency.add(res.timings.duration);

  const ok = check(res, {
    'trigger-scan: status 200 or 202': (r) => r.status === 200 || r.status === 202,
  });
  errorRate.add(!ok);
}

// ── Default VU function ───────────────────────────────────────────────────────
export default function () {
  // 1. List targets — always run
  const listRes = listTargets();

  // 2. Trigger scan on first target found — best-effort
  try {
    const body    = JSON.parse(listRes.body);
    const targets = body.content;
    if (Array.isArray(targets) && targets.length > 0) {
      const targetId = targets[0].id;
      triggerScan(targetId);
    }
  } catch (_) {
    // body parse failed — already recorded as error via check above
  }

  sleep(1); // ~1 req/s per VU
}
