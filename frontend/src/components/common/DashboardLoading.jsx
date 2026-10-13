export default function DashboardLoading({ label, count = 4 }) {
  return (
    <div className="workspace-dashboard-loading" role="status" aria-label={label}>
      <span className="sr-only">{label}</span>
      <div className={`workspace-metrics${count === 3 ? " workspace-metrics-three" : ""}`} aria-hidden="true">
        {Array.from({ length: count }, (_, index) => (
          <div key={index} className="workspace-stat workspace-table-loading">
            <div className="workspace-skeleton-row"><span /></div>
            <div className="workspace-skeleton-row"><span /></div>
            <div className="workspace-skeleton-row"><span /></div>
          </div>
        ))}
      </div>
      <div className="workspace-table-loading" aria-hidden="true">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="workspace-skeleton-row"><span /><span /><span /></div>
        ))}
      </div>
    </div>
  );
}
