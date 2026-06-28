import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

// ── CIDR validation ────────────────────────────────────────────────────────────
const CIDR_REGEX = /^(\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/;

// RFC1918 address space: 10/8, 172.16/12, 192.168/16
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

function isRfc1918(cidr) {
  const ip = cidr.split("/")[0];
  const n = ipToUint32(ip);
  return RFC1918_RANGES.some(([lo, hi]) => n >= lo && n <= hi);
}

function validateForm(agentId, cidr, portProfile, customPorts) {
  if (!agentId) return "Please select an active agent.";
  if (!CIDR_REGEX.test(cidr)) return "Enter a valid CIDR notation (e.g. 192.168.1.0/24).";
  if (!isRfc1918(cidr))
    return "CIDR must be a private network address (10.0.0.0/8, 172.16.0.0/12, or 192.168.0.0/16).";
  if (portProfile === "CUSTOM") {
    const ports = customPorts
      .split(",")
      .map((p) => p.trim())
      .filter(Boolean);
    if (ports.length === 0) return "Enter at least one custom port.";
    if (ports.length > 500) return "Maximum 500 custom ports allowed.";
    if (ports.some((p) => isNaN(p) || +p < 1 || +p > 65535))
      return "All ports must be numbers between 1 and 65535.";
  }
  return null;
}

export function NetworkScanModal({ token, orgId, agents, toast, onClose, onCreated }) {
  const [agentId, setAgentId]         = useState("");
  const [cidr, setCidr]               = useState("");
  const [portProfile, setPortProfile] = useState("COMMON_TLS");
  const [customPorts, setCustomPorts] = useState("");
  const [submitting, setSubmitting]   = useState(false);
  const [error, setError]             = useState("");

  const activeAgents = (agents || []).filter((a) => a.status === "ACTIVE");

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");

    const validationMsg = validateForm(agentId, cidr, portProfile, customPorts);
    if (validationMsg) {
      setError(validationMsg);
      return;
    }

    const body = {
      agentId,
      cidr,
      portProfile,
      customPorts:
        portProfile === "CUSTOM"
          ? customPorts
              .split(",")
              .map((p) => parseInt(p.trim(), 10))
              .filter((n) => !isNaN(n))
          : [],
    };

    setSubmitting(true);
    try {
      await api.networkScans.create(token, orgId, body);
      toast("Network scan started", "success");
      onCreated();
    } catch (e) {
      // Map known ProblemDetail / status codes to inline messages (not toast)
      const pd = e.problemDetail || {};
      if (
        e.status === 400 &&
        (pd.code === "SCOPE_VIOLATION" ||
          (pd.type || "").includes("scope-violation") ||
          (pd.detail || "").toLowerCase().includes("cidr"))
      ) {
        setError("CIDR is outside this agent's allowed network ranges.");
      } else if (e.status === 400) {
        setError(e.message || "Invalid request.");
      } else if (e.status === 409) {
        setError("A scan is already running for this agent.");
      } else if (e.status === 402) {
        setError("Scans are blocked — subscription suspended.");
      } else {
        setError(e.message || "An unexpected error occurred.");
      }
    } finally {
      setSubmitting(false);
    }
  };

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
          Select an agent and CIDR range to sweep for open ports and TLS
          certificates. Only private (RFC 1918) ranges are permitted.
        </p>

        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="ns-agent">Agent</label>
            <select
              id="ns-agent"
              value={agentId}
              onChange={(e) => setAgentId(e.target.value)}
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
              <div
                style={{
                  fontSize: "0.72rem",
                  color: "var(--muted)",
                  marginTop: 4,
                }}
              >
                No active agents. Deploy an agent first from the Agents panel.
              </div>
            )}
          </div>

          <div className="field">
            <label htmlFor="ns-cidr">CIDR Range</label>
            <input
              id="ns-cidr"
              type="text"
              placeholder="192.168.1.0/24"
              value={cidr}
              onChange={(e) => setCidr(e.target.value.trim())}
              required
              disabled={submitting}
              aria-required="true"
              aria-describedby={error ? "ns-error" : undefined}
              autoComplete="off"
              spellCheck={false}
            />
          </div>

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
              <div
                style={{
                  fontSize: "0.7rem",
                  color: "var(--muted)",
                  marginTop: 4,
                }}
              >
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
              disabled={submitting || activeAgents.length === 0}
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
