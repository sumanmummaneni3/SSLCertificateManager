import { useState, useEffect, useCallback, useRef } from "react";
import { api } from "./lib/api.js";
import { useToasts } from "./lib/useToasts.js";
import { Toast, Spinner } from "./components/index.js";
import { AppShell }               from "./layout/AppShell.jsx";
import { LaunchScreen }           from "./screens/LaunchScreen.jsx";
import { PostRegistrationScreen } from "./screens/PostRegistrationScreen.jsx";
import { SessionExpiredScreen }   from "./screens/SessionExpiredScreen.jsx";
import { ForgotPasswordScreen }   from "./screens/ForgotPasswordScreen.jsx";
import { VerifyEmailScreen }      from "./screens/VerifyEmailScreen.jsx";
import { ResetPasswordScreen }    from "./screens/ResetPasswordScreen.jsx";
import { OrgSetup }               from "./screens/OrgSetup.jsx";
import { FirstTarget }            from "./screens/FirstTarget.jsx";
import { InviteAcceptScreen }     from "./screens/InviteAcceptScreen.jsx";
import { AnonScanDashboard }      from "./screens/AnonScanDashboard.jsx";

// All application styles live in src/styles/global.css (imported in main.jsx).
// API client → src/lib/api.js | Helpers → src/lib/helpers.js | Validation → src/lib/validation.js

