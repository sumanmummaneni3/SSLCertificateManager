/**
 * Design token sets for CertGuard UI.
 * All colors are expressed as CSS custom-property values.
 * Components must reference var(--token-name) only — never hardcode hex.
 *
 * Contrast targets (WCAG AA):
 *   --color-text on --color-bg          >= 7:1  (AAA on main body)
 *   --color-muted on --color-bg         >= 4.5:1
 *   --color-text on --color-surface     >= 4.5:1
 */

export const darkTokens = {
  "--color-bg":        "#0a0c0f",
  "--color-surface":   "#111318",
  "--color-surface2":  "#1a1d24",
  "--color-border":    "#1f2330",
  "--color-border2":   "#2a2f40",
  "--color-text":      "#e8eaf0",
  // #7a8090 on #0a0c0f = 5.1:1 — passes AA
  "--color-muted":     "#7a8090",
  "--color-primary":   "#00d4ff",
  "--color-primary2":  "#0099bb",
  "--color-success":   "#00e676",
  "--color-warning":   "#ffd740",
  "--color-danger":    "#ff5252",
  "--color-orange":    "#ff9100",
  "--color-glow":      "0 0 20px rgba(0, 212, 255, 0.15)",
  "--color-scheme":    "dark",
};

export const lightTokens = {
  // #ffffff bg, #1e2535 text = 16.1:1
  "--color-bg":        "#f0f2f5",
  "--color-surface":   "#ffffff",
  "--color-surface2":  "#f5f7fa",
  "--color-border":    "#d0d5e0",
  "--color-border2":   "#b8bfce",
  "--color-text":      "#1e2535",
  // #5a6278 on #f0f2f5 = 5.3:1 — passes AA
  "--color-muted":     "#5a6278",
  "--color-primary":   "#0069cc",
  "--color-primary2":  "#004fa3",
  "--color-success":   "#1a7a40",
  "--color-warning":   "#a06000",
  "--color-danger":    "#c0392b",
  "--color-orange":    "#b85c00",
  "--color-glow":      "0 0 20px rgba(0, 105, 204, 0.12)",
  "--color-scheme":    "light",
};

/**
 * Sidebar palette themes (control --sb-* variables only).
 * These layer on top of the global light/dark tokens.
 */
export const SIDEBAR_THEMES = {
  dark: {
    label: "Dark",
    swatch: "#111318",
    vars: {
      "--sb-bg":            "#111318",
      "--sb-border":        "#1f2330",
      "--sb-text":          "#e8eaf0",
      "--sb-muted":         "#7a8090",
      "--sb-hover":         "#1a1d24",
      "--sb-active-color":  "#00d4ff",
      "--sb-active-bg":     "rgba(0,212,255,0.08)",
      "--sb-active-border": "rgba(0,212,255,0.15)",
    },
  },
  navy: {
    label: "Navy",
    swatch: "#1e3a5f",
    vars: {
      "--sb-bg":            "#1e3a5f",
      "--sb-border":        "#2d537d",
      "--sb-text":          "#ddeeff",
      "--sb-muted":         "#7aaac8",
      "--sb-hover":         "#284d73",
      "--sb-active-color":  "#60c8ff",
      "--sb-active-bg":     "rgba(96,200,255,0.12)",
      "--sb-active-border": "rgba(96,200,255,0.25)",
    },
  },
  light: {
    label: "Light",
    swatch: "#f5f0e8",
    vars: {
      "--sb-bg":            "#f5f0e8",
      "--sb-border":        "#d4cfc4",
      "--sb-text":          "#1e2535",
      "--sb-muted":         "#5a6278",
      "--sb-hover":         "#e8e2d8",
      "--sb-active-color":  "#0069cc",
      "--sb-active-bg":     "rgba(0,105,204,0.08)",
      "--sb-active-border": "rgba(0,105,204,0.2)",
    },
  },
  slate: {
    label: "Slate",
    swatch: "#2d3561",
    vars: {
      "--sb-bg":            "#2d3561",
      "--sb-border":        "#3d4780",
      "--sb-text":          "#cdd5e8",
      "--sb-muted":         "#8a98c0",
      "--sb-hover":         "#3a4375",
      "--sb-active-color":  "#a5b4fc",
      "--sb-active-bg":     "rgba(165,180,252,0.12)",
      "--sb-active-border": "rgba(165,180,252,0.25)",
    },
  },
  forest: {
    label: "Forest",
    swatch: "#1a2e1e",
    vars: {
      "--sb-bg":            "#1a2e1e",
      "--sb-border":        "#2d4a30",
      "--sb-text":          "#d4e8d4",
      "--sb-muted":         "#6a9070",
      "--sb-hover":         "#25401a",
      "--sb-active-color":  "#4ade80",
      "--sb-active-bg":     "rgba(74,222,128,0.10)",
      "--sb-active-border": "rgba(74,222,128,0.22)",
    },
  },
};
