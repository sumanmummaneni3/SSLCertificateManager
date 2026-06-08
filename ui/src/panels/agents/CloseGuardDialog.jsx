export function CloseGuardDialog({ onConfirm, onCancel }) {
  return (
    <div className="close-guard-overlay" role="alertdialog" aria-modal="true" aria-labelledby="cg-title" aria-describedby="cg-body">
      <div className="close-guard-dialog">
        <div className="close-guard-title" id="cg-title">Discard install key?</div>
        <p className="close-guard-body" id="cg-body">
          You will not be able to retrieve this install key. To re-deploy the agent,
          you would need to delete it and create a new one.
        </p>
        <div style={{ display: "flex", gap: "0.75rem" }}>
          <button className="btn btn-secondary btn-sm" style={{ flex: 1 }} onClick={onCancel} autoFocus>
            Go back
          </button>
          <button className="btn btn-danger btn-sm" style={{ flex: 1 }} onClick={onConfirm}>
            Discard key
          </button>
        </div>
      </div>
    </div>
  );
}
