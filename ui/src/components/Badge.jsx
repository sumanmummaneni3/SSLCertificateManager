/**
 * Badge — general purpose status/label chip.
 *
 * type prop maps to a CSS class suffix: badge-{type}.
 *
 * Special handling:
 *  - "revoked"  → cert-status REVOKED  (uses .badge-revoked-status to avoid collision
 *                  with the agent-.badge-revoked class)
 *  - "on-hold"  → cert suspended on hold (amber, reversible)
 *  - "invalid"  → cert INVALID (broken-chain / purple-red)
 */
export function Badge({ type, children, title, style }) {
  // Map cert-status "revoked" to a CSS class that doesn't collide with the
  // pre-existing .badge-revoked (agent status) class.
  const cssClass = type === "revoked" ? "badge badge-revoked-status" : `badge badge-${type}`;
  return (
    <span className={cssClass} title={title} style={style}>
      {children}
    </span>
  );
}
