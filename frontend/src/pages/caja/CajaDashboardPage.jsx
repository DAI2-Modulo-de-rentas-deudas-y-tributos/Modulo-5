import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader.jsx";
import FeatureCard from "../../components/ui/FeatureCard.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import ReceiptModal from "../../components/caja/ReceiptModal.jsx";
import { CAJA_MODULES } from "../../config/cajaModules.js";
import { cashierService } from "../../services/rentasService.js";
import useResource from "../../hooks/useResource.js";
import { useAuth } from "../../context/AuthContext.jsx";
import {
  formatCurrency,
  formatDate,
  formatDateTime,
  formatTime,
  labelFor,
} from "../../lib/format.js";

/** Panel de caja: cómo viene la jornada del cajero y acceso a sus cuatro módulos. */
export default function CajaDashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [receiptId, setReceiptId] = useState(null);

  const loader = useCallback(
    () => cashierService.dailySummary({ registeredBy: user.username }),
    [user.username],
  );
  const { data: summary, loading, error } = useResource(loader);

  const firstName = user.fullName.split(" ")[0];

  const latestColumns = [
    {
      key: "receiptNumber",
      header: "Comprobante",
      render: (row) => <span className="font-medium text-neutral-800">{row.receiptNumber}</span>,
    },
    { key: "taxpayerName", header: "Contribuyente" },
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
    {
      key: "paidAt",
      header: "Hora",
      render: (row) => <span className="tabular-nums">{formatTime(row.paidAt)}</span>,
    },
  ];

  const activityColumns = [
    { key: "description", header: "Descripción" },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    { key: "at", header: "Fecha", render: (row) => formatDateTime(row.at) },
  ];

  return (
    <>
      <PageHeader
        label={user.roleLabel}
        title={`Hola, ${firstName}.`}
        highlight="Tu caja de hoy"
        description={
          user.counter
            ? `${user.counter} — movimientos de la jornada y accesos de ventanilla.`
            : "Movimientos de la jornada y accesos de ventanilla."
        }
      />

      <div className="mx-auto flex max-w-6xl flex-col gap-8 px-5 py-8">
        {error && (
          <Alert variant="error" title="No pudimos cargar el panel de caja">
            {error}
          </Alert>
        )}

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-10">
            <Spinner />
            <span className="text-[13px] text-neutral-400">Calculando la jornada…</span>
          </div>
        ) : (
          summary && (
            <>
              <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <StatTile
                  label="Pagos registrados"
                  value={summary.registeredCount}
                  hint={`Jornada del ${formatDate(summary.date)}`}
                  iconName="Receipt"
                />
                <StatTile
                  label="Total cobrado"
                  value={formatCurrency(summary.totalCollected)}
                  hint={summary.byMethod
                    .map((m) => `${labelFor(m.method)}: ${m.count}`)
                    .join(" · ")}
                  iconName="Wallet"
                  tone="success"
                />
                <StatTile
                  label="Pagos pendientes"
                  value={summary.pendingCount}
                  hint="Sin imputar a una deuda"
                  iconName="Clock"
                  tone={summary.pendingCount > 0 ? "danger" : "success"}
                />
                <StatTile
                  label="Reversados del día"
                  value={summary.reversedCount}
                  hint="Los resuelve Personal de Rentas"
                  iconName="RotateCcw"
                  tone={summary.reversedCount > 0 ? "danger" : "success"}
                />
              </section>

              <Card
                title="Últimos pagos"
                description="Cobros de la jornada. Hacé clic para ver o reimprimir el comprobante."
                actions={
                  <Button size="sm" variant="primary" onClick={() => navigate("/caja/cobros")}>
                    Nuevo cobro
                  </Button>
                }
              >
                <DataTable
                  columns={latestColumns}
                  rows={summary.latest}
                  rowKey={(row) => row.id}
                  emptyIconName="Receipt"
                  emptyTitle="Todavía no cobraste hoy"
                  emptyDescription="Registrá un cobro desde el módulo Cobros."
                  onRowClick={(row) => setReceiptId(row.id)}
                />
              </Card>

              <Card
                title="Operaciones de hoy"
                description="Detalle cronológico de lo que pasó por esta caja."
              >
                <DataTable
                  columns={activityColumns}
                  rows={summary.activity}
                  rowKey={(row) => row.id}
                  emptyIconName="Inbox"
                  emptyTitle="Sin movimientos"
                  emptyDescription="La jornada todavía no registra operaciones."
                />
              </Card>

              <section className="flex flex-col gap-4">
                <div>
                  <h2 className="text-[15px] font-semibold text-[#0F2C59]">Ventanilla</h2>
                  <p className="text-[13px] text-neutral-400">
                    Cobrar es la tarea principal; el resto son consultas de apoyo.
                  </p>
                </div>

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                  {CAJA_MODULES.map((module) => (
                    <FeatureCard
                      key={module.id}
                      title={module.label}
                      description={module.description}
                      iconName={module.iconName}
                      showItemCount={false}
                      onClick={() => navigate(module.path)}
                    />
                  ))}
                </div>
              </section>
            </>
          )
        )}
      </div>

      <ReceiptModal paymentId={receiptId} onClose={() => setReceiptId(null)} />
    </>
  );
}
