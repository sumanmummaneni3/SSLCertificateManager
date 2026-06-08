export function Badge({ type, children }) {
  return <span className={`badge badge-${type}`}>{children}</span>;
}
