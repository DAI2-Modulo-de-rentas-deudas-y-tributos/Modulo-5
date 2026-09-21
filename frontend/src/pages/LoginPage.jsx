import { useState } from "react";
import { ArrowRight, LockKeyhole, Mail, UserRound } from "lucide-react";
import BorderGlow from "../components/react-bits/BorderGlow.jsx";
import "./LoginPage.css";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import FormField from "../components/ui/FormField.jsx";
import Alert from "../components/ui/Alert.jsx";
import Button from "../components/common/Button.jsx";
import logo from "../assets/logo.png";
import { useAuth } from "../context/AuthContext.jsx";
import { WORKSPACES, homePathForRole } from "../config/workspaces.js";

// El ciudadano ingresa por defecto; los agentes eligen su área antes de autenticarse.
const AREAS_AGENTE = ["PERSONAL", "SUPERVISOR", "CAJERO", "AUDITOR"];

export default function LoginPage() {
  const { login, isAuthenticated, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [door, setDoor] = useState("ciudadano");
  const [area, setArea] = useState("PERSONAL");
  const [form, setForm] = useState({ username: "", password: "" });
  const [verContrasena, setVerContrasena] = useState(false);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [puertaEquivocada, setPuertaEquivocada] = useState(null);

  if (isAuthenticated) {
    return <Navigate to={location.state?.from ?? homePathForRole(user.role)} replace />;
  }

  const esCiudadano = door === "ciudadano";
  const areaActiva = esCiudadano ? "CONTRIBUYENTE" : area;

  const espacio = WORKSPACES[areaActiva];

  const onChange = (event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
    setPuertaEquivocada(null);
  };

  const cambiarPuerta = (siguiente) => {
    setDoor(siguiente);
    setErrors({});
    setSubmitError(null);
    setPuertaEquivocada(null);
  };

  const elegirArea = (siguiente) => {
    setArea(siguiente);
    setDoor("agente");
    setSubmitError(null);
    setPuertaEquivocada(null);
  };

  /** Lleva al usuario a su puerta y le deja los datos cargados: le queda un click. */
  const irAMiArea = () => {
    const destino = puertaEquivocada.role;
    setPuertaEquivocada(null);
    setSubmitError(null);
    if (destino === "CONTRIBUYENTE") {
      setDoor("ciudadano");
      return;
    }
    setArea(destino);
    setDoor("agente");
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
    setPuertaEquivocada(null);
    if (!validate()) return;

    setSubmitting(true);
    try {
      // Credenciales válidas de otra área: no se abre sesión, se avisa la puerta.
      const { profile, accepted } = await login(form, {
        accept: (perfil) => perfil.role === areaActiva,
      });
      if (!accepted) {
        setPuertaEquivocada(profile);
        return;
      }
      navigate(location.state?.from ?? homePathForRole(profile.role), { replace: true });
    } catch (caught) {
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
          <h1 id="login-intro-title">Tus tributos,<br />en un solo lugar.</h1>
          <p className="login-description">
            Consultá tus deudas, accedé a tus boletas y seguí tus gestiones con el municipio.
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
              <span>{esCiudadano ? "Acceso ciudadano" : "Acceso municipal"}</span>
            </div>
            <h2 id="login-title">{esCiudadano ? "Entrar al Portal" : "Iniciar sesión"}</h2>
            <p className="login-card-description">
              {esCiudadano
                ? "Ingresá con el usuario que te asignó el municipio."
                : "Seleccioná tu área e ingresá con tu usuario municipal."}
            </p>

            <form id="login-form" onSubmit={onSubmit} noValidate aria-labelledby="login-title" aria-busy={submitting} tabIndex={-1}>
              {!esCiudadano && (
                <fieldset className="login-areas" disabled={submitting}>
                  <legend>Área de trabajo</legend>
                  <div>
                    {AREAS_AGENTE.map((clave) => (
                      <button
                        key={clave}
                        type="button"
                        aria-label={WORKSPACES[clave].label}
                        aria-pressed={area === clave}
                        onClick={() => elegirArea(clave)}
                      >
                        {{ PERSONAL: "Rentas", SUPERVISOR: "Supervisión", CAJERO: "Caja", AUDITOR: "Auditoría" }[clave]}
                      </button>
                    ))}
                  </div>
                </fieldset>
              )}

              <div className="login-fields">
                <div aria-live="polite" aria-atomic="true">
                  {submitError && (
                    <Alert variant="error" title="No pudimos validar tus datos">{submitError}</Alert>
                  )}
                  {puertaEquivocada && (
                    <Alert variant="error" title="Estás en la pestaña equivocada">
                      <p>
                        Tu usuario pertenece a {WORKSPACES[puertaEquivocada.role].label}.
                        Conservamos tus datos para que continúes por el acceso correspondiente.
                      </p>
                      <Button variant="danger" size="sm" onClick={irAMiArea} className="mt-2.5">
                        Llevame a la puerta correcta
                      </Button>
                    </Alert>
                  )}
                </div>
                <FormField
                  label="Usuario"
                  name="username"
                  placeholder="nombre.apellido"
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
                      disabled={submitting}
                    >
                      {verContrasena ? "Ocultar" : "Mostrar"}
                    </button>
                  }
                />
                <BorderGlow
                  className="login-submit-glow"
                  backgroundColor="#0F2C59"
                  borderRadius={8}
                  glowColor="215 70 70"
                  colors={["#7199cf", "#b2cbed", "#4779b8"]}
                  glowRadius={20}
                  glowIntensity={0.85}
                  edgeSensitivity={10}
                  coneSpread={35}
                  fillOpacity={0.15}
                  animated
                >
                  <Button type="submit" loading={submitting} className="login-submit">
                  {submitting ? "Verificando…" : esCiudadano ? "Ingresar al Portal" : `Entrar a ${espacio.label}`}
                  {!submitting && <ArrowRight size={17} aria-hidden="true" />}
                  </Button>
                </BorderGlow>
              </div>
              <p className="login-account-note">
                Los campos son obligatorios. Si aún no tenés usuario, solicitá tu cuenta en la Dirección de Rentas.
              </p>
            </form>

            <div className="login-switch">
              <p>{esCiudadano ? "¿Sos agente municipal?" : "¿Querés consultar tus tributos?"}</p>
              <button
                type="button"
                onClick={() => cambiarPuerta(esCiudadano ? "agente" : "ciudadano")}
                disabled={submitting}
              >
                {esCiudadano ? "Trabajo en el municipio" : "Soy ciudadano o contribuyente"}
                <ArrowRight size={15} aria-hidden="true" />
              </button>
            </div>
          </div>
          <p className="login-access-note">
            <LockKeyhole size={13} aria-hidden="true" />
            {esCiudadano ? "Estás en el acceso para ciudadanos" : "Acceso exclusivo para agentes municipales"}
          </p>
        </section>
      </main>
    </div>
  );
}
