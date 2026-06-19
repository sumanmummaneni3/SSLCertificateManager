import { useState, useEffect } from "react";
import { api } from "@/lib/api.js";
import { Spinner, Badge } from "@/components/index.js";
import { MspClientModal } from "./MspClientModal.jsx";

export function MspOrgsView({ token, me, toast }) {
  const [clients, setClients]     = useState([]);
  const [loading, setLoading]     = useState(true);
  const [showAdd, setShowAdd]     = useState(false);
  const [editClient, setEditClient] = useState(null);
  const [archiveId, setArchiveId] = useState(null);
  const [archiveName, setArchiveName] = useState("");
  const upgradePending = me?.permissions?.mspUpgradePending === true;

  const canManage = me == null || me?.permissions?.canManageMspClients ||
    me?.user?.role === "ADMIN" || me?.user?.role === "ENGINEER";

  const load = () => {
    setLoading(true);
    api.msp.listClients(token)
      .then((data) => setClients(Array.isArray(data) ? data : (data?.content || [])))
      .catch((e) => toast("Failed to load client orgs: " + e.message, "error"))
      .finally(() => setLoading(false));
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [token]);

  const handleArchiveRequest = (client) => {
    setArchiveId(client.id);
    setArchiveName(client.name);
  };

  const confirmArchiveRequest = () => {
    toast("Archive request submitted. Our team will process it shortly.", "info");
    setArchiveId(null);
    setArchiveName("");
  };

  if (upgradePending) {
    return (
      <>
        <div className="page-header"><div className="page-title">Client Organizations</div></div>
        <div className="empty" style={{ padding: "4rem 2rem" }}>
          <div className="empty-icon" aria-hidden="true">⬡</div>
          <div className="empty-title">MSP Upgrade Pending</div>
          <p className="empty-sub">Your MSP upgrade request is under review by our team. You&apos;ll receive an email once it&apos;s approved and MSP features are unlocked.</p>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Client Orgs</div>
          <div className="page-sub">Manage your client organisations</div>
        </div>
        {canManage && (
          <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)}>
            + Add Client Org
          </button>
        )}
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading client orgs...</span></div>
        ) : clients.length === 0 ? (
          <div className="empty">
            <div className="empty-icon" aria-hidden="true">⬡</div>
            <div className="empty-title">No client organizations yet</div>
            <p className="empty-sub">Add your first client to get started.</p>
            {canManage && (
              <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)} style={{ margin: "0 auto" }}>
                + Add Client Org
              </button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Org Name</th>
                  <th>Contact Email</th>
                  <th>Status</th>
                  <th>Targets</th>
                  {canManage && <th></th>}
                </tr>
              </thead>
              <tbody>
                {clients.map((c) => (
                  <tr key={c.id}>
                    <td className="host-cell">{c.name}</td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.78rem" }}>
                      {c.contactEmail || "—"}
                    </td>
                    <td>
                      <Badge type={c.archivedAt ? "revoked" : "active"}>
                        {c.archivedAt ? "archived" : "active"}
                      </Badge>
                    </td>
                    <td className="mono">{c.targetCount ?? 0}</td>
                    {canManage && (
                      <td>
                        <div className="row-actions">
                          <button
                            className="scan-btn"
                            style={{ color: "var(--muted)", borderColor: "rgba(90,96,112,0.3)" }}
                            onClick={() => setEditClient(c)}
                            aria-label={`Edit ${c.name}`}
                          >
                            ✎
                          </button>
                          {!c.archivedAt && (
                            <button
                              className="scan-btn"
                              style={{ color: "var(--orange)", borderColor: "rgba(255,145,0,0.3)" }}
                              onClick={() => handleArchiveRequest(c)}
                              aria-label={`Request archive for ${c.name}`}
                            >
                              Archive
                            </button>
                          )}
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showAdd && (
        <MspClientModal
          token={token}
          onClose={() => setShowAdd(false)}
          onSaved={() => { setShowAdd(false); load(); toast("Client org created", "success"); }}
        />
      )}

      {editClient && (
        <MspClientModal
          token={token}
          client={editClient}
          onClose={() => setEditClient(null)}
          onSaved={() => { setEditClient(null); load(); toast("Client org updated", "success"); }}
        />
      )}

      {archiveId && (
        <div className="modal-bg">
          <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="archive-modal-title">
            <div className="modal-title" id="archive-modal-title">Request Archive</div>
            <p className="modal-sub">
              Archive requests for <strong>{archiveName}</strong> are processed by our support team. Continue?
            </p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => { setArchiveId(null); setArchiveName(""); }}>
                Cancel
              </button>
              <button className="btn btn-danger" onClick={confirmArchiveRequest}>
                Submit Request
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
