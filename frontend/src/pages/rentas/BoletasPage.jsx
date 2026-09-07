import { useCallback, useState } from "react";
import BillPdfDownload from "../../components/documentos/BillPdfDownload.jsx";
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
import { billService, debtService } from "../../services/rentasService.js";
import { formatCurrency, formatDate, formatDateTime } from "../../lib/format.js";

/** Boletas emitidas y descarga del documento generado por el backend. */
export default function BoletasPage() {
  const [status, setStatus] = useState("");
  const [issuing, setIssuing] = useState(false);
  const [feedback, setFeedback] = useState(null);

  const loader = useCallback(() => billService.list({ status }), [status]);
  const { data: bills, loading, error, reload } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const columns = [
    { key: "id", header: "Boleta", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    { key: "conceptCode", header: "Concepto" },
    {
      key: "debtId",
      header: "Deuda",
      render: (row) => <span className="tabular-nums text-neutral-400">#{row.debtId}</span>,
    },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    {
      key: "issuedAt",
      header: "Emitida",
      render: (row) => formatDateTime(row.issuedAt),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <BillPdfDownload billId={row.id} />
      ),
    },
  ];

  return (
    <ModuleShell
      label="Operación"
      title="Boletas"
      highlight="y comprobantes"
      description="Emisión de boletas de pago a partir de una deuda con saldo pendiente."
      breadcrumb={[{ id: "boletas", label: "Boletas" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar las boletas">{error}</Alert>}

      <Card
        title="Boletas emitidas"
        description="Consultá y descargá las boletas emitidas."
        actions={
          <Button size="sm" variant="primary" onClick={() => setIssuing(true)}>
            Emitir boleta
          </Button>
        }
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "ISSUED", label: "Emitida" },
                { value: "EXPIRED", label: "Vencida" },
              ],
            },
          ]}
          values={{ status }}
          onFilterChange={(_, value) => setStatus(value)}
        />

        <DataTable
          columns={columns}
          rows={bills ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="FileText"
          emptyTitle="Sin boletas emitidas"
          emptyDescription="Emití una boleta desde una deuda con saldo pendiente."
        />
      </Card>

      <IssueBillModal
        open={issuing}
        onClose={() => setIssuing(false)}
        onIssued={(bill) => {
          setIssuing(false);
          setFeedback({
            variant: "success",
            title: "Boleta emitida",
            message: `Boleta #${bill.id} por ${formatCurrency(bill.amount)} disponible para descarga.`,
          });
          reload();
        }}
      />
    </ModuleShell>
  );
}

/** IssueBillRequest: sólo deudas con saldo pendiente pueden generar boleta. */
function IssueBillModal({ open, onClose, onIssued }) {
  const loader = useCallback(() => debtService.list(), []);
  const { data: debts } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const [debtId, setDebtId] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const pendingDebts = (debts ?? []).filter((d) => d.outstandingAmount > 0);
  const selected = pendingDebts.find((d) => d.id === Number(debtId));

  const onSubmit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!debtId) {
      setError("Seleccioná la deuda a facturar.");
      return;
    }
    setSubmitting(true);
    try {
      const bill = await billService.issue({ debtId });
      setDebtId("");
      onIssued(bill);
    } catch (caught) {
      setError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Emitir boleta"
      description="Se genera por el saldo pendiente de la deuda seleccionada."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            Emitir
          </Button>
        </>
      }
    >
      {error && <Alert variant="error" title="No se pudo emitir">{error}</Alert>}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          label="Deuda"
          name="debtId"
          type="select"
          value={debtId}
          onChange={(event) => setDebtId(event.target.value)}
          required
          options={pendingDebts.map((debt) => ({
            value: String(debt.id),
            label: `#${debt.id} · ${nameOf(debt.taxpayerId)} · ${formatCurrency(debt.outstandingAmount)}`,
          }))}
        />

        {selected && (
          <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4 text-[13px] text-neutral-600">
            <p>
              Concepto: <span className="font-medium text-neutral-800">{selected.conceptCode}</span>
            </p>
            <p className="mt-1">
              Importe:{" "}
              <span className="font-semibold text-[#0F2C59]">
                {formatCurrency(selected.outstandingAmount)}
              </span>
            </p>
            <p className="mt-1">
              Vencimiento:{" "}
              <span className="font-medium text-neutral-800">{formatDate(selected.dueDate)}</span>
            </p>
          </div>
        )}
      </form>
    </Modal>
  );
}
