import { useEffect, useState, useCallback } from "react";
import { darkTokens, lightTokens } from "./tokens.js";
import { ThemeContext } from "./themeContext.js";

const LS_KEY = "cg-color-mode"; // "light" | "dark"

function getSystemPreference() {
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

function applyTokens(mode) {
  const tokens = mode === "light" ? lightTokens : darkTokens;
  const root = document.documentElement;
  for (const [prop, value] of Object.entries(tokens)) {
    root.style.setProperty(prop, value);
  }
  root.setAttribute("data-color-mode", mode);
}

export function ThemeProvider({ children }) {
  const [mode, setModeState] = useState(() => {
    return localStorage.getItem(LS_KEY) || getSystemPreference();
  });

  // Apply tokens whenever mode changes
  useEffect(() => {
    applyTokens(mode);
  }, [mode]);

  // Listen to system preference changes when user has not made a manual choice
  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: light)");
    const handler = (e) => {
      if (!localStorage.getItem(LS_KEY)) {
        setModeState(e.matches ? "light" : "dark");
      }
    };
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, []);

  const setMode = useCallback((next) => {
    localStorage.setItem(LS_KEY, next);
    setModeState(next);
  }, []);

  const toggle = useCallback(() => {
    setModeState((prev) => {
      const next = prev === "dark" ? "light" : "dark";
      localStorage.setItem(LS_KEY, next);
      return next;
    });
  }, []);

  return (
    <ThemeContext.Provider value={{ mode, setMode, toggle }}>
      {children}
    </ThemeContext.Provider>
  );
}
