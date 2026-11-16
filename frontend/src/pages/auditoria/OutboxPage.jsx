import { useCallback, useMemo, useRef, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import Button from "../../components/common/Button.jsx";
import Modal from "../../components/common/Modal.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { integrationOutboxService } from "../../services/integrationOutboxService.js";
import { formatDateTime } from "../../lib/format.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { homePathForRole } from "../../config/workspaces.js";

const PAGE_SIZE = 20;
const FAILURE_STATES = ["FAILED", "DEAD_LETTER"];
const RETRY_COUNT_LABEL = "Reintentos del ciclo actual";
const STATES = {
  PENDING: "Pendiente", LOCAL_ONLY: "Solo local", BROKER_CONFIRMED: "Confirmado por broker",
  CORE_ACCEPTED: "Aceptado por Core", PUBLISHED: "Publicado", FAILED: "Fallido", DEAD_LETTER: "Reintentos agotados",
};
const badge = (status) => <StatusBadge status={status} label={STATES[status] ?? status}
  tone={FAILURE_STATES.includes(status) ? "danger" : undefined} />;
export default function OutboxPage() {
  const { user } = useAuth();
  const loader = useCallback(() => integrationOutboxService.list(), []);
  const { data, loading, error, reload } = useResource(loader, []);
  const [filters, setFilters] = useState({ status: "", query: "" });
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState(null);
  const [retryQueued, setRetryQueued] = useState(false);
  const retryAttempts = useRef(new Map());
  const selected = data?.find((event) => event.id === selectedId);
  const hasFailed = FAILURE_STATES.includes(selected?.status);
  const canRetry = user.role === "SUPERVISOR" && selected?.targetModule !== "PLATFORM" && hasFailed;
  const eventErrorAlert = hasFailed
    ? { variant: "error", title: "Último error" }
    : { variant: "info", title: "Error del intento anterior" };
  const filtered = useMemo(() => {
    const query = filters.query.trim().toLocaleLowerCase("es");
    return (data ?? []).filter((row) => (!filters.status || row.status === filters.status)
      && (!query || [row.id, row.eventType, row.targetModule, row.aggregateId].some((value) => String(value ?? "").toLocaleLowerCase("es").includes(query))));
  }, [data, filters]);
  const pages = Math.ceil(filtered.length / PAGE_SIZE);
  const currentPage = Math.min(page, Math.max(0, pages - 1));
  const changeFilter = (name, value) => { setFilters((previous) => ({ ...previous, [name]: value })); setPage(0); };

  const openDetail = (event) => {
    setRetryError(null);
    setSelectedId(event.id);
  };

  const closeDetail = () => {
    if (retrying) return;
    setRetryError(null);
    setSelectedId(null);
  };

  const retrySelected = async () => {
    if (!canRetry || retrying) return;
    const { id } = selected;
    setRetrying(true);
    setRetryError(null);
    setRetryQueued(false);
    try {
      // Conserva la misma clave y versión si se perdió la respuesta del POST.
      const attempt = retryAttempts.current.get(id) ?? await integrationOutboxService.prepareRetry(selected);
      retryAttempts.current.set(id, attempt);
      await integrationOutboxService.retry(id, attempt);
      retryAttempts.current.delete(id);
      setRetryQueued(true);
      setSelectedId(null);
      await reload();
    } catch (caught) {
      setRetryError(caught.message ?? "No se pudo solicitar el reintento.");
      if ([404, 409, 412].includes(caught.status)) {
        retryAttempts.current.delete(id);
        await reload();
      }
    } finally {
      setRetrying(false);
    }
  };

  const columns = [
    { key: "eventType", header: "Evento" },
    { key: "targetModule", header: "Destino" },
    { key: "aggregateId", header: "Referencia" },
    { key: "status", header: "Estado", render: (row) => badge(row.status) },
    { key: "retryCount", header: RETRY_COUNT_LABEL, align: "right" },
    { key: "createdAt", header: "Creado", render: (row) => formatDateTime(row.createdAt) },
    { key: "detail", header: "", render: (row) => <Button size="sm" variant="secondary" onClick={() => openDetail(row)}>Ver detalle</Button> },
  ];
  return <ModuleShell label="Integraciones" title="Eventos salientes" description="Estado de los envíos a otros módulos y resultado de cada intento."
    homePath={homePathForRole(user.role)}
    breadcrumb={[{ id: "outbox", label: "Eventos salientes" }]}>
    <Alert variant="info" title="Estados de entrega">«Solo local» no confirma recepción; «Aceptado por Core» o «Confirmado por broker» tampoco confirman procesamiento por el consumidor.</Alert>
    {retryQueued && <Alert variant="success" title="Reintento encolado" onDismiss={() => setRetryQueued(false)}>El evento quedó en cola y el contador de reintentos del ciclo se reinició.</Alert>}
    {retryError && !selected && <Alert variant="error" title="No se pudo reintentar el envío" onDismiss={() => setRetryError(null)}>{retryError}</Alert>}
    <Card title="Bandeja de envíos" actions={<Button variant="secondary" loading={loading} onClick={reload}>Actualizar</Button>}>
      <FilterBar searchValue={filters.query} searchPlaceholder="Evento, destino o referencia…" onSearchChange={(value) => changeFilter("query", value)}
        filters={[{ name: "status", label: "Estado", options: Object.entries(STATES).map(([value, label]) => ({ value, label })) }]}
        values={filters} onFilterChange={changeFilter} />
      {error ? <Alert variant="error" title="No se pudieron cargar los eventos"><p>{error}</p><Button variant="secondary" onClick={reload}>Reintentar</Button></Alert>
        : <DataTable columns={columns} rows={filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)} rowKey={(row) => row.id} loading={loading}
          emptyTitle="Sin eventos salientes" emptyDescription="No hay eventos que coincidan con los filtros." emptyIconName="Send" />}
      {!loading && !error && filtered.length > 0 && <nav aria-label="Paginación de eventos" className="flex flex-wrap items-center justify-between gap-3 p-4 text-sm">
        <span>{filtered.length} eventos · Página {currentPage + 1} de {pages}</span>
        <div className="flex gap-2"><Button variant="secondary" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>Anterior</Button>
          <Button variant="secondary" disabled={currentPage + 1 >= pages} onClick={() => setPage(currentPage + 1)}>Siguiente</Button></div>
      </nav>}
    </Card>
    {selected && <Modal open title="Detalle del evento saliente" description={selected.id} onClose={closeDetail}
      footer={<>
        <Button variant="secondary" disabled={retrying} onClick={closeDetail}>Cerrar</Button>
        {canRetry && <Button loading={retrying} onClick={retrySelected}>Reintentar envío</Button>}
      </>}>
      <div className="flex flex-col gap-4">
        {retryError && <Alert variant="error" title="No se pudo reintentar el envío">{retryError}</Alert>}
        {badge(selected.status)}
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div><dt className="text-neutral-500">Evento</dt><dd>{selected.eventType}</dd></div>
          <div><dt className="text-neutral-500">Destino</dt><dd>{selected.targetModule}</dd></div>
          <div><dt className="text-neutral-500">Creado</dt><dd>{formatDateTime(selected.createdAt)}</dd></div>
          <div><dt className="text-neutral-500">Publicado</dt><dd>{formatDateTime(selected.publishedAt)}</dd></div>
          <div><dt className="text-neutral-500">Último intento</dt><dd>{formatDateTime(selected.lastAttemptAt)}</dd></div>
          <div><dt className="text-neutral-500">{RETRY_COUNT_LABEL}</dt><dd>{selected.retryCount}</dd></div>
        </dl>
        {selected.errorMessage && <Alert {...eventErrorAlert}>{selected.errorMessage}</Alert>}
        <p className="text-sm text-neutral-500">El detalle técnico completo del payload no se muestra en esta pantalla.</p>
      </div>
    </Modal>}
  </ModuleShell>;
}
