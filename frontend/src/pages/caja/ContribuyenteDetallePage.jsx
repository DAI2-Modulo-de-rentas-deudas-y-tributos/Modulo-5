import { useCallback, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Download } from "lucide-react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import ReceiptModal from "../../components/caja/ReceiptModal.jsx";
import useResource from "../../hooks/useResource.js";
import { billService, cashierService } from "../../services/rentasService.js";
import { formatCurrency, formatDate, formatDateTime, labelFor } from "../../lib/format.js";

/**
 * Ficha completa del contribuyente en ventanilla: deudas, pagos y boletas.
 * Desde acá el cajero puede cobrar una deuda o generarle la boleta al contribuyente.
 */
export default function ContribuyenteDetallePage() {
  const { taxpayerId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => cashierService.taxpayerFile(taxpayerId), [taxpayerId]);
  const { data: file, loading, error, reload } = useResource(loader);

  const [feedback, setFeedback] = useState(null);
  const [issuingDebtId, setIssuingDebtId] = useState(null);
  const [receiptId, setReceiptId] = useState(null);

  const issueBill = async (debt) => {
    setIssuingDebtId(debt.id);
    setFeedback(null);
    try {
      const bill = await billService.issue({ debtId: debt.id });
      setFeedback({
        variant: "success",
        title: "Boleta generada",
        message: `Boleta #${bill.id} por ${formatCurrency(bill.amount)} lista para imprimir.`,
      });
      reload();
    } catch (caught) {
      setFeedback({ variant: "error", title: "No se pudo generar la boleta", message: caught.message });
    } finally {
      setIssuingDebtId(null);
    }
  };

  const debtColumns = [
    { key: "id", header: "Deuda", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "conceptCode", header: "Concepto" },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    {
      key: "outstandingAmount",
      header: "Saldo",
      align: "right",
      render: (row) => formatCurrency(row.outstandingAmount),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        row.outstandingAmount > 0 ? (
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              variant="secondary"
              loading={issuingDebtId === row.id}
              onClick={() => issueBill(row)}
            >
              Generar boleta
            </Button>
            <Button
              size="sm"
              variant="primary"
              onClick={() => navigate(`/caja/cobros?deuda=${row.id}`)}
            >
              Cobrar
            </Button>
          </div>
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
  ];

  const paymentColumns = [
    {
      key: "receiptNumber",
      header: "Comprobante",
      render: (row) => <span className="font-medium text-neutral-800">{row.receiptNumber}</span>,
    },
    {
      key: "debtId",
      header: "Imputación",
      render: (row) =>
        row.debtId ? (
          <span className="tabular-nums text-neutral-600">Deuda #{row.debtId}</span>
        ) : (
          <StatusBadge tone="warning" label="Sin imputar" />
        ),
    },
    {
      key: "amountPaid",
      header: "Importe",
      align: "right",
      render: (row) => (
        <span className={row.status === "REVERSED" ? "text-neutral-400 line-through" : ""}>
          {formatCurrency(row.amountPaid)}
        </span>
      ),
    },
    { key: "method", header: "Medio", render: (row) => labelFor(row.method) },
    { key: "paidAt", header: "Fecha", render: (row) => formatDateTime(row.paidAt) },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
  ];

  const billColumns = [
    { key: "id", header: "Boleta", render: (row) => <span className="tabular-nums">#{row.id}</span> },
    { key: "conceptCode", header: "Concepto" },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <a
          href={row.documentUrl}
          title={row.documentUrl}
          className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-[#0F2C59] transition-colors hover:text-[#D63031]"
        >
          <Download className="h-3.5 w-3.5" strokeWidth={2} />
          PDF
        </a>
      ),
    },
  ];

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando la ficha…</span>
      </div>
    );
  }

  if (error || !file) {
    return (
      <ModuleShell
        label="Ventanilla"
        title="Contribuyente"
        description="No pudimos abrir la ficha."
        breadcrumb={[
          { id: "contribuyentes", label: "Contribuyentes", path: "/caja/contribuyentes" },
          { id: "detalle", label: "Ficha" },
        ]}
        homePath="/caja"
        homeLabel="Panel de caja"
      >
        <Alert variant="error" title="No pudimos abrir la ficha">
          {error ?? "El contribuyente no existe en el padrón local."}
        </Alert>
      </ModuleShell>
    );
  }

  const { taxpayer, totals } = file;

  return (
    <ModuleShell
      label="Ventanilla"
      title={taxpayer.name}
      description={`${labelFor(taxpayer.type)} · ${taxpayer.documentType} ${taxpayer.document} · CUIT ${taxpayer.cuit}`}
      breadcrumb={[
        { id: "contribuyentes", label: "Contribuyentes", path: "/caja/contribuyentes" },
        { id: "detalle", label: taxpayer.name },
      ]}
      homePath="/caja"
      homeLabel="Panel de caja"
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

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatTile
          label="Deuda total"
          value={formatCurrency(totals.outstanding)}
          hint={`${file.debts.filter((d) => d.outstandingAmount > 0).length} deudas con saldo`}
          iconName="FileWarning"
        />
        <StatTile
          label="Deuda vencida"
          value={formatCurrency(totals.overdue)}
          hint="Exigible en ventanilla"
          iconName="TrendingDown"
          tone={totals.overdue > 0 ? "danger" : "success"}
        />
        <StatTile
          label="Pagado"
          value={formatCurrency(totals.paid)}
          hint="Pagos registrados y vigentes"
          iconName="Wallet"
          tone="success"
        />
      </section>

      <Card
        title="Deudas"
        description="Generá la boleta o cobrala directamente en ventanilla."
      >
        <DataTable
          columns={debtColumns}
          rows={file.debts}
          rowKey={(row) => row.id}
          emptyIconName="FileWarning"
          emptyTitle="Sin deudas"
          emptyDescription="El contribuyente no tiene obligaciones registradas."
        />
      </Card>

      <Card title="Pagos" description="Hacé clic en un pago para ver su comprobante.">
        <DataTable
          columns={paymentColumns}
          rows={file.payments}
          rowKey={(row) => row.id}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos"
          emptyDescription="Todavía no registró pagos."
          onRowClick={(row) => setReceiptId(row.id)}
        />
      </Card>

      <Card title="Boletas" description="Documentos emitidos; el PDF vive en S3.">
        <DataTable
          columns={billColumns}
          rows={file.bills}
          rowKey={(row) => row.id}
          emptyIconName="FileText"
          emptyTitle="Sin boletas"
          emptyDescription="Generá una boleta desde una deuda con saldo pendiente."
        />
      </Card>

      <ReceiptModal paymentId={receiptId} onClose={() => setReceiptId(null)} />
    </ModuleShell>
  );
}
