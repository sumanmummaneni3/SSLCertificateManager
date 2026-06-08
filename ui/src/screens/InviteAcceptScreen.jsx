import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function InviteAcceptScreen({ inviteToken, onAccepted, toast }) {
  const [step, setStep]     = useState("validating"); // validating | otp | error
  const [email, setEmail]   = useState("");
  const [otp, setOtp]       = useState("");
  const [errMsg, setErrMsg] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api.validateInvite(inviteToken)
      .then((res) => { setEmail(res.email); setStep("otp"); })
      .catch((e) => { setErrMsg(e.message); setStep("error"); });
  }, [inviteToken]);

  const handleAccept = async (ev) => {
    ev.preventDefault();
    if (!otp.trim()) return;
    setSubmitting(true);
    try {
      const res = await api.acceptInvite({ token: inviteToken, email, otp: otp.trim() });
      onAccepted(res.token, { fromInvite: true });
    } catch (e) {
      setErrMsg(e.message);
      toast("Failed to accept invite: " + e.message, "error");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="launch">
      <div style={{ width: "100%", maxWidth: 400, margin: "auto", padding: "2rem" }}>
        <div className="wordmark" style={{ marginBottom: "2rem", textAlign: "center" }}>
          <span className="wordmark-cert">Cert</span><span className="wordmark-guard">Guard</span>
        </div>

        {step === "validating" && (
          <div className="loading-center">
            <Spinner lg />
            <span>Validating invite...</span>
          </div>
        )}

        {step === "error" && (
          <div className="cert-detail" style={{ textAlign: "center" }}>
            <div style={{ color: "var(--red)", marginBottom: "1rem", fontSize: "1.1rem" }}>Invalid or expired invite</div>
            <div style={{ color: "var(--muted)", fontSize: "0.82rem", marginBottom: "1.5rem" }}>{errMsg}</div>
            <button className="btn btn-secondary" onClick={() => window.location.replace("/")}>Go to Login</button>
          </div>
        )}

        {step === "otp" && (
          <div className="cert-detail">
            <div style={{ fontFamily: "var(--font-head)", fontSize: "1.1rem", marginBottom: "0.5rem" }}>
              You&apos;ve been invited
            </div>
            <div style={{ color: "var(--muted)", fontSize: "0.82rem", marginBottom: "1.5rem" }}>
              A one-time code was sent to <strong style={{ color: "var(--text)" }}>{email}</strong>. Enter it below to accept your invitation.
            </div>
            <form onSubmit={handleAccept}>
              <div className="field">
                <label>One-Time Code</label>
                <input type="text" value={otp} autoFocus
                  onChange={(e) => setOtp(e.target.value)} placeholder="123456"
                  style={{ letterSpacing: "0.2em", fontSize: "1.1rem", textAlign: "center" }} />
              </div>
              {errMsg && <div style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }}>{errMsg}</div>}
              <button type="submit" className="btn btn-primary" style={{ width: "100%", marginTop: "0.5rem" }}
                disabled={submitting || !otp.trim()}>
                {submitting ? <Spinner /> : "Accept Invitation"}
              </button>
            </form>
          </div>
        )}
      </div>
    </div>
  );
}
