import { useState, useEffect, useCallback } from "react";
import { Spinner, Accordion } from "@/components/index.js";
import { useCountdown } from "./useCountdown.js";
import { CloseGuardDialog } from "./CloseGuardDialog.jsx";

export function AgentInstallKeyModal({ result, onClose, toast }) {
  const { agentId, installKey, bundleDownloadUrl, expiresAt } = result;
  const [confirmed, setConfirmed] = useState(false);
  const [showGuard, setShowGuard] = useState(false);
  const { label: countdownLabel, urgent } = useCountdown(expiresAt);

  // Use a ref so the keydown handler always sees the latest value without
  // needing to be recreated on each render (avoids react-compiler warning).
  const confirmedRef = useCallback(() => confirmed, [confirmed]);

  const handleCloseRequest = useCallback(() => {
    if (!confirmedRef()) { setShowGuard(true); return; }
    onClose();
  }, [confirmedRef, onClose]);

  useEffect(() => {
    const handler = (e) => { if (e.key === "Escape") handleCloseRequest(); };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [handleCloseRequest]);

  const copyKey = () => {
    navigator.clipboard.writeText(installKey).then(
      () => toast("Install key copied!", "success"),
      () => toast("Clipboard unavailable — select and copy manually", "error"),
    );
  };

  return (
    <>
      <div
        className="modal-bg"
        onClick={(e) => { if (e.target === e.currentTarget) handleCloseRequest(); }}
        aria-hidden={showGuard}
      >
        <div className="modal-wide" role="dialog" aria-modal="true" aria-labelledby="ik-title">
          <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", marginBottom: "0.4rem" }}>
            <div className="modal-title" id="ik-title">Agent created — save your install key</div>
            <button
              className="btn-ghost"
              style={{ padding: "4px 8px", fontSize: "1rem", lineHeight: 1 }}
              onClick={handleCloseRequest}
              aria-label="Close"
            >
              ✕
            </button>
          </div>
          <p className="modal-sub">
            This key is shown exactly once. Store it securely before downloading the bundle.
          </p>

          <div className={`countdown-badge ${urgent ? "urgent" : ""}`} aria-live="off">
            Bundle link expires in: <strong>{countdownLabel}</strong>
          </div>

          <div style={{ marginBottom: "0.5rem" }}>
            <div style={{ fontSize: "0.68rem", color: "var(--muted)", letterSpacing: "0.08em", textTransform: "uppercase", marginBottom: 6 }}>
              Install Key (shown once)
            </div>
            <div className="install-key-field">
              <input
                type="text"
                readOnly
                value={installKey}
                aria-label="Install key"
                onFocus={(e) => e.target.select()}
              />
              <button
                className="btn btn-secondary btn-sm"
                style={{ flexShrink: 0, whiteSpace: "nowrap" }}
                onClick={copyKey}
                aria-label="Copy install key to clipboard"
              >
                Copy
              </button>
            </div>
          </div>

          <div style={{ marginBottom: "1rem" }}>
            <a
              href={bundleDownloadUrl || "#"}
              download
              className="btn btn-primary"
              style={{
                display: "flex",
                textDecoration: "none",
                gap: "8px",
                opacity: bundleDownloadUrl ? 1 : 0.4,
                pointerEvents: bundleDownloadUrl ? "auto" : "none",
              }}
              aria-label={`Download installer bundle for agent ${agentId}`}
              aria-disabled={!bundleDownloadUrl}
            >
              <span aria-hidden="true">&#8681;</span> Download Installer Bundle (.zip)
            </a>
          </div>

          <div style={{ marginBottom: "0.75rem" }}>
            <div style={{ fontSize: "0.72rem", color: "var(--muted)", marginBottom: "0.5rem" }}>
              Platform installation instructions
            </div>
            <Accordion title="Linux / macOS">
              <pre>{`unzip certguard-agent-${agentId}.zip
cd certguard-agent-${agentId}
chmod +x run.sh
./run.sh
# When prompted, enter the install key above`}</pre>
            </Accordion>
            <Accordion title="Windows">
              <pre>{`1. Unzip certguard-agent-${agentId}.zip
2. Open a Command Prompt in the extracted folder
3. Run:  run.bat
4. When prompted, enter the install key above`}</pre>
            </Accordion>
            <Accordion title="Headless / CI">
              <pre>{`CERTGUARD_INSTALL_KEY=<key> java -jar certguard-agent.jar --bundle bundle.cgb`}</pre>
            </Accordion>
          </div>

          <label className="confirm-check" htmlFor="ik-confirm">
            <input
              id="ik-confirm"
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            <span>I have securely stored the install key and understand it cannot be retrieved again.</span>
          </label>

          <div className="modal-actions" style={{ marginTop: "0.5rem" }}>
            <button
              className="btn btn-primary"
              onClick={onClose}
              disabled={!confirmed}
              aria-disabled={!confirmed}
            >
              Done
            </button>
          </div>
        </div>
      </div>

      {showGuard && (
        <CloseGuardDialog
          onConfirm={() => { setShowGuard(false); onClose(); }}
          onCancel={() => setShowGuard(false)}
        />
      )}
    </>
  );
}