// ─── APP ROOT ─────────────────────────────────────────────────────────────────
export default function App() {
  const [token, setToken]     = useState(null);
  const [orgData, setOrgData] = useState(null);
  const [meData, setMeData]   = useState(null);
  const [, setTargets] = useState(null);
  // launch | org-setup | first-target | app | invite |
  // post-register | forgot-password | verify-email | reset-password | anon-scan
  const [phase, setPhase]     = useState("launch");
  // RFC 0011: viewToken from /scan/:viewToken URL; cleared after claim
  const [anonViewToken, setAnonViewToken] = useState(null);
  const [loading, setLoading] = useState(false);
  const [inviteToken, setInviteToken] = useState(null);
  // Stored email for post-registration screen
  const [pendingEmail, setPendingEmail] = useState("");
  // Token read from URL for verify-email and reset-password pages
  const [authUrlToken, setAuthUrlToken] = useState("");
  // Deep-link returnTo certId (from /certificates/{certId} path or ?certId= param)
  const [returnToCertId, setReturnToCertId] = useState(null);
  const { toasts, add: toast } = useToasts();

  // ── Session expiry ────────────────────────────────────────────────────────
  const [sessionExpiredMsg, setSessionExpiredMsg] = useState("");
  const expireSession = useCallback((message) => {
    toast(message, "error");
    setSessionExpiredMsg(message);
    setToken(null);
    setOrgData(null);
    setMeData(null);
    setPhase("session-expired");
  }, [toast]);

  useEffect(() => {
    api.setSessionExpiredHandler(() =>
      expireSession("Your session has expired — please sign in again.")
    );
    return () => api.setSessionExpiredHandler(null);
  }, [expireSession]);

  // ── Idle timeout (normal users only; platform admins are exempt) ──────────
  const IDLE_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
  const IDLE_WARN_MS    = 29 * 60 * 1000; // show warning at 29 minutes

  const lastActivityRef  = useRef(Date.now());
  const warnShownRef     = useRef(false);

  useEffect(() => {
    if (phase !== "app" || meData?.platformAdmin === true) return;

    lastActivityRef.current = Date.now();
    warnShownRef.current    = false;

    const resetActivity = () => {
      lastActivityRef.current = Date.now();
      warnShownRef.current = false;
    };

    const EVENTS = ["mousemove", "keydown", "click", "scroll", "touchstart"];
    let throttleTimer = null;
    const throttledReset = () => {
      if (throttleTimer) return;
      throttleTimer = setTimeout(() => {
        throttleTimer = null;
        resetActivity();
      }, 1000);
    };

    EVENTS.forEach((ev) => window.addEventListener(ev, throttledReset, { passive: true }));

    const interval = setInterval(() => {
      const idle = Date.now() - lastActivityRef.current;
      if (idle >= IDLE_TIMEOUT_MS) {
        expireSession("You were signed out due to inactivity.");
      } else if (idle >= IDLE_WARN_MS && !warnShownRef.current) {
        warnShownRef.current = true;
        toast("You'll be signed out in 1 minute due to inactivity.", "error");
      }
    }, 12000); // check every 12 seconds

    return () => {
      EVENTS.forEach((ev) => window.removeEventListener(ev, throttledReset));
      if (throttleTimer) clearTimeout(throttleTimer);
      clearInterval(interval);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps -- IDLE_TIMEOUT_MS/IDLE_WARN_MS are named constants
  }, [phase, meData?.platformAdmin, expireSession, toast]);

  const handleToken = async (t, ctx = {}) => {
    setToken(t);
    api.resetSessionExpired();
    setLoading(true);
    try {
      const [org, me] = await Promise.all([
        api.getOrg(t),
        api.getMe(t).catch(() => null),
      ]);
      setOrgData(org);
      setMeData(me);

      // RFC 0011: If the user just signed up/in from the anon scan page, claim the session.
      if (anonViewToken) {
        try {
          await api.anon.claimSession(anonViewToken, t);
          toast("Scan claimed — run a full network scan to see details.", "success");
        } catch {
          // Non-fatal: session may already be claimed or expired
        } finally {
          setAnonViewToken(null);
        }
      }

      if (ctx.fromInvite) {
        const tgts = await api.getTargets(t);
        setTargets(tgts?.content || []);
        setPhase("app");
        return;
      }

      const onboardingDone = me?.user?.onboardingCompleted === true;
      if (!onboardingDone) {
        setPhase("org-setup");
        return;
      }

      const tgts = await api.getTargets(t);
      const list = tgts?.content || [];
      setTargets(list);

      if (list.length === 0) {
        setPhase("first-target");
      } else {
        setPhase("app");
      }
    } catch (e) {
      toast("Error loading data: " + e.message, "error");
      setPhase("launch");
      setToken(null);
    } finally {
      setLoading(false);
    }
  };

  // Parse deep-link cert ID from URL: /certificates/{certId} or ?certId={certId}
  const parseCertIdFromUrl = () => {
    const params = new URLSearchParams(window.location.search);
    const fromParam = params.get("certId");
    if (fromParam) return fromParam;
    const pathMatch = window.location.pathname.match(/^\/certificates\/([^/]+)$/);
    return pathMatch ? pathMatch[1] : null;
  };

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const urlToken = params.get("token");
    const urlInvite = params.get("invite");
    const pathname = window.location.pathname;
    const isInvitePath = pathname === "/invite";

    // RFC 0011: Detect anonymous scan dashboard: /scan/:viewToken
    if (pathname.startsWith("/scan/")) {
      const viewToken = pathname.replace("/scan/", "").split("/")[0];
      if (viewToken) {
        window.history.replaceState({}, "", "/");
        setAnonViewToken(viewToken);
        setPhase("anon-scan");
        return;
      }
    }

    if (pathname === "/auth/verify-email") {
      const verifyToken = params.get("token");
      window.history.replaceState({}, "", "/");
      setAuthUrlToken(verifyToken || "");
      setPhase("verify-email");
      return;
    }

    if (pathname === "/auth/reset-password") {
      const resetTok = params.get("token");
      window.history.replaceState({}, "", "/");
      setAuthUrlToken(resetTok || "");
      setPhase("reset-password");
      return;
    }

    const deepLinkCertId = parseCertIdFromUrl();
    if (deepLinkCertId) {
      setReturnToCertId(deepLinkCertId);
      window.history.replaceState({}, "", "/");
      if (urlToken) { handleToken(urlToken); return; }
      return;
    }

    const hash = window.location.hash;
    if ((pathname === "/auth/callback" || hash.startsWith("#token=")) && hash) {
      const hashParams = new URLSearchParams(hash.slice(1));
      const hashToken = hashParams.get("token");
      window.history.replaceState({}, "", "/");
      if (hashToken) {
        handleToken(hashToken);
        return;
      }
    }

    window.history.replaceState({}, "", "/");
    if (isInvitePath && urlToken) {
      setInviteToken(urlToken);
      setPhase("invite");
    } else if (urlToken) {
      handleToken(urlToken);
    } else if (urlInvite) {
      setInviteToken(urlInvite);
      setPhase("invite");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional mount-only effect
  }, []);

  const handleLogout = async () => {
    try { await api.logout(token); } catch { /* ignore logout errors */ }
    setToken(null);
    setOrgData(null);
    setMeData(null);
    setPhase("launch");
  };

  const afterOrgSetup = async () => {
    const org = await api.getOrg(token).catch(() => orgData);
    setOrgData(org);
    setPhase("first-target");
  };

  const afterFirstTarget = async () => {
    const [org, tgts] = await Promise.all([
      api.getOrg(token).catch(() => orgData),
      api.getTargets(token).catch(() => ({ content: [] })),
    ]);
    setOrgData(org);
    setTargets(tgts?.content || []);
    setPhase("app");
  };

  if (loading) {
    return (
      <div className="launch">
        <div className="loading-center" style={{ minHeight: "100vh" }}>
          <Spinner lg />
          <span>Loading your workspace...</span>
        </div>
      </div>
    );
  }

  const goToLaunch = () => setPhase("launch");

  return (
    <>
      {phase === "launch" && (
        <LaunchScreen
          onToken={handleToken}
          onPostRegister={(email) => { setPendingEmail(email); setPhase("post-register"); }}
          onForgotPassword={() => setPhase("forgot-password")}
          returnToCertId={returnToCertId}
        />
      )}
      {phase === "session-expired" && <SessionExpiredScreen message={sessionExpiredMsg} onSignIn={goToLaunch} />}
      {phase === "post-register"  && <PostRegistrationScreen email={pendingEmail} onBack={goToLaunch} />}
      {phase === "forgot-password" && <ForgotPasswordScreen onBack={goToLaunch} />}
      {phase === "verify-email"   && <VerifyEmailScreen verifyToken={authUrlToken} onGoToSignIn={goToLaunch} />}
      {phase === "reset-password" && <ResetPasswordScreen resetToken={authUrlToken} onGoToSignIn={goToLaunch} />}
      {phase === "org-setup"    && <OrgSetup token={token} onDone={afterOrgSetup} toast={toast} />}
      {phase === "first-target" && <FirstTarget token={token} onDone={afterFirstTarget} toast={toast} />}
      {phase === "app"          && <AppShell token={token} org={orgData} me={meData} toast={toast} onLogout={handleLogout} initialCertId={returnToCertId} onExpireSession={expireSession} />}
      {phase === "invite"       && <InviteAcceptScreen inviteToken={inviteToken} onAccepted={handleToken} toast={toast} />}
      {phase === "anon-scan" && anonViewToken && (
        <AnonScanDashboard
          viewToken={anonViewToken}
          onSignUp={() => setPhase("launch")}
        />
      )}
      <Toast toasts={toasts} />
    </>
  );
}
