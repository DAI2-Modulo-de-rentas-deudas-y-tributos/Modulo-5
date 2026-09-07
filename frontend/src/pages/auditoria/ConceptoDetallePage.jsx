import { useCallback, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import Button from "../../components/common/Button.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import { formatCurrency, formatDate, formatPercentage, labelFor } from "../../lib/format.js";
import { MODULE_LABELS } from "../../services/mockDb.js";

/** Ficha del concepto y sus versiones: permite auditar con qué regla se liquidó. */
export default function ConceptoDetallePage() {
  const { code } = useParams();
  // Dos versiones elegidas de a una: la comparación necesita exactamente dos.
  const [comparadas, setComparadas] = useState([]);
  const loader = useCallback(() => auditService.conceptDetail(code), [code]);
  const { data: concept, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando el concepto…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "conceptos", label: "Conceptos", path: "/auditor/conceptos" },
    { id: "detalle", label: concept?.code ?? code },
  ];

  if (error || !concept) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Concepto"
        description="No pudimos abrir la ficha."
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir el concepto">
          {error ?? "El concepto no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  return (
    <ModuleShell
      label="Auditoría"
      title={concept.code}
      description={concept.name}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      <Card title="Definición vigente">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Código", value: concept.code },
              { label: "Nombre", value: concept.name },
              { label: "Tipo", value: <StatusBadge status={concept.type} /> },
              { label: "Estado", value: <StatusBadge status={concept.status} /> },
              { label: "Tipo de cálculo", value: labelFor(concept.calculationType) },
              {
                label: "Alícuota",
                value: concept.rate === null ? null : formatPercentage(concept.rate),
              },
              { label: "Mínimo", value: concept.minimumAmount && formatCurrency(concept.minimumAmount) },
              { label: "Máximo", value: concept.maximumAmount && formatCurrency(concept.maximumAmount) },
              {
                label: "Vigencia",
                value: `${formatDate(concept.validFrom)} — ${formatDate(concept.validUntil)}`,
                span: 2,
              },
              {
                label: "Importe informado por",
                value: concept.externalModule ? MODULE_LABELS[concept.externalModule] : null,
                span: 2,
              },
            ]}
          />
        </div>
      </Card>

      {concept.externalModule && (
        <Alert variant="info" title="El importe no lo calcula Rentas">
          Este concepto llega valorizado desde {MODULE_LABELS[concept.externalModule]} por evento.
          Rentas lo liquida y lo cobra, pero no define su monto.
        </Alert>
      )}

      <Card
        title="Historial / Versiones"
        description="Elegí dos versiones para ver exactamente qué cambió entre una y otra."
        actions={
          comparadas.length > 0 && (
            <Button size="sm" variant="secondary" onClick={() => setComparadas([])}>
              Limpiar selección
            </Button>
          )
        }
      >
        <DataTable
          columns={[
            {
              key: "seleccion",
              header: "",
              render: (row) => (
                <input
                  type="checkbox"
                  checked={comparadas.includes(row.version)}
                  // Sólo se comparan dos: la tercera queda deshabilitada.
                  disabled={comparadas.length === 2 && !comparadas.includes(row.version)}
                  onChange={() =>
                    setComparadas((previas) =>
                      previas.includes(row.version)
                        ? previas.filter((v) => v !== row.version)
                        : [...previas, row.version],
                    )
                  }
                  aria-label={`Comparar la versión ${row.version}`}
                  className="h-4 w-4 accent-[#0F2C59] disabled:opacity-30"
                />
              ),
            },
            {
              key: "version",
              header: "Versión",
              render: (row) => <span className="tabular-nums">v{row.version}</span>,
            },
            { key: "date", header: "Fecha", render: (row) => formatDate(row.date) },
            { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
            { key: "user", header: "Usuario" },
            { key: "note", header: "Cambio" },
          ]}
          rows={concept.versions}
          rowKey={(row) => row.version}
          emptyIconName="Tags"
          emptyTitle="Sin versiones"
        />
      </Card>

      <VersionComparison concept={concept} selected={comparadas} />
    </ModuleShell>
  );
}

