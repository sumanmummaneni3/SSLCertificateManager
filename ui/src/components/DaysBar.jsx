import { daysColor, daysWidth } from "@/lib/helpers.js";

export function DaysBar({ days }) {
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
