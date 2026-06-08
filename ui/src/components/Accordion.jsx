import { useState } from "react";

export function Accordion({ title, children }) {
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
