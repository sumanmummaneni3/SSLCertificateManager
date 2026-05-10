import { useState, useEffect, useCallback } from "react";
import { useTheme } from "./theme/useTheme.js";
import { SIDEBAR_THEMES } from "./theme/tokens.js";

// ─── CONFIG ──────────────────────────────────────────────────────────────────
const API_BASE = "";
const DEV_MODE = import.meta.env.VITE_DEV_MODE === "true";

// SIDEBAR_THEMES imported from src/theme/tokens.js — see that file for palette definitions.

// ─── STYLES ──────────────────────────────────────────────────────────────────
const styles = `
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=DM+Mono:wght@400;500&display=swap');

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
    --font-head: 'Inter', sans-serif;
    --font-mono: 'DM Mono', monospace;
    --radius:    8px;
  }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: var(--font-head);
    min-height: 100vh;
    overflow-x: hidden;
  }

  /* ── GRID BACKGROUND ── */
  body::before {
    content: '';
    position: fixed; inset: 0;
    background-image:
      linear-gradient(color-mix(in srgb, var(--color-primary) 2%, transparent) 1px, transparent 1px),
      linear-gradient(90deg, color-mix(in srgb, var(--color-primary) 2%, transparent) 1px, transparent 1px);
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
    background: var(--accent);
    border-radius: 14px;
    display: flex; align-items: center; justify-content: center;
    font-size: 28px;
    box-shadow: var(--glow);
  }

  .logo-text {
    font-family: var(--font-head);
    font-size: 2rem; font-weight: 800;
    letter-spacing: -0.03em;
    color: var(--text);
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
    background: var(--accent);
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
    font-size: 0.6rem; color: var(--sb-muted, var(--muted));
    letter-spacing: 0.12em; text-transform: uppercase;
    padding: 0 0.5rem; margin: 1.25rem 0 0.35rem;
    display: flex; align-items: center; gap: 8px;
  }
  .nav-section::after {
    content: '';
    flex: 1; height: 1px;
    background: var(--sb-border, var(--border));
    opacity: 0.5;
  }
  .nav-section:first-of-type { margin-top: 0; }

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

  .logout-btn {
    display: flex; align-items: center; gap: 8px;
    width: 100%; padding: 8px 12px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: var(--radius);
    color: var(--sb-muted, var(--muted));
    font-family: var(--font-head);
    font-size: 0.82rem;
    cursor: pointer;
    transition: all 0.15s;
    text-align: left;
    margin-top: 0.5rem;
  }
  .logout-btn:hover {
    color: var(--red);
    background: rgba(255, 82, 82, 0.08);
    border-color: rgba(255, 82, 82, 0.2);
  }
  .logout-btn:focus-visible {
    outline: 2px solid var(--red);
    outline-offset: 2px;
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

  /* ── AGENT INSTALL KEY MODAL ── */
  .modal-wide {
    background: var(--surface);
    border: 1px solid var(--border2);
    border-radius: 16px;
    padding: 2rem;
    width: 100%; max-width: 620px;
    box-shadow: 0 32px 80px color-mix(in srgb, var(--color-bg) 50%, transparent);
    animation: slideUp 0.2s ease;
  }

  .install-key-field {
    display: flex; gap: 8px; align-items: stretch;
    margin-bottom: 0.75rem;
  }

  .install-key-field input {
    flex: 1;
    background: var(--surface2);
    border: 1px solid var(--border2);
    border-radius: var(--radius);
    color: var(--green);
    font-family: var(--font-mono);
    font-size: 0.78rem;
    padding: 10px 14px;
    outline: none;
    word-break: break-all;
    letter-spacing: 0.02em;
  }

  .install-key-field input:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(0, 212, 255, 0.1);
  }

  .countdown-badge {
    display: inline-flex; align-items: center; gap: 6px;
    background: rgba(255, 215, 64, 0.1);
    border: 1px solid rgba(255, 215, 64, 0.3);
    color: var(--yellow);
    font-size: 0.72rem; font-weight: 500;
    padding: 4px 12px; border-radius: 20px;
    font-family: var(--font-mono);
    margin-bottom: 1rem;
  }

  .countdown-badge.urgent {
    background: rgba(255, 82, 82, 0.1);
    border-color: rgba(255, 82, 82, 0.3);
    color: var(--red);
  }

  /* ── ACCORDION / DISCLOSURE ── */
  .accordion {
    border: 1px solid var(--border2);
    border-radius: var(--radius);
    margin-bottom: 0.75rem;
    overflow: hidden;
  }

  .accordion-header {
    display: flex; align-items: center; justify-content: space-between;
    padding: 10px 14px;
    background: var(--surface2);
    cursor: pointer;
    font-size: 0.78rem; font-weight: 500;
    color: var(--text);
    border: none; width: 100%; text-align: left;
    transition: background 0.15s;
  }

  .accordion-header:hover { background: color-mix(in srgb, var(--color-primary) 6%, var(--surface2)); }
  .accordion-header:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }

  .accordion-chevron { font-size: 0.65rem; transition: transform 0.2s; color: var(--muted); }
  .accordion-chevron.open { transform: rotate(180deg); }

  .accordion-body {
    padding: 12px 14px;
    border-top: 1px solid var(--border);
    background: var(--surface);
  }

  .accordion-body pre {
    font-family: var(--font-mono);
    font-size: 0.72rem;
    color: var(--accent);
    white-space: pre-wrap;
    word-break: break-all;
    margin: 0;
    line-height: 1.7;
  }

  /* ── CHECKBOX CONFIRMATION ── */
  .confirm-check {
    display: flex; align-items: flex-start; gap: 10px;
    padding: 12px 14px;
    background: var(--surface2);
    border: 1px solid var(--border2);
    border-radius: var(--radius);
    margin: 1rem 0;
    cursor: pointer;
    transition: border-color 0.15s;
  }

  .confirm-check:hover { border-color: var(--accent); }

  .confirm-check input[type="checkbox"] {
    margin-top: 2px; flex-shrink: 0;
    width: 16px; height: 16px;
    accent-color: var(--accent);
    cursor: pointer;
  }

  .confirm-check label {
    font-size: 0.8rem; color: var(--text);
    cursor: pointer; line-height: 1.5;
  }

  /* ── WIZARD STEPS INDICATOR ── */
  .wizard-steps {
    display: flex; align-items: center; gap: 0;
    margin-bottom: 1.5rem;
  }

  .wizard-step {
    flex: 1; text-align: center;
    padding: 6px 4px; font-size: 0.68rem;
    border-bottom: 2px solid var(--border);
    color: var(--muted); transition: all 0.2s;
  }

  .wizard-step.active { color: var(--accent); border-bottom-color: var(--accent); }
  .wizard-step.done   { color: var(--green);  border-bottom-color: var(--green); }

  /* ── CLOSE GUARD DIALOG ── */
  .close-guard-overlay {
    position: fixed; inset: 0;
    background: rgba(0,0,0,0.6);
    display: flex; align-items: center; justify-content: center;
    z-index: 110; padding: 1rem;
  }

  .close-guard-dialog {
    background: var(--surface);
    border: 1px solid var(--border2);
    border-radius: 14px;
    padding: 1.75rem;
    width: 100%; max-width: 400px;
    box-shadow: 0 24px 60px rgba(0,0,0,0.5);
  }

  .close-guard-title {
    font-family: var(--font-head);
    font-size: 1.05rem; font-weight: 700;
    color: var(--red); margin-bottom: 0.5rem;
  }

  .close-guard-body {
    font-size: 0.8rem; color: var(--text);
    line-height: 1.6; margin-bottom: 1.25rem;
  }

  /* ── AGENT STATUS BADGES ── */
  .badge-pending  { background: rgba(90,96,112,0.15);  color: var(--muted);   border: 1px solid var(--border2); }
  .badge-active   { background: rgba(0,230,118,0.12);  color: var(--green);   border: 1px solid rgba(0,230,118,0.25); }
  .badge-offline  { background: rgba(255,145,0,0.12);  color: var(--orange);  border: 1px solid rgba(255,145,0,0.25); }
  .badge-revoked  { background: rgba(255,82,82,0.12);  color: var(--red);     border: 1px solid rgba(255,82,82,0.25); }

  /* ── IMPERSONATION BANNER ── */
  .impersonation-banner {
    background: var(--yellow, #fef3c7);
    border-bottom: 1px solid rgba(180, 130, 0, 0.3);
    padding: 8px 2rem;
    display: flex; align-items: center; gap: 12px;
    font-size: 0.78rem;
    color: #78350f;
    position: sticky; top: 0; z-index: 50;
  }

  .impersonation-banner-text {
    flex: 1;
    font-family: var(--font-mono);
  }

  .impersonation-banner-warn {
    font-size: 0.72rem;
    opacity: 0.75;
    margin-left: 8px;
  }

  /* ── PLATFORM ADMIN ── */
  .admin-tabs {
    display: flex; gap: 0; margin-bottom: 1.5rem;
    border-bottom: 1px solid var(--border);
  }

  .admin-tab {
    padding: 8px 18px;
    font-size: 0.8rem; font-family: var(--font-mono);
    border: none; background: transparent;
    color: var(--muted); cursor: pointer;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    transition: color 0.15s, border-color 0.15s;
  }

  .admin-tab:hover { color: var(--text); }
  .admin-tab.active { color: var(--accent); border-bottom-color: var(--accent); }
  .admin-tab:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

  .search-bar {
    display: flex; align-items: center; gap: 8px;
    background: var(--surface2);
    border: 1px solid var(--border2);
    border-radius: var(--radius);
    padding: 6px 12px;
    min-width: 220px;
  }

  .search-bar input {
    background: transparent; border: none; outline: none;
    color: var(--text); font-size: 0.82rem; width: 100%;
    font-family: var(--font-head);
  }

  .search-bar input::placeholder { color: var(--muted); }

  .org-tree-indent {
    padding-left: 1.5rem;
    border-left: 2px solid var(--border2);
    margin-left: 8px;
  }

  .admin-api-unavailable {
    display: flex; flex-direction: column; align-items: center;
    padding: 4rem 2rem; text-align: center; color: var(--muted);
  }
`;

