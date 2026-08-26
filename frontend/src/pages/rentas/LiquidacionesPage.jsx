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
import { settlementService } from "../../services/rentasService.js";
import GeneracionMasivaModal from "./GeneracionMasivaModal.jsx";
import { CONCEPTS } from "../../services/mockDb.js";
import { formatCurrency, formatDate, formatPercentage } from "../../lib/format.js";

const CONCEPT_OPTIONS = CONCEPTS.filter((c) =>
  ["TASA_SERVICIOS", "ABL", "PATENTE"].includes(c.code),
).map((c) => ({ value: c.code, label: c.label }));

/**
 * Liquidaciones: primer paso del flujo. Se genera en borrador, se revisa el
 * descuento aplicado por beneficio social y recién al emitirla nace la deuda.
 */
export default function LiquidacionesPage() {
  const [filters, setFilters] = useState({ period: "", conceptCode: "", status: "" });
  const [creating, setCreating] = useState(false);
  const [batchOpen, setBatchOpen] = useState(false);
  const [feedback, setFeedback] = useState(null);
  const [issuing, setIssuing] = useState(null);

  const loader = useCallback(() => settlementService.list(filters), [filters]);
  const { data: settlements, loading, error, reload } = useResource(loader, []);
  const { nameOf, options: taxpayerOptions } = useTaxpayerIndex();

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const onIssue = async (settlement) => {
    setIssuing(settlement.id);
    setFeedback(null);
    try {
      await settlementService.issue(settlement.id);
      setFeedback({
        variant: "success",
        title: "Liquidación emitida",
        message: `Se generó la deuda asociada a la liquidación #${settlement.id}.`,
      });
      reload();
    } catch (caught) {
      setFeedback({ variant: "error", title: "No se pudo emitir", message: caught.message });
    } finally {
      setIssuing(null);
    }
  };

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
      render: (row) => CONCEPTS.find((c) => c.code === row.conceptCode)?.label ?? row.conceptCode,
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
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        row.status === "DRAFT" ? (
          <Button
            size="sm"
            variant="accent"
            loading={issuing === row.id}
            onClick={() => onIssue(row)}
          >
            Emitir
          </Button>
        ) : null,
    },
  ];

  return (
    <ModuleShell
      label="Operación"
      title="Liquidaciones"
      highlight="del período"
      description="Generá la liquidación de un concepto, revisá el descuento aplicado y emitila para que genere deuda."
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
      {error && <Alert variant="error" title="No pudimos cargar las liquidaciones">{error}</Alert>}

      <Card
        title="Liquidaciones registradas"
        description="Las liquidaciones en borrador no generan deuda hasta ser emitidas."
        actions={
          <>
            <Button size="sm" variant="secondary" onClick={() => setBatchOpen(true)}>
              Generación masiva
            </Button>
            <Button size="sm" variant="primary" onClick={() => setCreating(true)}>
              Nueva liquidación
            </Button>
          </>
        }
      >
        <FilterBar
          filters={[
            {
              name: "conceptCode",
              label: "Concepto",
              options: CONCEPT_OPTIONS,
            },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "DRAFT", label: "Borrador" },
                { value: "ISSUED", label: "Emitida" },
                { value: "SETTLED", label: "Cancelada" },
              ],
            },
            {
              name: "period",
              label: "Período",
              options: [
                { value: "2026-07", label: "2026-07" },
                { value: "2026-08", label: "2026-08" },
                { value: "2026-09", label: "2026-09" },
              ],
            },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />

        <DataTable
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
        onClose={() => setCreating(false)}
        onCreated={(settlement) => {
          setCreating(false);
          setFeedback({
            variant: "success",
            title: "Liquidación generada en borrador",
            message: `#${settlement.id} por ${formatCurrency(settlement.amount)}. Revisala y emitila para generar la deuda.`,
          });
          reload();
        }}
      />

      {batchOpen && (
        <GeneracionMasivaModal
          onClose={() => setBatchOpen(false)}
          onGenerated={(result) => {
            setBatchOpen(false);
            setFeedback({
              variant: "success",
              title: "Lote generado",
              message: `${result.generated.length} liquidaciones en borrador por ${formatCurrency(result.totals.amount)}. Emitilas para que generen deuda.`,
            });
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

/** GenerateSettlementRequest: contribuyente + concepto + período + base imponible. */
function NewSettlementModal({ open, taxpayerOptions, onClose, onCreated }) {
  const empty = {
    taxpayerId: "",
    conceptCode: "",
    period: "",
    baseAmount: "",
    dueDate: "",
  };
  const [form, setForm] = useState(empty);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
  };

  const validate = () => {
    const found = {};
    if (!form.taxpayerId) found.taxpayerId = "Seleccioná el contribuyente.";
    if (!form.conceptCode) found.conceptCode = "Seleccioná el concepto.";
    if (!/^\d{4}-\d{2}$/.test(form.period)) found.period = "Usá el formato AAAA-MM.";
    if (!(Number(form.baseAmount) > 0)) found.baseAmount = "Ingresá un importe mayor a cero.";
    if (!form.dueDate) found.dueDate = "Indicá el vencimiento.";
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setSubmitError(null);
    if (!validate()) return;
    setSubmitting(true);
    try {
      const settlement = await settlementService.generate(form);
      setForm(empty);
      onCreated(settlement);
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Nueva liquidación"
      description="El descuento por beneficio social se aplica automáticamente."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Generar borrador
          </Button>
        </>
      }
    >
      {submitError && <Alert variant="error" title="No se pudo generar">{submitError}</Alert>}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
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
          options={CONCEPT_OPTIONS}
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
      </form>
    </Modal>
  );
}
