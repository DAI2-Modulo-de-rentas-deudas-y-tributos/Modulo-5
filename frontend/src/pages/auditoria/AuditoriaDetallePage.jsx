import { useCallback } from "react";
import { useNavigate, useParams } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService } from "../../services/rentasService.js";
import {
  actionLabelFor,
  entityLabelFor,
  formatDateTime,
  labelFor,
} from "../../lib/format.js";

/** Detalle de un registro de auditoría: el cambio reconstruido valor por valor. */
export default function AuditoriaDetallePage() {
  const { entryId } = useParams();
  const navigate = useNavigate();

  const loader = useCallback(() => auditService.auditDetail(entryId), [entryId]);
  const { data: entry, loading, error } = useResource(loader);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-3 py-24">
        <Spinner />
        <span className="text-[13px] text-neutral-400">Cargando el registro…</span>
      </div>
    );
  }

  const breadcrumb = [
    { id: "auditoria", label: "Auditoría", path: "/auditor/auditoria" },
    { id: "detalle", label: `#${entryId}` },
  ];

  if (error || !entry) {
    return (
      <ModuleShell
        label="Auditoría"
        title="Registro"
        breadcrumb={breadcrumb}
        homePath="/auditor"
        homeLabel="Dashboard"
      >
        <Alert variant="error" title="No pudimos abrir el registro">
          {error ?? "El registro no existe."}
        </Alert>
      </ModuleShell>
    );
  }

  const links = entry.references ?? {};

  return (
    <ModuleShell
      label="Auditoría"
      title={actionLabelFor(entry.action)}
      description={`${entry.username} · ${formatDateTime(entry.at)}`}
      breadcrumb={breadcrumb}
      homePath="/auditor"
      homeLabel="Dashboard"
    >
      {entry.result === "ERROR" && (
        <Alert variant="error" title="La operación terminó con error">
          {entry.reason}
        </Alert>
      )}

      <Card title="Datos del registro">
        <div className="px-5 py-4">
          <FieldGrid
            columns={4}
            items={[
              { label: "Fecha/Hora", value: formatDateTime(entry.at) },
              { label: "Usuario", value: entry.username },
              { label: "Rol", value: labelFor(entry.role) },
              { label: "Resultado", value: <StatusBadge status={entry.result} /> },
              { label: "Acción", value: actionLabelFor(entry.action) },
              {
                label: "Entidad afectada",
                value: `${entityLabelFor(entry.entity.type)} ${entry.entity.id}`,
              },
              { label: "Motivo", value: entry.reason, span: 2 },
            ]}
          />
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card title="Valores anteriores">
          <div className="px-5 py-4">
            <ValueList values={entry.before} empty="La entidad no existía antes de esta acción." />
          </div>
        </Card>
        <Card title="Valores posteriores">
          <div className="px-5 py-4">
            <ValueList values={entry.after} empty="La acción no dejó valores nuevos." />
          </div>
        </Card>
      </div>

      <Card title="Referencias" description="Las entidades que quedaron alcanzadas por el cambio.">
        <div className="flex flex-wrap gap-2 px-5 py-4">
          {links.paymentId && (
            <Button size="sm" variant="secondary" onClick={() => navigate(`/auditor/pagos/${links.paymentId}`)}>
              Pago #{links.paymentId}
            </Button>
          )}
          {links.debtId && (
            <Button size="sm" variant="secondary" onClick={() => navigate(`/auditor/deudas/${links.debtId}`)}>
              Deuda #{links.debtId}
            </Button>
          )}
          {links.settlementId && (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => navigate(`/auditor/liquidaciones/${links.settlementId}`)}
            >
              Liquidación #{links.settlementId}
            </Button>
          )}
          {links.reversalId && (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => navigate(`/auditor/reversiones/${links.reversalId}`)}
            >
              Reversión #{links.reversalId}
            </Button>
          )}
          {links.exemptionId && (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => navigate(`/auditor/exenciones/${links.exemptionId}`)}
            >
              Exención #{links.exemptionId}
            </Button>
          )}
          {links.ticketId && (
            <Button size="sm" variant="secondary" onClick={() => navigate(`/auditor/tickets/${links.ticketId}`)}>
              Ticket #{links.ticketId}
            </Button>
          )}
          {links.taxpayerId && (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => navigate(`/auditor/contribuyentes/${links.taxpayerId}`)}
            >
              Contribuyente #{links.taxpayerId}
            </Button>
          )}
          {Object.keys(links).length === 0 && (
            <p className="text-[13px] text-neutral-400">Sin referencias registradas.</p>
          )}
        </div>
      </Card>
    </ModuleShell>
  );
}

function ValueList({ values, empty }) {
  const rows = Object.entries(values ?? {});
  if (rows.length === 0) {
    return <p className="text-[13px] text-neutral-400">{empty}</p>;
  }
  return (
    <dl className="flex flex-col gap-2">
      {rows.map(([key, value]) => (
        <div key={key} className="flex items-baseline justify-between gap-4">
          <dt className="text-[13px] text-neutral-500">{key}</dt>
          <dd className="text-[13px] font-medium tabular-nums text-neutral-800">
            {value === null || value === undefined ? "—" : String(value)}
          </dd>
        </div>
      ))}
    </dl>
  );
}
