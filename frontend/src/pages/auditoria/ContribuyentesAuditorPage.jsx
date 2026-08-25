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
import { formatCurrency, labelFor } from "../../lib/format.js";

/** Padrón visto desde auditoría: quién es y cuánto debe, como entrada a su ficha. */
export default function ContribuyentesAuditorPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");

  const loader = useCallback(() => auditService.taxpayers({ query }), [query]);
  const { data: taxpayers, loading, error } = useResource(loader, []);

  const columns = [
    {
      key: "name",
      header: "Contribuyente",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.name}</span>
          <span className="text-[12px] text-neutral-400">{labelFor(row.type)}</span>
        </div>
      ),
    },
    {
      key: "document",
      header: "DNI / CUIT",
      render: (row) => (
        <div className="flex flex-col tabular-nums">
          <span>
            {row.documentType} {row.document}
          </span>
          <span className="text-[12px] text-neutral-400">{row.cuit}</span>
        </div>
      ),
    },
    {
      key: "totalDebt",
      header: "Deuda total",
      align: "right",
      render: (row) => formatCurrency(row.totalDebt),
    },
    {
      key: "overdueDebt",
      header: "Deuda vencida",
      align: "right",
      render: (row) => (
        <span className={row.overdueDebt > 0 ? "font-semibold text-[#D63031]" : ""}>
          {formatCurrency(row.overdueDebt)}
        </span>
      ),
    },
    { key: "status", header: "Situación", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Auditoría"
      title="Contribuyentes"
      highlight="del padrón"
      description="Buscá por DNI, CUIT o nombre y abrí la ficha con todo su historial fiscal."
      breadcrumb={[{ id: "contribuyentes", label: "Contribuyentes" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar el padrón">
          {error}
        </Alert>
      )}

      <Card title="Buscar contribuyente" description="Por DNI, CUIT, nombre o identificador local.">
        <FilterBar
          searchValue={query}
          searchPlaceholder="DNI / CUIT / Nombre…"
          onSearchChange={setQuery}
        />
        <DataTable
          columns={columns}
          rows={taxpayers ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="UserSearch"
          emptyTitle="Sin coincidencias"
          emptyDescription="Revisá el documento ingresado."
          onRowClick={(row) => navigate(`/auditor/contribuyentes/${row.id}`)}
        />
      </Card>
    </ModuleShell>
  );
}
