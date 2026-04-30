import { useState, useEffect, useCallback } from "react";
import { useTheme } from "./theme/useTheme.js";
import { SIDEBAR_THEMES } from "./theme/tokens.js";

// ─── CONFIG ──────────────────────────────────────────────────────────────────
const API_BASE = "";
const DEV_MODE = true;               // Set false to use Google OAuth

// SIDEBAR_THEMES imported from src/theme/tokens.js — see that file for palette definitions.

// ─── STYLES ──────────────────────────────────────────────────────────────────
const styles = `
  @import url('https://fonts.googleapis.com/css2?family=DM+Mono:ital,wght@0,300;0,400;0,500;1,400&family=Syne:wght@400;600;700;800&display=swap');

  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  :root {
    /* Semantic aliases — actual values injected by ThemeContext */
    --bg:        var(--color-bg);
    --surface:   var(--color-surface);
    --surface2:  var(--color-surface2);
    --border:    var(--color-border);
    --border2:   var(--color-border2);
    --text:      var(--color-text);
    --muted:     var(--color-muted);
    --accent:    var(--color-primary);
    --accent2:   var(--color-primary2);
    --green:     var(--color-success);
    --yellow:    var(--color-warning);
    --red:       var(--color-danger);
    --orange:    var(--color-orange);
    --glow:      var(--color-glow);
    --font-head: 'Syne', sans-serif;
    --font-mono: 'DM Mono', monospace;
    --radius:    8px;
  }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: var(--font-mono);
    min-height: 100vh;
    overflow-x: hidden;
  }

  /* ── GRID BACKGROUND ── */
  body::before {
    content: '';
    position: fixed; inset: 0;
    background-image:
      linear-gradient(color-mix(in srgb, var(--color-primary) 4%, transparent) 1px, transparent 1px),
      linear-gradient(90deg, color-mix(in srgb, var(--color-primary) 4%, transparent) 1px, transparent 1px);
    background-size: 40px 40px;
    pointer-events: none; z-index: 0;
  }

  #root { position: relative; z-index: 1; min-height: 100vh; }

  /* ── LAUNCH SCREEN ── */
  .launch {
    min-height: 100vh;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 2rem;
    animation: fadeIn 0.6s ease;
  }

  .launch-logo {
    display: flex; align-items: center; gap: 14px;
    margin-bottom: 3rem;
  }

  .logo-icon {
    width: 56px; height: 56px;
    background: linear-gradient(135deg, var(--accent), var(--accent2));
    border-radius: 14px;
    display: flex; align-items: center; justify-content: center;
    font-size: 28px;
    box-shadow: var(--glow);
  }

  .logo-text {
    font-family: var(--font-head);
    font-size: 2rem; font-weight: 800;
    letter-spacing: -0.03em;
    background: linear-gradient(135deg, var(--text) 40%, var(--accent));
    -webkit-background-clip: text; -webkit-text-fill-color: transparent;
  }

  .launch-card {
    background: var(--surface);
    border: 1px solid var(--border2);
    border-radius: 16px;
    padding: 2.5rem;
    width: 100%; max-width: 420px;
    box-shadow: 0 24px 60px color-mix(in srgb, var(--color-bg) 30%, transparent), var(--glow);
  }

  .launch-title {
    font-family: var(--font-head);
    font-size: 1.4rem; font-weight: 700;
    margin-bottom: 0.5rem;
  }

  .launch-sub {
    color: var(--muted); font-size: 0.8rem;
    margin-bottom: 2rem; line-height: 1.6;
  }

  .dev-badge {
    display: inline-flex; align-items: center; gap: 6px;
    background: rgba(255, 215, 64, 0.1);
    border: 1px solid rgba(255, 215, 64, 0.3);
    color: var(--yellow);
    font-size: 0.7rem; font-weight: 500;
    padding: 3px 10px; border-radius: 20px;
    margin-bottom: 1.5rem;
    font-family: var(--font-mono);
  }

  /* ── INPUTS ── */
  .field { margin-bottom: 1.2rem; }

  .field label {
    display: block; font-size: 0.72rem;
    color: var(--muted); margin-bottom: 6px;
    letter-spacing: 0.08em; text-transform: uppercase;
  }

  .field input, .field select {
    width: 100%;
    background: var(--surface2);
    border: 1px solid var(--border2);
    border-radius: var(--radius);
    color: var(--text);
    font-family: var(--font-mono);
    font-size: 0.85rem;
    padding: 10px 14px;
    outline: none;
    transition: border-color 0.2s, box-shadow 0.2s;
  }

  .field input:focus, .field select:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(0, 212, 255, 0.1);
  }

  .field input::placeholder { color: var(--muted); }

  /* ── BUTTONS ── */
  .btn {
    width: 100%;
    padding: 12px 20px;
    border-radius: var(--radius);
    border: none; cursor: pointer;
    font-family: var(--font-mono);
    font-size: 0.85rem; font-weight: 500;
    transition: all 0.2s;
    display: flex; align-items: center; justify-content: center; gap: 8px;
    letter-spacing: 0.02em;
  }

  .btn-primary {
    background: linear-gradient(135deg, var(--accent), var(--accent2));
    color: var(--color-bg);
    font-weight: 600;
  }

  .btn-primary:hover:not(:disabled) {
    transform: translateY(-1px);
    box-shadow: 0 8px 24px color-mix(in srgb, var(--color-primary) 30%, transparent);
  }

  .btn-secondary {
    background: var(--surface2);
    border: 1px solid var(--border2);
    color: var(--text);
  }

  .btn-secondary:hover:not(:disabled) {
    border-color: var(--accent);
    color: var(--accent);
  }

  .btn-ghost {
    background: transparent; border: none;
    color: var(--muted); font-size: 0.75rem;
    padding: 6px 12px; width: auto;
  }

  .btn-ghost:hover { color: var(--accent); }

  .btn-danger {
    background: rgba(255, 82, 82, 0.1);
    border: 1px solid rgba(255, 82, 82, 0.3);
    color: var(--red);
  }

  .btn-danger:hover:not(:disabled) {
    background: rgba(255, 82, 82, 0.2);
  }

  .btn:disabled { opacity: 0.4; cursor: not-allowed; transform: none; }

  .btn-sm {
    width: auto; padding: 6px 14px;
    font-size: 0.75rem;
  }

  /* ── APP SHELL ── */
  .app { display: flex; min-height: 100vh; }

  .sidebar {
    width: 220px; flex-shrink: 0;
    background: var(--sb-bg, var(--surface));
    border-right: 1px solid var(--sb-border, var(--border));
    display: flex; flex-direction: column;
    padding: 1.5rem 1rem;
    position: sticky; top: 0; height: 100vh;
    transition: background 0.25s, border-color 0.25s;
  }

  .sidebar-logo {
    display: flex; align-items: center; gap: 10px;
    padding: 0 0.5rem; margin-bottom: 2rem;
  }

  .sidebar-logo .logo-icon { width: 34px; height: 34px; font-size: 16px; border-radius: 8px; }
  .sidebar-logo .logo-text { font-size: 1rem; color: var(--sb-text, var(--text)); }

  .nav-section {
    font-size: 0.65rem; color: var(--sb-muted, var(--muted));
    letter-spacing: 0.1em; text-transform: uppercase;
    padding: 0 0.5rem; margin: 1rem 0 0.5rem;
  }

  .nav-item {
    display: flex; align-items: center; gap: 10px;
    padding: 9px 12px; border-radius: var(--radius);
    cursor: pointer; font-size: 0.82rem;
    color: var(--sb-muted, var(--muted));
    transition: all 0.15s;
    border: 1px solid transparent;
    /* a11y: make it a keyboard target */
    outline: none;
    user-select: none;
  }

  .nav-item:hover {
    color: var(--sb-text, var(--text));
    background: var(--sb-hover, var(--surface2));
  }

  .nav-item:focus-visible {
    outline: 2px solid var(--sb-active-color, var(--accent));
    outline-offset: 2px;
  }

  .nav-item.active {
    color: var(--sb-active-color, var(--accent));
    background: var(--sb-active-bg, rgba(0,212,255,0.08));
    border-color: var(--sb-active-border, rgba(0,212,255,0.15));
  }

  /* ── THEME TOGGLE ── */
  .theme-toggle-btn {
    background: transparent;
    border: 1px solid var(--sb-border, var(--border));
    color: var(--sb-muted, var(--muted));
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 0.72rem;
    font-family: var(--font-mono);
    cursor: pointer;
    transition: all 0.15s;
    display: inline-flex; align-items: center; gap: 6px;
    width: 100%;
    margin-top: 0.5rem;
  }

  .theme-toggle-btn:hover {
    border-color: var(--sb-active-color, var(--accent));
    color: var(--sb-active-color, var(--accent));
  }

  .theme-toggle-btn:focus-visible {
    outline: 2px solid var(--sb-active-color, var(--accent));
    outline-offset: 2px;
  }

  .sidebar-footer {
    margin-top: auto;
    padding-top: 1rem;
    border-top: 1px solid var(--sb-border, var(--border));
  }

  .org-tag {
    font-size: 0.7rem; color: var(--sb-muted, var(--muted));
    padding: 0 0.5rem; margin-bottom: 0.5rem;
    display: flex; align-items: center; gap: 6px;
  }

  .org-tag span { color: var(--sb-text, var(--text)); font-weight: 500; }

  /* ── THEME PICKER ── */
  .theme-picker {
    padding: 0 0.5rem;
    margin-top: 0.75rem;
  }

  .theme-picker-label {
    font-size: 0.6rem; letter-spacing: 0.08em; text-transform: uppercase;
    color: var(--sb-muted, var(--muted));
    margin-bottom: 0.4rem;
  }

  .theme-swatches {
    display: flex; gap: 6px; flex-wrap: wrap;
  }

  .theme-swatch {
    width: 18px; height: 18px;
    border-radius: 50%;
    cursor: pointer;
    border: 2px solid transparent;
    transition: transform 0.15s, border-color 0.15s;
  }

  .theme-swatch:hover { transform: scale(1.2); }
  .theme-swatch.active { border-color: var(--sb-active-color, var(--accent)); transform: scale(1.15); }
  .theme-swatch:focus-visible { outline: 2px solid var(--sb-active-color, var(--accent)); outline-offset: 2px; }

  /* ── MAIN CONTENT ── */
  .main { flex: 1; overflow-y: auto; }

  .page-header {
    padding: 2rem 2rem 1rem;
    border-bottom: 1px solid var(--border);
    display: flex; align-items: flex-start; justify-content: space-between;
  }

  .page-title {
    font-family: var(--font-head);
    font-size: 1.6rem; font-weight: 700;
    letter-spacing: -0.02em;
  }

  .page-sub { color: var(--muted); font-size: 0.78rem; margin-top: 4px; }

  .page-content { padding: 2rem; }

  /* ── STAT CARDS ── */
  .stats-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 1rem; margin-bottom: 2rem;
  }

  .stat-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 1.25rem;
    transition: border-color 0.2s;
  }

  .stat-card:hover { border-color: var(--border2); }

  .stat-label {
    font-size: 0.68rem; color: var(--muted);
    letter-spacing: 0.08em; text-transform: uppercase;
    margin-bottom: 0.75rem;
  }

  .stat-value {
    font-family: var(--font-head);
    font-size: 2rem; font-weight: 800;
    line-height: 1;
  }

  .stat-card.valid .stat-value   { color: var(--green); }
  .stat-card.expiring .stat-value { color: var(--yellow); }
  .stat-card.expired .stat-value  { color: var(--red); }
  .stat-card.total .stat-value    { color: var(--accent); }
  .stat-card.unreachable .stat-value { color: var(--orange); }

  /* ── TARGET TABLE ── */
  .section-header {
    display: flex; align-items: center; justify-content: space-between;
    margin-bottom: 1rem;
  }

  .section-title {
    font-family: var(--font-head);
    font-size: 1rem; font-weight: 600;
  }

  .table-wrap {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    overflow: hidden;
  }

  table { width: 100%; border-collapse: collapse; }

  thead { background: var(--surface2); }

  th {
    padding: 10px 16px; text-align: left;
    font-size: 0.68rem; color: var(--muted);
    letter-spacing: 0.08em; text-transform: uppercase;
    font-weight: 500; white-space: nowrap;
  }

  td {
    padding: 12px 16px;
    font-size: 0.82rem;
    border-top: 1px solid var(--border);
  }

  tr:hover td { background: color-mix(in srgb, var(--color-primary) 3%, transparent); }

  .host-cell { font-weight: 500; color: var(--text); }
  .mono      { font-family: var(--font-mono); color: var(--muted); font-size: 0.75rem; }

  /* ── BADGES ── */
  .badge {
    display: inline-flex; align-items: center; gap: 5px;
    padding: 3px 10px; border-radius: 20px;
    font-size: 0.7rem; font-weight: 500;
    font-family: var(--font-mono);
    white-space: nowrap;
  }

  .badge-valid     { background: rgba(0,230,118,0.12); color: var(--green);  border: 1px solid rgba(0,230,118,0.25); }
  .badge-expiring  { background: rgba(255,215,64,0.12); color: var(--yellow); border: 1px solid rgba(255,215,64,0.25); }
  .badge-expired   { background: rgba(255,82,82,0.12);  color: var(--red);   border: 1px solid rgba(255,82,82,0.25); }
  .badge-unreachable { background: rgba(255,145,0,0.12); color: var(--orange); border: 1px solid rgba(255,145,0,0.25); }
  .badge-unknown   { background: rgba(90,96,112,0.15);  color: var(--muted); border: 1px solid var(--border2); }
  .badge-domain    { background: rgba(0,212,255,0.08);  color: var(--accent); border: 1px solid rgba(0,212,255,0.2); }
  .badge-ip        { background: rgba(0,102,255,0.1);   color: #6699ff;      border: 1px solid rgba(102,153,255,0.2); }
  .badge-hostname  { background: rgba(128,0,255,0.1);   color: #bb88ff;      border: 1px solid rgba(187,136,255,0.2); }
  .badge-private   { background: rgba(255,145,0,0.08);  color: var(--orange); border: 1px solid rgba(255,145,0,0.2); font-size: 0.65rem; }
  .badge-public    { background: rgba(0,230,118,0.08);  color: var(--green);  border: 1px solid rgba(0,230,118,0.2); font-size: 0.65rem; }

  /* ── EMPTY STATE ── */
  .empty {
    text-align: center; padding: 4rem 2rem;
    color: var(--muted);
  }

  .empty-icon { font-size: 3rem; margin-bottom: 1rem; opacity: 0.4; }
  .empty-title { font-family: var(--font-head); font-size: 1.1rem; color: var(--text); margin-bottom: 0.5rem; }
  .empty-sub { font-size: 0.8rem; line-height: 1.6; margin-bottom: 1.5rem; }

  /* ── MODAL ── */
  .modal-bg {
    position: fixed; inset: 0;
    background: color-mix(in srgb, var(--color-bg) 70%, transparent);
    backdrop-filter: blur(4px);
    display: flex; align-items: center; justify-content: center;
    z-index: 100; padding: 1rem;
    animation: fadeIn 0.15s ease;
  }

  .modal {
    background: var(--surface);
    border: 1px solid var(--border2);
    border-radius: 16px;
    padding: 2rem;
    width: 100%; max-width: 480px;
    box-shadow: 0 32px 80px color-mix(in srgb, var(--color-bg) 50%, transparent);
    animation: slideUp 0.2s ease;
  }

  .modal-title {
    font-family: var(--font-head);
    font-size: 1.2rem; font-weight: 700;
    margin-bottom: 0.4rem;
  }

  .modal-sub { color: var(--muted); font-size: 0.78rem; margin-bottom: 1.5rem; line-height: 1.6; }

  .modal-actions { display: flex; gap: 0.75rem; margin-top: 1.5rem; }
  .modal-actions .btn { flex: 1; }

  /* ── CERT DETAIL ── */
  .cert-detail {
    background: var(--surface2);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 1rem;
    margin-top: 0.75rem;
  }

  .cert-row {
    display: flex; justify-content: space-between; align-items: center;
    font-size: 0.78rem; padding: 5px 0;
    border-bottom: 1px solid var(--border);
  }

  .cert-row:last-child { border-bottom: none; }
  .cert-row .key { color: var(--muted); }
  .cert-row .val { color: var(--text); font-weight: 500; text-align: right; max-width: 60%; word-break: break-all; }

  /* ── DAYS BAR ── */
  .days-bar { display: flex; align-items: center; gap: 8px; }
  .days-track {
    flex: 1; height: 4px; background: var(--border2);
    border-radius: 2px; overflow: hidden;
  }
  .days-fill { height: 100%; border-radius: 2px; transition: width 0.5s ease; }

  /* ── SCAN BUTTON ── */
  .scan-btn {
    background: transparent;
    border: 1px solid var(--border2);
    color: var(--muted);
    border-radius: 6px;
    padding: 5px 10px;
    font-size: 0.72rem;
    font-family: var(--font-mono);
    cursor: pointer;
    transition: all 0.15s;
    display: inline-flex; align-items: center; gap: 5px;
  }

  .scan-btn:hover { border-color: var(--accent); color: var(--accent); }
  .scan-btn.scanning { color: var(--accent); border-color: var(--accent); animation: pulse 1s infinite; }

  /* ── TOAST ── */
  .toast-wrap {
    position: fixed; bottom: 1.5rem; right: 1.5rem;
    z-index: 200; display: flex; flex-direction: column; gap: 0.5rem;
  }

  .toast {
    background: var(--surface);
    border: 1px solid var(--border2);
    border-radius: var(--radius);
    padding: 12px 16px;
    font-size: 0.8rem;
    min-width: 260px;
    display: flex; align-items: center; gap: 10px;
    box-shadow: 0 8px 24px color-mix(in srgb, var(--color-bg) 40%, transparent);
    animation: slideRight 0.25s ease;
  }

  .toast.success { border-color: rgba(0,230,118,0.3); }
  .toast.error   { border-color: rgba(255,82,82,0.3); }
  .toast.info    { border-color: rgba(0,212,255,0.3); }

  /* ── LOADER ── */
  .spinner {
    width: 16px; height: 16px;
    border: 2px solid var(--border2);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
    flex-shrink: 0;
  }

  .spinner-lg { width: 32px; height: 32px; border-width: 3px; }

  .loading-center {
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 4rem; gap: 1rem; color: var(--muted); font-size: 0.8rem;
  }

  /* ── ALERT ── */
  .alert {
    padding: 12px 16px; border-radius: var(--radius);
    font-size: 0.8rem; margin-bottom: 1rem;
    display: flex; align-items: flex-start; gap: 10px;
  }

  .alert-error { background: rgba(255,82,82,0.1); border: 1px solid rgba(255,82,82,0.3); color: #ff8a8a; }
  .alert-info  { background: rgba(0,212,255,0.08); border: 1px solid rgba(0,212,255,0.2); color: var(--accent); }

  /* ── ANIMATIONS ── */
  @keyframes fadeIn  { from { opacity: 0 } to { opacity: 1 } }
  @keyframes slideUp { from { opacity: 0; transform: translateY(16px) } to { opacity: 1; transform: none } }
  @keyframes slideRight { from { opacity: 0; transform: translateX(16px) } to { opacity: 1; transform: none } }
  @keyframes spin    { to { transform: rotate(360deg) } }
  @keyframes pulse   { 0%, 100% { opacity: 1 } 50% { opacity: 0.5 } }
  @keyframes blink   { 0%, 100% { opacity: 1 } 50% { opacity: 0 } }

  /* ── MISC ── */
  .divider { height: 1px; background: var(--border); margin: 1.5rem 0; }
  .text-muted { color: var(--muted); }
  .text-accent { color: var(--accent); }
  .text-sm { font-size: 0.78rem; }
  .cursor { display: inline-block; animation: blink 1s infinite; }

  .row-actions { display: flex; gap: 6px; align-items: center; justify-content: flex-end; }

  /* ── ONBOARDING STEPS ── */
  .steps { display: flex; gap: 0; margin-bottom: 2rem; }
  .step-item {
    flex: 1; text-align: center;
    padding: 0.75rem; font-size: 0.72rem;
    border-bottom: 2px solid var(--border);
    color: var(--muted);
    transition: all 0.2s;
  }
  .step-item.active { color: var(--accent); border-bottom-color: var(--accent); }
  .step-item.done   { color: var(--green);  border-bottom-color: var(--green); }
  .step-num {
    display: block; font-family: var(--font-head);
    font-size: 1rem; font-weight: 700; margin-bottom: 2px;
  }
`;

