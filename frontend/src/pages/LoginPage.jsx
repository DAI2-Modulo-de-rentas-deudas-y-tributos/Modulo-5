import { useState } from "react";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import { Building2, Lock } from "lucide-react";
import FormField from "../components/ui/FormField.jsx";
import Alert from "../components/ui/Alert.jsx";
import Button from "../components/common/Button.jsx";
import logo from "../assets/logo.png";
import { useAuth } from "../context/AuthContext.jsx";
import { homePathForRole } from "../config/workspaces.js";
import { USE_MOCKS } from "../services/apiClient.js";

/** Ingreso al área de trabajo. Sólo agentes municipales: no hay autorregistro. */
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

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      {/* Panel institucional */}
      <section className="relative flex flex-col justify-between overflow-hidden bg-[#0F2C59] px-8 py-10 lg:w-[45%] lg:px-14 lg:py-14">
        <div className="absolute -right-16 -top-16 h-64 w-64 rounded-full border-4 border-white/5" />
        <div className="absolute -bottom-24 -left-10 h-72 w-72 rounded-full border-4 border-[#D63031]/20" />

        <div className="relative flex items-center gap-2">
          <img src={logo} alt="Ciudad UADE" className="h-7 w-auto object-contain" />
          <span className="text-[15px] font-bold text-white">Ciudad UADE</span>
        </div>

        <div className="relative mt-12 lg:mt-0">
          <div className="mb-4 flex items-center gap-3">
            <div className="h-[2px] w-8 bg-[#D63031]" />
            <span className="text-[12px] font-bold uppercase tracking-[0.2em] text-[#D63031]">
              Módulo 5
            </span>
          </div>
          <h1 className="text-[2.25rem] font-extrabold leading-tight tracking-[-0.02em] text-white lg:text-[2.75rem]">
            Área de trabajo de{" "}
            <span className="bg-gradient-to-r from-[#D63031] to-[#e74c3c] bg-clip-text text-transparent">
              Rentas
            </span>
          </h1>
          <p className="mt-4 max-w-sm text-[15px] leading-relaxed text-white/60">
            Liquidaciones, deudas, boletas, pagos, planes de pago, exenciones y la caja de
            atención al contribuyente de la Municipalidad de Ciudad UADE.
          </p>
        </div>

        <p className="relative mt-12 flex items-center gap-2 text-[12px] text-white/40 lg:mt-0">
          <Building2 className="h-3.5 w-3.5" strokeWidth={1.5} />
          Uso exclusivo de agentes municipales — Gestión 2026
        </p>
      </section>

      {/* Formulario */}
      <section className="flex flex-1 items-center justify-center bg-[#FAFAFA] px-5 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-7">
            <span className="inline-flex items-center gap-2 rounded-full border border-neutral-200 bg-white px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-500">
              <Lock className="h-3 w-3 text-[#D63031]" strokeWidth={2} />
              Acceso restringido
            </span>
            <h2 className="mt-4 text-[26px] font-extrabold tracking-[-0.02em] text-[#0F2C59]">
              Iniciar sesión
            </h2>
            <p className="mt-1.5 text-[14px] text-neutral-500">
              Ingresá con tu usuario del padrón de agentes.
            </p>
          </div>

          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
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
              placeholder="••••••••"
              value={form.password}
              onChange={onChange}
              error={errors.password}
              required
            />

            <Button type="submit" variant="primary" loading={submitting} className="mt-1 w-full">
              {submitting ? "Verificando…" : "Ingresar"}
            </Button>
          </form>

          {USE_MOCKS && (
            <div className="mt-6 rounded-lg border border-neutral-200 bg-white p-4">
              <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-400">
                Datos de prueba
              </p>
              <ul className="mt-2 flex flex-col gap-1 text-[12px] text-neutral-500">
                <li>
                  <code className="font-semibold text-[#0F2C59]">mrivas</code> / rentas123 —
                  Personal de Rentas
                </li>
                <li>
                  <code className="font-semibold text-[#0F2C59]">jlopez</code> / rentas123 —
                  Supervisor
                </li>
                <li>
                  <code className="font-semibold text-[#0F2C59]">pcabrera</code> / caja123 —
                  Cajero
                </li>
                <li>
                  <code className="font-semibold text-[#0F2C59]">acastro</code> / audit123 —
                  Auditor
                </li>
              </ul>
            </div>
          )}

          <p className="mt-6 text-center text-[12px] text-neutral-400">
            ¿Problemas para ingresar? Escribí a{" "}
            <span className="font-medium text-neutral-600">soporte@ciudaduade.gob.ar</span>
          </p>
        </div>
      </section>
    </div>
  );
}
