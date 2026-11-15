/** Encabezado del área autenticada, con la misma jerarquía visual que el ingreso. */
export default function WorkspaceHeader({ label, title, highlight, description, compact = false }) {
  return (
    <header className={`workspace-page-header${compact ? " workspace-page-header-compact" : ""}`}>
      <div className="workspace-container workspace-heading">
        <p className="workspace-eyebrow">{label}</p>
        <h1>{title} {highlight && <span>{highlight}</span>}</h1>
        {description && <p className="workspace-description">{description}</p>}
      </div>
      <svg className="workspace-arches" viewBox="0 0 500 300" fill="none" aria-hidden="true">
        {Array.from({ length: 7 }, (_, index) => (
          <path key={index} d="M90 370V180a105 105 0 0 1 210 0v190"
            transform={`translate(${index * 26} ${index * -19}) rotate(-26 250 180)`} />
        ))}
      </svg>
    </header>
  );
}