/** Los parámetros que definen una versión, en el orden en que se leen. */
const COMPARED_FIELDS = [
  { key: "calculationType", label: "Forma de cálculo", format: (v) => labelFor(v) },
  { key: "rate", label: "Alícuota", format: (v) => (v == null ? "—" : formatPercentage(v)) },
  { key: "minimumAmount", label: "Mínimo", format: (v) => (v == null ? "—" : formatCurrency(v)) },
  { key: "maximumAmount", label: "Máximo", format: (v) => (v == null ? "—" : formatCurrency(v)) },
  { key: "validFrom", label: "Vigente desde", format: (v) => (v ? formatDate(v) : "—") },
  { key: "validUntil", label: "Vigente hasta", format: (v) => (v ? formatDate(v) : "—") },
];

/**
 * Comparación entre dos versiones.
 *
 * Muestra sólo lo que cambió, porque es lo que el auditor está buscando: qué se
 * modificó entre el criterio con el que se liquidó un período y el del siguiente.
 * Las versiones viejas del dataset no guardan parámetros, y eso también se informa.
 */
function VersionComparison({ concept, selected }) {
  const [a, b] = useMemo(() => {
    const encontradas = selected
      .map((n) => concept.versions.find((v) => v.version === n))
      .filter(Boolean)
      .sort((x, y) => x.version - y.version);
    return [encontradas[0] ?? null, encontradas[1] ?? null];
  }, [concept.versions, selected]);

  if (selected.length === 0) return null;

  if (!b) {
    return (
      <Alert variant="info" title="Elegí una segunda versión">
        Marcá otra versión del historial para ver la comparación.
      </Alert>
    );
  }

  const sinParametros = !a.calculationType && !b.calculationType;
  const filas = COMPARED_FIELDS.map((campo) => ({
    ...campo,
    antes: a[campo.key],
    despues: b[campo.key],
    cambio: a[campo.key] !== b[campo.key],
  }));
  const cambios = filas.filter((f) => f.cambio);

  return (
    <Card
      title={`Comparación · v${a.version} → v${b.version}`}
      description={
        sinParametros
          ? "Estas versiones son anteriores al versionado de parámetros."
          : cambios.length === 0
            ? "Los parámetros son idénticos entre ambas versiones."
            : `${cambios.length} ${cambios.length === 1 ? "parámetro cambió" : "parámetros cambiaron"}.`
      }
    >
      {sinParametros ? (
        <div className="px-5 py-8 text-center text-[13px] text-neutral-400">
          Ninguna de las dos guarda sus reglas de cálculo: sólo se registró el cambio
          en la nota del historial.
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-b border-neutral-200">
                <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">
                  Parámetro
                </th>
                <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">
                  v{a.version} · {labelFor(a.status)}
                </th>
                <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.08em] text-neutral-400">
                  v{b.version} · {labelFor(b.status)}
                </th>
              </tr>
            </thead>
            <tbody>
              {filas.map((fila) => (
                <tr
                  key={fila.key}
                  className={`border-b border-neutral-100 last:border-0 ${
                    fila.cambio ? "bg-[#D63031]/[0.03]" : ""
                  }`}
                >
                  <td className="px-4 py-3 text-[13px] text-neutral-500">{fila.label}</td>
                  <td className="px-4 py-3 text-[13px] text-neutral-400">
                    {fila.format(fila.antes)}
                  </td>
                  <td
                    className={`px-4 py-3 text-[13px] ${
                      fila.cambio ? "font-semibold text-[#0F2C59]" : "text-neutral-400"
                    }`}
                  >
                    {fila.format(fila.despues)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="border-t border-neutral-100 px-5 py-4">
        <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
          Qué se registró en cada cambio
        </p>
        <p className="mt-1.5 text-[13px] text-neutral-600">
          <span className="font-medium">v{a.version}:</span> {a.note || "—"}{" "}
          <span className="text-neutral-400">({a.user})</span>
        </p>
        <p className="mt-1 text-[13px] text-neutral-600">
          <span className="font-medium">v{b.version}:</span> {b.note || "—"}{" "}
          <span className="text-neutral-400">({b.user})</span>
        </p>
      </div>
    </Card>
  );
}
