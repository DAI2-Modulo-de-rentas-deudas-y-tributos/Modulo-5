import { useCallback, useState } from "react";
import { useSearchParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { debtService } from "../../services/rentasService.js";
import { ORIGIN_TYPES } from "../../services/mockDb.js";
import { formatCurrency, formatDate, labelFor } from "../../lib/format.js";

const ORIGIN_LABELS = Object.fromEntries(ORIGIN_TYPES.map((o) => [o.value, o.label]));

/**
 * Deudas: originadas en liquidaciones propias o en eventos de otros módulos
 * (permitFeeGenerated y commercialFineGenerated de M4, infractionConfirmed de M7).
 * La referencia al origen es lógica — originType + originId — sin FK física.
 */
export default function DeudasPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const taxpayerId = searchParams.get("taxpayerId") ?? "";
  const [filters, setFilters] = useState({ status: "", originType: "" });
  const [feedback, setFeedback] = useState(null);
  const [reporting, setReporting] = useState(null);

  const loader = useCallback(
    () => debtService.list({ ...filters, taxpayerId }),
    [filters, taxpayerId],
  );
  const { data: debts, loading, error, reload } = useResource(loader, []);
  const { nameOf, index } = useTaxpayerIndex();

  const rows = debts ?? [];
  const totalOutstanding = rows.reduce((acc, d) => acc + d.outstandingAmount, 0);
  const totalOverdue = rows
    .filter((d) => d.status === "OVERDUE")
    .reduce((acc, d) => acc + d.outstandingAmount, 0);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const onReportOverdue = async (debt) => {
    setReporting(debt.id);
    setFeedback(null);
    try {
      await debtService.reportOverdue(debt.id);
      setFeedback({
        variant: "success",
        title: "Deuda informada a Desarrollo Social",
        message: `Se publicó overdueDebt para la deuda #${debt.id}.`,
      });
      reload();
    } catch (caught) {
      setFeedback({ variant: "error", title: "No se pudo informar", message: caught.message });
    } finally {
      setReporting(null);
    }
  };

  const columns = [
    { key: "id", header: "N°", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    {
      key: "origin",
      header: "Origen",
      render: (row) => (
        <div className="flex flex-col">
          <span className="text-neutral-700">{ORIGIN_LABELS[row.originType] ?? row.originType}</span>
          <span className="text-[12px] text-neutral-400 tabular-nums">
            {row.originType}:{row.originId}
          </span>
        </div>
      ),
    },
    { key: "conceptCode", header: "Concepto" },
    {
      key: "outstandingAmount",
      header: "Saldo",
      align: "right",
      render: (row) => (
        <span className={row.outstandingAmount > 0 ? "font-semibold text-neutral-800" : ""}>
          {formatCurrency(row.outstandingAmount)}
        </span>
      ),
    },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        row.status === "OVERDUE" &&
        (row.reportedToM8 ? (
          <StatusBadge tone="info" label="Informada a M8" />
        ) : (
          <Button
            size="sm"
            variant="secondary"
            loading={reporting === row.id}
            onClick={() => onReportOverdue(row)}
          >
            Informar a M8
          </Button>
        )),
    },
  ];

  return (
    <ModuleShell
      label="Operación"
      title="Deudas"
      highlight="por contribuyente"
      description="Saldo vigente y vencido, con la referencia lógica al módulo que originó cada obligación."
      breadcrumb={[{ id: "deudas", label: "Deudas" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar las deudas">{error}</Alert>}

      {taxpayerId && (
        <Alert variant="info" title={`Filtrando por ${index[taxpayerId]?.name ?? `contribuyente #${taxpayerId}`}`}>
          <button
            type="button"
            className="font-semibold underline"
            onClick={() => setSearchParams({})}
          >
            Ver todas las deudas
          </button>
        </Alert>
      )}

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatTile label="Deudas listadas" value={rows.length} iconName="FileWarning" />
        <StatTile
          label="Saldo total"
          value={formatCurrency(totalOutstanding)}
          iconName="Wallet"
        />
        <StatTile
          label="Vencido"
          value={formatCurrency(totalOverdue)}
          iconName="TrendingDown"
          tone="danger"
        />
      </section>

      <Card
        title="Detalle de deudas"
        description="La deuda vencida se informa a Desarrollo Social mediante el evento overdueDebt."
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "PENDING", label: labelFor("PENDING") },
                { value: "OVERDUE", label: labelFor("OVERDUE") },
                { value: "SETTLED", label: labelFor("SETTLED") },
              ],
            },
            { name: "originType", label: "Origen", options: ORIGIN_TYPES },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />

        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="FileWarning"
          emptyTitle="Sin deudas registradas"
          emptyDescription="No hay obligaciones que coincidan con los filtros aplicados."
        />
      </Card>
    </ModuleShell>
  );
}
