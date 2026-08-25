import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import {
  actionLabelFor,
  AUDIT_ACTION_LABELS,
  entityLabelFor,
  ENTITY_LABELS,
  formatDateTime,
  labelFor,
} from "../../lib/format.js";

const ACTION_OPTIONS = Object.entries(AUDIT_ACTION_LABELS).map(([value, label]) => ({
  value,
  label,
}));
const ENTITY_OPTIONS = Object.entries(ENTITY_LABELS).map(([value, label]) => ({ value, label }));

/** Registro de auditoría: la traza de quién tocó qué, con motivo y resultado. */
export default function AuditoriaPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({
    username: "",
    role: "",
    action: "",
    entityType: "",
    from: "",
    to: "",
  });

  const loader = useCallback(() => auditService.auditTrail(filters), [filters]);
  const { data: entries, loading, error } = useResource(loader, []);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    { key: "at", header: "Fecha/Hora", render: (row) => formatDateTime(row.at) },
    {
      key: "username",
      header: "Usuario",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.username}</span>
          <span className="text-[12px] text-neutral-400">{labelFor(row.role)}</span>
        </div>
      ),
    },
    { key: "action", header: "Acción", render: (row) => actionLabelFor(row.action) },
    {
      key: "entity",
      header: "Entidad",
      render: (row) => (
        <span className="text-neutral-600">
          {entityLabelFor(row.entity.type)}{" "}
          <span className="tabular-nums">
            {String(row.entity.id).length > 12 ? "" : `#${row.entity.id}`}
          </span>
        </span>
      ),
    },
    { key: "result", header: "Resultado", render: (row) => <StatusBadge status={row.result} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Registro"
      highlight="de auditoría"
      description="Quién hizo qué, sobre qué entidad, con qué motivo y qué valores cambió."
      breadcrumb={[{ id: "auditoria", label: "Auditoría" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar el registro">
          {error}
        </Alert>
      )}

      <Card
        title="Resultados"
        description="Hacé clic en un registro para ver los valores antes y después del cambio."
      >
        <FilterBar
          searchValue={filters.username}
          searchPlaceholder="Usuario…"
          onSearchChange={(value) => onFilterChange("username", value)}
          filters={[
            {
              name: "role",
              label: "Rol",
              options: [
                { value: "PERSONAL", label: "Personal de Rentas" },
                { value: "SUPERVISOR", label: "Supervisor" },
                { value: "CAJERO", label: "Cajero" },
                { value: "SISTEMA", label: "Sistema" },
              ],
            },
            { name: "action", label: "Acción", options: ACTION_OPTIONS },
            { name: "entityType", label: "Entidad", options: ENTITY_OPTIONS },
            { name: "from", label: "Desde", type: "date" },
            { name: "to", label: "Hasta", type: "date" },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />
        <DataTable
          columns={columns}
          rows={entries ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="ScrollText"
          emptyTitle="Sin registros"
          emptyDescription="Probá con otro usuario o quitá los filtros."
          onRowClick={(row) => navigate(`/auditor/auditoria/${row.id}`)}
        />
      </Card>
    </ModuleShell>
  );
}
