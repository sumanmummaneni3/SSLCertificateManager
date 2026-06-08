import { useState, useCallback } from "react";

// ─── TOAST SYSTEM ────────────────────────────────────────────────────────────
// Module-level counter so IDs survive re-renders without needing useRef.
let toastId = 0;

/**
 * useToasts — manages a list of transient toast notifications.
 *
 * Returns { toasts, add } where:
 *   toasts  — array of { id, msg, type } objects (type: "info" | "success" | "error")
 *   add(msg, type) — enqueues a toast that auto-dismisses after 3.5 s
 */
export function useToasts() {
  const [toasts, setToasts] = useState([]);
  const add = useCallback((msg, type = "info") => {
    const id = ++toastId;
    setToasts((t) => [...t, { id, msg, type }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3500);
  }, []);
  return { toasts, add };
}
