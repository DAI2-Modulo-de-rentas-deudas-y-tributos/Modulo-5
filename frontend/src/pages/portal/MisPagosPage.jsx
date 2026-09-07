import { useCallback } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/** Pagos acreditados al contribuyente y a qué deuda se aplicó cada uno. */
export default function MisPagosPage() {
  const { user } = useAuth();

  const loader = useCallback(
    () => portalService.payments({ taxpayerId: user.taxpayerId }),
    [user.taxpayerId],
  );
  const { data: payments, loading, error } = useResource(loader, []);

  const sinImputar = (payments ?? []).filter((p) => p.status === "UNALLOCATED");
  const reversados = (payments ?? []).filter((p) => p.status === "REVERSED");

  const columns = [
    {
      key: "receiptNumber",
      header: "Comprobante",
      render: (row) => <span className="font-medium text-neutral-800">{row.receiptNumber}</span>,
    },
    {
      key: "conceptName",
      header: "Aplicado a",
      render: (row) =>
        row.debtId ? (
          <div className="flex flex-col">
            <span>{row.conceptName}</span>
            <span className="text-[12px] tabular-nums text-neutral-400">Deuda #{row.debtId}</span>
          </div>
        ) : Number(row.allocatedAmount) > 0 ? (
          <StatusBadge
            tone={Number(row.unallocatedAmount) > 0 ? "warning" : "success"}
            label={Number(row.unallocatedAmount) > 0 ? "Imputado parcialmente" : "Imputado"}
          />
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

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Mis pagos"
      highlight="registrados"
      description="Los pagos acreditados a tu nombre y la deuda a la que se aplicó cada uno."
      breadcrumb={[{ id: "pagos", label: "Mis pagos" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar tus pagos">
          {error}
        </Alert>
      )}

      {sinImputar.length > 0 && (
        <Alert variant="info" title="Tenés un pago sin imputar">
          Se acreditó un pago que todavía no se aplicó a ninguna deuda. Acercate a la
          oficina de Rentas con el comprobante para que lo imputen.
        </Alert>
      )}

      {reversados.length > 0 && (
        <Alert variant="error" title="Tenés un pago reversado">
          Un pago fue dado de baja y su importe volvió al saldo de la deuda. Si no
          entendés el motivo, consultá en la oficina de Rentas.
        </Alert>
      )}

      <Card title="Historial de pagos" description="De los más recientes a los más antiguos.">
        <DataTable
          columns={columns}
          rows={payments ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="Banknote"
          emptyTitle="Sin pagos registrados"
          emptyDescription="Todavía no hay pagos acreditados a tu nombre."
        />
      </Card>
    </ModuleShell>
  );
}
