import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";

export function OrgSetup({ token, onDone, toast }) {
  const [step, setStep]       = useState(1); // 1 = org type, 2 = org name
  const [orgType, setOrgType] = useState("SINGLE");
  const [name, setName]       = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState("");

  const handleSave = async () => {
    if (!name.trim()) { setError("Organization name is required"); return; }
    setError(""); setLoading(true);
    try {
      await api.completeOnboarding({ orgName: name.trim(), orgType }, token);
      toast("Organization created!", "success");
      onDone();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon">🏢</div>
        <div className="logo-text">OOPSSSL</div>
      </div>

      <div className="launch-card">
        <div className="steps">
          <div className="step-item done"><span className="step-num">✓</span>Signed In</div>
          <div className={`step-item ${step === 2 ? "done" : "active"}`}>
            <span className="step-num">{step === 2 ? "✓" : "2"}</span>Org Type
          </div>
          <div className={`step-item ${step === 2 ? "active" : ""}`}>
            <span className="step-num">3</span>Your Org
          </div>
          <div className="step-item"><span className="step-num">4</span>Add Targets</div>
        </div>

        {step === 1 && (
          <>
            <div className="launch-title">Choose your account type</div>
            <p className="launch-sub">Select how you plan to use CertGuard.</p>

            <div className="org-type-cards" role="radiogroup" aria-label="Account type">
              <button
                className={`org-type-card ${orgType === "SINGLE" ? "selected" : ""}`}
                role="radio"
                aria-checked={orgType === "SINGLE"}
                onClick={() => setOrgType("SINGLE")}
              >
                <div className="org-type-card-icon" aria-hidden="true">◈</div>
                <div className="org-type-card-title">Standard Organization <span className="org-type-card-check" aria-hidden="true">✓</span></div>
                <div className="org-type-card-desc">Manage certificates for your own infrastructure</div>
              </button>
              <button
                className={`org-type-card ${orgType === "MSP" ? "selected" : ""}`}
                role="radio"
                aria-checked={orgType === "MSP"}
                onClick={() => setOrgType("MSP")}
              >
                <div className="org-type-card-icon" aria-hidden="true">⬡</div>
                <div className="org-type-card-title">MSP <span className="org-type-card-check" aria-hidden="true">✓</span></div>
                <div className="org-type-card-desc">Manage certificates across multiple client organizations</div>
              </button>
            </div>

            {orgType === "MSP" && (
              <div className="alert alert-info" style={{ marginBottom: "1rem" }}>
                <span aria-hidden="true">ℹ</span>
                <span>MSP accounts start on a free trial — manage up to 10 certificates across all client organizations at no cost. Upgrade anytime to remove limits.</span>
              </div>
            )}

            <button className="btn btn-primary" onClick={() => setStep(2)}>
              → Continue
            </button>
          </>
        )}

        {step === 2 && (
          <>
            <div className="launch-title">Name your organization</div>
            <p className="launch-sub">This will appear on your dashboard and in reports. You can change it later.</p>

            {error && <div className="alert alert-error">⚠ {error}</div>}

            <div className="field">
              <label htmlFor="org-name">Organization Name</label>
              <input
                id="org-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSave()}
                placeholder="e.g. Acme Corporation"
                autoFocus
              />
            </div>

            <div style={{ display: "flex", gap: "0.5rem" }}>
              <button className="btn btn-secondary" onClick={() => setStep(1)} disabled={loading}>
                Back
              </button>
              <button className="btn btn-primary" onClick={handleSave} disabled={loading || !name.trim()}>
                {loading ? <><Spinner /> Saving...</> : "→ Continue"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
