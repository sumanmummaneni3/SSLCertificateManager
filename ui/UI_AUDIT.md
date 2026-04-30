# CertGuard UI Audit

**Date:** 2026-04-19  
**Auditor:** CertGuard Frontend Engineer  
**Scope:** `/Users/suman/git/certguard-ui/src/certguard-ui.jsx` (single-file React app)

---

## 1. Layout & Visual Consistency

| Finding | Severity | Notes |
|---|---|---|
| All CSS is injected via `document.createElement("style")` at module level, bypassing Vite's build graph and making style deduplication/HMR fragile | High | Should be a `.css` file or CSS Modules |
| `index.css` and `App.css` are imported but override the injected styles unpredictably (both files contain `:root` color and `body` rules that conflict with the inline `styles` string) | High | `index.css` has `color: rgba(255,255,255,0.87)` while the inline block sets `color: var(--text)` — whichever loads last wins |
| Sidebar `THEMES` only patches `--sb-*` variables; main content (`--bg`, `--surface`, `--text`, etc.) is always dark regardless of chosen sidebar theme | High | The "Light/offwhite" swatch makes the sidebar cream but leaves the entire right pane black |
| No light-mode support for main content area; `prefers-color-scheme: light` in `index.css` only affects `index.css` defaults, not the injected styles | High | |
| Spacing is consistent (rem-based scale, 0.5 / 1 / 1.5 / 2rem rhythm) | Good | |
| Typography uses `Syne` + `DM Mono` loaded from Google Fonts with no fallback timeout handling | Low | Network failure leaves bare sans-serif |

---

## 2. Accessibility (WCAG 2.1 AA)

| Finding | Severity | Notes |
|---|---|---|
| Nav items rendered as `<div>` with `onClick` — not keyboard-navigable (no `tabIndex`, no `role="button"` or `<button>`) | High | Users cannot reach navigation via keyboard |
| Toast container has no `role="status"` or `aria-live` — screen readers will not announce toasts | High | |
| Form `<label>` elements are not associated with inputs via `htmlFor`/`id` — labels are visually present but programmatically unlinked | High | All field labels in every modal and form |
| Modal lacks `role="dialog"`, `aria-modal="true"`, `aria-labelledby` | High | |
| Modal does not trap focus — Tab escapes the modal to behind-the-overlay content | High | |
| Theme swatches are `<div>` with no `aria-label` or `role="button"` | Medium | |
| `<img>` logo SVGs from `App.jsx` have `alt` text; lock/globe/org emojis used as informational indicators have no `aria-label` | Medium | |
| Color contrast: `--muted` (`#5a6070`) on `--bg` (`#0a0c0f`) = ~3.8:1, fails AA for normal text | High | Affects `.nav-section`, `.stat-label`, `.page-sub`, many `<th>` cells |
| `scan-btn` disabled state has no `:focus-visible` ring | Medium | |
| Spinner has no `aria-label="Loading"` | Low | |

---

## 3. Empty & Error States

| Finding | Severity | Notes |
|---|---|---|
| Dashboard, Targets, Certs, and Agents all have meaningful empty states with icon + title + CTA | Good | |
| Error state for dashboard load is a toast only — if load fails the user sees a blank sidebar with no recovery path shown inline | Medium | Should render an inline error with a retry button |
| API errors surfaced as raw `e.message` (may expose internal stack traces or server error text) | Medium | Should sanitize before displaying |
| No network-offline detection | Low | |

---

## 4. Loading States

| Finding | Severity | Notes |
|---|---|---|
| Dashboard has a full-page spinner during initial load | Good | |
| Certs and Agents views have inline loading spinners | Good | |
| No skeleton loaders — layout shift on data arrival | Low | |
| `Dashboard` component passes `theme`/`onTheme` to `Sidebar` only in the loading branch; the main `return` passes neither, so theme picker disappears once data loads | **Bug** | `theme` and `onTheme` props are dropped in the non-loading render path |

---

## 5. Interaction States

| Finding | Severity | Notes |
|---|---|---|
| Buttons have hover + disabled states | Good | |
| `scan-btn` has active scanning state with pulse animation | Good | |
| No `:focus-visible` ring on `.nav-item` (rendered as `<div>`) | High | |
| `.btn-ghost` has no focus ring definition | Medium | |
| `.theme-swatch` has no focus ring | Low | |

---

## 6. Security

| Finding | Severity | Notes |
|---|---|---|
| No `dangerouslySetInnerHTML` usage — XSS safe | Good | |
| API token stored only in React state (lost on refresh — user must re-login, which is acceptable) | Good | |
| `pre` blocks rendering config snippets use static strings, not user data — safe | Good | |

---

## 7. Performance

| Finding | Severity | Notes |
|---|---|---|
| Entire app is one 2000-line JSX file — no code splitting | Medium | First load parses everything |
| Tables slice to 10 rows on Dashboard but full lists on Targets/Certs — no pagination/virtualization for large datasets | Medium | |
| `useCallback` used correctly for `load`/`loadCerts`/`loadAgents` | Good | |

---

## Priority Recommendations

### P0 — Bugs
1. **Fix theme/onTheme prop drop** in `Dashboard` non-loading render (theme picker disappears after load)

### P1 — Accessibility (WCAG AA blockers)
2. **Add `role="button"` + `tabIndex={0}` + `onKeyDown` to nav items and theme swatches**
3. **Associate all `<label>` elements** with inputs via `htmlFor`/`id`
4. **Add `role="status"` + `aria-live="polite"` to toast container**
5. **Add `role="dialog"` + `aria-modal` + focus trap to modals**
6. **Fix `--muted` contrast** — lighten to at least `#7a8090` on dark background to reach 4.5:1

### P2 — Theme
7. **Implement proper light/dark global theme** — CSS custom property sets for both modes; system preference detection; localStorage persistence; toggle in sidebar/header
8. **Move styles out of JS string** into `src/index.css` or separate `.css` file

### P3 — UX Polish
9. **Add inline error state** on dashboard load failure with retry button
10. **Add `aria-label` to `Spinner`** components
11. **Sanitize API error messages** before displaying in toasts/alerts
