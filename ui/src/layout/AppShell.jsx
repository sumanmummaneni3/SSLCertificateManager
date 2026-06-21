import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api.js";
import { Spinner } from "@/components/index.js";
import { Sidebar } from "./Sidebar.jsx";
import { DashboardView }         from "@/panels/dashboard/index.jsx";
import { TargetsView }           from "@/panels/targets/index.jsx";
import { AddTargetModal }        from "@/panels/targets/AddTargetModal.jsx";
import { EditTargetModal }       from "@/panels/targets/EditTargetModal.jsx";
import { CertsView }             from "@/panels/certificates/index.jsx";
import { CertificateDetailView } from "@/panels/certificates/CertificateDetailView.jsx";
import { AgentsView }            from "@/panels/agents/index.jsx";
import { LocationsView }         from "@/panels/locations/index.jsx";
import { TeamView }              from "@/panels/team/index.jsx";
import { SettingsView }          from "@/panels/settings/index.jsx";
import { MspDashboardView }      from "@/panels/msp/DashboardView.jsx";
import { MspOrgsView }           from "@/panels/msp/OrgsView.jsx";
import { MspTargetsView }        from "@/panels/msp/TargetsView.jsx";
import { PlatformOrgsView }      from "@/panels/platform/OrgsView.jsx";
import { PlatformOrgDetailView } from "@/panels/platform/OrgDetailView.jsx";

