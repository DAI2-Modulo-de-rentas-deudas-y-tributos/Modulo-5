import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import useResource from "../../hooks/useResource.js";
import { resetPlanConfigurationCache } from "../../hooks/usePlanConfiguration.js";
import { paymentPlanConfigurationService } from "../../services/rentasService.js";
import { formatDate, formatDateTime, formatPercentage } from "../../lib/format.js";

const today = () => new Date().toISOString().slice(0, 10);

const appliesToday = (configuration) =>
  configuration.active &&
  configuration.validFrom <= today() &&
  (!configuration.validUntil || configuration.validUntil >= today());

const currentOf = (configurations) =>
  [...configurations].filter(appliesToday).sort((a, b) => b.version - a.version)[0] ?? null;

/** Configuración versionada: una versión nueva no modifica las condiciones de planes otorgados. */
export default function ParametrosFinanciacionPage() {
  const [creating, setCreating] = useState(false);
  const [feedback, setFeedback] = useState(null);
  const loader = useCallback(() => paymentPlanConfigurationService.list(), []);
  const { data: configurations, loading, error, reload } = useResource(loader, []);
  const current = currentOf(configurations ?? []);

  const columns = [
    { key: "version", header: "Versión", render: (row) => <span className="font-semibold tabular-nums">v{row.version}</span> },
    {
      key: "validity",
      header: "Vigencia",
      render: (row) => `${formatDate(row.validFrom)} — ${row.validUntil ? formatDate(row.validUntil) : "sin fin"}`,
    },
    {
      key: "installments",
      header: "Cuotas",
      render: (row) => `${row.minimumInstallments} a ${row.maximumInstallments}`,
    },
    { key: "downPayment", header: "Anticipo mínimo", align: "right", render: (row) => formatPercentage(row.minimumDownPaymentPercentage) },
    { key: "interest", header: "Tasa", align: "right", render: (row) => formatPercentage(row.interestRate) },
    { key: "graceDays", header: "Gracia", render: (row) => `${row.graceDays} días` },
    { key: "maxOverdueInstallments", header: "Tolerancia", render: (row) => `${row.maxOverdueInstallments} cuotas` },
    {
      key: "status",
      header: "Estado",
      render: (row) => row.id === current?.id
        ? <StatusBadge tone="success" label="Vigente" />
        : <StatusBadge tone={row.active ? "neutral" : "danger"} label={row.active ? "Histórica" : "Inactiva"} />,
    },
  ];

  return (
    <ModuleShell
      label="Planes de pago"
      title="Parámetros de financiación"
      highlight="versionados"
      description="Definí las reglas que usan las simulaciones y conservá el historial aplicado a cada plan."
      breadcrumb={[{ id: "financiacion", label: "Parámetros de financiación" }]}
    >
      {feedback && <Alert variant="success" title="Configuración creada" onDismiss={() => setFeedback(null)}>{feedback}</Alert>}
      {error && <Alert variant="error" title="No pudimos cargar los parámetros">{error}</Alert>}

      {current && (
        <Card title={`Configuración vigente · v${current.version}`} description={`Rige desde ${formatDate(current.validFrom)}. Creada por ${current.createdBy ?? "Rentas"} el ${formatDateTime(current.createdAt)}.`}>
          <div className="grid grid-cols-2 gap-x-6 gap-y-4 px-5 py-5 md:grid-cols-4">
            <Metric label="Cuotas permitidas" value={`${current.minimumInstallments} a ${current.maximumInstallments}`} />
            <Metric label="Anticipo mínimo" value={formatPercentage(current.minimumDownPaymentPercentage)} />
            <Metric label="Tasa de financiación" value={formatPercentage(current.interestRate)} />
            <Metric label="Días de gracia" value={current.graceDays} />
            <Metric label="Cuotas impagas toleradas" value={current.maxOverdueInstallments} />
            <Metric label="Pago parcial" value={current.partialInstallmentPaymentAllowed ? "Permitido" : "No permitido"} />
            <Metric label="Refinanciación" value={current.refinancingAllowed ? "Permitida" : "No permitida"} />
            <Metric label="Máximo de refinanciaciones" value={current.maxRefinancingCount} />
          </div>
        </Card>
      )}

      <Card
        title="Historial de configuraciones"
        description="Los planes ya otorgados conservan la versión con la que fueron calculados."
        actions={<Button size="sm" variant="primary" onClick={() => setCreating(true)}>Nueva versión</Button>}
      >
        <DataTable
          columns={columns}
          rows={configurations ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="SlidersHorizontal"
          emptyTitle="Sin parámetros configurados"
          emptyDescription="Creá la primera versión para habilitar simulaciones de planes."
        />
      </Card>

      {creating && (
        <ConfigurationModal
          base={current}
          onClose={() => setCreating(false)}
          onDone={(created) => {
            resetPlanConfigurationCache();
            setCreating(false);
            setFeedback(`La versión ${created.version} quedó registrada y disponible desde ${formatDate(created.validFrom)}.`);
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

function Metric({ label, value }) {
  return (
    <div className="border-b border-neutral-100 pb-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">{label}</p>
      <p className="mt-1 text-[16px] font-bold tabular-nums text-[#0F2C59]">{value}</p>
    </div>
  );
}

const initialForm = (base) => ({
  minimumInstallments: String(base?.minimumInstallments ?? 3),
  maximumInstallments: String(base?.maximumInstallments ?? 12),
  minimumDownPaymentPercentage: String(base?.minimumDownPaymentPercentage ?? 10),
  interestRate: String(base?.interestRate ?? 5),
  graceDays: String(base?.graceDays ?? 5),
  maxOverdueInstallments: String(base?.maxOverdueInstallments ?? 2),
  partialInstallmentPaymentAllowed: base?.partialInstallmentPaymentAllowed ?? true,
  refinancingAllowed: base?.refinancingAllowed ?? true,
  maxRefinancingCount: String(base?.maxRefinancingCount ?? 1),
  validFrom: today(),
  validUntil: "",
  active: true,
});

function ConfigurationModal({ base, onClose, onDone }) {
  const [form, setForm] = useState(() => initialForm(base));
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const onChange = (event) => {
    const { name, value, checked, type } = event.target;
    setForm((previous) => ({ ...previous, [name]: type === "checkbox" ? checked : value }));
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    const min = Number(form.minimumInstallments);
    const max = Number(form.maximumInstallments);
    const down = Number(form.minimumDownPaymentPercentage);
    const interest = Number(form.interestRate);
    if (!Number.isInteger(min) || min < 1 || !Number.isInteger(max) || max < min) {
      setError("El rango de cuotas es inválido.");
      return;
    }
    if (down < 0 || down > 100 || interest < 0) {
      setError("El anticipo debe estar entre 0 y 100 y la tasa no puede ser negativa.");
      return;
    }
    if (form.validUntil && form.validUntil < form.validFrom) {
      setError("La fecha de fin no puede ser anterior al inicio de vigencia.");
      return;
    }

    setSubmitting(true);
    try {
      onDone(await paymentPlanConfigurationService.create({
        minimumInstallments: min,
        maximumInstallments: max,
        minimumDownPaymentPercentage: down,
        interestRate: interest,
        graceDays: Number(form.graceDays),
        maxOverdueInstallments: Number(form.maxOverdueInstallments),
        partialInstallmentPaymentAllowed: form.partialInstallmentPaymentAllowed,
        refinancingAllowed: form.refinancingAllowed,
        maxRefinancingCount: Number(form.maxRefinancingCount),
        validFrom: form.validFrom,
        validUntil: form.validUntil || null,
        active: form.active,
      }));
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open
      size="xl"
      title="Nueva versión de financiación"
      description="Se crea una versión nueva; las condiciones históricas de los planes existentes no cambian."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Cancelar</Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>Guardar versión</Button>
        </>
      }
    >
      {error && <Alert variant="error" title="No se pudo guardar">{error}</Alert>}
      <form onSubmit={onSubmit} noValidate className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <FormField label="Cuotas mínimas" name="minimumInstallments" type="number" value={form.minimumInstallments} onChange={onChange} required />
        <FormField label="Cuotas máximas" name="maximumInstallments" type="number" value={form.maximumInstallments} onChange={onChange} required />
        <FormField label="Anticipo mínimo" name="minimumDownPaymentPercentage" type="number" suffix={<span className="text-neutral-400">%</span>} value={form.minimumDownPaymentPercentage} onChange={onChange} required />
        <FormField label="Tasa de financiación" name="interestRate" type="number" suffix={<span className="text-neutral-400">%</span>} value={form.interestRate} onChange={onChange} required />
        <FormField label="Días de gracia" name="graceDays" type="number" value={form.graceDays} onChange={onChange} required />
        <FormField label="Cuotas impagas toleradas" name="maxOverdueInstallments" type="number" value={form.maxOverdueInstallments} onChange={onChange} required />
        <FormField label="Máximo de refinanciaciones" name="maxRefinancingCount" type="number" value={form.maxRefinancingCount} onChange={onChange} required />
        <div />
        <FormField label="Inicio de vigencia" name="validFrom" type="date" value={form.validFrom} onChange={onChange} required />
        <FormField label="Fin de vigencia" name="validUntil" type="date" value={form.validUntil} onChange={onChange} />
        <BooleanField name="partialInstallmentPaymentAllowed" checked={form.partialInstallmentPaymentAllowed} onChange={onChange} label="Permitir pagos parciales de cuotas" />
        <BooleanField name="refinancingAllowed" checked={form.refinancingAllowed} onChange={onChange} label="Permitir refinanciaciones" />
      </form>
    </Modal>
  );
}

function BooleanField({ name, checked, onChange, label }) {
  return (
    <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-3 text-[13px] font-medium text-neutral-700">
      <input type="checkbox" name={name} checked={checked} onChange={onChange} className="h-4 w-4 accent-[#0F2C59]" />
      {label}
    </label>
  );
}