// ─── API CLIENT ──────────────────────────────────────────────────────────────
const api = {
  async call(method, path, body, token, { actingAsOrgId, reason } = {}) {
    const headers = { "Content-Type": "application/json" };
    if (token) headers["Authorization"] = `Bearer ${token}`;
    if (actingAsOrgId) {
      headers["X-Acting-As-Org"] = actingAsOrgId;
      if (reason) headers["X-Acting-As-Reason"] = reason;
    }
    const res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      // ProblemDetail (RFC 9457) uses title + detail; fall back to message for older endpoints
      const msg = err.detail || err.title || err.message || `HTTP ${res.status}: ${res.statusText}`;
      const error = new Error(msg);
      error.status = res.status;
      error.problemDetail = err; // preserve full object for callers that want it
      throw error;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  getDevToken: (email) =>
    api.call("POST", `/api/v1/auth/dev-token?email=${encodeURIComponent(email)}`),
  logout: (token) => api.call("POST", "/api/v1/auth/logout", null, token),
  getMe:         (token) => api.call("GET",  "/api/v1/auth/me",            null, token),
  getOrg:        (token) => api.call("GET",  "/api/v1/org",              null, token),
  updateOrgName: (name, token) => api.call("PUT", `/api/v1/org/name?name=${encodeURIComponent(name)}`, null, token),
  getTargets:    (token, opts) => api.call("GET",  "/api/v1/targets?size=100", null, token, opts),
  createTarget:  (data, token, opts) => api.call("POST", "/api/v1/targets",    data, token, opts),
  updateTarget:  (id, data, token, opts) => api.call("PUT", `/api/v1/targets/${id}`, data, token, opts),
  deleteTarget:  (id, token, opts) => api.call("DELETE", `/api/v1/targets/${id}`, null, token, opts),
  scanTarget:    (id, token, opts) => api.call("POST", `/api/v1/targets/${id}/scan`, null, token, opts),
  getDashboard:  (token) => api.call("GET",  "/api/v1/dashboard",        null, token),
  getCerts:      (token) => api.call("GET",  "/api/v1/certificates?size=100", null, token),
  getExpiring:   (days, token) => api.call("GET", `/api/v1/certificates/expiring?days=${days}`, null, token),
  // Agent endpoints
  listAgents:    (token, opts) => api.call("GET",  "/api/v1/agent/list",                    null,   token, opts),
  genAgentToken: (name, token) => api.call("POST", `/api/v1/agent/tokens?agentName=${encodeURIComponent(name)}`, null, token),
  revokeAgent:   (id, token, opts) => api.call("POST", `/api/v1/agent/${id}/revoke`,        null,   token, opts),
  createAgent:   (data, token, opts) => api.call("POST", "/api/v1/agents",                  data,   token, opts),
  queueScan:     (targetId, token) => api.call("POST", `/api/v1/targets/${targetId}/scan`, null, token),
  // Location endpoints
  listLocations:   (token, opts) => api.call("GET",  "/api/v1/locations",        null, token, opts),
  createLocation:  (data, token, opts) => api.call("POST", "/api/v1/locations",  data, token, opts),
  updateLocation:  (id, data, token, opts) => api.call("PUT", `/api/v1/locations/${id}`, data, token, opts),
  deleteLocation:  (id, token, opts) => api.call("DELETE", `/api/v1/locations/${id}`, null, token, opts),
  // Team endpoints
  listMembers:   (token, opts) => api.call("GET",  "/api/v1/org/members",                    null, token, opts),
  inviteMember:  (data, token, opts) => api.call("POST", "/api/v1/org/invitations",           data, token, opts),
  changeRole:    (userId, role, token, opts) => api.call("PUT", `/api/v1/org/members/${userId}/role?role=${role}`, null, token, opts),
  revokeMember:  (userId, token, opts) => api.call("DELETE", `/api/v1/org/members/${userId}`, null, token, opts),
  // Org profile endpoints
  getOrgProfile:    (token) => api.call("GET", "/api/v1/org/profile",       null, token),
  updateOrgProfile: (data, token) => api.call("PUT", "/api/v1/org/profile", data, token),
  // Invite acceptance endpoints
  validateInvite: (token) => api.call("POST", `/api/v1/auth/invite/validate?token=${encodeURIComponent(token)}`),
  acceptInvite:   (data) => api.call("POST", "/api/v1/auth/invite/accept", data),
  // Platform admin endpoints
  admin: {
    listOrgs:    (token) => api.call("GET", "/api/v1/admin/orgs", null, token),
    getOrgTree:  (token) => api.call("GET", "/api/v1/admin/orgs/tree", null, token),
    getMsps:     (token) => api.call("GET", "/api/v1/admin/msps", null, token),
    getOrgDetail:(token, orgId) => api.call("GET", `/api/v1/admin/orgs/${orgId}`, null, token),
    updateQuota: (token, orgId, body) => api.call("PUT", `/api/v1/admin/orgs/${orgId}/quota`, body, token),
    getAuditLog: (token, params) => api.call("GET", `/api/v1/admin/audit?${new URLSearchParams(params)}`, null, token),
  },
};

// ─── HELPERS ─────────────────────────────────────────────────────────────────
const statusColor = (s) => ({ VALID: "green", EXPIRING: "yellow", EXPIRED: "red", UNREACHABLE: "orange" }[s] || "unknown");
const hostTypeColor = (t) => t?.toLowerCase() || "unknown";
const fmtDate = (iso) => iso ? new Date(iso).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }) : "—";
const daysColor = (d) => d < 0 ? "var(--red)" : d <= 7 ? "var(--red)" : d <= 30 ? "var(--yellow)" : "var(--green)";
const daysWidth = (d) => `${Math.min(100, Math.max(0, (d / 365) * 100))}%`;
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
        <div className="logo-text">OOPSSSL</div>
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
        <div className="logo-text">OOPSSSL</div>
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

