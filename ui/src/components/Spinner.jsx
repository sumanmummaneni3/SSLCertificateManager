export function Spinner({ lg }) {
  return (
    <div
      className={`spinner${lg ? " spinner-lg" : ""}`}
      role="status"
      aria-label="Loading"
    />
  );
}
