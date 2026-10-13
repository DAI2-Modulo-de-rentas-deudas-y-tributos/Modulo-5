import { useNavigate } from "react-router-dom";
import Breadcrumb from "../ui/Breadcrumb.jsx";
import PageHeader from "./WorkspaceHeader.jsx";

/**
 * Encabezado común de cada módulo funcional y migas de pan del kit compartido.
 * `breadcrumb`: [{ id, label, path }] — el último ítem es la página actual.
 * `homePath` / `homeLabel`: raíz del área de trabajo que abrió la página.
 */
export default function ModuleShell({
  label,
  title,
  highlight,
  description,
  breadcrumb = [],
  homePath = "/rentas",
  homeLabel = "Panel de Rentas",
  actions,
  children,
}) {
  const navigate = useNavigate();

  const onNavigate = (index) => {
    if (index === -1) {
      navigate(homePath);
      return;
    }
    const target = breadcrumb[index];
    if (target?.path) navigate(target.path);
  };

  return (
    <>
      <PageHeader label={label} title={title} highlight={highlight} description={description} compact />

      <div className="workspace-container workspace-content">
        {(breadcrumb.length > 0 || actions) && (
          <div className="workspace-content-toolbar">
            {breadcrumb.length > 0 && (
              <Breadcrumb items={breadcrumb} onNavigate={onNavigate} homeLabel={homeLabel} />
            )}
            {actions && <div className="workspace-actions">{actions}</div>}
          </div>
        )}
        {children}
      </div>
    </>
  );
}
