import { useCallback } from "react";
import { useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
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
        description="Cada cambio de regla queda versionado: permite auditar con qué criterio se liquidó cada período."
      >
        <DataTable
          columns={[
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
    </ModuleShell>
  );
}
