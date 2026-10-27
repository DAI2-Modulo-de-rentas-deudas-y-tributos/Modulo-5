import { Phone, Mail, MapPin } from "lucide-react";
import logo from "../../assets/logo.png";

function Footer({ areaName, areaEmail }) {
  return (
    <footer className="border-t border-neutral-200/60 bg-white">
      <div className="h-px bg-gradient-to-r from-transparent via-[#D63031]/20 to-transparent" />

      <div className="mx-auto max-w-6xl px-5 py-10">
        <div className="flex flex-col md:flex-row md:items-start md:justify-between gap-8">
          {/* Brand + contact */}
          <div className="max-w-xs">
            <div className="flex items-center gap-2 mb-3">
              <img src={logo} alt="Ciudad UADE Logo" className="h-5 w-auto object-contain" />
              <span className="text-[13px] font-bold text-[#0F2C59]">Ciudad UADE</span>
            </div>
            <p className="text-[12px] text-neutral-400 leading-relaxed">
              {areaName}
              <br />
              Municipalidad de Ciudad UADE — Gestión 2026.
            </p>
            <div className="mt-4 flex flex-col gap-1.5">
              <span className="flex items-center gap-2 text-[12px] text-neutral-400">
                <Phone className="h-3 w-3 text-[#D63031]/50" strokeWidth={1.5} /> 147 — Línea Municipal
              </span>
              <span className="flex items-center gap-2 text-[12px] text-neutral-400">
                <Mail className="h-3 w-3 text-[#D63031]/50" strokeWidth={1.5} /> {areaEmail}
              </span>
              <span className="flex items-center gap-2 text-[12px] text-neutral-400">
                <MapPin className="h-3 w-3 text-[#D63031]/50" strokeWidth={1.5} /> Av. Independencia 1100, CABA
              </span>
            </div>
          </div>

          <div className="max-w-xs text-[12px] text-neutral-400">
            <p className="mb-2 text-[10px] font-semibold uppercase tracking-[0.15em]">Otros trámites</p>
            <p>Los accesos a otros trámites municipales no están disponibles desde este portal.</p>
          </div>
        </div>

        {/* Bottom bar */}
        <div className="mt-8 pt-5 border-t border-neutral-100 flex items-center justify-between">
          <p className="text-[11px] text-neutral-300">
            © {new Date().getFullYear()} Municipalidad de Ciudad UADE. Todos los derechos reservados.
          </p>
          <div className="flex items-center gap-1">
            <div className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
            <span className="text-[10px] text-neutral-300">Portal de gestión municipal</span>
          </div>
        </div>
      </div>
    </footer>
  );
}

export default Footer;