// ─── API CLIENT ──────────────────────────────────────────────────────────────
const api = {
  async call(method, path, body, token) {
    const headers = { "Content-Type": "application/json" };
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ message: res.statusText }));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  getDevToken: (email) =>
    api.call("POST", `/api/v1/auth/dev-token?email=${encodeURIComponent(email)}`),
  getOrg:        (token) => api.call("GET",  "/api/v1/org",              null, token),
  updateOrgName: (name, token) => api.call("PUT", `/api/v1/org/name?name=${encodeURIComponent(name)}`, null, token),
  getTargets:    (token) => api.call("GET",  "/api/v1/targets?size=100", null, token),
  createTarget:  (data, token) => api.call("POST", "/api/v1/targets",    data, token),
  updateTarget:  (id, data, token) => api.call("PUT", `/api/v1/targets/${id}`, data, token),
  deleteTarget:  (id, token) => api.call("DELETE", `/api/v1/targets/${id}`, null, token),
  scanTarget:    (id, token) => api.call("POST", `/api/v1/targets/${id}/scan`, null, token),
  getDashboard:  (token) => api.call("GET",  "/api/v1/dashboard",        null, token),
  getCerts:      (token) => api.call("GET",  "/api/v1/certificates?size=100", null, token),
  getExpiring:   (days, token) => api.call("GET", `/api/v1/certificates/expiring?days=${days}`, null, token),
  // Agent endpoints
  listAgents:    (token) => api.call("GET",  "/api/v1/agent/list",                    null,   token),
  genAgentToken: (name, token) => api.call("POST", `/api/v1/agent/tokens?agentName=${encodeURIComponent(name)}`, null, token),
  revokeAgent:   (id, token) => api.call("POST", `/api/v1/agent/${id}/revoke`,        null,   token),
  queueScan:     (targetId, token) => api.call("POST", `/api/v1/targets/${targetId}/scan`, null, token),
  // Location endpoints
  listLocations:   (token) => api.call("GET",  "/api/v1/locations",        null, token),
  createLocation:  (data, token) => api.call("POST", "/api/v1/locations",  data, token),
  updateLocation:  (id, data, token) => api.call("PUT", `/api/v1/locations/${id}`, data, token),
  deleteLocation:  (id, token) => api.call("DELETE", `/api/v1/locations/${id}`, null, token),
};

