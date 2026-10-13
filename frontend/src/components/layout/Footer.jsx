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
      </div>
    </footer>
  );
}
