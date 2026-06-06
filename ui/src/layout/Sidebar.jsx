import { useTheme } from "@/theme/useTheme.js";
import { SIDEBAR_THEMES } from "@/theme/tokens.js";
import { NAV_GROUPS, MSP_GROUP, ADMIN_GROUP } from "./navGroups.js";

function NavItem({ item, active, onView }) {
  return (
    <div
      className={`nav-item ${active ? "active" : ""}`}
      role="button"
      tabIndex={0}
      aria-current={active ? "page" : undefined}
      onClick={() => onView(item.id)}
      onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onView(item.id)}
    >
      <span aria-hidden="true">{item.icon}</span>{item.label}
    </div>
  );
}

export function Sidebar({ view, onView, org, me, theme = "dark", onTheme, onLogout }) {
  const { mode, toggle } = useTheme();
  const themeVars = SIDEBAR_THEMES[theme]?.vars || SIDEBAR_THEMES.dark.vars;
  const isMsp = org?.orgType === "MSP";
  const isPlatformAdmin = me?.platformAdmin === true;
  const mspUpgradePending = me?.permissions?.mspUpgradePending === true;
  let groups = [...NAV_GROUPS];
  if (isMsp) groups = [...groups, MSP_GROUP];
  if (isPlatformAdmin) groups = [...groups, ADMIN_GROUP];
  return (
    <nav className="sidebar" style={themeVars} aria-label="Main navigation">
      <div className="sidebar-logo">
        <div className="logo-icon" aria-hidden="true">🔐</div>
        <div className="logo-text">OOPSSSL</div>
      </div>
      {groups.map((group) => {
        const isMspGroup = group.label === "MSP";
        const locked = isMspGroup && mspUpgradePending;
        return (
          <div key={group.label}>
            <div className="nav-section" aria-hidden="true">
              {group.label}
              {locked && <span className="badge-locked" aria-label="MSP upgrade pending">[locked]</span>}
            </div>
            {group.items.map((item) =>
              locked ? (
                <div
                  key={item.id}
                  className="nav-item msp-locked"
                  aria-disabled="true"
                  title="MSP upgrade pending approval"
                >
                  <span aria-hidden="true">{item.icon}</span>{item.label}
                </div>
              ) : (
                <NavItem key={item.id} item={item} active={view === item.id} onView={onView} />
              )
            )}
          </div>
        );
      })}
      <div className="sidebar-footer">
        <div className="org-tag"><span aria-hidden="true">🏢</span> <span>{org?.name || "My Org"}</span></div>
        {org?.email && <div className="org-tag" style={{ fontSize: "0.62rem" }}>{org.email}</div>}
        <div className="org-tag" style={{ fontSize: "0.62rem" }}>
          Plan: <span>{org?.subscriptionTier || "—"}</span>
        </div>
        <div className="org-tag" style={{ fontSize: "0.62rem" }}>
          Targets: <span>{org?.currentTargetCount || 0} / {org?.maxTargets || "∞"}</span>
        </div>
        {onTheme && (
          <div className="theme-picker">
            <div className="theme-picker-label">Sidebar</div>
            <div className="theme-swatches">
              {Object.entries(SIDEBAR_THEMES).map(([key, t]) => (
                <div
                  key={key}
                  className={`theme-swatch ${theme === key ? "active" : ""}`}
                  style={{ background: t.swatch }}
                  title={t.label}
                  aria-label={`Sidebar: ${t.label}`}
                  role="button"
                  tabIndex={0}
                  onClick={() => onTheme(key)}
                  onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onTheme(key)}
                />
              ))}
            </div>
            <button
              className="theme-toggle-btn"
              onClick={toggle}
              aria-label={`Switch to ${mode === "dark" ? "light" : "dark"} mode`}
            >
              {mode === "dark" ? "☀ Light mode" : "☾ Dark mode"}
            </button>
          </div>
        )}
        <button className="logout-btn" onClick={onLogout} aria-label="Sign out">
          <span aria-hidden="true">⏻</span> Sign out
        </button>
      </div>
    </nav>
  );
}
