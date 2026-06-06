import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { statusColor, hostTypeColor, fmtDate } from "@/lib/helpers.js";
import { Spinner, Badge, DaysBar } from "@/components/index.js";
import { RenewalStatusPanel, RenewalHistoryList, RequestRenewalModal } from "./renewal/index.js";

export function CertificateDetailView({ certId, token, toast, onBack }) {
  const [cert, setCert]             = useState(null);
  const [loading, setLoading]       = useState(true);
  const [error, setError]           = useState("");
  const [showRenewModal, setShowRenewModal] = useState(false);
  const [activeRenewal, setActiveRenewal]   = useState(null);

  useEffect(() => {
    if (!certId) return;
    let cancelled = false;
    async function fetchCert() {
      try {
        const data = await api.getCert(certId, token);
        if (!cancelled) {
          setCert(data);
          if (data?.activeRenewal) setActiveRenewal(data.activeRenewal);
          setLoading(false);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e.message || "Failed to load certificate");
          setLoading(false);
        }
      }
    }
    fetchCert();
    return () => { cancelled = true; };
  }, [certId, token]);

  const isAgentManaged = !!(cert?.target?.agentId || cert?.target?.agent);
  const canRequestRenewal = isAgentManaged && !activeRenewal;

  const handleRenewalRequested = (renewal) => {
    setShowRenewModal(false);
    setActiveRenewal(renewal);
  };

  return (
    <>
      <div className="page-header">
        <div>
          <button
            className="btn-ghost"
            style={{ padding: 0, fontSize: "0.78rem", marginBottom: 6, display: "flex", alignItems: "center", gap: 4 }}
            onClick={onBack}
            aria-label="Back to Certificates"
          >
            &#8592; Back to Certificates
          </button>
          <div className="page-title">{cert ? cert.commonName : "Certificate Detail"}</div>
          <div className="page-sub">Certificate details and renewal management</div>
        </div>
        {isAgentManaged && (
          <button
            className="btn btn-primary btn-sm"
            onClick={() => setShowRenewModal(true)}
            disabled={!canRequestRenewal}
            title={activeRenewal ? "A renewal is already in progress" : "Request certificate renewal via agent"}
          >
            {activeRenewal ? "Renewal In Progress" : "Request Renewal"}
          </button>
        )}
      </div>

      <div className="cert-detail-page">
        {loading && (
          <div className="loading-center"><Spinner lg /><span>Loading certificate...</span></div>
        )}

        {error && (
          <div className="alert alert-error" role="alert">{error}</div>
        )}

        {cert && (
          <>
            {/* Certificate fields */}
            <div className="cert-detail-section">
              <div className="cert-detail-section-title">Certificate Info</div>
              <div className="cert-field-grid">
                <span className="cert-field-key">Common Name</span>
                <span className="cert-field-val">{cert.commonName || "—"}</span>

                <span className="cert-field-key">Status</span>
                <span className="cert-field-val">
                  <Badge type={statusColor(cert.status)}>{cert.status || "—"}</Badge>
                </span>

                <span className="cert-field-key">Issuer</span>
                <span className="cert-field-val">{cert.issuer || "—"}</span>

                <span className="cert-field-key">Valid From</span>
                <span className="cert-field-val">{fmtDate(cert.notBefore)}</span>

                <span className="cert-field-key">Expires</span>
                <span className="cert-field-val">{fmtDate(cert.expiryDate)}</span>

                <span className="cert-field-key">Days Left</span>
                <span className="cert-field-val">
                  <DaysBar days={cert.daysRemaining} />
                </span>

                {cert.subjectAltNames?.length > 0 && (
                  <>
                    <span className="cert-field-key">SANs</span>
                    <span className="cert-field-val" style={{ fontFamily: "var(--font-mono)", fontSize: "0.75rem" }}>
                      {cert.subjectAltNames.join(", ")}
                    </span>
                  </>
                )}

                <span className="cert-field-key">Serial</span>
                <span className="cert-field-val mono" style={{ fontSize: "0.72rem" }}>{cert.serialNumber || "—"}</span>

                <span className="cert-field-key">Last Scanned</span>
                <span className="cert-field-val">{fmtDate(cert.scannedAt)}</span>
              </div>
            </div>

            {/* Target info */}
            {cert.target && (
              <div className="cert-detail-section">
                <div className="cert-detail-section-title">Target</div>
                <div className="cert-field-grid">
                  <span className="cert-field-key">Host</span>
                  <span className="cert-field-val">{cert.target.host || "—"}</span>

                  <span className="cert-field-key">Port</span>
                  <span className="cert-field-val">{cert.target.port || 443}</span>

                  <span className="cert-field-key">Type</span>
                  <span className="cert-field-val">
                    <Badge type={hostTypeColor(cert.target.hostType)}>{cert.target.hostType || "—"}</Badge>
                  </span>

                  <span className="cert-field-key">Visibility</span>
                  <span className="cert-field-val">
                    <Badge type={cert.target.isPrivate ? "private" : "public"}>
                      {cert.target.isPrivate ? "Private" : "Public"}
                    </Badge>
                  </span>

                  {isAgentManaged && (
                    <>
                      <span className="cert-field-key">Agent</span>
                      <span className="cert-field-val">
                        {cert.target.agentName || cert.target.agentId || "Agent-managed"}
                        <Badge type="active" style={{ marginLeft: 8 }}>Agent</Badge>
                      </span>
                    </>
                  )}
                </div>

                {!isAgentManaged && (
                  <div className="alert alert-info" style={{ marginTop: "0.75rem" }}>
                    Automatic renewal is only available for agent-managed targets. Assign an agent to enable renewal.
                  </div>
                )}
              </div>
            )}

            {/* Active renewal status */}
            {activeRenewal && (
              <div className="cert-detail-section">
                <div className="cert-detail-section-title">Active Renewal</div>
                <RenewalStatusPanel renewal={activeRenewal} token={token} />
              </div>
            )}

            {/* Renewal history */}
            <div className="cert-detail-section">
              <div className="cert-detail-section-title">Renewal History</div>
              <RenewalHistoryList certId={certId} token={token} />
            </div>
          </>
        )}
      </div>

      {showRenewModal && (
        <RequestRenewalModal
          certId={certId}
          token={token}
          onClose={() => setShowRenewModal(false)}
          onRequested={handleRenewalRequested}
          toast={toast}
        />
      )}
    </>
  );
}
