import { Spinner } from "./Spinner.jsx";

/**
 * ConfirmModal — shared parameterized confirmation dialog.
 *
 * The two delete dialogs in the app differ intentionally:
 *
 *   Targets delete   role="alertdialog"  NO backdrop dismiss  .btn-danger class
 *   Locations delete role="dialog"       HAS backdrop dismiss  inline red style
 *
 * Props:
 *   title          {string}    Modal heading text
 *   body           {string}    Body paragraph text
 *   confirmLabel   {string}    Label for the confirm button (default "Confirm")
 *   cancelLabel    {string}    Label for the cancel button (default "Cancel")
 *   onConfirm      {function}  Called when the confirm button is clicked
 *   onCancel       {function}  Called when cancelled (cancel button or backdrop click)
 *   loading        {boolean}   When true disables buttons and shows a spinner
 *   role           {string}    ARIA role for the inner modal div.
 *                              "alertdialog" for destructive irreversible actions (default),
 *                              "dialog" for gentler confirms that allow backdrop dismiss.
 *   backdropDismiss {boolean}  If true, clicking the backdrop calls onCancel (default false).
 *   dangerButtonClass {string} CSS class for the confirm button. Defaults to "btn-danger".
 *                              Pass "" to use inline styling instead (locations pattern).
 *   dangerButtonStyle {object} Inline style for the confirm button (used when dangerButtonClass is "").
 *   labelledBy     {string}    id of the element that labels the dialog (for aria-labelledby).
 */
export function ConfirmModal({
  title,
  body,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  onConfirm,
  onCancel,
  loading = false,
  role = "alertdialog",
  backdropDismiss = false,
  dangerButtonClass = "btn-danger",
  dangerButtonStyle = {},
  labelledBy,
}) {
  const handleBackdrop = (e) => {
    if (backdropDismiss && e.target === e.currentTarget) onCancel();
  };

  return (
    <div className="modal-bg" onClick={handleBackdrop}>
      <div
        className="modal"
        role={role}
        aria-modal="true"
        aria-labelledby={labelledBy}
      >
        {labelledBy && (
          <div className="modal-title" id={labelledBy}>{title}</div>
        )}
        {!labelledBy && (
          <div className="modal-title">{title}</div>
        )}
        <p className="modal-sub">{body}</p>
        <div className="modal-actions">
          <button
            className="btn btn-secondary"
            onClick={onCancel}
            disabled={loading}
          >
            {cancelLabel}
          </button>
          <button
            className={`btn ${dangerButtonClass}`}
            style={dangerButtonStyle}
            onClick={onConfirm}
            disabled={loading}
          >
            {loading ? <><Spinner /> Working...</> : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
