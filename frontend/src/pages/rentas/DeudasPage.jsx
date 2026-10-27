import { useCallback, useState } from "react";
import { useSearchParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Modal from "../../components/common/Modal.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import useTaxConcepts from "../../hooks/useTaxConcepts.js";
import { administrationService, debtService } from "../../services/rentasService.js";
import { DEBT_ORIGIN_TYPES } from "../../config/catalogosDominio.js";
import { formatCurrency, formatDate, labelFor } from "../../lib/format.js";

const ORIGIN_LABELS = Object.fromEntries(DEBT_ORIGIN_TYPES.map((o) => [o.value, o.label]));

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
  const [lateCharge, setLateCharge] = useState(null);
  const [processing, setProcessing] = useState(false);

  const loader = useCallback(
    () => debtService.list({ ...filters, taxpayerId }),
    [filters, taxpayerId],
  );
  const { data: debts, loading, error, reload } = useResource(loader, []);
  const { nameOf, index } = useTaxpayerIndex();
  const { concepts } = useTaxConcepts();

  const rows = debts ?? [];
  const totalOutstanding = rows.reduce((acc, d) => acc + d.outstandingAmount, 0);
  const totalOverdue = rows
    .filter((d) => d.status === "OVERDUE")
    .reduce((acc, d) => acc + d.outstandingAmount, 0);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const onPreviewLateCharge = async (debt) => {
    setFeedback(null);
    try { setLateCharge({ debt, preview: await debtService.previewLateCharge(debt.id, new Date().toISOString().slice(0, 10)), applying: false }); }
    catch (caught) { setFeedback({ variant: "error", title: "No se pudo calcular", message: caught.message }); }
  };

  const onApplyLateCharge = async () => {
    setLateCharge((value) => ({ ...value, applying: true }));
    try {
      const result = await debtService.applyLateCharge(lateCharge.debt.id, lateCharge.preview.calculationDate);
      setLateCharge(null);
      setFeedback(result.applied
        ? { variant: "success", title: "Recargo aplicado", message: `Se aplicaron ${formatCurrency(result.totalAdjustment)} a la deuda #${result.debtId}.` }
        : { variant: "info", title: "Recargo ya aplicado", message: `La deuda #${result.debtId} ya incluye el recargo del ${formatDate(result.calculationDate)}. No se agregó otro cargo.` });
      reload();
    }
    catch (caught) { setLateCharge((value) => ({ ...value, applying: false }));setFeedback({ variant: "error", title: "No se pudo aplicar", message: caught.message }); }
  };

  const onProcessDueDates = async () => {
    setProcessing(true);setFeedback(null);
    try { const result=await administrationService.processDueDates(new Date().toISOString().slice(0,10));setFeedback({ variant:"success",title:"Vencimientos procesados",message:`${result.debtsOverdue} deudas vencidas, ${result.adjustmentsGenerated} ajustes y ${result.installmentsOverdue} cuotas actualizadas.`});reload(); }
    catch(caught){setFeedback({variant:"error",title:"No se pudieron procesar",message:caught.message});}
    finally{setProcessing(false);}
  };

  const columns = [
    { key: "id", header: "N°", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    {
      key: "origin",
      header: "Origen",
      render: (row) => (
        <span className="text-neutral-700">{ORIGIN_LABELS[row.originType] ?? "Origen registrado"}</span>
      ),
    },
    {
      key: "conceptCode",
      header: "Concepto",
      render: (row) => concepts.find((concept) => concept.id === row.taxConceptId)?.name ?? "Concepto tributario",
    },
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
        row.status === "OVERDUE" && (
          <div className="flex justify-end gap-2">
          <Button size="sm" variant="secondary" onClick={() => onPreviewLateCharge(row)}>Calcular recargo</Button>
        <StatusBadge
          tone="info"
          label={row.reportedToM8 ? "Aviso registrado" : "Notificación externa no disponible"}
        />
          </div>
        ),
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
        description="El aviso de vencimiento a Desarrollo Social depende de una integración externa todavía pendiente."
        actions={<Button size="sm" variant="primary" loading={processing} onClick={onProcessDueDates}>Procesar vencimientos</Button>}
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
            { name: "originType", label: "Origen", options: DEBT_ORIGIN_TYPES },
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

      {lateCharge && <Modal open title={`Recargos de deuda #${lateCharge.debt.id}`} description={`Regla ${lateCharge.preview.ruleCode} al ${formatDate(lateCharge.preview.calculationDate)}`} onClose={() => setLateCharge(null)} footer={lateCharge.preview.applicationId != null
        ? <Button variant="secondary" onClick={() => setLateCharge(null)}>Cerrar</Button>
        : <><Button variant="secondary" onClick={() => setLateCharge(null)}>Cancelar</Button><Button variant="primary" loading={lateCharge.applying} onClick={onApplyLateCharge}>Confirmar aplicación</Button></>}>
        {lateCharge.preview.applicationId != null && <p className="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-900">El recargo de esta fecha ya fue aplicado. Los importes de abajo son los registrados; no se agregará otro cargo.</p>}
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div><dt>Días vencidos</dt><dd className="font-semibold">{lateCharge.preview.daysOverdue}</dd></div>
          <div><dt>{lateCharge.preview.applicationId != null ? "Capital al aplicar" : "Capital"}</dt><dd className="font-semibold">{formatCurrency(lateCharge.preview.principal)}</dd></div>
          <div><dt>Recargo ({lateCharge.preview.surchargeRate}%)</dt><dd>{formatCurrency(lateCharge.preview.surchargeAmount)}</dd></div>
          <div><dt>Interés diario ({lateCharge.preview.interestRate}%)</dt><dd>{formatCurrency(lateCharge.preview.interestAmount)}</dd></div>
          <div><dt>Ajuste total</dt><dd className="font-semibold">{formatCurrency(lateCharge.preview.totalAdjustment)}</dd></div>
          <div><dt>{lateCharge.preview.applicationId != null ? "Total tras la aplicación" : "Total actualizado"}</dt><dd className="font-semibold">{formatCurrency(lateCharge.preview.updatedTotal)}</dd></div>
        </dl>
      </Modal>}
    </ModuleShell>
  );
}