function isRfc1918(h) {
  const s = h.trim();
  return s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("127.") ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(s);
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
          Private IPs (10.x, 172.16–31.x, 192.168.x) auto-switch to Private.
        </p>

        {error && <div className="alert alert-error" role="alert">⚠ {error}</div>}

        <div className="field">
          <label htmlFor="add-host">Host *</label>
          <input id="add-host" value={host} onChange={(e) => {
              const v = e.target.value;
              setHost(v);
              if (isRfc1918(v)) setIsPrivate(true);
            }}
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
          <div className="logo-text">OOPSSSL</div>
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
        <div className="logo-text">OOPSSSL</div>
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
function Dashboard({ token, org, me, toast, onLogout }) {
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
  // Platform admin impersonation state
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

  if (loading) {
    return (
      <div className="app">
        <Sidebar view={view} onView={setView} org={org} me={me} theme={theme} onTheme={setTheme} onLogout={onLogout} />
        <div className="main"><div className="loading-center"><Spinner lg /><span>Loading dashboard...</span></div></div>
      </div>
    );
  }

  return (
    <div className="app">
      <Sidebar view={view} onView={setView} org={org} me={me} theme={theme} onTheme={setTheme} onLogout={onLogout} />
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
        {view === "dashboard" && (
          <DashboardView dash={dash} targets={targets} onScan={triggerScan}
            scanning={scanning} onAddTarget={() => setShowAdd(true)} me={me} />
        )}
        {view === "targets" && (
          <TargetsView targets={targets} onScan={triggerScan} scanning={scanning}
            onAdd={() => setShowAdd(true)} onDelete={setDeleteId}
            onEdit={setEditTarget} onRefresh={load} me={me} />
        )}
        {view === "certs" && (
          <CertsView certs={certs} loading={certsLoading} onRefresh={loadCerts} />
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
        {view === "settings" && <SettingsView token={token} org={org} toast={toast} />}
        {view === "msp-orgs" && <MspOrgsView  token={token} toast={toast} />}
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

const NAV_GROUPS = [
  {
    label: "Monitor",
    items: [
      { id: "dashboard", icon: "◈", label: "Dashboard"    },
      { id: "targets",   icon: "⊕", label: "Targets"      },
      { id: "certs",     icon: "⊞", label: "Certificates" },
    ],
  },
  {
    label: "Infrastructure",
    items: [
      { id: "locations", icon: "⊙", label: "Locations" },
      { id: "agents",    icon: "⬡", label: "Agents"    },
    ],
  },
  {
    label: "Account",
    items: [
      { id: "team",     icon: "◎", label: "Team"     },
      { id: "settings", icon: "⚙", label: "Settings" },
    ],
  },
];

const MSP_GROUP = {
  label: "MSP",
  items: [
    { id: "msp-orgs", icon: "⬡", label: "MSP Orgs" },
  ],
};

const ADMIN_GROUP = {
  label: "Admin",
  items: [
    { id: "platform-admin-orgs", icon: "◫", label: "All Orgs" },
  ],
};

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

function Sidebar({ view, onView, org, me, theme = "dark", onTheme, onLogout }) {
  const { mode, toggle } = useTheme();
  const themeVars = SIDEBAR_THEMES[theme]?.vars || SIDEBAR_THEMES.dark.vars;
  const isMsp = org?.orgType === "MSP";
  const isPlatformAdmin = me?.platformAdmin === true;
  let groups = [...NAV_GROUPS];
  if (isMsp) groups = [...groups, MSP_GROUP];
  if (isPlatformAdmin) groups = [...groups, ADMIN_GROUP];
  return (
    <nav className="sidebar" style={themeVars} aria-label="Main navigation">
      <div className="sidebar-logo">
        <div className="logo-icon" aria-hidden="true">🔐</div>
        <div className="logo-text">OOPSSSL</div>
      </div>
      {groups.map((group) => (
        <div key={group.label}>
          <div className="nav-section" aria-hidden="true">{group.label}</div>
          {group.items.map((item) => (
            <NavItem key={item.id} item={item} active={view === item.id} onView={onView} />
          ))}
        </div>
      ))}
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

function DashboardView({ dash, targets, onScan, scanning, onAddTarget, me }) {
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
        {(me == null || me?.permissions?.canWriteTargets) && (
          <button className="btn btn-primary btn-sm" onClick={onAddTarget}>+ Add Target</button>
        )}
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
            {(me == null || me?.permissions?.canWriteTargets) && (
              <button className="btn btn-primary btn-sm" onClick={onAddTarget} style={{ margin: "0 auto" }}>
                + Add First Target
              </button>
            )}
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
                        {(me == null || me?.permissions?.canWriteTargets) && (
                          <button className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                            onClick={() => onScan(t)} disabled={scanning[t.id]}>
                            {scanning[t.id] ? <><Spinner /> Scanning</> : "⟳ Scan"}
                          </button>
                        )}
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

function TargetsView({ targets, onScan, scanning, onAdd, onDelete, onEdit, onRefresh, me }) {
  const canWrite = me == null || me?.permissions?.canWriteTargets;
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Targets</div>
          <div className="page-sub">Manage your monitored endpoints</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          {canWrite && (
            <button className="btn btn-primary btn-sm" onClick={onAdd}>+ Add Target</button>
          )}
        </div>
      </div>
      <div className="page-content">
        {targets.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">🎯</div>
            <div className="empty-title">No targets</div>
            <p className="empty-sub">Add your first endpoint to start monitoring.</p>
            {canWrite && (
              <button className="btn btn-primary btn-sm" onClick={onAdd} style={{ margin: "0 auto" }}>+ Add Target</button>
            )}
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
                          {canWrite && (
                            <button className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                              onClick={() => onScan(t)} disabled={scanning[t.id]}
                              title={t.isPrivate ? "Queue scan job for agent" : "Trigger scan"}>
                              {scanning[t.id] ? <Spinner /> : "⟳"}
                            </button>
                          )}
                          {canWrite && (
                            <button className="scan-btn" style={{ color: "var(--muted)", borderColor: "rgba(90,96,112,0.3)" }}
                              onClick={() => onEdit(t)} title="Edit target">✎</button>
                          )}
                          {canWrite && (
                            <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                              onClick={() => onDelete(t.id)} title="Delete target">✕</button>
                          )}
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

// ─── VALIDATION HELPERS ──────────────────────────────────────────────────────
// Hyphen at end of character class — no escape needed
const AGENT_NAME_RE = /^[A-Za-z0-9 _.-]{3,64}$/;
const CIDR_RE = /^(\d{1,3}\.){3}\d{1,3}\/([0-9]|[1-2]\d|3[0-2])$/;

function validateCidrs(raw) {
  if (!raw.trim()) return "At least one CIDR is required";
  const parts = raw.split(",").map((s) => s.trim()).filter(Boolean);
  for (const p of parts) {
    if (!CIDR_RE.test(p)) return `Invalid CIDR: "${p}" — use format 192.168.1.0/24`;
    const octets = p.split("/")[0].split(".");
    if (octets.some((o) => parseInt(o) > 255)) return `Invalid IP in CIDR: "${p}"`;
  }
  return null;
}

// ─── COUNTDOWN HOOK ───────────────────────────────────────────────────────────
function useCountdown(expiresAt) {
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

// ─── ACCORDION ───────────────────────────────────────────────────────────────
function Accordion({ title, children }) {
  const [open, setOpen] = useState(false);
  const headId = `acc-${title.replace(/\s/g, "-").toLowerCase()}`;
  const bodyId = `acc-body-${title.replace(/\s/g, "-").toLowerCase()}`;
  return (
    <div className="accordion">
      <button
        className="accordion-header"
        id={headId}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((v) => !v)}
      >
        {title}
        <span className={`accordion-chevron ${open ? "open" : ""}`} aria-hidden="true">▼</span>
      </button>
      {open && (
        <div className="accordion-body" id={bodyId} role="region" aria-labelledby={headId}>
          {children}
        </div>
      )}
    </div>
  );
}

// ─── CLOSE GUARD DIALOG ───────────────────────────────────────────────────────
function CloseGuardDialog({ onConfirm, onCancel }) {
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

// ─── AGENT INSTALL KEY MODAL ──────────────────────────────────────────────────
function AgentInstallKeyModal({ result, onClose, toast }) {
  const { agentId, installKey, bundleDownloadUrl, expiresAt } = result;
  const [confirmed, setConfirmed] = useState(false);
  const [showGuard, setShowGuard] = useState(false);
  const { label: countdownLabel, urgent } = useCountdown(expiresAt);

  // Use a ref so the keydown handler always sees the latest value without
  // needing to be recreated on each render (avoids react-compiler warning).
  const confirmedRef = useCallback(() => confirmed, [confirmed]);

  const handleCloseRequest = useCallback(() => {
    if (!confirmedRef()) { setShowGuard(true); return; }
    onClose();
  }, [confirmedRef, onClose]);

  useEffect(() => {
    const handler = (e) => { if (e.key === "Escape") handleCloseRequest(); };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [handleCloseRequest]);

  const copyKey = () => {
    navigator.clipboard.writeText(installKey).then(
      () => toast("Install key copied!", "success"),
      () => toast("Clipboard unavailable — select and copy manually", "error"),
    );
  };

  return (
    <>
      <div
        className="modal-bg"
        onClick={(e) => { if (e.target === e.currentTarget) handleCloseRequest(); }}
        aria-hidden={showGuard}
      >
        <div className="modal-wide" role="dialog" aria-modal="true" aria-labelledby="ik-title">
          <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", marginBottom: "0.4rem" }}>
            <div className="modal-title" id="ik-title">Agent created — save your install key</div>
            <button
              className="btn-ghost"
              style={{ padding: "4px 8px", fontSize: "1rem", lineHeight: 1 }}
              onClick={handleCloseRequest}
              aria-label="Close"
            >
              ✕
            </button>
          </div>
          <p className="modal-sub">
            This key is shown exactly once. Store it securely before downloading the bundle.
          </p>

          <div className={`countdown-badge ${urgent ? "urgent" : ""}`} aria-live="off">
            Bundle link expires in: <strong>{countdownLabel}</strong>
          </div>

          <div style={{ marginBottom: "0.5rem" }}>
            <div style={{ fontSize: "0.68rem", color: "var(--muted)", letterSpacing: "0.08em", textTransform: "uppercase", marginBottom: 6 }}>
              Install Key (shown once)
            </div>
            <div className="install-key-field">
              <input
                type="text"
                readOnly
                value={installKey}
                aria-label="Install key"
                onFocus={(e) => e.target.select()}
              />
              <button
                className="btn btn-secondary btn-sm"
                style={{ flexShrink: 0, whiteSpace: "nowrap" }}
                onClick={copyKey}
                aria-label="Copy install key to clipboard"
              >
                Copy
              </button>
            </div>
          </div>

          <div style={{ marginBottom: "1rem" }}>
            <a
              href={bundleDownloadUrl || "#"}
              download
              className="btn btn-primary"
              style={{
                display: "flex",
                textDecoration: "none",
                gap: "8px",
                opacity: bundleDownloadUrl ? 1 : 0.4,
                pointerEvents: bundleDownloadUrl ? "auto" : "none",
              }}
              aria-label={`Download installer bundle for agent ${agentId}`}
              aria-disabled={!bundleDownloadUrl}
            >
              <span aria-hidden="true">&#8681;</span> Download Installer Bundle (.zip)
            </a>
          </div>

          <div style={{ marginBottom: "0.75rem" }}>
            <div style={{ fontSize: "0.72rem", color: "var(--muted)", marginBottom: "0.5rem" }}>
              Platform installation instructions
            </div>
            <Accordion title="Linux / macOS">
              <pre>{`unzip certguard-agent-${agentId}.zip
cd certguard-agent-${agentId}
chmod +x run.sh
./run.sh
# When prompted, enter the install key above`}</pre>
            </Accordion>
            <Accordion title="Windows">
              <pre>{`1. Unzip certguard-agent-${agentId}.zip
2. Open a Command Prompt in the extracted folder
3. Run:  run.bat
4. When prompted, enter the install key above`}</pre>
            </Accordion>
            <Accordion title="Headless / CI">
              <pre>{`CERTGUARD_INSTALL_KEY=<key> java -jar certguard-agent.jar --bundle bundle.cgb`}</pre>
            </Accordion>
          </div>

          <label className="confirm-check" htmlFor="ik-confirm">
            <input
              id="ik-confirm"
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            <span>I have securely stored the install key and understand it cannot be retrieved again.</span>
          </label>

          <div className="modal-actions" style={{ marginTop: "0.5rem" }}>
            <button
              className="btn btn-primary"
              onClick={onClose}
              disabled={!confirmed}
              aria-disabled={!confirmed}
            >
              Done
            </button>
          </div>
        </div>
      </div>

      {showGuard && (
        <CloseGuardDialog
          onConfirm={() => { setShowGuard(false); onClose(); }}
          onCancel={() => setShowGuard(false)}
        />
      )}
    </>
  );
}

// ─── AGENT CREATE WIZARD ──────────────────────────────────────────────────────
function AgentCreateWizard({ token, locations, onClose, onCreated, toast }) {
  const [agentName, setAgentName]     = useState("");
  const [cidrs, setCidrs]             = useState("");
  const [maxTargets, setMaxTargets]   = useState("50");
  const [locationId, setLocationId]   = useState("");
  const [loading, setLoading]         = useState(false);
  const [errors, setErrors]           = useState({});

  const validate = () => {
    const e = {};
    if (!AGENT_NAME_RE.test(agentName.trim())) {
      e.agentName = "Name must be 3–64 characters and contain only A-Za-z0-9 space _ . -";
    }
    const cidrErr = validateCidrs(cidrs);
    if (cidrErr) e.cidrs = cidrErr;
    const mt = parseInt(maxTargets);
    if (!maxTargets || isNaN(mt) || mt < 1) e.maxTargets = "Must be at least 1";
    return e;
  };

  const handleSubmit = async () => {
    const e = validate();
    if (Object.keys(e).length) { setErrors(e); return; }
    setErrors({});
    setLoading(true);
    try {
      const body = {
        agentName: agentName.trim(),
        allowedCidrs: cidrs.split(",").map((s) => s.trim()).filter(Boolean),
        maxTargets: parseInt(maxTargets),
        ...(locationId ? { locationId } : {}),
      };
      const result = await api.createAgent(body, token);
      onCreated(result);
    } catch (err) {
      // Surface ProblemDetail (title + detail already merged by api.call into err.message)
      const pd = err.problemDetail || {};
      const msg = (pd.title && pd.detail)
        ? `${pd.title} — ${pd.detail}`
        : err.message;
      setErrors({ submit: msg });
      toast("Failed to create agent: " + msg, "error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="acw-title">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "0.4rem" }}>
          <div className="modal-title" id="acw-title">Deploy New Agent</div>
          <button className="btn-ghost" style={{ padding: "4px 8px", fontSize: "1rem", lineHeight: 1 }}
            onClick={onClose} aria-label="Close">✕</button>
        </div>
        <p className="modal-sub">
          Configure the agent. An encrypted installer bundle will be generated and
          a one-time install key will be shown — download the bundle and store the key securely.
        </p>

        {errors.submit && (
          <div className="alert alert-error" role="alert">
            <span>⚠</span> {errors.submit}
          </div>
        )}

        <div className="field">
          <label htmlFor="acw-name">
            Agent Name <span style={{ color: "var(--red)" }}>*</span>
          </label>
          <input
            id="acw-name"
            value={agentName}
            onChange={(e) => { setAgentName(e.target.value); setErrors((v) => ({ ...v, agentName: undefined })); }}
            placeholder="e.g. office-agent-01"
            autoFocus
            aria-invalid={!!errors.agentName}
            aria-describedby={errors.agentName ? "acw-name-err" : undefined}
          />
          {errors.agentName && (
            <div id="acw-name-err" style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }} role="alert">
              {errors.agentName}
            </div>
          )}
        </div>

        <div className="field">
          <label htmlFor="acw-cidrs">
            Allowed CIDRs <span style={{ color: "var(--red)" }}>*</span>
          </label>
          <input
            id="acw-cidrs"
            value={cidrs}
            onChange={(e) => { setCidrs(e.target.value); setErrors((v) => ({ ...v, cidrs: undefined })); }}
            placeholder="192.168.1.0/24, 10.0.0.0/8"
            aria-invalid={!!errors.cidrs}
            aria-describedby={errors.cidrs ? "acw-cidrs-err" : "acw-cidrs-hint"}
          />
          {errors.cidrs ? (
            <div id="acw-cidrs-err" style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }} role="alert">
              {errors.cidrs}
            </div>
          ) : (
            <div id="acw-cidrs-hint" style={{ fontSize: "0.72rem", color: "var(--muted)", marginTop: 4 }}>
              Comma-separated IPv4 CIDRs the agent is permitted to scan.
            </div>
          )}
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
          <div className="field">
            <label htmlFor="acw-max">
              Max Targets <span style={{ color: "var(--red)" }}>*</span>
            </label>
            <input
              id="acw-max"
              type="number"
              min="1"
              value={maxTargets}
              onChange={(e) => { setMaxTargets(e.target.value); setErrors((v) => ({ ...v, maxTargets: undefined })); }}
              aria-invalid={!!errors.maxTargets}
              aria-describedby={errors.maxTargets ? "acw-max-err" : undefined}
            />
            {errors.maxTargets && (
              <div id="acw-max-err" style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }} role="alert">
                {errors.maxTargets}
              </div>
            )}
          </div>
          <div className="field">
            <label htmlFor="acw-loc">Location <span style={{ color: "var(--muted)", fontWeight: 400 }}>(optional)</span></label>
            <select id="acw-loc" value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">— None —</option>
              {(locations || []).map((l) => (
                <option key={l.id} value={l.id}>{l.name}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
          <button
            className="btn btn-primary"
            onClick={handleSubmit}
            disabled={loading || !agentName.trim() || !cidrs.trim() || !maxTargets}
          >
            {loading ? <><Spinner /> Creating...</> : "Create Agent"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── AGENTS VIEW ─────────────────────────────────────────────
function AgentsView({ agents, loading, token, onRefresh, toast, me }) {
  const [showWizard, setShowWizard]     = useState(false);
  const [installResult, setInstallResult] = useState(null);
  const [locations, setLocations]       = useState([]);

  // Load locations for the wizard dropdown
  useEffect(() => {
    api.listLocations(token).then(setLocations).catch(() => {});
  }, [token]);

  // Poll every 10 s while any agent is PENDING or OFFLINE
  useEffect(() => {
    const needsPoll = agents.some((a) => a.status === "PENDING" || a.status === "OFFLINE");
    if (!needsPoll) return;
    const id = setInterval(onRefresh, 10000);
    return () => clearInterval(id);
  }, [agents, onRefresh]);

  const agentStatusBadgeType = (s) => ({
    ACTIVE: "active", PENDING: "pending", OFFLINE: "offline", REVOKED: "revoked", EXPIRED: "revoked"
  }[s] || "pending");

  const handleRevoke = async (id) => {
    try {
      await api.revokeAgent(id, token);
      toast("Agent revoked", "success");
      onRefresh();
    } catch (e) {
      toast("Revoke failed: " + e.message, "error");
    }
  };

  const handleCreated = (result) => {
    setShowWizard(false);
    setInstallResult(result);
    onRefresh();
  };

  const handleInstallDone = () => {
    setInstallResult(null);
  };

  const canWrite = me == null || me?.permissions?.canWriteAgents;
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Agents</div>
          <div className="page-sub">On-premise agents for private network scanning</div>
        </div>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button className="btn btn-secondary btn-sm" onClick={onRefresh}>↻ Refresh</button>
          {canWrite && (
            <button className="btn btn-primary btn-sm" onClick={() => setShowWizard(true)}>+ Deploy New Agent</button>
          )}
        </div>
      </div>
      <div className="page-content">

        <div className="alert alert-info" style={{ marginBottom: "1.5rem" }}>
          <span>ℹ</span>
          <div>
            Agents run inside your private network and scan hosts not reachable from the internet.
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
              Click "+ Deploy New Agent" to create an encrypted installer bundle.
            </p>
            {canWrite && (
              <button className="btn btn-primary btn-sm" onClick={() => setShowWizard(true)}
                style={{ margin: "0 auto" }}>+ Deploy First Agent</button>
            )}
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
                  <th>Last Seen</th>
                  <th>Registered</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {agents.map((a) => (
                  <tr key={a.id}>
                    <td className="host-cell">
                      {a.name}
                      {a.locationName && (
                        <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)" }}>{a.locationName}</div>
                      )}
                    </td>
                    <td><Badge type={agentStatusBadgeType(a.status)}>{a.status}</Badge></td>
                    <td className="mono">{a.currentTargetCount ?? 0} / {a.maxTargets}</td>
                    <td>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                        {(a.allowedCidrs || []).map((c) => (
                          <span key={c} className="badge badge-domain" style={{ fontSize: "0.68rem" }}>{c}</span>
                        ))}
                      </div>
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtRelative(a.lastSeenAt)}
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>
                      {fmtDate(a.registeredAt)}
                    </td>
                    <td>
                      {canWrite && (a.status === "ACTIVE" || a.status === "PENDING") && (
                        <button
                          className="scan-btn"
                          style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                          onClick={() => handleRevoke(a.id)}
                          aria-label={`Revoke agent ${a.name}`}
                        >
                          Revoke
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showWizard && (
        <AgentCreateWizard
          token={token}
          locations={locations}
          onClose={() => setShowWizard(false)}
          onCreated={handleCreated}
          toast={toast}
        />
      )}

      {installResult && (
        <AgentInstallKeyModal
          result={installResult}
          onClose={handleInstallDone}
          toast={toast}
        />
      )}
    </>
  );
}

// ─── TEAM VIEW ────────────────────────────────────────────────────────────────
function TeamView({ token, org, toast, me }) {
  const [members, setMembers]       = useState([]);
  const [loading, setLoading]       = useState(true);
  const [showInvite, setShowInvite] = useState(false);
  const [revokeId, setRevokeId]     = useState(null);
  const [revoking, setRevoking]     = useState(false);

  const load = () => {
    setLoading(true);
    api.listMembers(token)
      .then(setMembers)
      .catch((e) => toast("Failed to load members: " + e.message, "error"))
      .finally(() => setLoading(false));
  };

  useEffect(load, [token]);

  const handleRevoke = async () => {
    setRevoking(true);
    try {
      await api.revokeMember(revokeId, token);
      toast("Member removed", "success");
      setRevokeId(null);
      load();
    } catch (e) {
      toast("Failed to remove member: " + e.message, "error");
    } finally {
      setRevoking(false);
    }
  };

  const inviteStatusBadgeType = (s) => ({ ACCEPTED: "active", PENDING: "pending", REVOKED: "revoked" }[s] || "unknown");

  const canManage = me == null || me?.permissions?.canManageTeam;
  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Team</div>
          <div className="page-sub">Manage members and access for {org?.name || "your organisation"}</div>
        </div>
        {canManage && (
          <button className="btn btn-primary btn-sm" onClick={() => setShowInvite(true)}>+ Invite Member</button>
        )}
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading members...</span></div>
        ) : members.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">◎</div>
            <div className="empty-title">No team members yet</div>
            <p className="empty-sub">Invite your colleagues to collaborate on certificate monitoring.</p>
            {canManage && (
              <button className="btn btn-primary btn-sm" style={{ margin: "0 auto" }} onClick={() => setShowInvite(true)}>+ Invite Member</button>
            )}
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Member</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Invited By</th>
                  <th>Joined</th>
                  {canManage && <th></th>}
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <div style={{ fontWeight: 500 }}>{m.name || m.email}</div>
                      {m.name && <div className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{m.email}</div>}
                    </td>
                    <td>
                      {canManage
                        ? <RoleDropdown member={m} token={token} onChanged={load} toast={toast} />
                        : <Badge type={m.role === "ADMIN" ? "active" : "pending"}>{m.role}</Badge>
                      }
                    </td>
                    <td><Badge type={inviteStatusBadgeType(m.inviteStatus)}>{m.inviteStatus}</Badge></td>
                    <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{m.invitedByEmail || "—"}</td>
                    <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{fmtDate(m.createdAt)}</td>
                    {canManage && (
                      <td>
                        <button className="scan-btn" style={{ color: "var(--red)", borderColor: "rgba(255,82,82,0.3)" }}
                          onClick={() => setRevokeId(m.userId)} title="Remove member">✕</button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {showInvite && (
        <InviteMemberModal token={token} onClose={() => setShowInvite(false)}
          onInvited={() => { setShowInvite(false); load(); toast("Invitation sent", "success"); }}
          toast={toast} />
      )}

      {revokeId && (
        <div className="modal-bg">
          <div className="modal" role="alertdialog" aria-modal="true">
            <div className="modal-title">Remove Member?</div>
            <p className="modal-sub">This member will lose access to your organisation immediately.</p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setRevokeId(null)} disabled={revoking}>Cancel</button>
              <button className="btn btn-danger" onClick={handleRevoke} disabled={revoking}>
                {revoking ? <Spinner /> : "Remove"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function RoleDropdown({ member, token, onChanged, toast }) {
  const [role, setRole] = useState(member.role);
  const [saving, setSaving] = useState(false);

  const handleChange = async (e) => {
    const newRole = e.target.value;
    setSaving(true);
    try {
      await api.changeRole(member.userId, newRole, token);
      setRole(newRole);
      onChanged();
      toast("Role updated", "success");
    } catch (err) {
      toast("Failed to update role: " + err.message, "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <select value={role} onChange={handleChange} disabled={saving}
      style={{ background: "var(--surface2)", color: "var(--text)", border: "1px solid var(--border2)",
               borderRadius: 6, padding: "3px 8px", fontSize: "0.78rem", cursor: "pointer" }}>
      <option value="ADMIN">Admin</option>
      <option value="ENGINEER">Engineer</option>
      <option value="VIEWER">Viewer</option>
    </select>
  );
}

function InviteMemberModal({ token, onClose, onInvited }) {
  const [email, setEmail]   = useState("");
  const [role, setRole]     = useState("ENGINEER");
  const [loading, setLoading] = useState(false);
  const [error, setError]   = useState("");

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!email.trim()) { setError("Email is required"); return; }
    setError("");
    setLoading(true);
    try {
      await api.inviteMember({ email: email.trim(), role }, token);
      onInvited();
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="invite-title">
        <div className="modal-title" id="invite-title">Invite Team Member</div>
        <form onSubmit={handleSubmit}>
          <div className="field">
            <label>Email address</label>
            <input type="email" value={email} autoFocus
              onChange={(e) => setEmail(e.target.value)} placeholder="colleague@example.com" />
          </div>
          <div className="field">
            <label>Role</label>
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="ADMIN">Admin — full access, can manage team</option>
              <option value="ENGINEER">Engineer — manage targets and scans</option>
              <option value="VIEWER">Viewer — read-only access</option>
            </select>
          </div>
          {error && <div style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }}>{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={loading}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? <Spinner /> : "Send Invite"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─── SETTINGS VIEW ────────────────────────────────────────────────────────────
// Hoisted outside SettingsView so React sees a stable component type across
// renders. Defining it inside the parent caused a new type on every keystroke,
// forcing unmount/remount and losing input focus.
function SettingsField({ label, field, type = "text", placeholder, form, errors, set }) {
  return (
    <div className="field">
      <label>{label}</label>
      <input
        type={type}
        placeholder={placeholder}
        value={form[field] || ""}
        onChange={(e) => set(field, e.target.value)}
      />
      {errors[field] && (
        <div style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }}>
          {errors[field]}
        </div>
      )}
    </div>
  );
}

// eslint-disable-next-line no-unused-vars
function SettingsView({ token, org, toast }) {
  const [profile, setProfile] = useState(null);
  const [form, setForm]       = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [dirty, setDirty]     = useState(false);
  const [errors, setErrors]   = useState({});

  useEffect(() => {
    api.getOrgProfile(token)
      .then((p) => { setProfile(p); setForm(p); })
      .catch((e) => toast("Failed to load profile: " + e.message, "error"))
      .finally(() => setLoading(false));
  }, [token]);

  const set = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const validate = () => {
    const e = {};
    if (!form.name?.trim()) e.name = "Organisation name is required";
    if (form.contactEmail && !/\S+@\S+\.\S+/.test(form.contactEmail)) e.contactEmail = "Invalid email";
    return e;
  };

  const handleSave = async (ev) => {
    ev.preventDefault();
    const e = validate();
    if (Object.keys(e).length) { setErrors(e); return; }
    setSaving(true);
    try {
      const updated = await api.updateOrgProfile({
        name: form.name?.trim(),
        addressLine1: form.addressLine1 || null,
        addressLine2: form.addressLine2 || null,
        city: form.city || null,
        stateProvince: form.stateProvince || null,
        postalCode: form.postalCode || null,
        country: form.country || null,
        phone: form.phone || null,
        contactEmail: form.contactEmail || null,
      }, token);
      setProfile(updated);
      setForm(updated);
      setDirty(false);
      toast("Organisation profile saved", "success");
    } catch (err) {
      toast("Save failed: " + err.message, "error");
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => { setForm(profile); setDirty(false); setErrors({}); };

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">Settings</div>
          <div className="page-sub">Organisation profile and configuration</div>
        </div>
      </div>
      <div className="page-content">
        {loading ? (
          <div className="loading-center"><Spinner lg /><span>Loading settings...</span></div>
        ) : (
          <form onSubmit={handleSave} style={{ maxWidth: 640 }}>
            <div className="cert-detail" style={{ marginBottom: "1.5rem" }}>
              <div style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase",
                            letterSpacing: "0.1em", marginBottom: "1rem" }}>Organisation Profile</div>

              <SettingsField label="Organisation Name *" field="name" placeholder="Acme Corp" form={form} errors={errors} set={set} />
              <SettingsField label="Contact Email" field="contactEmail" type="email" placeholder="admin@acme.com" form={form} errors={errors} set={set} />
              <SettingsField label="Phone" field="phone" placeholder="+1 555 000 0000" form={form} errors={errors} set={set} />

              <div style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase",
                            letterSpacing: "0.1em", margin: "1.25rem 0 0.75rem" }}>Address</div>

              <SettingsField label="Address Line 1" field="addressLine1" placeholder="123 Main Street" form={form} errors={errors} set={set} />
              <SettingsField label="Address Line 2" field="addressLine2" placeholder="Suite 400" form={form} errors={errors} set={set} />
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
                <SettingsField label="City" field="city" placeholder="San Francisco" form={form} errors={errors} set={set} />
                <SettingsField label="State / Province" field="stateProvince" placeholder="CA" form={form} errors={errors} set={set} />
              </div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.75rem" }}>
                <SettingsField label="Postal Code" field="postalCode" placeholder="94105" form={form} errors={errors} set={set} />
                <SettingsField label="Country" field="country" placeholder="US" form={form} errors={errors} set={set} />
              </div>
            </div>

            <div className="cert-detail" style={{ marginBottom: "1.5rem" }}>
              <div style={{ fontSize: "0.7rem", color: "var(--muted)", textTransform: "uppercase",
                            letterSpacing: "0.1em", marginBottom: "0.75rem" }}>Plan & Quota</div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1rem", fontSize: "0.82rem" }}>
                <div>
                  <div style={{ color: "var(--muted)", marginBottom: 4 }}>Subscription</div>
                  <Badge type={profile?.status === "ACTIVE" ? "active" : "pending"}>{profile?.status || "—"}</Badge>
                </div>
                <div>
                  <div style={{ color: "var(--muted)", marginBottom: 4 }}>Certificate Quota</div>
                  <span style={{ fontFamily: "var(--font-mono)", fontWeight: 600 }}>{profile?.maxCertificateQuota ?? "—"}</span>
                </div>
              </div>
            </div>

            <div style={{ display: "flex", gap: "0.5rem" }}>
              <button type="submit" className="btn btn-primary" disabled={saving || !dirty}>
                {saving ? <Spinner /> : "Save Changes"}
              </button>
              {dirty && (
                <button type="button" className="btn btn-secondary" onClick={handleReset} disabled={saving}>
                  Discard
                </button>
              )}
            </div>
          </form>
        )}
      </div>
    </>
  );
}

// ─── MSP ORGS VIEW ────────────────────────────────────────────────────────────
// eslint-disable-next-line no-unused-vars
function MspOrgsView({ token, toast }) {
  return (
    <div className="page">
      <div className="page-header">
        <div className="page-title">MSP Orgs</div>
      </div>
      <div className="cert-detail" style={{ textAlign: "center", padding: "3rem 2rem" }}>
        <div style={{ fontSize: "2rem", marginBottom: "1rem" }}>⬡</div>
        <div style={{ fontFamily: "var(--font-head)", fontSize: "1.1rem", marginBottom: "0.5rem" }}>
          Managed Client Organisations
        </div>
        <div style={{ color: "var(--muted)", fontSize: "0.82rem" }}>
          Provision and manage certificate monitoring for your client organisations.
        </div>
      </div>
    </div>
  );
}

// ─── INVITE ACCEPT SCREEN ────────────────────────────────────────────────────
function InviteAcceptScreen({ inviteToken, onAccepted, toast }) {
  const [step, setStep]     = useState("validating"); // validating | otp | error
  const [email, setEmail]   = useState("");
  const [otp, setOtp]       = useState("");
  const [errMsg, setErrMsg] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api.validateInvite(inviteToken)
      .then((res) => { setEmail(res.email); setStep("otp"); })
      .catch((e) => { setErrMsg(e.message); setStep("error"); });
  }, [inviteToken]);

  const handleAccept = async (ev) => {
    ev.preventDefault();
    if (!otp.trim()) return;
    setSubmitting(true);
    try {
      const res = await api.acceptInvite({ token: inviteToken, email, otp: otp.trim() });
      onAccepted(res.token);
    } catch (e) {
      setErrMsg(e.message);
      toast("Failed to accept invite: " + e.message, "error");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="launch">
      <div style={{ width: "100%", maxWidth: 400, margin: "auto", padding: "2rem" }}>
        <div className="wordmark" style={{ marginBottom: "2rem", textAlign: "center" }}>
          <span className="wordmark-cert">Cert</span><span className="wordmark-guard">Guard</span>
        </div>

        {step === "validating" && (
          <div className="loading-center">
            <Spinner lg />
            <span>Validating invite...</span>
          </div>
        )}

        {step === "error" && (
          <div className="cert-detail" style={{ textAlign: "center" }}>
            <div style={{ color: "var(--red)", marginBottom: "1rem", fontSize: "1.1rem" }}>Invalid or expired invite</div>
            <div style={{ color: "var(--muted)", fontSize: "0.82rem", marginBottom: "1.5rem" }}>{errMsg}</div>
            <button className="btn btn-secondary" onClick={() => window.location.replace("/")}>Go to Login</button>
          </div>
        )}

        {step === "otp" && (
          <div className="cert-detail">
            <div style={{ fontFamily: "var(--font-head)", fontSize: "1.1rem", marginBottom: "0.5rem" }}>
              You&apos;ve been invited
            </div>
            <div style={{ color: "var(--muted)", fontSize: "0.82rem", marginBottom: "1.5rem" }}>
              A one-time code was sent to <strong style={{ color: "var(--text)" }}>{email}</strong>. Enter it below to accept your invitation.
            </div>
            <form onSubmit={handleAccept}>
              <div className="field">
                <label>One-Time Code</label>
                <input type="text" value={otp} autoFocus
                  onChange={(e) => setOtp(e.target.value)} placeholder="123456"
                  style={{ letterSpacing: "0.2em", fontSize: "1.1rem", textAlign: "center" }} />
              </div>
              {errMsg && <div style={{ fontSize: "0.72rem", color: "var(--red)", marginTop: 4 }}>{errMsg}</div>}
              <button type="submit" className="btn btn-primary" style={{ width: "100%", marginTop: "0.5rem" }}
                disabled={submitting || !otp.trim()}>
                {submitting ? <Spinner /> : "Accept Invitation"}
              </button>
            </form>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── APP ROOT ─────────────────────────────────────────────────────────────────
export default function App() {
  const [token, setToken]     = useState(null);
  const [orgData, setOrgData] = useState(null);
  const [meData, setMeData]   = useState(null);
  const [, setTargets] = useState(null);
  const [phase, setPhase]     = useState("launch"); // launch | org-setup | first-target | app | invite
  const [loading, setLoading] = useState(false);
  const [inviteToken, setInviteToken] = useState(null);
  const { toasts, add: toast } = useToasts();

  const handleToken = async (t) => {
    setToken(t);
    setLoading(true);
    try {
      // Fetch org data and /me in parallel
      const [org, me] = await Promise.all([
        api.getOrg(t),
        api.getMe(t).catch(() => null), // non-fatal — degrade gracefully
      ]);
      setOrgData(org);
      setMeData(me);

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

  // Pick up the JWT after OAuth redirect (?token= on non-invite paths) or start invite
  // flow (?invite= anywhere, or ?token= when the path is /invite).
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const urlToken = params.get("token");
    const urlInvite = params.get("invite");
    const isInvitePath = window.location.pathname === "/invite";
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

  return (
    <>
      {phase === "launch"       && <LaunchScreen onToken={handleToken} />}
      {phase === "org-setup"    && <OrgSetup token={token} onDone={afterOrgSetup} toast={toast} />}
      {phase === "first-target" && <FirstTarget token={token} onDone={afterFirstTarget} toast={toast} />}
      {phase === "app"          && <Dashboard token={token} org={orgData} me={meData} toast={toast} onLogout={handleLogout} />}
      {phase === "invite"       && <InviteAcceptScreen inviteToken={inviteToken} onAccepted={handleToken} toast={toast} />}
      <Toast toasts={toasts} />
    </>
  );
}

// ─── PLATFORM ADMIN — ALL ORGS VIEW ─────────────────────────────────────────
function PlatformOrgsView({ token, toast, onManageOrg }) {
  const [orgs, setOrgs]         = useState([]);
  const [loading, setLoading]   = useState(true);
  const [apiUnavailable, setApiUnavailable] = useState(false);
  const [tab, setTab]           = useState("all"); // all | msps | single
  const [search, setSearch]     = useState("");

  useEffect(() => {
    let cancelled = false;
    const fetchOrgs = async () => {
      // setLoading already true from initial state
      try {
        const data = await api.admin.getOrgTree(token);
        if (!cancelled) {
          setOrgs(Array.isArray(data) ? data : (data?.content || []));
        }
      } catch (e) {
        if (!cancelled) {
          if (e.status === 404 || e.message?.includes("404")) {
            setApiUnavailable(true);
          } else {
            toast("Failed to load organisations: " + e.message, "error");
          }
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchOrgs();
    return () => { cancelled = true; };
  }, [token, toast]);

  if (loading) {
    return (
      <div className="loading-center" style={{ minHeight: "60vh" }}>
        <Spinner lg /><span>Loading organisations...</span>
      </div>
    );
  }

  if (apiUnavailable) {
    return (
      <>
        <div className="page-header">
          <div>
            <div className="page-title">All Organisations</div>
            <div className="page-sub">Platform-wide organisation management</div>
          </div>
        </div>
        <div className="page-content">
          <div className="admin-api-unavailable">
            <div style={{ fontSize: "2rem", marginBottom: "1rem", opacity: 0.4 }}>◫</div>
            <div style={{ fontFamily: "var(--font-head)", fontSize: "1rem", color: "var(--text)", marginBottom: "0.5rem" }}>
              Admin API not yet available
            </div>
            <div style={{ fontSize: "0.82rem" }}>
              The admin endpoints are not deployed in this environment. They will appear automatically once deployed.
            </div>
          </div>
        </div>
      </>
    );
  }

  // Flatten tree for display — each org may have children[] array for MSP clients
  const flatAll = [];
  const flattenOrg = (o, depth = 0) => {
    flatAll.push({ ...o, _depth: depth });
    if (Array.isArray(o.children)) o.children.forEach((c) => flattenOrg(c, depth + 1));
  };
  orgs.forEach((o) => flattenOrg(o));

  const q = search.trim().toLowerCase();
  const filtered = flatAll.filter((o) => {
    if (q && !o.name?.toLowerCase().includes(q)) return false;
    if (tab === "msps")   return o.orgType === "MSP";
    if (tab === "single") return o.orgType !== "MSP";
    return true;
  });

  return (
    <>
      <div className="page-header">
        <div>
          <div className="page-title">All Organisations</div>
          <div className="page-sub">Platform-wide organisation management ({flatAll.length} total)</div>
        </div>
        <div className="search-bar" style={{ minWidth: 200 }}>
          <span aria-hidden="true" style={{ color: "var(--muted)", fontSize: "0.8rem" }}>&#128269;</span>
          <input
            type="search"
            placeholder="Search orgs..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search organisations"
          />
        </div>
      </div>
      <div className="page-content">
        <div className="admin-tabs" role="tablist" aria-label="Organisation filter">
          {[
            { id: "all",    label: "All" },
            { id: "msps",   label: "MSPs" },
            { id: "single", label: "Single" },
          ].map(({ id, label }) => (
            <button
              key={id}
              className={`admin-tab ${tab === id ? "active" : ""}`}
              role="tab"
              aria-selected={tab === id}
              onClick={() => setTab(id)}
            >
              {label}
            </button>
          ))}
        </div>

        {filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">◫</div>
            <div className="empty-title">No organisations found</div>
            <p className="empty-sub">
              {q ? `No results for "${search}".` : "No organisations match the current filter."}
            </p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Parent</th>
                  <th>Targets</th>
                  <th>Agents</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((o) => (
                  <tr key={o.id}>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 6, paddingLeft: o._depth * 16 }}>
                        {o._depth > 0 && (
                          <span aria-hidden="true" style={{ color: "var(--border2)", fontSize: "0.8rem" }}>&#x2514;</span>
                        )}
                        <span className="host-cell">{o.name}</span>
                      </div>
                      {o.domain && <div className="mono" style={{ fontSize: "0.7rem", color: "var(--muted)", paddingLeft: o._depth * 16 + (o._depth > 0 ? 18 : 0) }}>{o.domain}</div>}
                    </td>
                    <td>
                      <Badge type={o.orgType === "MSP" ? "active" : "pending"}>{o.orgType || "SINGLE"}</Badge>
                    </td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: "0.78rem" }}>
                      {o.parentName || "—"}
                    </td>
                    <td className="mono">{o.targetCount ?? 0}</td>
                    <td className="mono">{o.agentCount ?? 0}</td>
                    <td>
                      <button
                        className="btn btn-secondary btn-sm"
                        onClick={() => onManageOrg(o.id, o.name)}
                        aria-label={`Manage organisation ${o.name}`}
                      >
                        Manage
                      </button>
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

// ─── PLATFORM ADMIN — ORG DETAIL VIEW ───────────────────────────────────────
// eslint-disable-next-line no-unused-vars
function PlatformOrgDetailView({ token, toast, actingAsOrgId, actingAsOrgName, onExit, me }) {
  const [detailTab, setDetailTab] = useState("targets");
  // Per-tab data
  const [targets, setTargets]     = useState([]);
  const [targetsLoading, setTargetsLoading] = useState(false);
  const [agents, setAgents]       = useState([]);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [members, setMembers]     = useState([]);
  const [membersLoading, setMembersLoading] = useState(false);
  const [locations, setLocations] = useState([]);
  const [locationsLoading, setLocationsLoading] = useState(false);
  const [scanning, setScanning]   = useState({});

  const opts = { actingAsOrgId, reason: "Platform admin inspection" };

  const loadTargets = useCallback(async () => {
    setTargetsLoading(true);
    try { const r = await api.getTargets(token, opts); setTargets(r?.content || []); }
    catch (e) { toast("Failed to load targets: " + e.message, "error"); }
    finally { setTargetsLoading(false); }
  }, [token, actingAsOrgId]);

  const loadAgents = useCallback(async () => {
    setAgentsLoading(true);
    try { setAgents(await api.listAgents(token, opts)); }
    catch (e) { toast("Failed to load agents: " + e.message, "error"); }
    finally { setAgentsLoading(false); }
  }, [token, actingAsOrgId]);

  const loadMembers = useCallback(async () => {
    setMembersLoading(true);
    try { setMembers(await api.listMembers(token, opts)); }
    catch (e) { toast("Failed to load members: " + e.message, "error"); }
    finally { setMembersLoading(false); }
  }, [token, actingAsOrgId]);

  const loadLocations = useCallback(async () => {
    setLocationsLoading(true);
    try { setLocations(await api.listLocations(token, opts)); }
    catch (e) { toast("Failed to load locations: " + e.message, "error"); }
    finally { setLocationsLoading(false); }
  }, [token, actingAsOrgId]);

  useEffect(() => { if (detailTab === "targets")   loadTargets();   }, [detailTab, loadTargets]);
  useEffect(() => { if (detailTab === "agents")    loadAgents();    }, [detailTab, loadAgents]);
  useEffect(() => { if (detailTab === "members")   loadMembers();   }, [detailTab, loadMembers]);
  useEffect(() => { if (detailTab === "locations") loadLocations(); }, [detailTab, loadLocations]);

  const triggerScan = async (target) => {
    setScanning((s) => ({ ...s, [target.id]: true }));
    try {
      await api.scanTarget(target.id, token, opts);
      toast(`Scan triggered for ${target.host}`, "info");
      setTimeout(() => { loadTargets(); setScanning((s) => ({ ...s, [target.id]: false })); }, 8000);
    } catch (e) {
      toast("Scan failed: " + e.message, "error");
      setScanning((s) => ({ ...s, [target.id]: false }));
    }
  };

  const agentStatusBadgeType = (s) =>
    ({ ACTIVE: "active", PENDING: "pending", OFFLINE: "offline", REVOKED: "revoked" }[s] || "pending");

  const inviteStatusBadgeType = (s) =>
    ({ ACCEPTED: "active", PENDING: "pending", REVOKED: "revoked" }[s] || "unknown");

  return (
    <>
      <div className="page-header">
        <div>
          <button
            className="btn-ghost"
            style={{ padding: 0, fontSize: "0.78rem", marginBottom: 6, display: "flex", alignItems: "center", gap: 4 }}
            onClick={onExit}
            aria-label="Back to All Orgs"
          >
            &#8592; Back to All Orgs
          </button>
          <div className="page-title">{actingAsOrgName || actingAsOrgId}</div>
          <div className="page-sub">Inspecting organisation — all actions are logged</div>
        </div>
      </div>
      <div className="page-content">
        <div className="admin-tabs" role="tablist" aria-label="Organisation detail tabs">
          {[
            { id: "targets",   label: "Targets"   },
            { id: "agents",    label: "Agents"    },
            { id: "members",   label: "Members"   },
            { id: "locations", label: "Locations" },
          ].map(({ id, label }) => (
            <button
              key={id}
              className={`admin-tab ${detailTab === id ? "active" : ""}`}
              role="tab"
              aria-selected={detailTab === id}
              onClick={() => setDetailTab(id)}
            >
              {label}
            </button>
          ))}
        </div>

        {detailTab === "targets" && (
          targetsLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading targets...</span></div>
          ) : targets.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">🎯</div>
              <div className="empty-title">No targets</div>
              <p className="empty-sub">This organisation has no monitored targets.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Host</th>
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
                        <td><Badge type={hostTypeColor(t.hostType)}>{t.hostType || "—"}</Badge></td>
                        <td>
                          <Badge type={t.isPrivate ? "private" : "public"}>
                            {t.isPrivate ? "Private" : "Public"}
                          </Badge>
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
                          <button
                            className={`scan-btn ${scanning[t.id] ? "scanning" : ""}`}
                            onClick={() => triggerScan(t)}
                            disabled={scanning[t.id]}
                            title="Trigger scan"
                          >
                            {scanning[t.id] ? <Spinner /> : "⟳"}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
        )}

        {detailTab === "agents" && (
          agentsLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading agents...</span></div>
          ) : agents.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">⬡</div>
              <div className="empty-title">No agents</div>
              <p className="empty-sub">This organisation has no registered agents.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Status</th>
                    <th>Targets</th>
                    <th>Last Seen</th>
                    <th>Registered</th>
                  </tr>
                </thead>
                <tbody>
                  {agents.map((a) => (
                    <tr key={a.id}>
                      <td className="host-cell">{a.name}</td>
                      <td><Badge type={agentStatusBadgeType(a.status)}>{a.status}</Badge></td>
                      <td className="mono">{a.currentTargetCount ?? 0} / {a.maxTargets}</td>
                      <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>{fmtRelative(a.lastSeenAt)}</td>
                      <td className="mono" style={{ color: "var(--muted)", fontSize: "0.75rem" }}>{fmtDate(a.registeredAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}

        {detailTab === "members" && (
          membersLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading members...</span></div>
          ) : members.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">◎</div>
              <div className="empty-title">No members</div>
              <p className="empty-sub">This organisation has no team members.</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Member</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Joined</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.id}>
                      <td>
                        <div style={{ fontWeight: 500 }}>{m.name || m.email}</div>
                        {m.name && <div className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{m.email}</div>}
                      </td>
                      <td><Badge type={m.role === "ADMIN" ? "active" : "pending"}>{m.role}</Badge></td>
                      <td><Badge type={inviteStatusBadgeType(m.inviteStatus)}>{m.inviteStatus}</Badge></td>
                      <td className="mono" style={{ fontSize: "0.72rem", color: "var(--muted)" }}>{fmtDate(m.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}

        {detailTab === "locations" && (
          locationsLoading ? (
            <div className="loading-center"><Spinner lg /><span>Loading locations...</span></div>
          ) : locations.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">📍</div>
              <div className="empty-title">No locations</div>
              <p className="empty-sub">This organisation has no configured locations.</p>
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
                  </tr>
                </thead>
                <tbody>
                  {locations.map((loc) => (
                    <tr key={loc.id}>
                      <td className="host-cell">{loc.name}</td>
                      <td><Badge type={providerColor(loc.provider)}>{providerLabel(loc.provider)}</Badge></td>
                      <td className="mono">{loc.geoRegion || "—"}</td>
                      <td className="mono">{loc.cloudRegion || "—"}</td>
                      <td className="mono">{loc.targetCount ?? 0}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
      </div>
    </>
  );
}

// ─── LOCATIONS VIEW ──────────────────────────────────────────────────────────
const PROVIDERS = ["AWS", "AZURE", "GCP", "COLOCATION", "ON_PREM"];
const providerLabel = (p) => ({ AWS: "AWS", AZURE: "Azure", GCP: "GCP", COLOCATION: "Colocation", ON_PREM: "On-Prem" }[p] || p);
const providerColor = (p) => ({ AWS: "yellow", AZURE: "blue", GCP: "green", COLOCATION: "purple", ON_PREM: "orange" }[p] || "unknown");

function LocationsView({ locations, loading, token, onRefresh, toast, me }) {
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
