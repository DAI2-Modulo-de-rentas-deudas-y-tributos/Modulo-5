import Button from "./Button.jsx";

export default function Pagination({ page, onPage, loading = false }) {
  if (!page || page.totalPages <= 1) return null;
  return <nav aria-label="Paginación" className="flex items-center justify-end gap-3 border-t border-neutral-100 p-4 text-sm">
    <Button variant="secondary" disabled={loading || page.number === 0} onClick={() => onPage(page.number - 1)}>Anterior</Button>
    <span>Página {page.number + 1} de {page.totalPages}</span>
    <Button variant="secondary" disabled={loading || page.last || page.number + 1 >= page.totalPages} onClick={() => onPage(page.number + 1)}>Siguiente</Button>
  </nav>;
}
