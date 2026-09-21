import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate } from "../../lib/format.js";

/** Deudas a nombre del contribuyente. Sólo consulta: desde acá se pide financiación. */
export default function MisDeudasPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [status, setStatus] = useState("");

  const loader = useCallback(
    () => portalService.debts({ taxpayerId: user.taxpayerId, status }),
    [user.taxpayerId, status],
  );
  const { data: debts, loading, error } = useResource(loader, []);

  const financiables = (debts ?? []).filter((d) => d.outstandingAmount > 0 && !d.planRequestId);

  const columns = [
    {
      key: "conceptName",
      header: "Concepto",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.conceptName}</span>
          <span className="text-[12px] tabular-nums text-neutral-400">Deuda #{row.id}</span>
        </div>
      ),
    },
    {
      key: "dueDate",
      header: "Vencimiento",
      render: (row) => (
        <div className="flex flex-col">
          <span>{formatDate(row.dueDate)}</span>
          <span className="text-[12px] text-neutral-400">
            {row.daysLeft < 0
              ? `Hace ${Math.abs(row.daysLeft)} días`
              : row.daysLeft === 0
                ? "Vence hoy"
                : `En ${row.daysLeft} días`}
          </span>
        </div>
      ),
    },
    {
      key: "originalAmount",
      header: "Importe original",
      align: "right",
      render: (row) => formatCurrency(row.originalAmount),
    },
    {
      key: "outstandingAmount",
      header: "Saldo a pagar",
      align: "right",
      render: (row) => (
        <span className={row.status === "OVERDUE" ? "font-semibold text-[#D63031]" : ""}>
          {formatCurrency(row.outstandingAmount)}
        </span>
      ),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "billId",
      header: "Boleta",
      render: (row) =>
        row.billId ? (
          <button
            type="button"
            onClick={() => navigate("/portal/boletas")}
            className="text-[12px] font-semibold text-[#0F2C59] underline underline-offset-2 transition-colors hover:text-[#D63031]"
          >
            #{row.billId}
          </button>
        ) : (
          <span className="text-neutral-300">Sin emitir</span>
        ),
    },
    {
      key: "planRequestId",
      header: "",
      align: "right",
      render: (row) =>
        row.planRequestId ? (
          <StatusBadge tone="info" label={`En plan #${row.planRequestId}`} />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
  ];

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Mis deudas"
      highlight="con Rentas"
      description="Las obligaciones a tu nombre, su vencimiento y lo que queda por pagar."
      breadcrumb={[{ id: "deudas", label: "Mis deudas" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar tus deudas">
          {error}
        </Alert>
      )}

      <Card
        title="Detalle"
        description="Si no podés pagar el total, podés pedir financiarlo en cuotas."
        actions={
          financiables.length > 0 && (
            <Button size="sm" variant="primary" onClick={() => navigate("/portal/planes")}>
              Pedir plan de pago
            </Button>
          )
        }
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "PENDING", label: "Pendiente" },
                { value: "OVERDUE", label: "Vencida" },
                { value: "SETTLED", label: "Cancelada" },
              ],
            },
          ]}
          values={{ status }}
          onFilterChange={(_, value) => setStatus(value)}
        />
        <DataTable
          columns={columns}
          rows={debts ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="CircleCheckBig"
          emptyTitle="Sin deudas"
          emptyDescription="No tenés obligaciones registradas con ese estado."
        />
      </Card>

      <Alert variant="info" title="¿Cómo pago?">
        Las deudas se pagan con la boleta, en la ventanilla de Rentas o por los canales
        habilitados. Este portal no registra pagos: cuando el tuyo se acredite, va a
        aparecer en <strong>Mis pagos</strong> y el saldo va a bajar acá.
      </Alert>
    </ModuleShell>
  );
}
