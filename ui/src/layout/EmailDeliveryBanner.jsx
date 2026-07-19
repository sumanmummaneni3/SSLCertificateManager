import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { fmtCountdown } from "@/lib/helpers.js";

// Poll interval for the delivery-status check — a background health signal,
// not real-time data, so a few minutes is plenty.
const POLL_INTERVAL_MS = 3 * 60 * 1000;

// lastError is a raw SMTP diagnostic, server-truncated at 2000 chars
// (NotificationOutboxService.truncate). Clamp what we render so a long,
// multi-line failure message can't blow out the banner layout — the full
// text is still available via the title tooltip on hover.
const LAST_ERROR_DISPLAY_LIMIT = 200;

function clampLastError(lastError) {
  const singleLine = lastError.replace(/\s+/g, " ").trim();
  if (singleLine.length <= LAST_ERROR_DISPLAY_LIMIT) return singleLine;
  return `${singleLine.slice(0, LAST_ERROR_DISPLAY_LIMIT)}…`;
}

/**
 * EmailDeliveryBanner — org-wide warning shown when the SMTP outbox is degraded
 * (certificate expiry/revocation alert emails are queued/retrying or failing).
 *
 * Fails quiet: the backing endpoint (GET .../notifications/delivery-status) may
 * not be deployed yet, so any non-2xx or network error renders nothing — never a
 * broken banner, never a console-spamming retry loop.
 *
 * Dismissal is in-memory only (component state, not localStorage/sessionStorage):
 * closing it hides it for the rest of this app session, and it reappears on the
 * next full page load if the org is still degraded. It is never permanently
 * dismissed.
 */
export function EmailDeliveryBanner({ token, orgId }) {
  const [status, setStatus] = useState(null);
  const [dismissed, setDismissed] = useState(false);

  const load = useCallback(async () => {
    if (!token || !orgId) return;
    try {
      const data = await api.getNotificationDeliveryStatus(orgId, token);
      setStatus(data);
    } catch {
      // Endpoint may 404 (not deployed yet) or error — fail quiet, render nothing.
      setStatus(null);
    } finally {
      // Intentionally empty, and required: eslint-plugin-react-hooks 7 flags the
      // load() call in the effect below as a synchronous set-state-in-effect unless
      // this try has a finally. The setState calls above are already past an await,
      // so the rule is a false positive here — but a line-level disable does not
      // suppress it (the rule reports against the effect's dependency line, not the
      // call). Removing this block reintroduces a lint failure. See AppShell.load
      // and loadCerts, which satisfy the same rule the same way.
    }
  }, [token, orgId]);

  useEffect(() => {
    load();
    const interval = setInterval(load, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [load]);

  if (!status?.degraded || dismissed) return null;

  const queuedCount = status.queuedCount ?? 0;
  const failedCount = status.failedCount ?? 0;
  const retryText = fmtCountdown(status.nextAttemptAt);

  const lines = [];
  if (queuedCount > 0) {
    lines.push(
      `${queuedCount} certificate alert email${queuedCount === 1 ? "" : "s"} delayed / not yet delivered`
    );
  }
  if (failedCount > 0) {
    lines.push(
      `${failedCount} certificate alert email${failedCount === 1 ? "" : "s"} could not be delivered`
    );
  }
  if (lines.length === 0) {
    lines.push("Delivery is degraded — emails are queued for retry");
  }

  return (
    <div
      className="alert alert-warning email-delivery-banner"
      role="status"
      style={{ margin: "1rem 1.5rem 0" }}
    >
      <span aria-hidden="true" style={{ fontSize: "1rem" }}>⚠</span>
      <div style={{ display: "flex", flexDirection: "column", gap: 4, flex: 1 }}>
        <strong>Certificate alert emails are not currently being delivered</strong>
        <span>
          {lines.join(" · ")}
          {retryText && ` — next retry ${retryText}`}
        </span>
        {status.lastError && (
          <span className="text-muted text-sm" title={status.lastError}>
            {clampLastError(status.lastError)}
          </span>
        )}
      </div>
      <button
        onClick={() => setDismissed(true)}
        aria-label="Dismiss email delivery warning"
        style={{
          background: "none",
          border: "none",
          cursor: "pointer",
          color: "var(--yellow)",
          fontSize: "1.1rem",
          padding: "0 0 0 1rem",
          lineHeight: 1,
          flexShrink: 0,
        }}
      >
        &times;
      </button>
    </div>
  );
}
