import { useState } from "react";
import { ArrowRight, Eye, EyeOff, LockKeyhole, Mail, UserRound } from "lucide-react";
import "./LoginPage.css";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import FormField from "../components/ui/FormField.jsx";
import Alert from "../components/ui/Alert.jsx";
import Button from "../components/common/Button.jsx";
import { useAuth } from "../context/AuthContext.jsx";
import { homePathForRole } from "../config/workspaces.js";
import { M1_LOGIN_ENABLED, STAFF_LOGIN_ENABLED } from "../services/apiClient.js";
import { M1_LOGIN_MAX_ATTEMPTS } from "../services/rentasService.js";

/** Cuentas con las que se puede ingresar: la DEMO de Rentas y la de Ciudadanos (M1). */
const ACCESS = {
  rentas: {
    tab: "Cuenta de Rentas",
    field: { label: "Usuario", name: "username", type: "text", placeholder: "Ingresá tu nombre de usuario", autoComplete: "username" },
    missing: "Ingresá tu usuario.",
    description: "Usá tus credenciales. Te llevaremos automáticamente al portal o área de trabajo que te corresponde.",
    note: "El acceso se define según tu perfil. Si todavía no tenés usuario, solicitá tu cuenta en la Dirección de Rentas.",
    source: "demo",
    credentials: (form) => ({ username: form.username, password: form.password }),
  },
  m1: {
    tab: "Cuenta de Ciudadanos",
    field: { label: "Correo de Ciudadanos", name: "email", type: "email", placeholder: "nombre@correo.com", autoComplete: "email" },
    missing: "Ingresá tu correo.",
    description: "Usá el correo y la contraseña de tu cuenta en el portal Ciudadanos de la Municipalidad.",
    note: "Si todavía no tenés cuenta, registrate en el portal Ciudadanos de la Municipalidad.",
    source: "m1",
    credentials: (form) => ({ email: form.email.trim(), password: form.password }),
  },
};

export default function LoginPage() {
  const { login, isAuthenticated, user, sessionNotice, dismissSessionNotice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [mode, setMode] = useState(M1_LOGIN_ENABLED && !STAFF_LOGIN_ENABLED ? "m1" : "rentas");
  const [form, setForm] = useState({ username: "", email: "", password: "" });
  const [verContrasena, setVerContrasena] = useState(false);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [retryAttempt, setRetryAttempt] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const access = ACCESS[mode];
  const identifier = access.field.name;

  if (isAuthenticated) {
    return <Navigate to={location.state?.from ?? homePathForRole(user.role)} replace />;
  }

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
  };

  const chooseMode = (next) => {
    setMode(next);
    setErrors({});
    setSubmitError(null);
  };

  const validate = () => {
    const found = {};
    if (!form[identifier].trim()) found[identifier] = access.missing;
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
      const { profile } = await login(access.credentials(form), { source: access.source, onRetry: setRetryAttempt });
      navigate(location.state?.from ?? homePathForRole(profile.role), { replace: true });
    } catch (caught) {
      setSubmitError(caught.message);
    } finally {
      setRetryAttempt(null);
      setSubmitting(false);
    }
  };

  return (
    <div className="login-page">
      <a className="login-skip" href="#login-form">Ir al formulario de ingreso</a>

      <main className="login-main">
        <section className="login-intro" aria-labelledby="login-intro-title">
          <svg className="login-arches" viewBox="0 0 800 1000" fill="none" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
            {Array.from({ length: 9 }, (_, index) => (
              <path
                key={index}
                d="M-220 1000V240C-220 65-75-80 100-80S420 65 420 240V1000Z"
                transform={`translate(${index * 52} ${index * 58}) rotate(-26 180 440)`}
              />
            ))}
          </svg>
          <div className="login-intro-copy">
            <h1 id="login-intro-title">Tu acceso a Rentas,<br />en un solo lugar.</h1>
            <p className="login-description">
              Ingresá con tu cuenta y accedé al espacio asignado a tu perfil.
            </p>
          </div>
          <footer className="login-intro-footer">
            <p>Cerca tuyo. También en línea.</p>
            <p>Ciudad UADE · Dirección General de Rentas</p>
          </footer>
        </section>

        <section className="login-access" aria-labelledby="login-title">
          <header className="login-access-header">
            <p>Portal de Rentas</p>
            <a className="login-help" href="mailto:soporte@ciudaduade.gob.ar" aria-label="¿Necesitás ayuda?">
              <Mail size={17} aria-hidden="true" /><span>Ayuda</span>
            </a>
          </header>
          <div className="login-card">
            <div className="login-card-heading">
              <span>Acceso unificado</span>
            </div>
            <h2 id="login-title">Iniciar sesión</h2>
            <p className="login-card-description">{access.description}</p>

            {STAFF_LOGIN_ENABLED && M1_LOGIN_ENABLED && (
              <fieldset className="login-access-switch">
                <legend className="sr-only">Cuenta para ingresar</legend>
                {Object.entries(ACCESS).map(([key, option]) => (
                  <button
                    key={key}
                    type="button"
                    aria-pressed={mode === key}
                    onClick={() => chooseMode(key)}
                    disabled={submitting}
                  >
                    {option.tab}
                  </button>
                ))}
              </fieldset>
            )}

            <form id="login-form" onSubmit={onSubmit} noValidate aria-labelledby="login-title" aria-busy={submitting} tabIndex={-1}>
              <div className="login-fields">
              {sessionNotice && (
                <Alert variant="info" title="Sesión finalizada" onDismiss={dismissSessionNotice}>
                  {sessionNotice}
                </Alert>
              )}
                <div aria-live="polite" aria-atomic="true">
                  {submitError && (
                    <Alert variant="error" title="No pudimos validar tus datos">{submitError}</Alert>
                  )}
                  {retryAttempt !== null && (
                    <Alert variant="info" title="Ciudadanos se está iniciando">
                      Reintentamos automáticamente (intento {retryAttempt} de {M1_LOGIN_MAX_ATTEMPTS}).
                    </Alert>
                  )}
                </div>
                <FormField
                  key={identifier}
                  {...access.field}
                  value={form[identifier]}
                  onChange={onChange}
                  error={errors[identifier]}
                  required
                  disabled={submitting}
                  prefix={<UserRound size={17} className="text-neutral-400" aria-hidden="true" />}
                />
                <FormField
                  label="Contraseña"
                  name="password"
                  type={verContrasena ? "text" : "password"}
                  placeholder="Ingresá tu contraseña"
                  value={form.password}
                  onChange={onChange}
                  error={errors.password}
                  required
                  disabled={submitting}
                  autoComplete="current-password"
                  prefix={<LockKeyhole size={17} className="text-neutral-400" aria-hidden="true" />}
                  suffix={
                    <button
                      type="button"
                      onClick={() => setVerContrasena((previous) => !previous)}
                      className="login-password-toggle"
                      aria-pressed={verContrasena}
                      aria-controls="password"
                      disabled={submitting}
                    >
                      {verContrasena ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
                      <span>{verContrasena ? "Ocultar" : "Mostrar"}</span>
                    </button>
                  }
                />
                <Button type="submit" loading={submitting} className="login-submit">
                  {submitting ? "Verificando…" : "Ingresar"}
                  {!submitting && <ArrowRight size={17} aria-hidden="true" />}
                </Button>
              </div>
              <p className="login-account-note">{access.note}</p>
            </form>
          </div>
          <p className="login-access-note">
            <LockKeyhole size={13} aria-hidden="true" />
            Tu perfil determina los permisos y el espacio de trabajo
          </p>
        </section>
      </main>
    </div>
  );
}
