import { serverFieldErrors } from "../lib/validation.js";
import { useState } from "react";
import { ArrowRight, Eye, EyeOff, LockKeyhole, Mail, UserRound } from "lucide-react";
import "./LoginPage.css";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import FormField from "../components/ui/FormField.jsx";
import Alert from "../components/ui/Alert.jsx";
import Button from "../components/common/Button.jsx";
import logo from "../assets/logo.png";
import { useAuth } from "../context/AuthContext.jsx";
import { homePathForRole } from "../config/workspaces.js";

export default function LoginPage() {
  const { login, isAuthenticated, user, sessionNotice, dismissSessionNotice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [form, setForm] = useState({ username: "", password: "" });
  const [verContrasena, setVerContrasena] = useState(false);
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
      const { profile } = await login(form);
      navigate(location.state?.from ?? homePathForRole(profile.role), { replace: true });
    } catch (caught) {
      setErrors((previous) => ({ ...previous, ...serverFieldErrors(caught, {}, Object.keys(form)) }));
      setSubmitError(caught.message);
    } finally {
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
          <header className="login-header">
        <div className="login-brand">
          <img src={logo} alt="" width="42" height="42" />
          <div>
            <p className="login-brand-name">Ciudad UADE</p>
            <p className="login-brand-area">Dirección General de Rentas</p>
          </div>
        </div>
          </header>
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
            <p className="login-card-description">
              Usá tus credenciales. Te llevaremos automáticamente al portal o área de trabajo que te corresponde.
            </p>

            <form id="login-form" onSubmit={onSubmit} noValidate aria-labelledby="login-title" aria-busy={submitting} tabIndex={-1}>
              <div className="login-fields">
                {sessionNotice && (
                  <Alert variant="info" title="Sesión vencida" onDismiss={dismissSessionNotice}>{sessionNotice}</Alert>
                )}
                {submitError && (
                  <Alert variant="error" title="No pudimos validar tus datos">{submitError}</Alert>
                )}
                <FormField
                  label="Usuario"
                  name="username"
                  placeholder="Ingresá tu nombre de usuario"
                  value={form.username}
                  onChange={onChange}
                  error={errors.username}
                  required
                  disabled={submitting}
                  autoComplete="username"
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
              <p className="login-account-note">
                El acceso se define según tu perfil. Si todavía no tenés usuario, solicitá tu cuenta en la Dirección de Rentas.
              </p>
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
