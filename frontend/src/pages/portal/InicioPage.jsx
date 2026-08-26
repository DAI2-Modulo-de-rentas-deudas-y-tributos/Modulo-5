import { useCallback } from "react";
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
import { PORTAL_MODULES } from "../../config/portalModules.js";
import { portalService } from "../../services/rentasService.js";
import useResource from "../../hooks/useResource.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate } from "../../lib/format.js";

/** Portada del portal: cómo está la cuenta y qué necesita atención. */
export default function InicioPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const summaryLoader = useCallback(
    () => portalService.accountSummary(user.taxpayerId),
    [user.taxpayerId],
  );
  const { data: summary, loading, error } = useResource(summaryLoader);

  const noticesLoader = useCallback(
    () => portalService.notices(user.taxpayerId),
    [user.taxpayerId],
  );
  const { data: notices } = useResource(noticesLoader, []);

  const firstName = user.fullName.split(" ")[0];

  const obligationColumns = [
    { key: "conceptName", header: "Concepto" },
    {
      key: "dueDate",
      header: "Vencimiento",
      render: (row) => (
        <div className="flex flex-col">
          <span>{formatDate(row.dueDate)}</span>
          <span className="text-[12px] text-neutral-400">
            {row.daysLeft < 0
              ? `Vencida hace ${Math.abs(row.daysLeft)} días`
              : row.daysLeft === 0
                ? "Vence hoy"
                : `En ${row.daysLeft} días`}
          </span>
        </div>
      ),
    },
    {
      key: "outstandingAmount",
      header: "Saldo",
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
          <span className="tabular-nums text-neutral-600">#{row.billId}</span>
        ) : (
          <span className="text-neutral-300">Sin emitir</span>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        label={user.roleLabel}
        title={`Hola, ${firstName}.`}
        highlight="Tu cuenta"
        description="Tu situación con la Dirección General de Rentas, al día de hoy."
      />

      <div className="mx-auto flex max-w-6xl flex-col gap-8 px-5 py-8">
        {error && (
          <Alert variant="error" title="No pudimos cargar tu cuenta">
            {error}
          </Alert>
        )}

        {loading ? (
          <div className="flex items-center justify-center gap-3 py-10">
            <Spinner />
            <span className="text-[13px] text-neutral-400">Consultando tu cuenta…</span>
          </div>
        ) : (
          summary && (
            <>
              <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <StatTile
                  label="Deuda total"
                  value={formatCurrency(summary.totalDebt)}
                  hint={
                    summary.overdueDebt > 0
                      ? `${formatCurrency(summary.overdueDebt)} vencidos`
                      : "Sin deuda vencida"
                  }
                  iconName="FileWarning"
                  tone={summary.overdueDebt > 0 ? "danger" : "neutral"}
                />
                <StatTile
                  label="Próximo vencimiento"
                  value={
                    summary.nextDueDate ? formatDate(summary.nextDueDate.dueDate) : "—"
                  }
                  hint={
                    summary.nextDueDate
                      ? `${summary.nextDueDate.conceptName} · ${formatCurrency(summary.nextDueDate.amount)}`
                      : "No tenés obligaciones pendientes"
                  }
                  iconName="CalendarClock"
                  tone={summary.nextDueDate?.overdue ? "danger" : "neutral"}
                />
                <StatTile
                  label="Saldo a favor"
                  value={formatCurrency(summary.creditBalance)}
                  hint={
                    summary.creditBalance > 0
                      ? "Podés aplicarlo a una deuda"
                      : "No tenés saldo a favor"
                  }
                  iconName="Wallet"
                  tone={summary.creditBalance > 0 ? "success" : "neutral"}
                />
              </section>

              {notices?.length > 0 && (
                <section className="flex flex-col gap-3">
                  <h2 className="text-[15px] font-semibold text-[#0F2C59]">Avisos</h2>
                  {notices.map((notice) => (
                    <Alert key={notice.id} variant={notice.severity} title={notice.title}>
                      <div className="flex flex-wrap items-center gap-3">
                        <span>{notice.detail}</span>
                        {notice.path && (
                          <button
                            type="button"
                            onClick={() => navigate(notice.path)}
                            className="font-semibold underline underline-offset-2"
                          >
                            Ver
                          </button>
                        )}
                      </div>
                    </Alert>
                  ))}
                </section>
              )}

              <Card
                title="Mis obligaciones"
                description="Lo que tenés pendiente, ordenado por vencimiento."
                actions={
                  summary.obligations.length > 0 && (
                    <Button size="sm" variant="primary" onClick={() => navigate("/portal/planes")}>
                      Pedir plan de pago
                    </Button>
                  )
                }
              >
                <DataTable
                  columns={obligationColumns}
                  rows={summary.obligations}
                  rowKey={(row) => row.id}
                  emptyIconName="CircleCheckBig"
                  emptyTitle="Estás al día"
                  emptyDescription="No tenés obligaciones pendientes con Rentas."
                  onRowClick={() => navigate("/portal/deudas")}
                />
              </Card>

              <Alert variant="info" title="Este portal es de consulta">
                Acá podés ver tu situación y pedir un plan de pago o una exención. Los pagos
                se registran en la ventanilla de Rentas o por los canales habilitados; cuando
                se acrediten, aparecen en <strong>Mis pagos</strong>.
              </Alert>

              <section className="flex flex-col gap-4">
                <h2 className="text-[15px] font-semibold text-[#0F2C59]">Mi cuenta</h2>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {PORTAL_MODULES.map((module) => (
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
    </>
  );
}
