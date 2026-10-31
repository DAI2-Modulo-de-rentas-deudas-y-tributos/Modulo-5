import { Mail, Phone } from "lucide-react";

export default function Footer({ areaName, areaEmail }) {
  return (
    <footer className="workspace-footer">
      <div className="workspace-container">
        <div><strong>Ciudad UADE</strong><span>{areaName}</span></div>
        <nav aria-label="Contacto municipal">
          <a href="tel:147"><Phone size={14} aria-hidden="true" />147 · Línea municipal</a>
          <a href={`mailto:${areaEmail}`}><Mail size={14} aria-hidden="true" />Contactar al área</a>
        </nav>
        <p className="text-xs text-neutral-500">Los accesos a otros trámites municipales no están disponibles.</p>
        <span className="text-xs text-neutral-500">Portal de gestión municipal</span>
      </div>
    </footer>
  );
}
