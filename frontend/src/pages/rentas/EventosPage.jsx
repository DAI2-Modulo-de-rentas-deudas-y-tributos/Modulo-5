import { useCallback, useState } from "react";
import { ArrowDownLeft, ArrowUpRight } from "lucide-react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import StatTile from "../../components/common/StatTile.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { eventService } from "../../services/rentasService.js";
import { formatDateTime } from "../../lib/format.js";

/**
 * Bitácora de eventos: evidencia de lo publicado y procesado, con reproceso manual
 * de la DLQ. El reintento es seguro porque los consumidores son idempotentes por eventId.
 */
export default function EventosPage() {
  const [filters, setFilters] = useState({ direction: "", status: "" });
  const [search, setSearch] = useState("");
  const [feedback, setFeedback] = useState(null);
  const [retrying, setRetrying] = useState(null);

  const loader = useCallback(
    () => eventService.list({ ...filters, eventType: search }),
    [filters, search],
  );
  const { data: events, loading, error, reload } = useResource(loader, []);

  const rows = events ?? [];
  const inDlq = rows.filter((e) => e.status === "DLQ").length;
  const retryingCount = rows.filter((e) => e.status === "RETRYING").length;

  const onFilterChange = (name, value) =>
    setFilters((previous) => ({ ...previous, [name]: value }));

  const onRetry = async (event) => {
    setRetrying(event.eventId);
    setFeedback(null);
    try {
      await eventService.retry(event.eventId);
      setFeedback({
        variant: "success",
        title: "Evento reprocesado",
        message: `${event.eventType} se procesó correctamente.`,
      });
      reload();
    } catch (caught) {
      setFeedback({ variant: "error", title: "No se pudo reprocesar", message: caught.message });
    } finally {
      setRetrying(null);
    }
  };

  const columns = [
    {
      key: "direction",
      header: "",
      render: (row) =>
        row.direction === "IN" ? (
          <ArrowDownLeft className="h-4 w-4 text-blue-500" strokeWidth={2} />
        ) : (
          <ArrowUpRight className="h-4 w-4 text-emerald-500" strokeWidth={2} />
        ),
    },
    {
      key: "eventType",
      header: "Evento",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.eventType}</span>
          <span className="text-[11px] text-neutral-400">{row.eventId}</span>
        </div>
      ),
    },
    {
      key: "ruta",
      header: "Ruta",
      render: (row) => (
        <span className="text-[12px] font-medium text-neutral-500">
          {row.sourceModule} → {row.destinationModule}
        </span>
      ),
    },
    { key: "occurredAt", header: "Ocurrió", render: (row) => formatDateTime(row.occurredAt) },
    { key: "processedAt", header: "Procesado", render: (row) => formatDateTime(row.processedAt) },
    { key: "attempts", header: "Intentos", align: "right" },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        row.status === "DLQ" ? (
          <Button
            size="sm"
            variant="secondary"
            loading={retrying === row.eventId}
            onClick={() => onRetry(row)}
          >
            Reprocesar
          </Button>
        ) : null,
    },
  ];

  return (
    <ModuleShell
      label="Supervisión"
      title="Bitácora"
      highlight="de eventos"
      description="Trazabilidad de la integración: qué publicó Rentas, qué consumió y qué quedó pendiente."
      breadcrumb={[{ id: "eventos", label: "Bitácora de eventos" }]}
    >
      {feedback && (
        <Alert variant={feedback.variant} title={feedback.title} onDismiss={() => setFeedback(null)}>
          {feedback.message}
        </Alert>
      )}
      {error && <Alert variant="error" title="No pudimos cargar la bitácora">{error}</Alert>}

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatTile label="Eventos listados" value={rows.length} iconName="Activity" />
        <StatTile
          label="Reintentando"
          value={retryingCount}
          hint="Hasta 3 intentos antes de la DLQ"
          iconName="RefreshCw"
        />
        <StatTile
          label="En DLQ"
          value={inDlq}
          hint="Requieren intervención manual"
          iconName="AlertTriangle"
          tone={inDlq > 0 ? "danger" : "success"}
        />
      </section>

      <Card
        title="Eventos"
        description="El reproceso es seguro: los consumidores descartan un eventId ya procesado."
      >
        <FilterBar
          searchValue={search}
          searchPlaceholder="Filtrar por tipo de evento…"
          onSearchChange={setSearch}
          filters={[
            {
              name: "direction",
              label: "Dirección",
              options: [
                { value: "IN", label: "Consumidos" },
                { value: "OUT", label: "Publicados" },
              ],
            },
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "PUBLISHED", label: "Publicado" },
                { value: "PROCESSED", label: "Procesado" },
                { value: "RETRYING", label: "Reintentando" },
                { value: "DLQ", label: "En DLQ" },
              ],
            },
          ]}
          values={filters}
          onFilterChange={onFilterChange}
        />

        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.eventId}
          loading={loading}
          emptyIconName="Activity"
          emptyTitle="Sin eventos"
          emptyDescription="No hay registros que coincidan con los filtros."
        />
      </Card>
    </ModuleShell>
  );
}
