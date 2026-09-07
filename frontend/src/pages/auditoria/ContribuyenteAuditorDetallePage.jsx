import { useCallback, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import {
  formatCurrency,
  formatDate,
  formatDateTime,
  formatPercentage,
  labelFor,
} from "../../lib/format.js";

const TABS = [
  { id: "settlements", label: "Liquidaciones" },
  { id: "debts", label: "Deudas" },
  { id: "payments", label: "Pagos" },
  { id: "plans", label: "Planes" },
  { id: "exemptions", label: "Exenciones" },
];

/** Ficha 360° del contribuyente: los cuatro frentes que el auditor cruza. */
export default function ContribuyenteAuditorDetallePage() {
  const { taxpayerId } = useParams();
  const navigate = useNavigate();
  const [tab, setTab] = useState("settlements");

  const loader = useCallback(() => auditService.taxpayerFile(taxpayerId), [taxpayerId]);
  const { data: file, loading, error } = useResource(loader);

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
        label="Auditoría"
        title="Contribuyente"
        description="No pudimos abrir la ficha."
        breadcrumb={[
          { id: "contribuyentes", label: "Contribuyentes", path: "/auditor/contribuyentes" },
          { id: "detalle", label: "Ficha" },
        ]}
        homePath="/auditor"
        homeLabel="Dashboard"
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
      label="Auditoría"
      title={taxpayer.name}
      description={`${labelFor(taxpayer.type)} · ${taxpayer.documentType} ${taxpayer.document} · CUIT ${taxpayer.cuit}`}
      breadcrumb={[
        { id: "contribuyentes", label: "Contribuyentes", path: "/auditor/contribuyentes" },
        { id: "detalle", label: taxpayer.name },
      ]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      <Card title="Identificación">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Nombre", value: taxpayer.name },
              { label: "Tipo", value: labelFor(taxpayer.type) },
              { label: "Documento", value: `${taxpayer.documentType} ${taxpayer.document}` },
              { label: "CUIT/CUIL", value: taxpayer.cuit },
              { label: "Situación", value: <StatusBadge status={taxpayer.status} /> },
              { label: "Referencia externa", value: taxpayer.externalRef },
              {
                label: "Beneficio social (M8)",
                value:
                  taxpayer.benefit?.status === "ACTIVE"
                    ? `${formatPercentage(taxpayer.benefit.discountPercentage)} hasta ${formatDate(taxpayer.benefit.validUntil)}`
                    : "Sin beneficio activo",
                span: 2,
              },
            ]}
          />
        </div>
      </Card>

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatTile
          label="Deuda total"
          value={formatCurrency(totals.totalDebt)}
          hint={`${file.debts.filter((d) => d.outstandingAmount > 0).length} deudas con saldo`}
          iconName="FileWarning"
        />
        <StatTile
          label="Deuda vencida"
          value={formatCurrency(totals.overdueDebt)}
          hint="Exigible"
          iconName="TrendingDown"
          tone={totals.overdueDebt > 0 ? "danger" : "success"}
        />
        <StatTile
          label="Saldo a favor"
          value={formatCurrency(totals.creditBalance)}
          hint="Pagos sin imputar acreditados"
          iconName="Wallet"
          tone={totals.creditBalance > 0 ? "success" : "neutral"}
        />
      </section>

      <Card
        title="Historial / información seleccionada"
        description="Elegí el frente que querés revisar."
        actions={
          <div className="flex flex-wrap gap-1">
            {TABS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setTab(item.id)}
                className={`rounded-lg px-3 py-1.5 text-[12px] font-semibold transition-colors ${
                  tab === item.id
                    ? "bg-[#0F2C59] text-white"
                    : "text-neutral-500 hover:bg-neutral-100"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
        }
      >
        {tab === "settlements" && (
          <DataTable
            columns={[
              {
                key: "id",
                header: "Liquidación",
                render: (row) => <span className="tabular-nums">#{row.id}</span>,
              },
              { key: "conceptName", header: "Concepto" },
              { key: "period", header: "Período" },
              {
                key: "amount",
                header: "Importe",
                align: "right",
                render: (row) => formatCurrency(row.amount),
              },
              {
                key: "conceptVersion",
                header: "Versión",
                align: "right",
                render: (row) =>
                  row.conceptVersion ? (
                    <span className="tabular-nums">v{row.conceptVersion}</span>
                  ) : (
                    <span className="text-neutral-300">—</span>
                  ),
              },
              { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
            ]}
            rows={file.settlements}
            rowKey={(row) => row.id}
            emptyIconName="Calculator"
            emptyTitle="Sin liquidaciones"
            emptyDescription="No se emitieron liquidaciones a su nombre."
            onRowClick={(row) => navigate(`/auditor/liquidaciones/${row.id}`)}
          />
        )}

        {tab === "debts" && (
          <DataTable
            columns={[
              { key: "id", header: "Deuda", render: (row) => <span className="tabular-nums">#{row.id}</span> },
              { key: "conceptName", header: "Concepto" },
              { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
              {
                key: "outstandingAmount",
                header: "Saldo",
                align: "right",
                render: (row) => formatCurrency(row.outstandingAmount),
              },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
            ]}
            rows={file.debts}
            rowKey={(row) => row.id}
            emptyIconName="FileWarning"
            emptyTitle="Sin deudas"
            onRowClick={(row) => navigate(`/auditor/deudas/${row.id}`)}
          />
        )}

        {tab === "payments" && (
          <DataTable
            columns={[
              { key: "receiptNumber", header: "Comprobante" },
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
            ]}
            rows={file.payments}
            rowKey={(row) => row.id}
            emptyIconName="Banknote"
            emptyTitle="Sin pagos"
            onRowClick={(row) => navigate(`/auditor/pagos/${row.id}`)}
          />
        )}

        {tab === "plans" && (
          <DataTable
            columns={[
              {
                key: "requestId",
                header: "Solicitud",
                render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
              },
              { key: "installments", header: "Cuotas", align: "right" },
              {
                key: "totalDebt",
                header: "Deuda incluida",
                align: "right",
                render: (row) => formatCurrency(row.totalDebt),
              },
              { key: "status", header: "Resolución", render: (row) => <StatusBadge status={row.status} /> },
              {
                key: "lifecycle",
                header: "Situación",
                render: (row) =>
                  row.lifecycle ? (
                    <StatusBadge status={row.lifecycle} />
                  ) : (
                    <span className="text-neutral-300">—</span>
                  ),
              },
            ]}
            rows={file.plans}
            rowKey={(row) => row.requestId}
            emptyIconName="CalendarClock"
            emptyTitle="Sin planes de pago"
            onRowClick={(row) => navigate(`/auditor/planes/${row.requestId}`)}
          />
        )}

        {tab === "exemptions" && (
          <DataTable
            columns={[
              {
                key: "requestId",
                header: "Solicitud",
                render: (row) => <span className="tabular-nums">#{row.requestId}</span>,
              },
              { key: "conceptName", header: "Concepto" },
              {
                key: "requestedPercentage",
                header: "Solicitado",
                align: "right",
                render: (row) => formatPercentage(row.requestedPercentage),
              },
              {
                key: "percentage",
                header: "Aprobado",
                align: "right",
                render: (row) =>
                  row.percentage === undefined || row.percentage === null
                    ? "—"
                    : formatPercentage(row.percentage),
              },
              { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
            ]}
            rows={file.exemptions}
            rowKey={(row) => row.requestId}
            emptyIconName="ShieldCheck"
            emptyTitle="Sin exenciones"
            onRowClick={(row) => navigate(`/auditor/exenciones/${row.requestId}`)}
          />
        )}
      </Card>
    </ModuleShell>
  );
}
