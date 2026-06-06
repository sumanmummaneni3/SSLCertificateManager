// LATENT BUG NOTE: providerColor returns "blue" for AZURE and "purple" for COLOCATION,
// but the CSS in global.css has no .badge-blue or .badge-purple class.
// Until those classes are added, AZURE and COLOCATION badges fall back to the base .badge style.
// File a CSS token task before shipping the Locations panel.

export const PROVIDERS = ["AWS", "AZURE", "GCP", "COLOCATION", "ON_PREM"];

export const providerLabel = (p) =>
  ({ AWS: "AWS", AZURE: "Azure", GCP: "GCP", COLOCATION: "Colocation", ON_PREM: "On-Prem" }[p] || p);

export const providerColor = (p) =>
  ({ AWS: "yellow", AZURE: "blue", GCP: "green", COLOCATION: "purple", ON_PREM: "orange" }[p] || "unknown");
