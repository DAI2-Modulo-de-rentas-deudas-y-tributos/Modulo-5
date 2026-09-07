import { useCallback, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { conceptDefinitions } from "../../services/mockDb.js";
import { formatCurrency, formatDate, formatDateTime } from "../../lib/format.js";

const CONCEPT_OPTIONS = conceptDefinitions.map((c) => ({ value: c.code, label: c.name }));

const TITLES = {
  totalSettled: "Total liquidado",
  totalCollected: "Total recaudado",
  pendingDebt: "Deuda pendiente",
  overdueDebt: "Deuda vencida",
  defaultedPlans: "Planes incumplidos",
};

/** Qué hay detrás de un indicador: las filas concretas que lo componen. */
export default function IndicadorDetallePage() {
  const { key } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [filters, setFilters] = useState({
    from: searchParams.get("from") ?? "",
    to: searchParams.get("to") ?? "",
    conceptCode: searchParams.get("conceptCode") ?? "",
    taxpayerId: "",
  });

  const loader = useCallback(
    () => auditService.indicatorBreakdown(key, filters),
    [key, filters],
  );
  const { data: breakdown, loading, error } = useResource(loader);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const title = TITLES[key] ?? "Indicador";
  const breadcrumb = [
    { id: "indicadores", label: "Indicadores", path: "/auditor/indicadores" },
    { id: "detalle", label: title },
  ];

  const columns = columnsFor(key, navigate);

  return (
    <ModuleShell
      label="Auditoría"
      title="Detalle"
      highlight={title}
      description="Las filas que suman el indicador, para poder verificarlo una por una."
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos abrir el indicador">
          {error}
        </Alert>
      )}

      {loading ? (
        <div className="flex items-center justify-center gap-3 py-14">
          <Spinner />
          <span className="text-[13px] text-neutral-400">Calculando…</span>
        </div>
      ) : (
        breakdown && (
          <>
            <section className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="rounded-xl border border-neutral-200 bg-white p-5">
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
                  Total
                </p>
                <p className="mt-2 text-[26px] font-extrabold tabular-nums leading-none text-[#0F2C59]">
                  {formatCurrency(breakdown.total)}
                </p>
              </div>
              <div className="rounded-xl border border-neutral-200 bg-white p-5">
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
                  Cantidad
                </p>
                <p className="mt-2 text-[26px] font-extrabold tabular-nums leading-none text-[#0F2C59]">
                  {breakdown.count}
                </p>
              </div>
            </section>

            <Card
              title="Filas que componen el indicador"
              description="Hacé clic en una fila para abrir su ficha completa."
            >
              <FilterBar
                searchValue={filters.taxpayerId}
                searchPlaceholder="Contribuyente, DNI o CUIT…"
                onSearchChange={(value) => onFilterChange("taxpayerId", value)}
                filters={[{ name: "conceptCode", label: "Concepto", options: CONCEPT_OPTIONS }]}
                values={filters}
                onFilterChange={onFilterChange}
              />
              <DataTable
                columns={columns.definition}
                rows={breakdown.rows}
                rowKey={columns.rowKey}
                emptyIconName="ChartColumn"
                emptyTitle="Sin filas"
                emptyDescription="Ningún registro compone este indicador con los filtros actuales."
                onRowClick={columns.onRowClick}
              />
            </Card>
          </>
        )
      )}
    </ModuleShell>
  );
}

/** Cada indicador se compone de una entidad distinta, con sus propias columnas. */
function columnsFor(key, navigate) {
  if (key === "totalCollected") {
    return {
      rowKey: (row) => row.id,
      onRowClick: (row) => navigate(`/auditor/pagos/${row.id}`),
      definition: [
        { key: "receiptNumber", header: "Comprobante" },
        { key: "taxpayerName", header: "Contribuyente" },
        {
          key: "amountPaid",
          header: "Importe",
          align: "right",
          render: (row) => formatCurrency(row.amountPaid),
        },
        { key: "paidAt", header: "Fecha", render: (row) => formatDateTime(row.paidAt) },
      ],
    };
  }

  if (key === "totalSettled") {
    return {
      rowKey: (row) => row.id,
      onRowClick: (row) => navigate(`/auditor/liquidaciones/${row.id}`),
      definition: [
        { key: "id", header: "Nº", render: (row) => <span className="tabular-nums">#{row.id}</span> },
        { key: "taxpayerName", header: "Contribuyente" },
        { key: "conceptName", header: "Concepto" },
        {
          key: "amount",
          header: "Importe",
          align: "right",
          render: (row) => formatCurrency(row.amount),
        },
        { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
      ],
    };
  }

  if (key === "defaultedPlans") {
    return {
      rowKey: (row) => row.requestId,
      onRowClick: (row) => navigate(`/auditor/planes/${row.requestId}`),
      definition: [
        {
          key: "requestId",
          header: "Nº",
          render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
        },
        { key: "taxpayerName", header: "Contribuyente" },
        { key: "installments", header: "Cuotas", align: "right" },
        {
          key: "outstandingAmount",
          header: "Saldo",
          align: "right",
          render: (row) => formatCurrency(row.outstandingAmount),
        },
        { key: "lifecycle", header: "Situación", render: (row) => <StatusBadge status={row.lifecycle} /> },
      ],
    };
  }

  return {
    rowKey: (row) => row.id,
    onRowClick: (row) => navigate(`/auditor/deudas/${row.id}`),
    definition: [
      { key: "id", header: "Nº", render: (row) => <span className="tabular-nums">#{row.id}</span> },
      { key: "taxpayerName", header: "Contribuyente" },
      { key: "conceptName", header: "Concepto" },
      {
        key: "outstandingAmount",
        header: "Saldo",
        align: "right",
        render: (row) => formatCurrency(row.outstandingAmount),
      },
      { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    ],
  };
}
