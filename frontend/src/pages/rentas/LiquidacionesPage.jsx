import Can from "../../components/common/Can.jsx";
import useSettlementForm from "../../hooks/useSettlementForm.js";
import LiquidationPreview from "../../components/common/LiquidationPreview.jsx";
import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import useTaxConcepts from "../../hooks/useTaxConcepts.js";
import { settlementService } from "../../services/rentasService.js";
import GeneracionMasivaModal from "./GeneracionMasivaModal.jsx";
import { formatCurrency, formatDate, formatPercentage } from "../../lib/format.js";

/** La liquidación individual se emite y crea la deuda en una sola operación. */
export default function LiquidacionesPage() {
  const [filters, setFilters] = useState({ period: "", conceptCode: "", status: "" });
  const [creating, setCreating] = useState(false);
  const [batchOpen, setBatchOpen] = useState(false);
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(() => settlementService.list(filters), [filters]);
  const { data: settlements, loading, error, reload } = useResource(loader, []);
  const { nameOf, options: taxpayerOptions } = useTaxpayerIndex();
  const { options: conceptOptions, labelOf: conceptLabelOf } = useTaxConcepts();

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const columns = [
    { key: "id", header: "N°", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    {
      key: "taxpayer",
      header: "Contribuyente",
      render: (row) => nameOf(row.taxpayerId),
    },
    {
      key: "conceptCode",
      header: "Concepto",
      render: (row) => conceptLabelOf(row.conceptCode),
    },
    { key: "period", header: "Período" },
    {
      key: "discountPercentage",
      header: "Desc.",
      align: "right",
      render: (row) =>
        row.discountPercentage > 0 ? (
          <StatusBadge tone="success" label={formatPercentage(row.discountPercentage)} />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Operación"
      title="Liquidaciones"
      highlight="del período"
      description="Al generar una liquidación individual, se emite y crea su deuda inmediatamente."
      breadcrumb={[{ id: "liquidaciones", label: "Liquidaciones" }]}
    >
      {feedback && (
        <Alert
          variant={feedback.variant}
          title={feedback.title}
          onDismiss={() => setFeedback(null)}
        >
          {feedback.message}
        </Alert>
      )}


      <Card
        title="Liquidaciones registradas"
        description="Cada liquidación individual registrada ya tiene una deuda asociada."
        actions={
          <>
            <Can permission="settlement.generate"><Button size="sm" variant="secondary" onClick={() => setBatchOpen(true)}>
              Generación masiva
            </Button></Can>
            <Can permission="settlement.generate"><Button size="sm" variant="primary" onClick={() => setCreating(true)}>
              Nueva liquidación
            </Button></Can>
          </>
        }
      >
        <FilterBar
          filters={[
            {
              name: "conceptCode",
              label: "Concepto",
              options: conceptOptions,
            },
            {
              name: "status",
              label: "Estado",
              options: [{ value: "ISSUED", label: "Emitida" }],
            },
            {
              name: "period",
              label: "Período",
              options: [...new Set([filters.period, ...(settlements ?? []).map((item) => item.period)].filter(Boolean))]
                .sort().reverse().map((period) => ({ value: period, label: period })),
            },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />

        <DataTable error={error} onRetry={reload}
          columns={columns}
          rows={settlements ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Calculator"
          emptyTitle="Sin liquidaciones"
          emptyDescription="Ajustá los filtros o generá una liquidación nueva."
        />
      </Card>

      <NewSettlementModal
        open={creating}
        taxpayerOptions={taxpayerOptions}
        conceptOptions={conceptOptions}
        onClose={() => setCreating(false)}
        onCreated={(settlement) => {
          setCreating(false);
          setFeedback({
            variant: "success",
            title: "Liquidación emitida",
            message: `#${settlement.id} por ${formatCurrency(settlement.amount)}. La deuda ya está disponible en el estado de cuenta.`,
          });
          reload();
        }}
      />

      {batchOpen && (
        <GeneracionMasivaModal
          conceptOptions={conceptOptions}
          onClose={() => setBatchOpen(false)}
          onGenerated={(result) => {
            setBatchOpen(false);
            setFeedback({
              variant: "success",
              title: "Corrida enviada a aprobación",
              message: `Corrida #${result.run.id} con ${result.submitted} liquidaciones previstas. El Supervisor debe aprobarla y luego Rentas ejecutarla para crear las deudas.`,
            });
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** GenerateSettlementRequest: contribuyente + concepto + período + base imponible. */
function NewSettlementModal({ open, taxpayerOptions, conceptOptions, onClose, onCreated }) {
  const { form, errors, preview, onChange, submitting, submitError, onSubmit, onPreview } = useSettlementForm(onCreated);

  return (
    <Modal
      open={open}
      title="Nueva liquidación"
      description="El descuento por beneficio social se aplica automáticamente."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" disabled={submitting} onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="secondary" disabled={submitting} onClick={onPreview}>Previsualizar</Button>
          <Can permission="settlement.generate"><Button variant="primary" loading={submitting} onClick={onSubmit}>
          Generar liquidación y deuda
          </Button></Can>
        </>
      }
    >
      {submitError && <Alert variant="error" title="No se pudo generar">{submitError}</Alert>}
      {preview && <LiquidationPreview preview={preview} taxableBase={form.baseAmount} />}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <fieldset disabled={submitting} className="contents">
        <FormField
          label="Contribuyente"
          name="taxpayerId"
          type="select"
          value={form.taxpayerId}
          onChange={onChange}
          options={taxpayerOptions}
          error={errors.taxpayerId}
          required
        />
        <FormField
          label="Concepto"
          name="conceptCode"
          type="select"
          value={form.conceptCode}
          onChange={onChange}
          options={conceptOptions}
          error={errors.conceptCode}
          required
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            label="Período fiscal"
            name="period"
            placeholder="2026-09"
            value={form.period}
            onChange={onChange}
            error={errors.period}
            required
          />
          <FormField
            label="Vencimiento"
            name="dueDate"
            type="date"
            value={form.dueDate}
            onChange={onChange}
            error={errors.dueDate}
            required
          />
        </div>
        <FormField
          label="Base imponible"
          name="baseAmount"
          type="number"
          placeholder="120000.00"
          value={form.baseAmount}
          onChange={onChange}
          error={errors.baseAmount}
          required
        />
        </fieldset>
      </form>
    </Modal>
  );
}
