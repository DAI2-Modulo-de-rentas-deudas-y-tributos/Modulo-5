import { useState } from "react";
import { CircleCheckBig, TriangleAlert } from "lucide-react";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FormField from "../../components/ui/FormField.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import StepIndicatorGeneric from "../../components/ui/StepIndicatorGeneric.jsx";
import { settlementService } from "../../services/rentasService.js";
import { formatCurrency, formatPercentage } from "../../lib/format.js";

const STEPS = [{ label: "Configurar" }, { label: "Previsualizar" }, { label: "Resultado" }];

const SCOPE_OPTIONS = [
  { value: "", label: "Todos los contribuyentes" },
  { value: "CITIZEN", label: "Sólo ciudadanos" },
  { value: "ORGANIZATION", label: "Sólo organizaciones" },
];

/**
 * Generación masiva de liquidaciones, en tres pasos.
 *
 * El paso de previsualización existe para que el operador vea qué va a pasar antes
 * de crear doscientos registros: cuántos se generan, cuántos quedan afuera y por qué.
 * El lote se crea en borrador; emitir sigue siendo un acto explícito.
 */
export default function GeneracionMasivaModal({ conceptOptions = [], onClose, onGenerated }) {
  const [form, setForm] = useState({
    conceptCode: "",
    period: "",
    baseAmount: "",
    dueDate: "",
    taxpayerType: "",
  });
  const [errors, setErrors] = useState({});
  const [preview, setPreview] = useState(null);
  const [result, setResult] = useState(null);
  const [submitError, setSubmitError] = useState(null);
  const [working, setWorking] = useState(false);

  const step = result ? 2 : preview ? 1 : 0;

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
  };

  const validate = () => {
    const found = {};
    if (!form.conceptCode) found.conceptCode = "Elegí el concepto a liquidar.";
    if (!/^\d{4}-\d{2}$/.test(form.period)) found.period = "Usá el formato AAAA-MM (por ejemplo 2026-09).";
    if (!(Number(form.baseAmount) > 0)) found.baseAmount = "La base imponible tiene que ser mayor a cero.";
    if (!form.dueDate) found.dueDate = "Indicá el vencimiento.";
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const onPreview = async (event) => {
    event.preventDefault();
    setSubmitError(null);
    if (!validate()) return;
    setWorking(true);
    try {
      setPreview(await settlementService.previewBatch(form));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setWorking(false);
    }
  };

  const onGenerate = async () => {
    setSubmitError(null);
    setWorking(true);
    try {
      setResult(await settlementService.generateBatch(form));
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setWorking(false);
    }
  };

  const previewColumns = [
    {
      key: "taxpayerName",
      header: "Contribuyente",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.taxpayerName}</span>
          <span className="text-[12px] text-neutral-400">{row.document}</span>
        </div>
      ),
    },
    {
      key: "status",
      header: "Situación",
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: "discountPercentage",
      header: "Descuento",
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
  ];

  return (
    <Modal
      open
      title="Generar liquidaciones masivas"
      description={
        step === 0
          ? "Definí el concepto, el período y la base imponible."
          : step === 1
            ? "Revisá qué se va a generar antes de confirmar."
            : "Resultado del lote."
      }
      onClose={onClose}
      footer={
        step === 0 ? (
          <>
            <Button variant="secondary" onClick={onClose}>
              Cancelar
            </Button>
            <Button variant="primary" loading={working} onClick={onPreview}>
              Previsualizar
            </Button>
          </>
        ) : step === 1 ? (
          <>
            <Button variant="secondary" onClick={() => setPreview(null)}>
              Volver
            </Button>
            <Button
              variant="accent"
              loading={working}
              disabled={preview.items.length === 0}
              onClick={onGenerate}
            >
              Generar {preview.totals.toGenerate} liquidaciones
            </Button>
          </>
        ) : (
          <Button variant="primary" onClick={() => onGenerated(result)}>
            Finalizar
          </Button>
        )
      }
    >
      <div className="flex justify-center pb-1">
        <StepIndicatorGeneric steps={STEPS} currentStep={step} />
      </div>

      {submitError && (
        <Alert variant="error" title="No se pudo completar">
          {submitError}
        </Alert>
      )}

      {step === 0 && (
        <form onSubmit={onPreview} noValidate className="flex flex-col gap-4">
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
            placeholder="120000"
            value={form.baseAmount}
            onChange={onChange}
            error={errors.baseAmount}
            required
          />
          <p className="-mt-3 text-[12px] text-neutral-400">
            Se aplica a todos los contribuyentes alcanzados. El descuento por beneficio
            social de M8 se calcula después, uno por uno.
          </p>
          <FormField
            label="Alcance"
            name="taxpayerType"
            type="select"
            value={form.taxpayerType}
            onChange={onChange}
            options={SCOPE_OPTIONS}
          />
        </form>
      )}

      {step === 1 && (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Metric label="A generar" value={preview.totals.toGenerate} tone="navy" />
            <Metric label="Con descuento" value={preview.totals.discounted} tone="success" />
            <Metric
              label="Omitidos"
              value={preview.totals.skipped}
              tone={preview.totals.skipped > 0 ? "danger" : "muted"}
            />
            <Metric label="Total a liquidar" value={formatCurrency(preview.totals.amount)} tone="navy" />
          </div>

          {preview.errors.length > 0 && (
            <Alert variant="error" title={`${preview.errors.length} quedan afuera del lote`}>
              <ul className="mt-1 flex flex-col gap-1">
                {preview.errors.map((e) => (
                  <li key={e.taxpayerId}>
                    <strong>{e.taxpayerName}</strong>: {e.reason}
                  </li>
                ))}
              </ul>
            </Alert>
          )}

          {preview.warnings.length > 0 && (
            <Alert variant="info" title={`${preview.warnings.length} se generan con advertencia`}>
              <ul className="mt-1 flex flex-col gap-1">
                {preview.warnings.map((w) => (
                  <li key={w.taxpayerId}>
                    <strong>{w.taxpayerName}</strong>: {w.reason}
                  </li>
                ))}
              </ul>
            </Alert>
          )}

          {preview.items.length === 0 ? (
            <Alert variant="info" title="No hay nada para generar">
              Ningún contribuyente queda alcanzado con esos parámetros. Probá con otro
              período o ampliá el alcance.
            </Alert>
          ) : (
            <div className="overflow-hidden rounded-lg border border-neutral-200">
              <DataTable
                columns={previewColumns}
                rows={preview.items}
                rowKey={(row) => row.taxpayerId}
                emptyTitle="Sin registros"
              />
            </div>
          )}

          <p className="text-[12px] text-neutral-400">
            El lote se crea en <strong>borrador</strong>. Cada liquidación se emite después,
            y recién ahí genera la deuda.
          </p>
        </div>
      )}

      {step === 2 && (
        <div className="flex flex-col gap-4">
          <div className="flex items-center gap-2 text-emerald-600">
            <CircleCheckBig className="h-5 w-5" strokeWidth={2} />
            <span className="text-[14px] font-semibold">
              {result.generated.length} liquidaciones generadas en borrador
            </span>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Metric label="Generadas" value={result.generated.length} tone="navy" />
            <Metric label="Total liquidado" value={formatCurrency(result.totals.amount)} tone="navy" />
          </div>

          {result.warnings.length > 0 && (
            <Alert variant="info" title="Revisá estas antes de emitir">
              <span className="flex items-start gap-2">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={2} />
                <span>
                  {result.warnings.length} liquidaciones son de contribuyentes bloqueados o
                  fallecidos. Se generaron igual, pero no emitas la boleta sin verificar.
                </span>
              </span>
            </Alert>
          )}

          <p className="text-[13px] text-neutral-500">
            Quedaron en el listado con estado <strong>Borrador</strong>. Emitilas una por
            una cuando estén verificadas.
          </p>
        </div>
      )}

      {working && step === 0 && (
        <div className="flex items-center gap-2">
          <Spinner size="sm" />
          <span className="text-[13px] text-neutral-400">Calculando el lote…</span>
        </div>
      )}
    </Modal>
  );
}

function Metric({ label, value, tone = "navy" }) {
  const toneClass =
    tone === "danger"
      ? "text-[#D63031]"
      : tone === "success"
        ? "text-emerald-600"
        : tone === "muted"
          ? "text-neutral-400"
          : "text-[#0F2C59]";
  return (
    <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </p>
      <p className={`mt-1 text-[18px] font-extrabold tabular-nums leading-none ${toneClass}`}>
        {value}
      </p>
    </div>
  );
}
