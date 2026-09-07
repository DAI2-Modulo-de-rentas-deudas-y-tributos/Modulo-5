import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import BarChart from "../../components/auditoria/BarChart.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { conceptDefinitions } from "../../services/mockDb.js";
import { formatCompactCurrency, formatCurrency, formatPercentage } from "../../lib/format.js";

const CONCEPT_OPTIONS = conceptDefinitions.map((c) => ({ value: c.code, label: c.name }));

const MONTHS = [
  "ene", "feb", "mar", "abr", "may", "jun",
  "jul", "ago", "sep", "oct", "nov", "dic",
];

const periodLabel = (period) => {
  const [year, month] = period.split("-");
  return `${MONTHS[Number(month) - 1]} ${year.slice(2)}`;
};

/** Indicadores del período. Cada tarjeta se abre para ver qué filas la componen. */
export default function IndicadoresPage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState({ from: "", to: "", conceptCode: "" });

  const loader = useCallback(() => auditService.indicators(filters), [filters]);
  const { data: indicators, loading, error } = useResource(loader);

  const [tableView, setTableView] = useState(false);

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const query = new URLSearchParams(
    Object.entries(filters).filter(([, value]) => value),
  ).toString();

  return (
    <ModuleShell
      label="Auditoría"
      title="Indicadores"
      highlight="del período"
      description="Liquidado, recaudado, deuda, morosidad e incumplimientos, con su composición."
      breadcrumb={[{ id: "indicadores", label: "Indicadores" }]}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {error && (
        <Alert variant="error" title="No pudimos calcular los indicadores">
          {error}
        </Alert>
      )}

      <Card title="Período" description="Los importes de deuda son a hoy; el resto respeta el rango.">
        <FilterBar
          filters={[
            { name: "from", label: "Desde", type: "date" },
            { name: "to", label: "Hasta", type: "date" },
            { name: "conceptCode", label: "Concepto", options: CONCEPT_OPTIONS },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />
      </Card>

      {loading ? (
        <div className="flex items-center justify-center gap-3 py-14">
          <Spinner />
          <span className="text-[13px] text-neutral-400">Calculando indicadores…</span>
        </div>
      ) : (
        indicators && (
          <>
            <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <IndicatorTile
                label="Total liquidado"
                value={formatCurrency(indicators.totalSettled)}
                hint={`${indicators.counts.totalSettled} liquidaciones`}
                onClick={() => navigate(`/auditor/indicadores/totalSettled?${query}`)}
              />
              <IndicatorTile
                label="Total recaudado"
                value={formatCurrency(indicators.totalCollected)}
                hint={`${indicators.counts.totalCollected} pagos`}
                tone="success"
                onClick={() => navigate(`/auditor/indicadores/totalCollected?${query}`)}
              />
              <IndicatorTile
                label="Deuda pendiente"
                value={formatCurrency(indicators.pendingDebt)}
                hint={`${indicators.counts.pendingDebt} deudas a vencer`}
                onClick={() => navigate(`/auditor/indicadores/pendingDebt?${query}`)}
              />
              <IndicatorTile
                label="Deuda vencida"
                value={formatCurrency(indicators.overdueDebt)}
                hint={`${indicators.counts.overdueDebt} deudas en mora`}
                tone="danger"
                onClick={() => navigate(`/auditor/indicadores/overdueDebt?${query}`)}
              />
              <IndicatorTile
                label="Morosidad"
                value={formatPercentage(indicators.delinquencyRate)}
                hint="Deuda vencida sobre deuda viva"
                tone={indicators.delinquencyRate > 15 ? "danger" : "neutral"}
              />
              <IndicatorTile
                label="Planes incumplidos"
                value={indicators.defaultedPlans}
                hint="Con cuotas impagas"
                tone={indicators.defaultedPlans > 0 ? "danger" : "success"}
                onClick={() => navigate(`/auditor/indicadores/defaultedPlans?${query}`)}
              />
            </section>

            <Card
              title="Recaudación por período"
              description="Importe cobrado en cada mes del rango."
              actions={
                <button
                  type="button"
                  onClick={() => setTableView((value) => !value)}
                  className="text-[12px] font-semibold text-[#0F2C59] transition-colors hover:text-[#D63031]"
                >
                  {tableView ? "Ver gráficos" : "Ver como tabla"}
                </button>
              }
            >
              <div className="px-5 py-5">
                {tableView ? (
                  <DataTable
                    columns={[
                      { key: "period", header: "Período", render: (row) => periodLabel(row.period) },
                      { key: "count", header: "Pagos", align: "right" },
                      {
                        key: "amount",
                        header: "Recaudado",
                        align: "right",
                        render: (row) => formatCurrency(row.amount),
                      },
                    ]}
                    rows={indicators.byPeriod}
                    rowKey={(row) => row.period}
                    emptyIconName="ChartColumn"
                    emptyTitle="Sin recaudación en el período"
                  />
                ) : (
                  <BarChart
                    data={indicators.byPeriod.map((row) => ({
                      key: row.period,
                      label: periodLabel(row.period),
                      value: row.amount,
                      hint: `${row.count} pagos`,
                    }))}
                    formatValue={formatCompactCurrency}
                    emptyText="Sin recaudación en el período seleccionado."
                  />
                )}
              </div>
            </Card>

            <Card
              title="Deuda por concepto"
              description="Saldo vivo acumulado en cada tributo."
            >
              <div className="px-5 py-5">
                {tableView ? (
                  <DataTable
                    columns={[
                      { key: "conceptName", header: "Concepto" },
                      { key: "count", header: "Deudas", align: "right" },
                      {
                        key: "amount",
                        header: "Saldo",
                        align: "right",
                        render: (row) => formatCurrency(row.amount),
                      },
                    ]}
                    rows={indicators.byConcept}
                    rowKey={(row) => row.conceptCode}
                    emptyIconName="ChartColumn"
                    emptyTitle="Sin deuda registrada"
                  />
                ) : (
                  <BarChart
                    orientation="horizontal"
                    data={indicators.byConcept.map((row) => ({
                      key: row.conceptCode,
                      label: row.conceptName,
                      value: row.amount,
                      hint: `${row.count} deudas`,
                    }))}
                    formatValue={formatCurrency}
                    emptyText="Sin deuda registrada para ese concepto."
                  />
                )}
              </div>
            </Card>
          </>
        )
      )}
    </ModuleShell>
  );
}

function IndicatorTile({ label, value, hint, tone = "neutral", onClick }) {
  const toneClass =
    tone === "danger" ? "text-[#D63031]" : tone === "success" ? "text-emerald-600" : "text-[#0F2C59]";

  const content = (
    <>
      <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
        {label}
      </p>
      <p className={`mt-2 text-[24px] font-extrabold tabular-nums leading-none ${toneClass}`}>
        {value}
      </p>
      <p className="mt-2 text-[12px] text-neutral-400">{hint}</p>
    </>
  );

  if (!onClick) {
    return <div className="rounded-xl border border-neutral-200 bg-white p-5">{content}</div>;
  }

  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-xl border border-neutral-200 bg-white p-5 text-left transition-all duration-300 hover:border-[#D63031]/30 hover:shadow-[0_4px_24px_-6px_rgba(0,0,0,0.1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D63031]/30"
    >
      {content}
    </button>
  );
}
