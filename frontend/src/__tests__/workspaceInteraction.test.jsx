import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import DataTable from "../components/common/DataTable.jsx";
import Sidebar from "../components/layout/Sidebar.jsx";
import Topbar from "../components/layout/Topbar.jsx";

vi.mock("../context/AuthContext.jsx", () => ({
  useAuth: () => ({ user: { fullName: "Mariana Rivas", roleLabel: "Personal de Rentas", email: "mariana@ciudaduade.gob.ar" }, logout: vi.fn() }),
}));

afterEach(cleanup);

describe("interacción del área de trabajo", () => {
  it("abre el detalle de una fila con Enter o Espacio", async () => {
    const user = userEvent.setup();
    const openDetail = vi.fn();
    const row = { id: 123, name: "Juan Pérez" };
    render(<DataTable columns={[{ key: "name", header: "Contribuyente" }]} rows={[row]} rowKey={(item) => item.id} onRowClick={openDetail} />);
    await user.tab();
    await user.keyboard("{Enter} ");
    expect(openDetail.mock.calls).toEqual([[row], [row]]);
  });

  it("conserva la acción de un botón dentro de una fila al usar el teclado", async () => {
    const user = userEvent.setup();
    const openDetail = vi.fn();
    const download = vi.fn();
    render(<DataTable columns={[{ key: "action", header: "Acción", render: () => <button onClick={(event) => { event.stopPropagation(); download(); }}>Descargar</button> }]}
      rows={[{ id: 1 }]} rowKey={(row) => row.id} onRowClick={openDetail} />);
    screen.getByRole("button", { name: "Descargar" }).focus();
    await user.keyboard("{Enter}");
    expect(download).toHaveBeenCalledOnce();
    expect(openDetail).not.toHaveBeenCalled();
  });

  it("cierra las opciones de cuenta con Escape o al pulsar fuera", async () => {
    const user = userEvent.setup();
    render(<><Topbar /><button>Contenido</button></>);
    const trigger = screen.getByRole("button", { name: /opciones de cuenta/ });
    await user.click(trigger);
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Cerrar sesión" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(trigger);
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Contenido" }));
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("mantiene el foco dentro de la navegación abierta y lo restaura al cerrar", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const props = { onClose, homePath: "/rentas", areaTag: "Rentas", modules: [{ id: "deudas", path: "/rentas/deudas", label: "Deudas", iconName: "FileWarning" }] };
    const view = render(<MemoryRouter><button>Abrir menú</button><Sidebar {...props} open={false} /></MemoryRouter>);
    const trigger = screen.getByRole("button", { name: "Abrir menú" });
    trigger.focus();
    view.rerender(<MemoryRouter><button>Abrir menú</button><Sidebar {...props} open /></MemoryRouter>);
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Cerrar menú" }));
    await user.keyboard("{Shift>}{Tab}{/Shift}");
    expect(document.activeElement).toBe(screen.getByRole("link", { name: "Deudas" }));
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Cerrar menú" }));
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledOnce();
    view.rerender(<MemoryRouter><button>Abrir menú</button><Sidebar {...props} open={false} /></MemoryRouter>);
    expect(document.activeElement).toBe(trigger);
    expect(document.body.style.overflow).toBe("");
  });
});