// ─── HELPERS ─────────────────────────────────────────────────────────────────
const statusColor = (s) => ({ VALID: "green", EXPIRING: "yellow", EXPIRED: "red", UNREACHABLE: "orange" }[s] || "unknown");
const hostTypeColor = (t) => t?.toLowerCase() || "unknown";
const fmtDate = (iso) => iso ? new Date(iso).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }) : "—";
const daysColor = (d) => d < 0 ? "var(--red)" : d <= 7 ? "var(--red)" : d <= 30 ? "var(--yellow)" : "var(--green)";
const daysWidth = (d) => `${Math.min(100, Math.max(0, (d / 365) * 100))}%`;

// ─── TOAST SYSTEM ────────────────────────────────────────────────────────────
let toastId = 0;
function useToasts() {
  const [toasts, setToasts] = useState([]);
  const add = useCallback((msg, type = "info") => {
    const id = ++toastId;
    setToasts((t) => [...t, { id, msg, type }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3500);
  }, []);
  return { toasts, add };
}

// ─── COMPONENTS ──────────────────────────────────────────────────────────────

function Toast({ toasts }) {
  const icons = { success: "✓", error: "✕", info: "ℹ" };
  return (
    <div className="toast-wrap" role="status" aria-live="polite" aria-atomic="false">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.type}`}>
          <span aria-hidden="true" style={{ color: t.type === "success" ? "var(--green)" : t.type === "error" ? "var(--red)" : "var(--accent)" }}>
            {icons[t.type]}
          </span>
          {t.msg}
        </div>
      ))}
    </div>
  );
}

function Spinner({ lg }) {
  return <div className={`spinner${lg ? " spinner-lg" : ""}`} role="status" aria-label="Loading" />;
}

function Badge({ type, children }) {
  return <span className={`badge badge-${type}`}>{children}</span>;
}

function DaysBar({ days }) {
  if (days === undefined || days === null) return <span className="text-muted">—</span>;
  return (
    <div className="days-bar">
      <span style={{ color: daysColor(days), fontSize: "0.8rem", fontWeight: 600, minWidth: 36 }}>
        {days < 0 ? "Expired" : `${days}d`}
      </span>
      <div className="days-track" style={{ width: 60 }}>
        <div className="days-fill" style={{ width: daysWidth(days), background: daysColor(days) }} />
      </div>
    </div>
  );
}

// ─── LAUNCH SCREEN ───────────────────────────────────────────────────────────
function LaunchScreen({ onToken }) {
  const [email, setEmail]     = useState("admin@certguard.local");
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState("");
  const [typed, setTyped]     = useState("");
  const tagline = "TLS certificate monitoring for teams.";

  // Typewriter effect
  useEffect(() => {
    let i = 0;
    const t = setInterval(() => {
      setTyped(tagline.slice(0, ++i));
      if (i >= tagline.length) clearInterval(t);
    }, 35);
    return () => clearInterval(t);
  }, []);

  const handleDevLogin = async () => {
    setError(""); setLoading(true);
    try {
      const data = await api.getDevToken(email);
      if (data?.token) onToken(data.token, data.orgId, data.email);
      else setError("No token in response");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon">🔐</div>
        <div className="logo-text">CertGuard</div>
      </div>

      <div style={{ textAlign: "center", marginBottom: "2.5rem" }}>
        <p style={{ color: "var(--muted)", fontSize: "0.9rem", fontFamily: "var(--font-mono)", minHeight: "1.4em" }}>
          {typed}<span className="cursor">|</span>
        </p>
      </div>

      <div className="launch-card">
        <div className="launch-title">Welcome back</div>
        <p className="launch-sub">Sign in to access your certificate dashboard.</p>

        {DEV_MODE ? (
          <>
            <div className="dev-badge">⚡ DEV MODE — Google OAuth bypassed</div>
            {error && <div className="alert alert-error">⚠ {error}</div>}
            <div className="field">
              <label htmlFor="dev-email">Dev Login Email</label>
              <input
                id="dev-email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleDevLogin()}
                placeholder="your@email.com"
              />
            </div>
            <button className="btn btn-primary" onClick={handleDevLogin} disabled={loading}>
              {loading ? <><Spinner /> Authenticating...</> : "→ Get Dev Token"}
            </button>
            <div className="divider" />
            <p className="text-muted text-sm" style={{ textAlign: "center" }}>
              Set <code style={{ color: "var(--accent)" }}>DEV_MODE = false</code> to enable Google OAuth
            </p>
          </>
        ) : (
          <>
            {error && <div className="alert alert-error">⚠ {error}</div>}
            <button className="btn btn-primary" onClick={() => window.location.href = `${API_BASE}/oauth2/authorization/google`}>
              <span>🔑</span> Continue with Google
            </button>
          </>
        )}
      </div>

      <p className="text-muted text-sm" style={{ marginTop: "2rem" }}>
        API — <span className="text-accent">{window.location.origin}</span>
      </p>
    </div>
  );
}

// ─── ORG SETUP ───────────────────────────────────────────────────────────────
function OrgSetup({ token, onDone, toast }) {
  const [name, setName]       = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState("");

  const handleSave = async () => {
    if (!name.trim()) { setError("Organization name is required"); return; }
    setError(""); setLoading(true);
    try {
      await api.updateOrgName(name.trim(), token);
      toast("Organization created!", "success");
      onDone();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon">🏢</div>
        <div className="logo-text">CertGuard</div>
      </div>

      <div className="launch-card">
        <div className="steps">
          <div className="step-item done"><span className="step-num">✓</span>Signed In</div>
          <div className="step-item active"><span className="step-num">2</span>Your Org</div>
          <div className="step-item"><span className="step-num">3</span>Add Targets</div>
        </div>

        <div className="launch-title">Name your organization</div>
        <p className="launch-sub">This will appear on your dashboard and in reports. You can change it later.</p>

        {error && <div className="alert alert-error">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="org-name">Organization Name</label>
          <input
            id="org-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSave()}
            placeholder="e.g. Acme Corporation"
            autoFocus
          />
        </div>

        <button className="btn btn-primary" onClick={handleSave} disabled={loading || !name.trim()}>
          {loading ? <><Spinner /> Saving...</> : "→ Continue"}
        </button>
      </div>
    </div>
  );
}

// ─── EDIT TARGET MODAL ───────────────────────────────────────────────────────
function EditTargetModal({ token, target, onClose, onSaved, toast }) {
  const [host, setHost]           = useState(target.host);
  const [port, setPort]           = useState(String(target.port));
  const [desc, setDesc]           = useState(target.description || "");
  const [isPrivate, setIsPrivate] = useState(!!target.isPrivate);
  const [enabled, setEnabled]     = useState(target.enabled !== false);
  const [agentId, setAgentId]     = useState(target.agentId || "");
  const [agents, setAgents]       = useState([]);
  const [loading, setLoading]     = useState(false);
  const [error, setError]         = useState("");

  useEffect(() => {
    api.listAgents(token).then(setAgents).catch(() => {});
  }, [token]);

  const handleSave = async () => {
    if (!host.trim()) { setError("Host is required"); return; }
    if (isPrivate && !agentId) { setError("Select an agent for private targets"); return; }
    setError(""); setLoading(true);
    try {
      const updated = await api.updateTarget(target.id, {
        host: host.trim().toLowerCase(),
        port: parseInt(port) || 443,
        isPrivate,
        enabled,
        description: desc.trim() || null,
        agentId: agentId || null,
      }, token);
      toast(`Target updated: ${updated.host}:${updated.port}`, "success");
      onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="edit-modal-title">
        <div className="modal-title" id="edit-modal-title">Edit Target</div>
        <p className="modal-sub">Update the monitored endpoint details.</p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="edit-host">Host *</label>
          <input id="edit-host" value={host} onChange={(e) => setHost(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSave()}
            placeholder="google.com or 192.168.1.10" autoFocus />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="edit-port">Port</label>
            <input id="edit-port" value={port} onChange={(e) => setPort(e.target.value)}
              type="number" min="1" max="65535" placeholder="443" />
          </div>
          <div className="field">
            <label htmlFor="edit-visibility">Visibility</label>
            <select id="edit-visibility" value={isPrivate ? "private" : "public"}
              onChange={(e) => setIsPrivate(e.target.value === "private")}>
              <option value="public">Public</option>
              <option value="private">Private (agent)</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="edit-monitoring">Monitoring</label>
            <select id="edit-monitoring" value={enabled ? "on" : "off"}
              onChange={(e) => setEnabled(e.target.value === "on")}>
              <option value="on">Enabled</option>
              <option value="off">Disabled</option>
            </select>
          </div>
        </div>

        {isPrivate && (
          <div className="field">
            <label htmlFor="edit-agent">Assign Agent *</label>
            <select id="edit-agent" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">— Select agent —</option>
              {agents.filter(a => a.status === "ACTIVE").map(a => (
                <option key={a.id} value={a.id}>{a.name} ({a.currentTargetCount}/{a.maxTargets} targets)</option>
              ))}
            </select>
          </div>
        )}

        <div className="field">
          <label htmlFor="edit-desc">Description (optional)</label>
          <input id="edit-desc" value={desc} onChange={(e) => setDesc(e.target.value)}
            placeholder="e.g. Production API Gateway" />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={loading || !host.trim()}>
            {loading ? <><Spinner /> Saving...</> : "Save Changes"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── ADD TARGET MODAL ─────────────────────────────────────────────────────────
function AddTargetModal({ token, onClose, onAdded, toast }) {
  const [host, setHost]           = useState("");
  const [port, setPort]           = useState("443");
  const [desc, setDesc]           = useState("");
  const [isPrivate, setIsPrivate] = useState(false);
  const [agentId, setAgentId]     = useState("");
  const [agents, setAgents]       = useState([]);
  const [loading, setLoading]     = useState(false);
  const [error, setError]         = useState("");

  useEffect(() => {
    api.listAgents(token).then(setAgents).catch(() => {});
  }, [token]);

  const handleAdd = async () => {
    if (!host.trim()) { setError("Host is required"); return; }
    if (isPrivate && !agentId) { setError("Select an agent for private targets"); return; }
    setError(""); setLoading(true);
    try {
      const target = await api.createTarget({
        host: host.trim().toLowerCase(),
        port: parseInt(port) || 443,
        isPrivate,
        agentId: isPrivate ? agentId : undefined,
        description: desc.trim() || undefined,
      }, token);
      toast(`Target added: ${target.host}:${target.port}`, "success");
      onAdded(target);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="add-modal-title">
        <div className="modal-title" id="add-modal-title">Add Target</div>
        <p className="modal-sub">
          Enter a domain, IP address, or hostname to monitor.<br />
          Private IPs (192.168.x.x) are auto-flagged as private.
        </p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="add-host">Host *</label>
          <input id="add-host" value={host} onChange={(e) => setHost(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
            placeholder="google.com or 192.168.1.10 or my-server" autoFocus />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="add-port">Port</label>
            <input id="add-port" value={port} onChange={(e) => setPort(e.target.value)}
              type="number" min="1" max="65535" placeholder="443" />
          </div>
          <div className="field">
            <label htmlFor="add-visibility">Visibility</label>
            <select id="add-visibility" value={isPrivate ? "private" : "public"}
              onChange={(e) => setIsPrivate(e.target.value === "private")}>
              <option value="public">Public</option>
              <option value="private">Private (agent)</option>
            </select>
          </div>
        </div>

        {isPrivate && (
          <div className="field">
            <label htmlFor="add-agent">Assign Agent *</label>
            <select id="add-agent" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">— Select agent —</option>
              {agents.filter(a => a.status === "ACTIVE").map(a => (
                <option key={a.id} value={a.id}>{a.name} ({a.currentTargetCount}/{a.maxTargets} targets)</option>
              ))}
            </select>
            {agents.filter(a => a.status === "ACTIVE").length === 0 && (
              <div style={{ fontSize: "0.72rem", color: "var(--orange)", marginTop: 4 }}>
                ⚠ No active agents. Deploy an agent first (Agents page).
              </div>
            )}
          </div>
        )}

        <div className="field">
          <label htmlFor="add-desc">Description (optional)</label>
          <input id="add-desc" value={desc} onChange={(e) => setDesc(e.target.value)}
            placeholder="e.g. Production API Gateway" />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleAdd} disabled={loading || !host.trim()}>
            {loading ? <><Spinner /> Adding...</> : "Add Target"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── FIRST TARGET ONBOARDING ─────────────────────────────────────────────────
function FirstTarget({ token, onDone, toast }) {
  const [host, setHost]           = useState("");
  const [port, setPort]           = useState("443");
  const [desc, setDesc]           = useState("");
  const [loading, setLoading]     = useState(false);
  const [scanning, setScanning]   = useState(false);
  const [error, setError]         = useState("");
  const [addedTarget, setAddedTarget] = useState(null);

  const handleAdd = async () => {
    if (!host.trim()) { setError("Host is required"); return; }
    setError(""); setLoading(true);
    try {
      const target = await api.createTarget({
        host: host.trim().toLowerCase(),
        port: parseInt(port) || 443,
        description: desc.trim() || undefined,
      }, token);
      setAddedTarget(target);
      toast(`Target added: ${target.host}`, "success");

      // Auto-trigger scan if public
      if (!target.isPrivate) {
        setScanning(true);
        try {
          await api.scanTarget(target.id, token);
          toast("Certificate scan triggered!", "info");
        } catch (e) {
          toast("Scan failed — " + e.message, "error");
        } finally {
          setScanning(false);
        }
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  if (addedTarget) {
    return (
      <div className="launch">
        <div className="launch-logo">
          <div className="logo-icon">🎯</div>
          <div className="logo-text">CertGuard</div>
        </div>
        <div className="launch-card">
          <div className="steps">
            <div className="step-item done"><span className="step-num">✓</span>Signed In</div>
            <div className="step-item done"><span className="step-num">✓</span>Your Org</div>
            <div className="step-item active"><span className="step-num">3</span>Add Targets</div>
          </div>
          <div className="launch-title">Target added! ✓</div>
          <div className="cert-detail" style={{ marginBottom: "1.5rem" }}>
            <div className="cert-row"><span className="key">Host</span><span className="val">{addedTarget.host}</span></div>
            <div className="cert-row"><span className="key">Port</span><span className="val">{addedTarget.port}</span></div>
            <div className="cert-row"><span className="key">Type</span><span className="val">{addedTarget.hostType}</span></div>
            <div className="cert-row">
              <span className="key">Visibility</span>
              <span className="val">{addedTarget.isPrivate ? "🔒 Private" : "🌐 Public"}</span>
            </div>
          </div>
          {scanning && (
            <div className="alert alert-info"><Spinner /> Scanning certificate...</div>
          )}
          <button className="btn btn-primary" onClick={onDone} disabled={scanning}>
            {scanning ? "Scanning..." : "→ Go to Dashboard"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="launch">
      <div className="launch-logo">
        <div className="logo-icon">🎯</div>
        <div className="logo-text">CertGuard</div>
      </div>
      <div className="launch-card">
        <div className="steps">
          <div className="step-item done"><span className="step-num">✓</span>Signed In</div>
          <div className="step-item done"><span className="step-num">✓</span>Your Org</div>
          <div className="step-item active"><span className="step-num">3</span>Add Targets</div>
        </div>

        <div className="launch-title">Add your first target</div>
        <p className="launch-sub">
          Enter a domain, IP or hostname to monitor. We'll scan the certificate immediately for public targets.
        </p>

        {error && <div className="alert alert-error">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="first-host">Host *</label>
          <input id="first-host" value={host} onChange={(e) => setHost(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
            placeholder="google.com or 1.1.1.1 or my-server" autoFocus />
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="first-port">Port</label>
            <input id="first-port" value={port} onChange={(e) => setPort(e.target.value)}
              type="number" min="1" max="65535" />
          </div>
          <div className="field">
            <label htmlFor="first-desc">Description</label>
            <input id="first-desc" value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="optional" />
          </div>
        </div>

        <button className="btn btn-primary" onClick={handleAdd} disabled={loading || !host.trim()}>
          {loading ? <><Spinner /> Adding...</> : "→ Add & Scan"}
        </button>

        <div style={{ textAlign: "center", marginTop: "1rem" }}>
          <button className="btn btn-ghost" onClick={onDone}>Skip for now →</button>
        </div>
      </div>
    </div>
  );
}

// ─── DASHBOARD ───────────────────────────────────────────────────────────────
function Dashboard({ token, org, toast }) {
  const [dash, setDash]           = useState(null);
  const [targets, setTargets]     = useState([]);
  const [loading, setLoading]     = useState(true);
  const [scanning, setScanning]   = useState({});
  const [showAdd, setShowAdd]     = useState(false);
  const [deleteId, setDeleteId]   = useState(null);
  const [editTarget, setEditTarget] = useState(null);
  const [view, setView]           = useState("dashboard");
  const [certs, setCerts]         = useState([]);
  const [certsLoading, setCertsLoading] = useState(false);
  const [agents, setAgents]       = useState([]);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [locations, setLocations]         = useState([]);
  const [locationsLoading, setLocationsLoading] = useState(false);
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
  }, [token]);

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
  }, [token]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { if (view === "certs")     loadCerts();     }, [view, loadCerts]);
  useEffect(() => { if (view === "agents")   loadAgents();    }, [view]);
  useEffect(() => { if (view === "locations") loadLocations(); }, [view]);

  const loadAgents = useCallback(async () => {
    setAgentsLoading(true);
    try { setAgents(await api.listAgents(token)); }
    catch (e) { toast("Failed to load agents: " + e.message, "error"); }
    finally { setAgentsLoading(false); }
  }, [token]);

  const loadLocations = useCallback(async () => {
    setLocationsLoading(true);
    try { setLocations(await api.listLocations(token)); }
    catch (e) { toast("Failed to load locations: " + e.message, "error"); }
    finally { setLocationsLoading(false); }
  }, [token]);

  const triggerScan = async (target) => {
    if (target.isPrivate) { toast("Private targets are scanned by the on-premise agent", "info"); return; }
    setScanning((s) => ({ ...s, [target.id]: true }));
    try {
      await api.scanTarget(target.id, token);
      toast(`Scan triggered for ${target.host}`, "info");
      setTimeout(() => { load(); setScanning((s) => ({ ...s, [target.id]: false })); }, 8000);
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

  if (loading) {
    return (
      <div className="app">
        <Sidebar view={view} onView={setView} org={org} theme={theme} onTheme={setTheme} />
        <div className="main"><div className="loading-center"><Spinner lg /><span>Loading dashboard...</span></div></div>
      </div>
    );
  }

  return (
    <div className="app">
      <Sidebar view={view} onView={setView} org={org} theme={theme} onTheme={setTheme} />
      <div className="main">
        {view === "dashboard" && (
          <DashboardView dash={dash} targets={targets} onScan={triggerScan}
            scanning={scanning} onAddTarget={() => setShowAdd(true)} />
        )}
        {view === "targets" && (
          <TargetsView targets={targets} onScan={triggerScan} scanning={scanning}
            onAdd={() => setShowAdd(true)} onDelete={setDeleteId}
            onEdit={setEditTarget} onRefresh={load} />
        )}
        {view === "certs" && (
          <CertsView certs={certs} loading={certsLoading} onRefresh={loadCerts} />
        )}
        {view === "agents" && (
          <AgentsView agents={agents} loading={agentsLoading} token={token}
            onRefresh={loadAgents} toast={toast} />
        )}
        {view === "locations" && (
          <LocationsView locations={locations} loading={locationsLoading} token={token}
            onRefresh={loadLocations} toast={toast} />
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

function Sidebar({ view, onView, org, theme = "dark", onTheme }) {
  const { mode, toggle } = useTheme();
  const themeVars = SIDEBAR_THEMES[theme]?.vars || SIDEBAR_THEMES.dark.vars;
  return (
    <nav className="sidebar" style={themeVars} aria-label="Main navigation">
      <div className="sidebar-logo">
        <div className="logo-icon" aria-hidden="true">🔐</div>
        <div className="logo-text">CertGuard</div>
      </div>
      <div className="nav-section" aria-hidden="true">Navigation</div>
      {[
        { id: "dashboard", icon: "◈", label: "Dashboard"    },
        { id: "targets",   icon: "⊕", label: "Targets"      },
        { id: "certs",     icon: "⊞", label: "Certificates" },
        { id: "agents",    icon: "⬡", label: "Agents"       },
        { id: "locations", icon: "⊙", label: "Locations"    },
      ].map((item) => (
        <div
          key={item.id}
          className={`nav-item ${view === item.id ? "active" : ""}`}
          role="button"
          tabIndex={0}
          aria-current={view === item.id ? "page" : undefined}
          onClick={() => onView(item.id)}
          onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onView(item.id)}
        >
          <span aria-hidden="true">{item.icon}</span>{item.label}
        </div>
      ))}
      <div className="sidebar-footer">
        <div className="org-tag"><span aria-hidden="true">🏢</span> <span>{org?.name || "My Org"}</span></div>
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
      </div>
    </nav>
  );
}

function DashboardView({ dash, targets, onScan, scanning, onAddTarget }) {
  const stats = dash ? [
    { label: "Total Targets",  value: dash.totalTargets, cls: "total"      },
    { label: "Valid",          value: dash.valid,        cls: "valid"      },
    { label: "Expiring Soon",  value: dash.expiring,     cls: "expiring"   },
    { label: "Expired",        value: dash.expired,      cls: "expired"    },
    { label: "Unreachable",    value: dash.unreachable,  cls: "unreachable"},
  ] : [];

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Dashboard</div>
          <div className="page-sub">Certificate monitoring overview</div>
        </div>
        <button className="btn btn-primary btn-sm" onClick={onAddTarget}>+ Add Target</button>
      </div>
      <div className="page-content">
        <div className="stats-grid">
          {stats.map((s) => (
            <div key={s.label} className={`stat-card ${s.cls}`}>
              <div className="stat-label">{s.label}</div>
              <div className="stat-value">{s.value ?? "—"}</div>
            </div>
          ))}
        </div>

        <div className="section-header">
          <div className="section-title">Recent Targets</div>
          <span className="text-muted text-sm">{targets.length} total</span>
        </div>

        {targets.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">🎯</div>
            <div className="empty-title">No targets yet</div>
            <p className="empty-sub">Add a domain or IP address to start monitoring certificates.</p>
            <button className="btn btn-primary btn-sm" onClick={onAddTarget} style={{ margin: "0 auto" }}>
              + Add First Target
            </button>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Host</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Expires</th>
                  <th>Days Left</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {targets.slice(0, 10).map((t) => {
                  const cert = t.latestCertificate;
                  return (
                    <tr key={t.id}>
                      <td>
                        <div className="host-cell">{t.host}</div>
                        <div className="mono">:{t.port} {t.isPrivate ? "🔒" : "🌐"}</div>
                      </td>
                      <td><Badge type={hostTypeColor(t.hostType)}>{t.hostType || "—"}</Badge></td>
                      <td>
                        {cert
                          ? <Badge type={statusColor(cert.status)}>{cert.status}</Badge>
                          : <Badge type="unknown">No scan</Badge>}
                      </td>
                      <td className="mono">{cert ? fmtDate(cert.expiryDate) : "—"}</td>
                      <td><DaysBar days={cert?.daysRemaining} /></td>
                      <td>
                        <button className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                          onClick={() => onScan(t)} disabled={scanning[t.id] || t.isPrivate}>
                          {scanning[t.id] ? <><Spinner /> Scanning</> : "⟳ Scan"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}

function TargetsView({ targets, onScan, scanning, onAdd, onDelete, onEdit, onRefresh }) {
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Targets</div>
          <div className="page-sub">Manage your monitored endpoints</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          <button className="btn btn-primary btn-sm" onClick={onAdd}>+ Add Target</button>
        </div>
      </div>
      <div className="page-content">
        {targets.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">🎯</div>
            <div className="empty-title">No targets</div>
            <p className="empty-sub">Add your first endpoint to start monitoring.</p>
            <button className="btn btn-primary btn-sm" onClick={onAdd} style={{ margin: "0 auto" }}>+ Add Target</button>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Host</th>
                  <th>Port</th>
                  <th>Type</th>
                  <th>Visibility</th>
                  <th>Cert Status</th>
                  <th>Expires</th>
                  <th>Last Scan</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {targets.map((t) => {
                  const cert = t.latestCertificate;
                  return (
                    <tr key={t.id}>
                      <td>
                        <div className="host-cell">{t.host}</div>
                        {t.description && <div className="mono">{t.description}</div>}
                      </td>
                      <td className="mono">{t.port}</td>
                      <td><Badge type={hostTypeColor(t.hostType)}>{t.hostType || "—"}</Badge></td>
                      <td>
                        <Badge type={t.isPrivate ? "private" : "public"}>
                          {t.isPrivate ? "🔒 Private" : "🌐 Public"}
                        </Badge>
                        {t.enabled === false && (
                          <Badge type="unknown" style={{ marginLeft: 4 }}>Disabled</Badge>
                        )}
                        {t.isPrivate && t.agentName && (
                          <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)", marginTop: 2 }}>
                            {t.agentName}
                          </div>
                        )}
                        {t.isPrivate && !t.agentName && (
                          <div className="mono" style={{ fontSize: "0.7rem", color: "var(--red)", marginTop: 2 }}>
                            No agent
                          </div>
                        )}
                      </td>
                      <td>
                        {cert
                          ? <Badge type={statusColor(cert.status)}>{cert.status}</Badge>
                          : <Badge type="unknown">No scan</Badge>}
                      </td>
                      <td className="mono">{cert ? fmtDate(cert.expiryDate) : "—"}</td>
                      <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
                        {t.lastScannedAt ? fmtDate(t.lastScannedAt) : "Never"}
                      </td>
                      <td>
                        <div className="row-actions">
                          <button className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                            onClick={() => onScan(t)} disabled={scanning[t.id] || t.isPrivate}
                            title={t.isPrivate ? "Private targets use the on-prem agent" : "Trigger scan"}>
                            {scanning[t.id] ? <Spinner /> : "⟳"}
                          </button>
                          <button className="scan-btn" style={{ color: "var(--muted)", borderColor: "rgba(90,96,112,0.3)" }}
                            onClick={() => onEdit(t)} title="Edit target">✎</button>
                          <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                            onClick={() => onDelete(t.id)} title="Delete target">✕</button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}

function CertsView({ certs, loading, onRefresh }) {
  const [filter, setFilter] = useState("ALL");
  const filtered = filter === "ALL" ? certs : certs.filter((c) => c.status === filter);

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Certificates</div>
          <div className="page-sub">Full certificate inventory</div>
        </div>
        <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
      </div>
      <div className="page-content">
        <div style={{ display: "flex", gap: "0.5rem", marginBottom: "1.5rem", flexWrap: "wrap" }}>
          {["ALL", "VALID", "EXPIRING", "EXPIRED", "UNREACHABLE"].map((s) => (
            <button key={s}
              className={`btn btn-sm ${filter === s ? "btn-primary" : "btn-secondary"}`}
              style={{ width: "auto" }}
              onClick={() => setFilter(s)}>
              {s}
            </button>
          ))}
        </div>

        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading certificates...</span></div>
        ) : filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">📜</div>
            <div className="empty-title">No certificates found</div>
            <p className="empty-sub">Trigger a scan on a target to discover certificates.</p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Common Name</th>
                  <th>Issuer</th>
                  <th>Status</th>
                  <th>Not Before</th>
                  <th>Expires</th>
                  <th>Days Left</th>
                  <th>Scanned</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((c) => (
                  <tr key={c.id}>
                    <td className="host-cell">{c.commonName}</td>
                    <td className="mono" style={{ maxWidth: 180, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {c.issuer?.split(",")[0]?.replace("CN=", "") || c.issuer}
                    </td>
                    <td><Badge type={statusColor(c.status)}>{c.status}</Badge></td>
                    <td className="mono">{fmtDate(c.notBefore)}</td>
                    <td className="mono">{fmtDate(c.expiryDate)}</td>
                    <td><DaysBar days={c.daysRemaining} /></td>
                    <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>
                      {fmtDate(c.scannedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}

// ─── AGENTS VIEW ─────────────────────────────────────────────
function AgentsView({ agents, loading, token, onRefresh, toast }) {
  const [showGenToken, setShowGenToken] = useState(false);
  const [agentName, setAgentName]       = useState("");
  const [genLoading, setGenLoading]     = useState(false);
  const [generatedToken, setGeneratedToken] = useState(null);

  const statusColor = (s) => ({
    ACTIVE: "valid", PENDING: "unknown", REVOKED: "expired", EXPIRED: "expiring"
  }[s] || "unknown");

  const fmtRelative = (iso) => {
    if (!iso) return "Never";
    const diff = Date.now() - new Date(iso).getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return "Just now";
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return `${hrs}h ago`;
    return `${Math.floor(hrs / 24)}d ago`;
  };

  const handleGenToken = async () => {
    if (!agentName.trim()) return;
    setGenLoading(true);
    try {
      const resp = await api.genAgentToken(agentName.trim(), token);
      setGeneratedToken(resp);
      setAgentName("");
      toast("Registration token generated — copy it now!", "success");
    } catch (e) {
      toast("Failed to generate token: " + e.message, "error");
    } finally {
      setGenLoading(false);
    }
  };

  const handleRevoke = async (id) => {
    try {
      await api.revokeAgent(id, token);
      toast("Agent revoked", "success");
      onRefresh();
    } catch (e) {
      toast("Revoke failed: " + e.message, "error");
    }
  };

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Agents</div>
          <div className="page-sub">On-premise agents for private network scanning</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          <button className="btn btn-primary btn-sm" onClick={() => setShowGenToken(true)}>+ Add Agent</button>
        </div>
      </div>
      <div className="page-content">

        {/* Architecture info card */}
        <div className="alert alert-info" style={{ marginBottom: "1.5rem" }}>
          <span>ℹ</span>
          <div>
            Agents run inside your private network and scan hosts that are not reachable from the internet.
            Communication uses TLS 1.3 + AES-256-GCM with HMAC-SHA256 payload signing.
          </div>
        </div>

        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading agents...</span></div>
        ) : agents.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">⬡</div>
            <div className="empty-title">No agents registered</div>
            <p className="empty-sub">
              Deploy an agent on your private network to start scanning internal hosts.
              <br />Generate a registration token, then run the agent JAR with the token in its config.
            </p>
            <button className="btn btn-primary btn-sm" onClick={() => setShowGenToken(true)}
              style={{ margin: "0 auto" }}>+ Add First Agent</button>
          </div>
        ) : (
          <div className="table-wrap" style={{ marginBottom: "2rem" }}>
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Status</th>
                  <th>Targets</th>
                  <th>Allowed CIDRs</th>
                  <th>Max Targets</th>
                  <th>Last Seen</th>
                  <th>Registered</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {agents.map((a) => (
                  <tr key={a.id}>
                    <td className="host-cell">{a.name}</td>
                    <td><Badge type={statusColor(a.status)}>{a.status}</Badge></td>
                    <td className="mono">{a.currentTargetCount} / {a.maxTargets}</td>
                    <td>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                        {(a.allowedCidrs || []).map((c) => (
                          <span key={c} className="badge badge-domain" style={{ fontSize: "0.68rem" }}>{c}</span>
                        ))}
                      </div>
                    </td>
                    <td className="mono">{a.maxTargets}</td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtRelative(a.lastSeenAt)}
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtDate(a.registeredAt)}
                    </td>
                    <td>
                      {a.status === "ACTIVE" && (
                        <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                          onClick={() => handleRevoke(a.id)}>Revoke</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Deploy instructions */}
        <div className="section-header"><div className="section-title">Deploy Instructions</div></div>
        <div className="cert-detail">
          <div style={{ padding: "0.5rem 0" }}>
            <div style={{ color: "var(--muted)", fontSize: "0.72rem", marginBottom: "0.5rem", letterSpacing: "0.06em" }}>
              STEP 1 — GENERATE TOKEN IN UI, THEN CONFIGURE AGENT
            </div>
            <pre style={{ fontSize: "0.75rem", color: "var(--accent)", overflowX: "auto" }}>{
`# application.properties on your private host
certguard.server.url=https://YOUR_SERVER_IP:58244
certguard.registration.token=CGR-XXXX-...  # from UI
certguard.registration.org-id=YOUR_ORG_ID
certguard.agent.name=office-agent
certguard.agent.allowed-cidrs=192.168.1.0/24,10.0.0.0/8
certguard.agent.max-targets=50`
            }</pre>
          </div>
          <div style={{ padding: "0.75rem 0 0.5rem", borderTop: "1px solid var(--border)" }}>
            <div style={{ color: "var(--muted)", fontSize: "0.72rem", marginBottom: "0.5rem", letterSpacing: "0.06em" }}>
              STEP 2 — RUN THE AGENT (JAR OR DOCKER)
            </div>
            <pre style={{ fontSize: "0.75rem", color: "var(--accent)", overflowX: "auto" }}>{
`# Option A — JAR
java -jar certguard-agent.jar

# Option B — Docker
docker run -v ./config:/opt/certguard-agent/config \\
  certguard/agent:latest`
            }</pre>
          </div>
        </div>
      </div>

      {/* Generate token modal */}
      {showGenToken && (
        <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && setShowGenToken(false)}>
          <div className="modal" role="dialog" aria-modal="true" aria-label="Add Agent">
            {generatedToken ? (
              <>
                <div className="modal-title">Token Generated ✓</div>
                <p className="modal-sub">
                  Copy this token immediately — it will not be shown again.
                  It expires in 1 hour and can only be used once.
                </p>
                <div className="alert alert-info" style={{ marginBottom: "1rem" }}>
                  <span>🔑</span>
                  <span>Agent: <strong>{generatedToken.agentName}</strong></span>
                </div>
                <div style={{
                  background: "var(--surface2)", border: "1px solid var(--border2)",
                  borderRadius: "var(--radius)", padding: "12px 16px",
                  fontFamily: "var(--font-mono)", fontSize: "0.78rem",
                  color: "var(--green)", wordBreak: "break-all", marginBottom: "1rem"
                }}>
                  {generatedToken.token}
                </div>
                <div style={{ fontSize: "0.72rem", color: "var(--muted)", marginBottom: "1.5rem" }}>
                  Expires: {fmtDate(generatedToken.expiresAt)}
                </div>
                <button className="btn btn-primary" onClick={() => {
                  navigator.clipboard?.writeText(generatedToken.token);
                  toast("Token copied!", "success");
                }}>Copy Token</button>
                <button className="btn btn-secondary" style={{ marginTop: "0.5rem" }}
                  onClick={() => { setGeneratedToken(null); setShowGenToken(false); onRefresh(); }}>
                  Done
                </button>
              </>
            ) : (
              <>
                <div className="modal-title">Add Agent</div>
                <p className="modal-sub">
                  Enter a name for the agent. A one-time registration token (valid 1 hour)
                  will be generated. Paste it into the agent's config file.
                </p>
                <div className="field">
                  <label htmlFor="agent-name">Agent Name</label>
                  <input id="agent-name" value={agentName} onChange={(e) => setAgentName(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && handleGenToken()}
                    placeholder="e.g. office-network-agent" autoFocus />
                </div>
                <div className="modal-actions">
                  <button className="btn btn-secondary" onClick={() => setShowGenToken(false)}>Cancel</button>
                  <button className="btn btn-primary" onClick={handleGenToken}
                    disabled={genLoading || !agentName.trim()}>
                    {genLoading ? <><Spinner /> Generating...</> : "Generate Token"}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}

// ─── APP ROOT ─────────────────────────────────────────────────────────────────
export default function App() {
  const [token, setToken]     = useState(null);
  const [orgData, setOrgData] = useState(null);
  const [, setTargets] = useState(null);
  const [phase, setPhase]     = useState("launch"); // launch | org-setup | first-target | app
  const [loading, setLoading] = useState(false);
  const { toasts, add: toast } = useToasts();

  const handleToken = async (t) => {
    setToken(t);
    setLoading(true);
    try {
      // Fetch org data
      const org = await api.getOrg(t);
      setOrgData(org);

      const orgNamed = org.name && org.name !== "Dev Organization";

      if (!orgNamed) {
        setPhase("org-setup");
        return;
      }

      // Check targets
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

  return (
    <>
      {phase === "launch"       && <LaunchScreen onToken={handleToken} />}
      {phase === "org-setup"    && <OrgSetup token={token} onDone={afterOrgSetup} toast={toast} />}
      {phase === "first-target" && <FirstTarget token={token} onDone={afterFirstTarget} toast={toast} />}
      {phase === "app"          && <Dashboard token={token} org={orgData} toast={toast} />}
      <Toast toasts={toasts} />
    </>
  );
}

// ─── LOCATIONS VIEW ──────────────────────────────────────────────────────────
const PROVIDERS = ["AWS", "AZURE", "GCP", "COLOCATION", "ON_PREM"];
const providerLabel = (p) => ({ AWS: "AWS", AZURE: "Azure", GCP: "GCP", COLOCATION: "Colocation", ON_PREM: "On-Prem" }[p] || p);
const providerColor = (p) => ({ AWS: "yellow", AZURE: "blue", GCP: "green", COLOCATION: "purple", ON_PREM: "orange" }[p] || "unknown");

function LocationsView({ locations, loading, token, onRefresh, toast }) {
  const [showAdd, setShowAdd]       = useState(false);
  const [editLoc, setEditLoc]       = useState(null);
  const [deleteId, setDeleteId]     = useState(null);
  const [deleting, setDeleting]     = useState(false);

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
          <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)}>+ Add Location</button>
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
            <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)} style={{ margin: "0 auto" }}>+ Add Location</button>
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
                  <th></th>
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
                    <td>
                      <div className="row-actions">
                        <button className="scan-btn" style={{ color: "var(--muted)", borderColor: "rgba(90,96,112,0.3)" }}
                          onClick={() => setEditLoc(loc)} title="Edit location">✎</button>
                        <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                          onClick={() => setDeleteId(loc.id)} title="Delete location">✕</button>
                      </div>
                    </td>
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

function LocationModal({ token, location, onClose, onSaved, toast }) {
  const isEdit = !!location;
  const [name, setName]             = useState(location?.name || "");
  const [provider, setProvider]     = useState(location?.provider || "");
  const [geoRegion, setGeoRegion]   = useState(location?.geoRegion || "");
  const [cloudRegion, setCloudRegion] = useState(location?.cloudRegion || "");
  const [address, setAddress]       = useState(location?.address || "");
  const [loading, setLoading]       = useState(false);
  const [error, setError]           = useState("");

  const handleSave = async () => {
    if (!name.trim())    { setError("Name is required"); return; }
    if (!provider)       { setError("Provider is required"); return; }
    setError(""); setLoading(true);
    const body = {
      name: name.trim(),
      provider,
      geoRegion:   geoRegion.trim()   || null,
      cloudRegion: cloudRegion.trim() || null,
      address:     address.trim()     || null,
    };
    try {
      if (isEdit) {
        await api.updateLocation(location.id, body, token);
        toast("Location updated", "success");
      } else {
        await api.createLocation(body, token);
        toast("Location created", "success");
      }
      onSaved();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true">
        <div className="modal-title">{isEdit ? "Edit Location" : "Add Location"}</div>
        <p className="modal-sub">{isEdit ? "Update location details." : "Group targets under a named site or cloud region."}</p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="loc-name">Name *</label>
          <input id="loc-name" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="e.g. US-East DC1" autoFocus />
        </div>

        <div className="field">
          <label htmlFor="loc-provider">Provider *</label>
          <select id="loc-provider" value={provider} onChange={(e) => setProvider(e.target.value)}>
            <option value="">— Select provider —</option>
            {PROVIDERS.map((p) => <option key={p} value={p}>{providerLabel(p)}</option>)}
          </select>
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="loc-geo">Geo Region</label>
            <input id="loc-geo" value={geoRegion} onChange={(e) => setGeoRegion(e.target.value)}
              placeholder="e.g. Asia Pacific" />
          </div>
          <div className="field">
            <label htmlFor="loc-cloud">Cloud Region</label>
            <input id="loc-cloud" value={cloudRegion} onChange={(e) => setCloudRegion(e.target.value)}
              placeholder="e.g. ap-southeast-2" />
          </div>
        </div>

        <div className="field">
          <label htmlFor="loc-address">Address <span style={{ color: "var(--muted)", fontWeight: 400 }}>(optional)</span></label>
          <input id="loc-address" value={address} onChange={(e) => setAddress(e.target.value)}
            placeholder="Physical address for on-prem / colocation" />
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={loading || !name.trim() || !provider}>
            {loading ? <><Spinner /> Saving...</> : isEdit ? "Save Changes" : "Create Location"}
          </button>
        </div>
      </div>
    </div>
  );
}

// Inject styles
const styleEl = document.createElement("style");
styleEl.textContent = styles;
document.head.appendChild(styleEl);
