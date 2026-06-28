import { useState, useMemo } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

// ── CIDR validation (used for manual / advanced custom input) ─────────────────
const CIDR_REGEX = /^(\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/;

// RFC 1918 address space: 10/8, 172.16/12, 192.168/16
const RFC1918_RANGES = [
  [0x0a000000, 0x0affffff], // 10.0.0.0 – 10.255.255.255
  [0xac100000, 0xac1fffff], // 172.16.0.0 – 172.31.255.255
  [0xc0a80000, 0xc0a8ffff], // 192.168.0.0 – 192.168.255.255
];

function ipToUint32(ip) {
  return ip
    .split(".")
    .reduce((acc, octet) => ((acc << 8) | parseInt(octet, 10)) >>> 0, 0);
}

function isRfc1918Cidr(cidr) {
  const ip = cidr.split("/")[0];
  const n = ipToUint32(ip);
  return RFC1918_RANGES.some(([lo, hi]) => n >= lo && n <= hi);
}

function validateCidrInput(cidr) {
  if (!cidr) return "Enter a CIDR range.";
  if (!CIDR_REGEX.test(cidr)) return "Enter a valid CIDR notation (e.g. 192.168.1.0/24).";
  if (!isRfc1918Cidr(cidr))
    return "CIDR must be a private network address (10.0.0.0/8, 172.16.0.0/12, or 192.168.0.0/16).";
  return null;
}

function validatePortProfile(portProfile, customPorts) {
  if (portProfile !== "CUSTOM") return null;
  const ports = customPorts
    .split(",")
    .map((p) => p.trim())
    .filter(Boolean);
  if (ports.length === 0) return "Enter at least one custom port.";
  if (ports.length > 500) return "Maximum 500 custom ports allowed.";
  if (ports.some((p) => isNaN(p) || +p < 1 || +p > 65535))
    return "All ports must be numbers between 1 and 65535.";
  return null;
}

// ── Component ─────────────────────────────────────────────────────────────────

export function NetworkScanModal({ token, orgId, agents, toast, onClose, onCreated }) {
  const [agentId, setAgentId]         = useState("");
  const [portProfile, setPortProfile] = useState("COMMON_TLS");
  const [customPorts, setCustomPorts] = useState("");
  const [submitting, setSubmitting]   = useState(false);
  const [error, setError]             = useState("");

  // Subnet picker state
  const [selectedSubnets, setSelectedSubnets] = useState(new Set());
  const [showCustomCidr, setShowCustomCidr]   = useState(false);
  const [customCidr, setCustomCidr]           = useState("");

  const activeAgents = (agents || []).filter((a) => a.status === "ACTIVE");

  // Derive selected agent + its discovered subnets
  const selectedAgent = useMemo(
    () => activeAgents.find((a) => a.id === agentId) ?? null,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [agentId, agents]
  );
  const discoveredSubnets = selectedAgent?.discoveredSubnets ?? [];
  const hasSubnets = discoveredSubnets.length > 0;

  // Reset subnet selection whenever agent changes
  const handleAgentChange = (newId) => {
    setAgentId(newId);
    const ag = activeAgents.find((a) => a.id === newId);
    const subnets = ag?.discoveredSubnets ?? [];
    setSelectedSubnets(new Set(subnets)); // all checked by default
    setShowCustomCidr(false);
    setCustomCidr("");
    setError("");
  };

  const toggleSubnet = (cidr) => {
    setSelectedSubnets((prev) => {
      const next = new Set(prev);
      if (next.has(cidr)) next.delete(cidr);
      else next.add(cidr);
      return next;
    });
  };

  const allChecked =
    discoveredSubnets.length > 0 &&
    selectedSubnets.size === discoveredSubnets.length;

  const toggleAll = () => {
    setSelectedSubnets(allChecked ? new Set() : new Set(discoveredSubnets));
  };

  // Build the list of CIDR strings to scan.
  // null in the list means "omit cidr from body" → server fan-out across all discovered subnets.
  const getCidrTargets = () => {
    if (!hasSubnets || showCustomCidr) {
      // manual / advanced mode — single explicit CIDR
      return [customCidr.trim()];
    }
    if (allChecked) {
      return [null]; // null → body omits cidr field → server-side fan-out
    }
    return [...selectedSubnets];
  };

  const validate = () => {
    if (!agentId) return "Please select an active agent.";

    const portsErr = validatePortProfile(portProfile, customPorts);
    if (portsErr) return portsErr;

    if (hasSubnets && !showCustomCidr) {
      if (selectedSubnets.size === 0) return "Select at least one subnet to scan.";
      return null;
    }
    // Manual CIDR (either no-subnet fallback or advanced override)
    return validateCidrInput(customCidr.trim());
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");

    const msg = validate();
    if (msg) { setError(msg); return; }

    const cidrTargets   = getCidrTargets();
    const customPortNums =
      portProfile === "CUSTOM"
        ? customPorts
            .split(",")
            .map((p) => parseInt(p.trim(), 10))
            .filter((n) => !isNaN(n))
        : [];

    setSubmitting(true);
    try {
      // One API call per CIDR target (null = fan-out, no cidr field in body)
      for (const cidr of cidrTargets) {
        const body = { agentId, portProfile, customPorts: customPortNums };
        if (cidr != null) body.cidr = cidr;
        await api.networkScans.create(token, orgId, body);
      }
      const count = cidrTargets.length;
      toast(count > 1 ? `${count} network scans started` : "Network scan started", "success");
      onCreated();
    } catch (err) {
      const pd = err.problemDetail || {};
      if (
        err.status === 400 &&
        (pd.code === "SCOPE_VIOLATION" ||
          (pd.type || "").includes("scope-violation") ||
          (pd.detail || "").toLowerCase().includes("cidr"))
      ) {
        setError("CIDR is outside this agent's allowed network ranges.");
      } else if (err.status === 400) {
        setError(err.message || "Invalid request.");
      } else if (err.status === 409) {
        setError("A scan is already running for this agent.");
      } else if (err.status === 402) {
        setError("Scans are blocked — subscription suspended.");
      } else {
        setError(err.message || "An unexpected error occurred.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  // Start Scan enabled when: not submitting, agent selected, and subnet/CIDR requirement met
  const canSubmit =
    !submitting &&
    activeAgents.length > 0 &&
    !!agentId &&
    (hasSubnets && !showCustomCidr
      ? selectedSubnets.size > 0
      : !!customCidr.trim());

  return (
    <div
      className="modal-bg"
      role="dialog"
      aria-modal="true"
      aria-labelledby="ns-modal-title"
    >
      <div className="modal" style={{ maxWidth: 520 }}>
        <div className="modal-title" id="ns-modal-title">
          Scan Network
        </div>
        <p className="modal-sub">
          Select an agent and the subnets to sweep for open ports and TLS certificates.
          Only private (RFC 1918) ranges are permitted.
        </p>

        <form onSubmit={handleSubmit} noValidate>
          {/* ── Agent selector ── */}
          <div className="field">
            <label htmlFor="ns-agent">Agent</label>
            <select
              id="ns-agent"
              value={agentId}
              onChange={(e) => handleAgentChange(e.target.value)}
              required
              disabled={submitting}
              aria-required="true"
            >
              <option value="">Select an active agent…</option>
              {activeAgents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
            {activeAgents.length === 0 && (
              <div style={{ fontSize: "0.72rem", color: "var(--muted)", marginTop: 4 }}>
                No active agents. Deploy an agent first from the Agents panel.
              </div>
            )}
          </div>

          {/* ── Subnet / CIDR section (only after an agent is chosen) ── */}
          {agentId && (
            hasSubnets ? (
              /* ── Discovered subnets: checkbox list ── */
              <div className="field">
                <div
                  style={{
                    display:        "flex",
                    alignItems:     "center",
                    justifyContent: "space-between",
                    marginBottom:   "0.5rem",
                  }}
                >
                  <label style={{ marginBottom: 0 }}>Subnets to scan</label>
                  <button
                    type="button"
                    className="btn-ghost"
                    style={{ fontSize: "0.72rem", padding: "2px 8px" }}
                    onClick={toggleAll}
                    disabled={submitting}
                    aria-label={allChecked ? "Deselect all subnets" : "Select all subnets"}
                  >
                    {allChecked ? "Deselect all" : "Select all"}
                  </button>
                </div>

                <div
                  style={{
                    background:    "var(--surface2)",
                    border:        "1px solid var(--border2)",
                    borderRadius:  "var(--radius)",
                    padding:       "0.375rem",
                    display:       "flex",
                    flexDirection: "column",
                    gap:           "0.125rem",
                  }}
                  role="group"
                  aria-label="Discovered subnets"
                >
                  {discoveredSubnets.map((cidr) => {
                    const checked = selectedSubnets.has(cidr);
                    return (
                      <label
                        key={cidr}
                        style={{
                          display:     "flex",
                          alignItems:  "center",
                          gap:         "0.5rem",
                          padding:     "0.4rem 0.5rem",
                          borderRadius: "var(--radius)",
                          cursor:      submitting ? "default" : "pointer",
                          background:  checked ? "rgba(0,212,255,0.06)" : "transparent",
                          transition:  "background 0.12s",
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => !submitting && toggleSubnet(cidr)}
                          disabled={submitting}
                          style={{
                            width:       15,
                            height:      15,
                            accentColor: "var(--accent)",
                            cursor:      submitting ? "default" : "pointer",
                            flexShrink:  0,
                          }}
                          aria-label={cidr}
                        />
                        <span
                          style={{
                            fontFamily: "var(--font-mono)",
                            fontSize:   "0.85rem",
                            color:      "var(--text)",
                          }}
                        >
                          {cidr}
                        </span>
                      </label>
                    );
                  })}
                </div>

                {selectedSubnets.size === 0 && !showCustomCidr && (
                  <div style={{ fontSize: "0.72rem", color: "var(--yellow)", marginTop: 4 }}>
                    Select at least one subnet to enable scanning.
                  </div>
                )}

                {/* Advanced: custom CIDR toggle */}
                <button
                  type="button"
                  style={{
                    marginTop:  "0.6rem",
                    background: "none",
                    border:     "none",
                    color:      "var(--muted)",
                    fontSize:   "0.72rem",
                    cursor:     "pointer",
                    padding:    0,
                    display:    "flex",
                    alignItems: "center",
                    gap:        "0.3rem",
                  }}
                  onClick={() => {
                    setShowCustomCidr((v) => !v);
                    setCustomCidr("");
                  }}
                  aria-expanded={showCustomCidr}
                  aria-controls="ns-adv-cidr-wrap"
                >
                  <span aria-hidden="true">{showCustomCidr ? "▾" : "▸"}</span>
                  Advanced: enter a custom CIDR instead
                </button>

                {showCustomCidr && (
                  <div id="ns-adv-cidr-wrap" style={{ marginTop: "0.5rem" }}>
                    <input
                      id="ns-adv-cidr"
                      type="text"
                      placeholder="192.168.1.0/24"
                      value={customCidr}
                      onChange={(e) => setCustomCidr(e.target.value.trim())}
                      disabled={submitting}
                      autoComplete="off"
                      spellCheck={false}
                      aria-label="Custom CIDR range"
                    />
                    <div style={{ fontSize: "0.7rem", color: "var(--muted)", marginTop: 4 }}>
                      RFC 1918 only — overrides the checkboxes above.
                    </div>
                  </div>
                )}
              </div>
            ) : (
              /* ── No discovered subnets yet: info banner + manual fallback ── */
              <div className="field">
                <div
                  className="alert alert-info"
                  role="status"
                  style={{ marginBottom: "0.75rem" }}
                >
                  <span aria-hidden="true">ℹ</span>
                  <span>
                    Waiting for agent to report its subnets. This may take up to a minute after
                    the agent starts. You can enter a CIDR manually to scan now.
                  </span>
                </div>
                <label htmlFor="ns-cidr">CIDR Range</label>
                <input
                  id="ns-cidr"
                  type="text"
                  placeholder="192.168.1.0/24"
                  value={customCidr}
                  onChange={(e) => setCustomCidr(e.target.value.trim())}
                  required
                  disabled={submitting}
                  aria-required="true"
                  aria-describedby={error ? "ns-error" : "ns-cidr-hint"}
                  autoComplete="off"
                  spellCheck={false}
                />
                <div
                  id="ns-cidr-hint"
                  style={{ fontSize: "0.7rem", color: "var(--muted)", marginTop: 4 }}
                >
                  RFC 1918 only: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
                </div>
              </div>
            )
          )}

          {/* ── Port profile ── */}
          <div className="field">
            <label htmlFor="ns-profile">Port Profile</label>
            <select
              id="ns-profile"
              value={portProfile}
              onChange={(e) => setPortProfile(e.target.value)}
              disabled={submitting}
            >
              <option value="COMMON_TLS">
                COMMON_TLS — ports 443, 8443, 9443, 993, 995…
              </option>
              <option value="EXTENDED">
                EXTENDED — Common TLS + HTTP (80, 8080, 8008…)
              </option>
              <option value="CUSTOM">CUSTOM — specify ports manually</option>
            </select>
          </div>

          {portProfile === "CUSTOM" && (
            <div className="field">
              <label htmlFor="ns-custom">Custom Ports (comma-separated)</label>
              <input
                id="ns-custom"
                type="text"
                placeholder="443, 8443, 9000, 6443"
                value={customPorts}
                onChange={(e) => setCustomPorts(e.target.value)}
                disabled={submitting}
                autoComplete="off"
              />
              <div style={{ fontSize: "0.7rem", color: "var(--muted)", marginTop: 4 }}>
                Up to 500 ports, each between 1 and 65535.
              </div>
            </div>
          )}

          {error && (
            <div
              id="ns-error"
              className="alert alert-error"
              role="alert"
              style={{ marginBottom: "1rem" }}
            >
              {error}
            </div>
          )}

          <div className="modal-actions">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={!canSubmit}
            >
              {submitting ? (
                <>
                  <Spinner />
                  Starting…
                </>
              ) : (
                "Start Scan"
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
