import { useState } from "react";
import { api } from "@/lib/api.js";
import { Spinner, Badge } from "@/components/index.js";
import { providerLabel, providerColor } from "./providers.js";
import { LocationModal } from "./LocationModal.jsx";

export function LocationsView({ locations, loading, token, onRefresh, toast, me }) {
  const [showAdd, setShowAdd]       = useState(false);
  const [editLoc, setEditLoc]       = useState(null);
  const [deleteId, setDeleteId]     = useState(null);
  const [deleting, setDeleting]     = useState(false);
  const canWrite = me == null || me?.permissions?.canWriteLocations;

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await api.deleteLocation(deleteId, token);
      toast("Location deleted", "success");
      setDeleteId(null);
      onRefresh();
    } catch (e) {
      toast("Delete failed: " + e.message, "error");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Locations</div>
          <div className="page-sub">Organise targets by physical or cloud location</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          {canWrite && (
            <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)}>+ Add Location</button>
          )}
        </div>
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading locations...</span></div>
        ) : locations.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">📍</div>
            <div className="empty-title">No locations yet</div>
            <p className="empty-sub">Create a location to group targets by site or cloud region.</p>
            {canWrite && (
              <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)} style={{ margin: "0 auto" }}>+ Add Location</button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Provider</th>
                  <th>Geo Region</th>
                  <th>Cloud Region</th>
                  <th>Targets</th>
                  {canWrite && <th></th>}
                </tr>
              </thead>
              <tbody>
                {locations.map((loc) => (
                  <tr key={loc.id}>
                    <td>
                      <div className="host-cell">{loc.name}</div>
                      {loc.address && <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)" }}>{loc.address}</div>}
                    </td>
                    <td><Badge type={providerColor(loc.provider)}>{providerLabel(loc.provider)}</Badge></td>
                    <td className="mono">{loc.geoRegion || "—"}</td>
                    <td className="mono">{loc.cloudRegion || "—"}</td>
                    <td className="mono">{loc.targetCount ?? 0}</td>
                    {canWrite && (
                      <td>
                        <div className="row-actions">
                          <button className="scan-btn" style={{ color: "var(--muted)", borderColor: "rgba(90,96,112,0.3)" }}
                            onClick={() => setEditLoc(loc)} title="Edit location">✎</button>
                          <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                            onClick={() => setDeleteId(loc.id)} title="Delete location">✕</button>
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
        <LocationModal token={token} onClose={() => setShowAdd(false)}
          onSaved={() => { setShowAdd(false); onRefresh(); }} toast={toast} />
      )}
      {editLoc && (
        <LocationModal token={token} location={editLoc} onClose={() => setEditLoc(null)}
          onSaved={() => { setEditLoc(null); onRefresh(); }} toast={toast} />
      )}
      {deleteId && (
        <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && setDeleteId(null)}>
          <div className="modal" role="dialog" aria-modal="true">
            <div className="modal-title">Delete Location</div>
            <p style={{ color: "var(--muted)", marginBottom: "1.5rem" }}>
              This will unlink all associated targets. This action cannot be undone.
            </p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setDeleteId(null)}>Cancel</button>
              <button className="btn" style={{ background: "var(--red)", color: "#fff" }}
                onClick={handleDelete} disabled={deleting}>
                {deleting ? <><Spinner /> Deleting...</> : "Delete"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
