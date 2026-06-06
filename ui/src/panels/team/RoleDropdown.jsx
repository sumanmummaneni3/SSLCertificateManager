import { useState } from "react";
import { api } from "@/lib/api.js";

export function RoleDropdown({ member, token, onChanged, toast }) {
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
