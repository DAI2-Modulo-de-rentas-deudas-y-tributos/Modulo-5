import logo from "../../assets/logo.png";
import StatusBadge from "../common/StatusBadge.jsx";
import { formatCurrency, formatDateTime, labelFor } from "../../lib/format.js";

/**
 * Comprobante de pago de ventanilla.
 *
 * Lleva la clase `print-area`: al imprimir, el resto de la pantalla se oculta y sólo
 * sale el ticket. En producción el PDF se genera en el backend y se guarda en S3;
 * esta es la vista que el contribuyente se lleva en el momento.
 */
export default function ReceiptCard({ receipt }) {
  if (!receipt) return null;

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
            Comprobante
          </p>
          <p className="text-[15px] font-bold tabular-nums text-[#0F2C59]">
            {receipt.receiptNumber}
          </p>
        </div>
      </header>

      <div className="flex flex-col gap-5 px-6 py-5">
        <div className="grid grid-cols-2 gap-4">
          <Line label="Contribuyente" value={receipt.taxpayer?.name ?? "—"} />
          <Line
            label="Documento"
            value={
              receipt.taxpayer
                ? `${receipt.taxpayer.documentType} ${receipt.taxpayer.document}`
                : "—"
            }
          />
          <Line label="Concepto" value={receipt.conceptCode ?? "—"} />
          <Line label="Deuda" value={receipt.debtId ? `#${receipt.debtId}` : "Sin imputar"} />
          <Line label="Boleta" value={receipt.billId ? `#${receipt.billId}` : "—"} />
          <Line label="Medio de pago" value={labelFor(receipt.method)} />
          <Line label="Fecha y hora" value={formatDateTime(receipt.issuedAt)} />
          <Line
            label="Situación de la deuda"
            value={
              receipt.wasOverdue === null || receipt.wasOverdue === undefined
                ? "—"
                : receipt.wasOverdue
                  ? "Vencida al momento del cobro"
                  : "Al día"
            }
          />
        </div>

        <div className="flex items-end justify-between gap-4 rounded-lg bg-[#0F2C59]/[0.04] px-4 py-3.5">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
              Importe abonado
            </p>
            <p className="mt-1 text-[24px] font-extrabold tabular-nums leading-none text-[#0F2C59]">
              {formatCurrency(receipt.amountPaid)}
            </p>
          </div>
          <div className="text-right">
            <p className="text-[11px] text-neutral-400">Saldo restante</p>
            <p className="text-[15px] font-bold tabular-nums text-neutral-700">
              {formatCurrency(receipt.remainingBalance ?? 0)}
            </p>
          </div>
        </div>

        {receipt.settled && (
          <p className="text-[13px] font-medium text-emerald-700">
            La deuda quedó cancelada con este pago.
          </p>
        )}
        {receipt.status === "REVERSED" && (
          <div>
            <StatusBadge status="REVERSED" />
            <span className="ml-2 text-[13px] text-neutral-500">
              Este comprobante fue anulado por una reversión.
            </span>
          </div>
        )}
      </div>

      <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-dashed border-neutral-200 px-6 py-4">
        <p className="text-[11px] text-neutral-400">
          Cobrado por {receipt.cashier?.fullName ?? "—"}
          {receipt.cashier?.counter ? ` · ${receipt.cashier.counter}` : ""}
        </p>
        <p className="text-[11px] text-neutral-300 tabular-nums">Pago #{receipt.paymentId}</p>
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
export const printReceipt = () => window.print();