export function AppShell({ token, org, me, toast, onLogout, initialCertId, onExpireSession }) {
  const [dash, setDash]           = useState(null);
  const [targets, setTargets]     = useState([]);
  const [loading, setLoading]     = useState(true);
  const [scanning, setScanning]   = useState({});
  const [showAdd, setShowAdd]     = useState(false);
  const [deleteId, setDeleteId]   = useState(null);
  const [editTarget, setEditTarget] = useState(null);
  const [view, setView]           = useState(initialCertId ? "cert-detail" : "dashboard");
  // When an MSP drills into a single client org's targets from the MSP dashboard.
  const [mspTargetFilter, setMspTargetFilter] = useState(null);

  // Proactive nav-guard: hit the server's session record before changing view.
  // Fail-open on network / 5xx — the reactive 401 handler still catches real expiry.
  const navigateTo = useCallback(async (viewId) => {
    try {
      await api.validateSession(token);
      setView(viewId);
    } catch (err) {
      if (err?.status === 401) {
        onExpireSession("Your session has expired — please sign in again.");
      } else {
        setView(viewId);
      }
    }
  }, [token, onExpireSession]);

  // Sidebar navigation always clears any MSP per-client target filter.
  const sidebarNavigate = useCallback((viewId) => {
    setMspTargetFilter(null);
    navigateTo(viewId);
  }, [navigateTo]);

  // Drill into one client org's targets from the MSP dashboard.
  const viewClientTargets = useCallback((orgId, orgName) => {
    setMspTargetFilter({ orgId, orgName });
    setView("msp-targets");
  }, []);

  const [selectedCertId, setSelectedCertId] = useState(initialCertId || null);
  const [certs, setCerts]         = useState([]);
  const [certsLoading, setCertsLoading] = useState(false);
  const [agents, setAgents]       = useState([]);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [locations, setLocations]         = useState([]);
  const [locationsLoading, setLocationsLoading] = useState(false);
  const [upgradeBannerDismissed, setUpgradeBannerDismissed] = useState(
    () => sessionStorage.getItem("cg-upgrade-banner-dismissed") === "true"
  );
  const [actingAsOrgId, setActingAsOrgId]   = useState(null);
  const [actingAsOrgName, setActingAsOrgName] = useState(null);
  const [theme, setThemeState]    = useState(() =>
    localStorage.getItem("cg-sidebar-theme") || "dark"
  );

  const setTheme = (t) => {
    localStorage.setItem("cg-sidebar-theme", t);
    setThemeState(t);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [d, t] = await Promise.all([
        api.getDashboard(token),
        api.getTargets(token),
      ]);
      setDash(d);
      setTargets(t?.content || []);
    } catch (e) {
      toast("Failed to load dashboard: " + e.message, "error");
    } finally {
      setLoading(false);
    }
  }, [token, toast]);

  const loadCerts = useCallback(async () => {
    setCertsLoading(true);
    try {
      const c = await api.getCerts(token);
      setCerts(c?.content || []);
    } catch (e) {
      toast("Failed to load certificates: " + e.message, "error");
    } finally {
      setCertsLoading(false);
    }
  }, [token, toast]);

  const loadAgents = useCallback(async () => {
    setAgentsLoading(true);
    try { setAgents(await api.listAgents(token)); }
    catch (e) { toast("Failed to load agents: " + e.message, "error"); }
    finally { setAgentsLoading(false); }
  }, [token, toast]);

  const loadLocations = useCallback(async () => {
    setLocationsLoading(true);
    try { setLocations(await api.listLocations(token)); }
    catch (e) { toast("Failed to load locations: " + e.message, "error"); }
    finally { setLocationsLoading(false); }
  }, [token, toast]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { if (view === "certs")     loadCerts();     }, [view, loadCerts]);
  useEffect(() => { if (view === "agents")    loadAgents();    }, [view, loadAgents]);
  useEffect(() => { if (view === "locations") loadLocations(); }, [view, loadLocations]);

  const triggerScan = async (target) => {
    if (target.isPrivate && !target.agentId) {
      toast("Assign an agent to this target before scanning", "error");
      return;
    }
    setScanning((s) => ({ ...s, [target.id]: true }));
    try {
      await api.scanTarget(target.id, token);
      const msg = target.isPrivate
        ? `Scan job queued for agent "${target.agentName}" — results in ~30s`
        : `Scan triggered for ${target.host}`;
      toast(msg, "info");
      setTimeout(() => { load(); setScanning((s) => ({ ...s, [target.id]: false })); }, 10000);
    } catch (e) {
      toast("Scan failed: " + e.message, "error");
      setScanning((s) => ({ ...s, [target.id]: false }));
    }
  };

  const confirmDelete = async () => {
    try {
      await api.deleteTarget(deleteId, token);
      toast("Target deleted", "success");
      setDeleteId(null);
      load();
    } catch (e) {
      toast("Delete failed: " + e.message, "error");
    }
  };

  const exitImpersonation = () => {
    setActingAsOrgId(null);
    setActingAsOrgName(null);
    setView("platform-admin-orgs");
  };

  const dismissUpgradeBanner = () => {
    sessionStorage.setItem("cg-upgrade-banner-dismissed", "true");
    setUpgradeBannerDismissed(true);
  };

  const showUpgradeBanner = !upgradeBannerDismissed && (
    me?.permissions?.mspUpgradePending || me?.permissions?.quotaUpgradePending
  );

  if (loading) {
    return (
      <div className="app">
        <Sidebar view={view} onView={sidebarNavigate} org={org} me={me} theme={theme} onTheme={setTheme} onLogout={onLogout} />
        <div className="main"><div className="loading-center"><Spinner lg /><span>Loading dashboard...</span></div></div>
      </div>
    );
  }

  return (
    <div className="app">
      <Sidebar view={view} onView={sidebarNavigate} org={org} me={me} theme={theme} onTheme={setTheme} onLogout={onLogout} />
      <div className="main">
        {actingAsOrgId && (
          <div className="impersonation-banner" role="alert">
            <span aria-hidden="true" style={{ fontSize: "1rem" }}>!</span>
            <span className="impersonation-banner-text">
              Acting as: <strong>{actingAsOrgName || actingAsOrgId}</strong>
              <span className="impersonation-banner-warn">— All changes are logged</span>
            </span>
            <button
              className="btn btn-secondary btn-sm"
              style={{ flexShrink: 0 }}
              onClick={exitImpersonation}
              aria-label="Exit impersonation mode"
            >
              Exit
            </button>
          </div>
        )}
        {showUpgradeBanner && (
          <div
            className="alert alert-warning"
            role="status"
            style={{ display: "flex", justifyContent: "space-between", alignItems: "center", margin: "1rem 1.5rem 0" }}
          >
            <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
              {me?.permissions?.mspUpgradePending && (
                <span>Your MSP upgrade request is pending Sales review. We&apos;ll notify you when it&apos;s approved.</span>
              )}
              {me?.permissions?.quotaUpgradePending && (
                <span>Your certificate quota increase request is pending review.</span>
              )}
            </div>
            <button
              onClick={dismissUpgradeBanner}
              aria-label="Dismiss upgrade banner"
              style={{ background: "none", border: "none", cursor: "pointer", color: "var(--yellow)", fontSize: "1.1rem", padding: "0 0 0 1rem", lineHeight: 1, flexShrink: 0 }}
            >
              &times;
            </button>
          </div>
        )}
        {view === "dashboard" && (
          <DashboardView dash={dash} targets={targets} onScan={triggerScan}
            scanning={scanning} onAddTarget={() => setShowAdd(true)} me={me} org={org} />
        )}
        {view === "targets" && (
          <TargetsView targets={targets} onScan={triggerScan} scanning={scanning}
            onAdd={() => setShowAdd(true)} onDelete={setDeleteId}
            onEdit={setEditTarget} onRefresh={load} me={me} org={org}
            token={token} toast={toast} />
        )}
        {view === "certs" && (
          <CertsView
            certs={certs}
            loading={certsLoading}
            onRefresh={loadCerts}
            onSelectCert={(certId) => {
              setSelectedCertId(certId);
              setView("cert-detail");
            }}
          />
        )}
        {view === "cert-detail" && selectedCertId && (
          <CertificateDetailView
            certId={selectedCertId}
            orgId={org?.id}
            token={token}
            me={me}
            toast={toast}
            onBack={() => {
              setView("certs");
              loadCerts();
            }}
          />
        )}
        {view === "agents" && (
          <AgentsView agents={agents} loading={agentsLoading} token={token}
            onRefresh={loadAgents} toast={toast} me={me} />
        )}
        {view === "locations" && (
          <LocationsView locations={locations} loading={locationsLoading} token={token}
            onRefresh={loadLocations} toast={toast} me={me} />
        )}
        {view === "team"     && <TeamView     token={token} org={org} toast={toast} me={me} />}
        {view === "settings" && <SettingsView token={token} org={org} me={me} toast={toast} />}
        {view === "msp-dashboard" && <MspDashboardView token={token} me={me} onViewClientTargets={viewClientTargets} />}
        {view === "msp-orgs"      && <MspOrgsView     token={token} me={me} toast={toast} />}
        {view === "msp-targets"   && <MspTargetsView   token={token} me={me} toast={toast}
            filter={mspTargetFilter} onClearFilter={() => setMspTargetFilter(null)} />}
        {view === "platform-admin-orgs" && (
          <PlatformOrgsView
            token={token}
            toast={toast}
            onManageOrg={(id, name) => {
              setActingAsOrgId(id);
              setActingAsOrgName(name);
              setView("platform-admin-org-detail");
            }}
          />
        )}
        {view === "platform-admin-org-detail" && actingAsOrgId && (
          <PlatformOrgDetailView
            token={token}
            toast={toast}
            actingAsOrgId={actingAsOrgId}
            actingAsOrgName={actingAsOrgName}
            onExit={exitImpersonation}
            me={me}
          />
        )}
      </div>

      {showAdd && (
        <AddTargetModal token={token} onClose={() => setShowAdd(false)}
          onAdded={() => { setShowAdd(false); load(); }} toast={toast} />
      )}

      {editTarget && (
        <EditTargetModal token={token} target={editTarget}
          onClose={() => setEditTarget(null)}
          onSaved={() => { setEditTarget(null); load(); }} toast={toast} />
      )}

      {deleteId && (
        <div className="modal-bg">
          <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="delete-modal-title">
            <div className="modal-title" id="delete-modal-title">Delete Target?</div>
            <p className="modal-sub">This will remove the target and all its certificate history. This cannot be undone.</p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setDeleteId(null)}>Cancel</button>
              <button className="btn btn-danger" onClick={confirmDelete}>Delete</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
