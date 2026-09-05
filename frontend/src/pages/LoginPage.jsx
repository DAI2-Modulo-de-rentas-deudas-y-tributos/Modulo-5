import { Fragment, useState } from "react";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import FormField from "../components/ui/FormField.jsx";
import Alert from "../components/ui/Alert.jsx";
import Button from "../components/common/Button.jsx";
import logo from "../assets/logo.png";
import { useAuth } from "../context/AuthContext.jsx";
import { homePathForRole } from "../config/workspaces.js";
import { AUTH_MODE, USE_MOCKS } from "../services/apiClient.js";

/** Cuentas del entorno de mocks. En el talón porque es lo que uno deja a mano. */
const CUENTAS_DE_PRUEBA = [
  { username: "mrivas", password: "rentas123", role: "Personal de Rentas" },
  { username: "jlopez", password: "rentas123", role: "Supervisor" },
  { username: "pcabrera", password: "caja123", role: "Cajero" },
  { username: "acastro", password: "audit123", role: "Auditor" },
  { username: "jperez", password: "ciudadano123", role: "Contribuyente" },
];

/**
 * Ingreso al módulo. Entran los agentes municipales y también el contribuyente a su
 * propio legajo; en ningún caso hay autorregistro: las cuentas las da de alta el
 * municipio y el rol decide a qué área se llega.
 *
 * La pantalla está dibujada como una hoja de trámite —banda de encabezado, cuerpo,
 * línea de corte y talón—, el mismo lenguaje de `BoletaDocument`: el ingreso y el
 * comprobante son documentos de la misma oficina.
 */
export default function LoginPage() {
  const { login, isAuthenticated, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [form, setForm] = useState({ username: "", password: "" });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  if (isAuthenticated) {
    return <Navigate to={location.state?.from ?? homePathForRole(user.role)} replace />;
  }

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
  };

  const usarCuenta = (cuenta) => {
    setForm({ username: cuenta.username, password: cuenta.password });
    setErrors({});
    setSubmitError(null);
  };

  const validate = () => {
    const found = {};
    if (!form.username.trim()) found.username = "Ingresá tu usuario.";
    if (!form.password) found.password = "Ingresá tu contraseña.";
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const onSubmit = async (event) => {
    event.preventDefault();
    setSubmitError(null);
    if (!validate()) return;

    setSubmitting(true);
    try {
      // Cada rol tiene su propia área: el destino sale del perfil que devuelve el login.
      const profile = await login(form);
      navigate(location.state?.from ?? homePathForRole(profile.role), { replace: true });
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setSubmitting(false);
    }
  };

  const conCuentasDePrueba = AUTH_MODE === "mock" && USE_MOCKS;

  return (
    <main className="flex min-h-screen items-center justify-center bg-[#FAFAFA] px-5 py-10">
      <div
        className="hoja-entrada w-full max-w-[460px] overflow-hidden rounded-xl border border-neutral-200
                   bg-white shadow-[0_24px_60px_-32px_rgba(15,44,89,0.45)]"
      >
        {/* Banda de encabezado: la oficina que emite, como en la boleta impresa. */}
        <header className="flex items-start justify-between gap-4 bg-[#0F2C59] px-6 py-5 sm:px-8">
          <div className="flex items-center gap-2.5">
            <img src={logo} alt="Ciudad UADE" className="h-7 w-auto object-contain" />
            <div className="leading-tight">
              <p className="text-[13px] font-bold text-white">Ciudad UADE</p>
              <p className="text-[10px] font-semibold uppercase tracking-[0.15em] text-white/45">
                Dirección General de Rentas
              </p>
            </div>
          </div>
          <p className="hidden shrink-0 pt-0.5 text-[11px] tabular-nums text-white/40 sm:block">
            Gestión 2026
          </p>
        </header>
        <div className="h-0.5 bg-[#D63031]" />

        <div className="px-6 py-7 sm:px-8">
          <h2 className="text-[26px] font-extrabold leading-none tracking-[-0.02em] text-[#0F2C59]">
            Iniciar sesión
          </h2>
          <p className="mt-2.5 text-[13px] leading-relaxed text-neutral-500">
            Agentes municipales y contribuyentes entran con el usuario que les dio el
            municipio. No hay registro público.
          </p>

          <form onSubmit={onSubmit} noValidate className="mt-6 flex flex-col gap-4">
            {submitError && (
              <Alert variant="error" title="No pudimos validar tus datos">
                {submitError}
              </Alert>
            )}

            <FormField
              label="Usuario"
              name="username"
              placeholder="nombre.apellido"
              value={form.username}
              onChange={onChange}
              error={errors.username}
              required
            />

            <FormField
              label="Contraseña"
              name="password"
              type="password"
              value={form.password}
              onChange={onChange}
              error={errors.password}
              required
            />

            <Button type="submit" variant="primary" loading={submitting} className="mt-1 w-full">
              {submitting ? "Verificando…" : "Ingresar"}
            </Button>
          </form>
        </div>

        {/* Talón: la parte que se conserva —a quién escribirle y con qué entrar—. */}
        <div className="hoja-corte" aria-hidden="true" />
        <footer className="px-6 pb-6 pt-5 sm:px-8">
          {conCuentasDePrueba && (
            <div className="mb-5">
              <div className="flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:justify-between sm:gap-3">
                <p className="text-[12px] font-semibold text-neutral-600">Cuentas de prueba</p>
                <p className="text-[11px] text-neutral-300">Elegí una y se completa el formulario</p>
              </div>

              <div className="mt-2 grid grid-cols-[auto_auto_1fr] items-baseline gap-x-3">
                {CUENTAS_DE_PRUEBA.map((cuenta) => (
                  <Fragment key={cuenta.username}>
                    <button
                      type="button"
                      onClick={() => usarCuenta(cuenta)}
                      className="col-span-3 grid grid-cols-subgrid rounded-md py-1 text-left
                                 transition-colors hover:bg-[#0F2C59]/[0.04]
                                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D63031]/25"
                    >
                      <span className="pl-1.5 font-mono text-[12px] text-[#0F2C59]">
                        {cuenta.username}
                      </span>
                      <span className="font-mono text-[12px] text-neutral-400">
                        {cuenta.password}
                      </span>
                      <span className="pr-1.5 text-right text-[12px] text-neutral-400">
                        {cuenta.role}
                      </span>
                    </button>
                  </Fragment>
                ))}
              </div>
            </div>
          )}

          <p className="text-[12px] leading-relaxed text-neutral-400">
            ¿No podés entrar? Escribí a{" "}
            <a
              href="mailto:soporte@ciudaduade.gob.ar"
              className="font-medium text-neutral-600 underline decoration-neutral-300 underline-offset-2
                         transition-colors hover:text-[#0F2C59] hover:decoration-[#0F2C59]/40
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D63031]/25"
            >
              soporte@ciudaduade.gob.ar
            </a>
            .
          </p>

          <div className="hoja-codigo mt-5 h-7 w-40" aria-hidden="true" />
        </footer>
      </div>
    </main>
  );
}
