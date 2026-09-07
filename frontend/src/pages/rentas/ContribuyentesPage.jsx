import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import Spinner from "../../components/ui/Spinner.jsx";
import useResource from "../../hooks/useResource.js";
import { debtService, taxpayerService } from "../../services/rentasService.js";
import { formatCurrency, formatDate, labelFor } from "../../lib/format.js";

/**
 * Padrón local de contribuyentes: réplica de sólo lectura alimentada por los eventos
 * de M1 (citizenRegistered, citizenUpdated, citizenBlocked, citizenDeceased,
 * organizationRegistered). Rentas nunca modifica estos datos.
 */
export default function ContribuyentesPage() {
  const [query, setQuery] = useState("");
  const [type, setType] = useState("");
  const [selected, setSelected] = useState(null);

  const loader = useCallback(() => taxpayerService.search({ query, type }), [query, type]);
  const { data: taxpayers, loading, error } = useResource(loader, []);

  const columns = [
    {
      key: "name",
      header: "Contribuyente",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.name}</span>
          <span className="text-[12px] text-neutral-400">{row.externalRef}</span>
        </div>
      ),
    },
    { key: "type", header: "Tipo", render: (row) => <StatusBadge status={row.type} /> },
    {
      key: "document",
      header: "Documento",
      render: (row) => (
        <span className="tabular-nums">
          {row.documentType} {row.document}
        </span>
      ),
    },
    { key: "cuit", header: "CUIT/CUIL", render: (row) => <span className="tabular-nums">{row.cuit}</span> },
    { key: "status", header: "Situación", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "benefit",
      header: "Beneficio M8",
      render: (row) =>
        row.benefit?.status === "ACTIVE" ? (
          <StatusBadge tone="success" label={`${row.benefit.discountPercentage}% desc.`} />
        ) : (
          <span className="text-neutral-300">—</span>
        ),
    },
  ];

  return (
    <ModuleShell
      label="Padrón"
      title="Contribuyentes"
      highlight="del municipio"
      description="Ciudadanos y organizaciones replicados desde el Módulo 1. Consulta de sólo lectura."
      breadcrumb={[{ id: "contribuyentes", label: "Contribuyentes" }]}
    >
      {error && <Alert variant="error" title="No pudimos cargar el padrón">{error}</Alert>}

      <Alert variant="info" title="Datos administrados por el Módulo 1">
        Altas, bajas y modificaciones se resuelven en Ciudadanos. Rentas mantiene una réplica
        local actualizada por eventos y la usa sólo para vincular deudas y pagos.
      </Alert>

      <Card
        title="Buscar contribuyente"
        description="Por nombre, documento, CUIT o identificador local."
      >
        <FilterBar
          searchValue={query}
          searchPlaceholder="Nombre, DNI, CUIT o ID…"
          onSearchChange={setQuery}
          filters={[
            {
              name: "type",
              label: "Tipo",
              options: [
                { value: "CITIZEN", label: "Ciudadano" },
                { value: "ORGANIZATION", label: "Organización" },
              ],
            },
          ]}
          values={{ type }}
          onFilterChange={(_, value) => setType(value)}
        />

        <DataTable
          columns={columns}
          rows={taxpayers ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="UserSearch"
          emptyTitle="Sin coincidencias"
          emptyDescription="Revisá el documento ingresado o esperá a que M1 replique el alta."
          onRowClick={setSelected}
        />
      </Card>

      <TaxpayerDetailModal taxpayer={selected} onClose={() => setSelected(null)} />
    </ModuleShell>
  );
}

/** Ficha con el estado de cuenta consolidado del contribuyente. */
function TaxpayerDetailModal({ taxpayer, onClose }) {
  const loader = useCallback(
    () => (taxpayer ? debtService.accountStatement(taxpayer.id) : Promise.resolve(null)),
    [taxpayer],
  );
  const { data: statement, loading } = useResource(loader);

  if (!taxpayer) return null;

  return (
    <Modal
      open
      title={taxpayer.name}
      description={`${labelFor(taxpayer.type)} · ${taxpayer.documentType} ${taxpayer.document}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cerrar
          </Button>
          <Link to={`/rentas/deudas?taxpayerId=${taxpayer.id}`}>
            <Button variant="primary">Ver deudas</Button>
          </Link>
        </>
      }
    >
      <dl className="grid grid-cols-2 gap-3">
        <Field label="CUIT/CUIL" value={taxpayer.cuit} />
        <Field label="Situación" value={labelFor(taxpayer.status)} />
        <Field label="Referencia externa" value={taxpayer.externalRef} />
        <Field
          label="Beneficio social (M8)"
          value={
            taxpayer.benefit?.status === "ACTIVE"
              ? `${taxpayer.benefit.discountPercentage}% hasta ${formatDate(taxpayer.benefit.validUntil)}`
              : "Sin beneficio activo"
          }
        />
      </dl>

      {taxpayer.status === "BLOCKED" && (
        <Alert variant="error" title="Contribuyente bloqueado">
          M1 informó un bloqueo. No emitas nuevas boletas hasta que se regularice.
        </Alert>
      )}
      {taxpayer.status === "DECEASED" && (
        <Alert variant="info" title="Fallecimiento informado por M1">
          Las deudas continúan vigentes, pero deben tramitarse con los herederos.
        </Alert>
      )}

      <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4">
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
          Estado de cuenta
        </p>
        {loading ? (
          <div className="mt-3 flex items-center gap-2">
            <Spinner size="sm" />
            <span className="text-[13px] text-neutral-400">Consultando…</span>
          </div>
        ) : (
          <div className="mt-3 flex gap-8">
            <div>
              <p className="text-[12px] text-neutral-400">Saldo total</p>
              <p className="text-[18px] font-bold tabular-nums text-[#0F2C59]">
                {formatCurrency(statement?.totalOutstanding ?? 0)}
              </p>
            </div>
            <div>
              <p className="text-[12px] text-neutral-400">Vencido</p>
              <p className="text-[18px] font-bold tabular-nums text-[#D63031]">
                {formatCurrency(statement?.totalOverdue ?? 0)}
              </p>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}

function Field({ label, value }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </dt>
      <dd className="mt-0.5 text-[13px] text-neutral-700 break-words">{value}</dd>
    </div>
  );
}
