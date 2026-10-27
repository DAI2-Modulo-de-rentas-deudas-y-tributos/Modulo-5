import { useCallback, useState } from "react";
import { useLocation } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import Pagination from "../../components/common/Pagination.jsx";
import FormField from "../../components/ui/FormField.jsx";
import useResource from "../../hooks/useResource.js";
import { securityAuditService } from "../../services/domains/security.js";
import { formatDateTime, labelFor } from "../../lib/format.js";

export default function SeguridadPage() {
  const { pathname } = useLocation();
  const homePath = pathname.startsWith("/rentas") ? "/rentas" : "/auditor";
  const [filters, setFilters] = useState({ userId: "", action: "", from: "", to: "", page: 0 });
  const loader = useCallback(() => securityAuditService.list(filters), [filters]);
  const { data, loading, error, reload } = useResource(loader, []);
  const change = (event) => setFilters((current) => ({ ...current, [event.target.name]: event.target.value, page: 0 }));
  return <ModuleShell label="Control" title="Auditoría de seguridad" description="Altas de cuentas y acciones de seguridad registradas. Consulta de sólo lectura."
    homePath={homePath} breadcrumb={[{ id: "seguridad", label: "Auditoría de seguridad" }]}>
    <Card title="Registros de seguridad" description="Se muestran los eventos de cuentas que el sistema registra actualmente.">
      <div className="grid gap-3 border-b border-neutral-100 p-5 sm:grid-cols-4">
        <FormField label="Usuario que realizó la acción" name="userId" value={filters.userId} onChange={change} />
        <FormField label="Acción" name="action" type="select" value={filters.action} onChange={change} options={[
          { value: "DEMO_USER_CREATED", label: "Alta de usuario" }, { value: "DEMO_BOOTSTRAP_COMPLETED", label: "Alta inicial de supervisor" },
        ]} />
        <FormField label="Desde" name="from" type="date" value={filters.from} onChange={change} max={filters.to || undefined} />
        <FormField label="Hasta" name="to" type="date" value={filters.to} onChange={change} min={filters.from || undefined} />
      </div>
      <DataTable rows={data ?? []} loading={loading} error={error} onRetry={reload} rowKey={(row) => row.id} emptyTitle="Sin registros de seguridad" columns={[
        { key: "at", header: "Fecha", render: (row) => formatDateTime(row.occurredAt ?? row.at) },
        { key: "userId", header: "Usuario", render: (row) => row.userId ?? row.username },
        { key: "role", header: "Rol", render: (row) => labelFor(row.userRole ?? row.role) },
        { key: "action", header: "Acción", render: (row) => ({ DEMO_USER_CREATED: "Alta de usuario", DEMO_BOOTSTRAP_COMPLETED: "Alta inicial de supervisor" })[row.action] ?? row.action },
        { key: "entityId", header: "Cuenta", render: (row) => `#${row.entityId ?? row.entity?.id}` },
      ]} />
      <Pagination page={data?.page} loading={loading} onPage={(page) => setFilters((current) => ({ ...current, page }))} />
    </Card>
  </ModuleShell>;
}
