import logo from "../../assets/logo.png";
import StatusBadge from "../common/StatusBadge.jsx";
import { formatCurrency, formatDate } from "../../lib/format.js";

/**
 * Boleta de pago imprimible.
 *
 * Lleva la clase `print-area`: al imprimir se oculta el resto de la pantalla y sólo
 * sale la boleta. En producción el PDF lo genera el backend y se guarda en S3; ésta
 * es la vista que el contribuyente puede imprimir en el momento.
 */
export default function BoletaDocument({ bill, taxpayer }) {
  if (!bill) return null;

  return (
    <div className="print-area rounded-xl border border-neutral-200 bg-white">
      <header className="flex items-start justify-between gap-4 border-b border-dashed border-neutral-200 px-6 py-5">
        <div className="flex items-center gap-2.5">
          <img src={logo} alt="Ciudad UADE" className="h-7 w-auto object-contain" />
          <div className="leading-tight">
            <p className="text-[13px] font-bold text-[#0F2C59]">Ciudad UADE</p>
            <p className="text-[10px] font-semibold uppercase tracking-[0.15em] text-neutral-400">
              Dirección General de Rentas
            </p>
          </div>
        </div>
        <div className="text-right leading-tight">
          <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
            Boleta de pago
          </p>
          <p className="text-[15px] font-bold tabular-nums text-[#0F2C59]">#{bill.id}</p>
        </div>
      </header>

      <div className="flex flex-col gap-5 px-6 py-5">
        <div className="grid grid-cols-2 gap-4">
          <Line label="Contribuyente" value={taxpayer?.name ?? "—"} />
          <Line
            label="Documento"
            value={taxpayer ? `${taxpayer.documentType} ${taxpayer.document}` : "—"}
          />
          <Line label="Concepto" value={bill.conceptName ?? bill.conceptCode} />
          <Line label="Deuda" value={`#${bill.debtId}`} />
          <Line label="Vencimiento" value={formatDate(bill.dueDate)} />
          <Line label="Emitida" value={formatDate(bill.issuedAt)} />
        </div>

        <div className="flex items-end justify-between gap-4 rounded-lg bg-[#0F2C59]/[0.04] px-4 py-3.5">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
              Importe a pagar
            </p>
            <p className="mt-1 text-[24px] font-extrabold tabular-nums leading-none text-[#0F2C59]">
              {formatCurrency(bill.amount)}
            </p>
          </div>
          <StatusBadge status={bill.status} />
        </div>

        {bill.status === "EXPIRED" && (
          <p className="text-[13px] font-medium text-[#D63031]">
            Esta boleta está vencida. Acercate a Rentas para que te emitan una nueva con
            los recargos actualizados.
          </p>
        )}
        {bill.status === "SETTLED" && (
          <p className="text-[13px] font-medium text-emerald-700">
            Esta boleta ya fue cancelada. Se muestra sólo como constancia.
          </p>
        )}

        {/* El código de barras es lo que lee la caja al cobrar. */}
        <div className="rounded-lg border border-neutral-200 px-4 py-3">
          <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
            Código de barras
          </p>
          <p className="mt-1 font-mono text-[15px] tracking-[0.18em] text-neutral-800 break-all">
            {bill.barcode}
          </p>
        </div>
      </div>

      <footer className="border-t border-dashed border-neutral-200 px-6 py-4">
        <p className="text-[11px] leading-relaxed text-neutral-400">
          Presentá esta boleta en la ventanilla de Rentas o en cualquier canal de cobro
          habilitado. El pago se acredita dentro de las 48 horas hábiles.
        </p>
      </footer>
    </div>
  );
}

function Line({ label, value }) {
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-[0.1em] text-neutral-400">
        {label}
      </p>
      <p className="mt-0.5 text-[13px] text-neutral-700 break-words">{value}</p>
    </div>
  );
}

/** El navegador imprime sólo `.print-area` gracias a las reglas de `index.css`. */
export const printBoleta = () => window.print();
