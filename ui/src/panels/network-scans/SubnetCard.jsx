/**
 * SubnetCard — displays a discovered subnet with device + TLS counts.
 * Used in the anonymous scan dashboard (AnonScanDashboard).
 *
 * Props:
 *   subnet: { id, cidr, deviceCount, tlsCount }
 */
export function SubnetCard({ subnet }) {
  return (
    <div
      style={{
        background:      "var(--surface)",
        border:          "1px solid var(--border)",
        borderRadius:    12,
        padding:         "1rem 1.25rem",
        display:         "flex",
        alignItems:      "center",
        justifyContent:  "space-between",
        gap:             "1rem",
      }}
    >
      <div
        style={{
          fontFamily: "var(--font-mono)",
          fontWeight: 500,
          color:      "var(--text)",
          fontSize:   "0.9rem",
        }}
      >
        {subnet.cidr}
      </div>
      <div style={{ display: "flex", gap: "1.25rem", flexShrink: 0 }}>
        <span
          style={{
            fontSize:   "0.78rem",
            color:      "var(--muted)",
            display:    "flex",
            alignItems: "center",
            gap:        4,
          }}
        >
          <span aria-hidden="true">◎</span>
          {subnet.deviceCount} device{subnet.deviceCount !== 1 ? "s" : ""}
        </span>
        <span
          style={{
            fontSize:   "0.78rem",
            color:      subnet.tlsCount > 0 ? "var(--green)" : "var(--muted)",
            display:    "flex",
            alignItems: "center",
            gap:        4,
          }}
        >
          <span aria-hidden="true">⊞</span>
          {subnet.tlsCount} TLS
        </span>
      </div>
    </div>
  );
}
