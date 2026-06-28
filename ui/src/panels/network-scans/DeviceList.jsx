import { fmtDate } from "@/lib/helpers.js";

// Maps device class enum to a text icon
const DEVICE_ICON = {
  ROUTER:      "⬡",
  SWITCH:      "◫",
  SERVER:      "⊞",
  WORKSTATION: "◎",
  UNKNOWN:     "?",
};

// Returns true when a TLS expiry date is within 30 days of now
function isExpiringSoon(isoDate) {
  if (!isoDate) return false;
  return new Date(isoDate).getTime() - Date.now() < 30 * 24 * 60 * 60 * 1000;
}

/**
 * DeviceList — renders one card per anonymous discovered device.
 * No IP address is shown; IPs are never stored server-side for anon sessions.
 *
 * Props:
 *   devices: AnonDeviceDto[]
 *     { id, subnetCidr, deviceClass, openPorts, banners, tlsSubjects, tlsExpiryMin }
 */
export function DeviceList({ devices }) {
  if (!devices || devices.length === 0) {
    return (
      <p
        style={{
          color:    "var(--muted)",
          fontSize: "0.82rem",
          padding:  "0.5rem 0",
        }}
      >
        No devices discovered.
      </p>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "0.75rem" }}>
      {devices.map((device, idx) => {
        const expiringSoon   = isExpiringSoon(device.tlsExpiryMin);
        const bannerEntries  = device.banners ? Object.entries(device.banners) : [];
        const icon           = DEVICE_ICON[device.deviceClass] || "?";

        return (
          <div
            key={device.id || idx}
            style={{
              background:   "var(--surface)",
              border:       "1px solid var(--border)",
              borderRadius: 12,
              padding:      "1rem 1.25rem",
            }}
          >
            {/* ── Row 1: class label + subnet + expiry ── */}
            <div
              style={{
                display:         "flex",
                alignItems:      "center",
                justifyContent:  "space-between",
                flexWrap:        "wrap",
                gap:             "0.5rem",
                marginBottom:    "0.6rem",
              }}
            >
              <div
                style={{
                  display:    "flex",
                  alignItems: "center",
                  gap:        "0.5rem",
                }}
              >
                <span
                  aria-hidden="true"
                  style={{ fontSize: "1.1rem", lineHeight: 1 }}
                >
                  {icon}
                </span>
                <span style={{ fontWeight: 600, fontSize: "0.85rem" }}>
                  {device.deviceClass}
                </span>
                {device.subnetCidr && (
                  <span
                    style={{
                      fontSize:   "0.7rem",
                      color:      "var(--muted)",
                      fontFamily: "var(--font-mono)",
                    }}
                  >
                    {device.subnetCidr}
                  </span>
                )}
              </div>

              {device.tlsExpiryMin && (
                <span
                  style={{
                    fontSize:   "0.72rem",
                    fontFamily: "var(--font-mono)",
                    color:      expiringSoon ? "var(--red)" : "var(--muted)",
                  }}
                  title={expiringSoon ? "Expires within 30 days" : undefined}
                >
                  Expires: {fmtDate(device.tlsExpiryMin)}
                </span>
              )}
            </div>

            {/* ── Row 2: open port chips ── */}
            {device.openPorts && device.openPorts.length > 0 && (
              <div style={{ marginBottom: "0.4rem" }}>
                {device.openPorts.map((p) => (
                  <span key={p} className="port-chip">
                    {p}
                  </span>
                ))}
              </div>
            )}

            {/* ── Row 3: TLS subjects ── */}
            {device.tlsSubjects && device.tlsSubjects.length > 0 && (
              <div
                className="text-muted"
                style={{ fontSize: "0.78rem", marginBottom: "0.3rem" }}
              >
                {device.tlsSubjects.join(", ")}
              </div>
            )}

            {/* ── Row 4: banners (secondary info) ── */}
            {bannerEntries.length > 0 && (
              <div
                style={{
                  fontSize:   "0.72rem",
                  color:      "var(--muted)",
                  fontFamily: "var(--font-mono)",
                  marginTop:  "0.3rem",
                }}
              >
                {bannerEntries.map(([k, v]) => (
                  <span key={k} style={{ marginRight: "0.75rem" }}>
                    {k}: {v}
                  </span>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
