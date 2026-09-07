import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
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
import { cashierService, taxpayerService } from "../../services/rentasService.js";
import { formatCurrency, labelFor } from "../../lib/format.js";

/**
 * Consulta del padrón desde la ventanilla: sólo lectura.
 * Las altas y modificaciones son de M1; el cajero no edita datos del contribuyente.
 */
export default function ContribuyentesCajaPage() {
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState(null);

  const loader = useCallback(() => taxpayerService.search({ query }), [query]);
  const { data: taxpayers, loading, error } = useResource(loader, []);

  const columns = [
    {
      key: "name",
      header: "Contribuyente",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium text-neutral-800">{row.name}</span>
          <span className="text-[12px] text-neutral-400">{labelFor(row.type)}</span>
        </div>
      ),
    },
    {
      key: "document",
      header: "Documento",
      render: (row) => (
        <span className="tabular-nums">
          {row.documentType} {row.document}
        </span>
      ),
    },
    {
      key: "cuit",
      header: "CUIT/CUIL",
      render: (row) => <span className="tabular-nums">{row.cuit}</span>,
    },
    { key: "status", header: "Situación", render: (row) => <StatusBadge status={row.status} /> },
  ];

  return (
    <ModuleShell
      label="Ventanilla"
      title="Contribuyentes"
      highlight="del padrón"
      description="Buscá por DNI, CUIT o nombre para ver sus datos, deudas y pagos."
      breadcrumb={[{ id: "contribuyentes", label: "Contribuyentes" }]}
      homePath="/caja"
      homeLabel="Panel de caja"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar el padrón">
          {error}
        </Alert>
      )}

      <Alert variant="info" title="Consulta de sólo lectura">
        Desde caja se consultan los datos del contribuyente, pero no se editan: el padrón lo
        administra el Módulo 1 y llega a Rentas por eventos.
      </Alert>

      <Card title="Buscar contribuyente" description="Por DNI, CUIT o nombre.">
        <FilterBar
          searchValue={query}
          searchPlaceholder="DNI / CUIT / Nombre…"
          onSearchChange={setQuery}
        />

        <DataTable
          columns={columns}
          rows={taxpayers ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="UserSearch"
          emptyTitle="Sin coincidencias"
          emptyDescription="Revisá el documento ingresado."
          onRowClick={setSelected}
        />
      </Card>

      <TaxpayerSummaryModal taxpayer={selected} onClose={() => setSelected(null)} />
    </ModuleShell>
  );
}

/** Ficha corta: datos del contribuyente y su deuda, con acceso al detalle completo. */
function TaxpayerSummaryModal({ taxpayer, onClose }) {
  const navigate = useNavigate();

  const loader = useCallback(
    () => (taxpayer ? cashierService.taxpayerFile(taxpayer.id) : Promise.resolve(null)),
    [taxpayer],
  );
  const { data: file, loading } = useResource(loader);

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
          <Button variant="primary" onClick={() => navigate(`/caja/contribuyentes/${taxpayer.id}`)}>
            Ver deudas y pagos
          </Button>
        </>
      }
    >
      <dl className="grid grid-cols-2 gap-3">
        <Field label="Nombre" value={taxpayer.name} />
        <Field label="Documento" value={`${taxpayer.documentType} ${taxpayer.document}`} />
        <Field label="CUIT/CUIL" value={taxpayer.cuit} />
        <Field label="Situación" value={labelFor(taxpayer.status)} />
      </dl>

      <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-4">
        <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
          Deuda a pagar
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
                {formatCurrency(file?.totals.outstanding ?? 0)}
              </p>
            </div>
            <div>
              <p className="text-[12px] text-neutral-400">Vencido</p>
              <p className="text-[18px] font-bold tabular-nums text-[#D63031]">
                {formatCurrency(file?.totals.overdue ?? 0)}
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
