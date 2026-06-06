import { useState, useEffect, useCallback } from "react";

export function useCountdown(expiresAt) {
  const calcRemaining = useCallback(() => {
    const ms = new Date(expiresAt).getTime() - Date.now();
    return Math.max(0, Math.floor(ms / 1000));
  }, [expiresAt]);

  const [secs, setSecs] = useState(calcRemaining);

  useEffect(() => {
    const id = setInterval(() => setSecs(calcRemaining()), 1000);
    return () => clearInterval(id);
  }, [calcRemaining]);

  const mins = Math.floor(secs / 60);
  const s = secs % 60;
  return { secs, label: `${mins}:${String(s).padStart(2, "0")} remaining`, urgent: secs < 120 };
}
