import { useNavigate } from "react-router-dom";
import Breadcrumb from "../ui/Breadcrumb.jsx";
import PageHeader from "../ui/PageHeader.jsx";

/**
 * Encabezado común de cada módulo funcional: migas de pan + PageHeader del kit.
 * `breadcrumb`: [{ id, label, path }] — el último ítem es la página actual.
 */
export default function ModuleShell({
  label,
  title,
  highlight,
  description,
  breadcrumb = [],
  children,
}) {
  const navigate = useNavigate();

  const onNavigate = (index) => {
    if (index === -1) {
      navigate("/rentas");
      return;
    }
    const target = breadcrumb[index];
    if (target?.path) navigate(target.path);
  };

  return (
    <>
      <PageHeader label={label} title={title} highlight={highlight} description={description} />

      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-5 py-8">
        {breadcrumb.length > 0 && (
          <Breadcrumb items={breadcrumb} onNavigate={onNavigate} homeLabel="Panel de Rentas" />
        )}
        {children}
      </div>
    </>
  );
}
