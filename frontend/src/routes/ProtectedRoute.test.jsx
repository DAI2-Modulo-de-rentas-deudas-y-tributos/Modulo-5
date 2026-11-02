import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import ProtectedRoute from "./ProtectedRoute.jsx";

const { authState } = vi.hoisted(() => ({ authState: { isAuthenticated: false, initializing: false, user: null } }));
vi.mock("../context/AuthContext.jsx", () => ({ useAuth: () => authState }));

function LoginDestination() {
  const location = useLocation();
  return <output>{location.state?.from}</output>;
}

afterEach(() => {
  cleanup();
  authState.isAuthenticated = false;
  authState.initializing = false;
  authState.user = null;
});

describe("ProtectedRoute", () => {
  it("conserva path, búsqueda y fragmento al redirigir al login", () => {
    render(
      <MemoryRouter initialEntries={["/portal/deudas?estado=ABIERTA#fila-2"]}>
        <Routes>
          <Route element={<ProtectedRoute />}>
            <Route path="/portal/deudas" element={<p>Privado</p>} />
          </Route>
          <Route path="/login" element={<LoginDestination />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("/portal/deudas?estado=ABIERTA#fila-2")).toBeTruthy();
  });
});
