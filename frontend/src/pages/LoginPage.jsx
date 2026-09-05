import { useState } from "react";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import FormField from "../components/ui/FormField.jsx";
import Alert from "../components/ui/Alert.jsx";
import Button from "../components/common/Button.jsx";
import logo from "../assets/logo.png";
import { useAuth } from "../context/AuthContext.jsx";
import { WORKSPACES, homePathForRole } from "../config/workspaces.js";

/**
 * Lo propio de esta pantalla para cada área: el monograma de la placa y la nota que
 * describe su emblema. La etiqueta y la ruta no se repiten acá — salen de
 * `config/workspaces.js`, que es donde se definen.
 */
const EMBLEMAS = {
  PERSONAL: { mono: "RE", nota: "Renglones de liquidación" },
  SUPERVISOR: { mono: "SU", nota: "Sello de aprobación" },
  CAJERO: { mono: "CA", nota: "Moneda sobre el mostrador" },
  AUDITOR: { mono: "AU", nota: "Lente de sólo lectura" },
  CONTRIBUYENTE: { mono: "CO", nota: "Legajo del ciudadano" },
};

/** Áreas de agente municipal, en el orden del riel. El Contribuyente no está: su
 *  acceso es la otra puerta. */
const AREAS_AGENTE = ["PERSONAL", "SUPERVISOR", "CAJERO", "AUDITOR"];

/**
 * Ingreso al módulo, con una puerta por tipo de usuario.
 *
 * La puerta por defecto es la del contribuyente, que es quien más entra y quien menos
 * sabe dónde tiene que ir; el agente municipal cruza a la suya con un click y elige su
 * área en el riel del panel.
 *
 * Las credenciales se validan contra la puerta elegida: si son correctas pero de otra
 * área, la sesión no se abre —`accept` de `AuthContext`— y la pantalla avisa cuál es
 * la puerta que corresponde. No hay autorregistro en ninguna de las dos: las cuentas
 * las da de alta el municipio y el rol decide a qué área se llega.
 */
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
  const emblema = EMBLEMAS[areaActiva];
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

  const monograma = (
    <span
      key={emblema.mono}
      className="login-placa flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-[7px]
                 bg-[#0F2C59] font-mono text-[10px] text-white"
    >
      {emblema.mono}
    </span>
  );

  const verContrasenaBoton = (
    <button
      type="button"
      onClick={() => setVerContrasena((previo) => !previo)}
      className="shrink-0 rounded text-[11.5px] font-semibold text-[#0F2C59] transition-colors
                 hover:text-[#D63031] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D63031]/25"
    >
      {verContrasena ? "Ocultar" : "Mostrar"}
    </button>
  );

  const campos = (
    <div className="mt-[18px] flex flex-col gap-[15px]">
      {submitError && (
        <Alert variant="error" title="No pudimos validar tus datos">
          {submitError}
        </Alert>
      )}

      {puertaEquivocada && (
        <Alert variant="error" title="Estás en la pestaña equivocada">
          <p>
            Entraste por {espacio.label} y tu usuario pertenece a{" "}
            {WORKSPACES[puertaEquivocada.role].label}. Te dejamos los datos cargados para
            que termines de entrar por la puerta que te corresponde.
          </p>
          <Button variant="danger" size="sm" onClick={irAMiArea} className="mt-2.5">
            Llevame a la puerta correcta
          </Button>
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
        autoComplete="username"
        prefix={monograma}
      />

      <FormField
        label="Contraseña"
        name="password"
        type={verContrasena ? "text" : "password"}
        value={form.password}
        onChange={onChange}
        error={errors.password}
        required
        autoComplete="current-password"
        suffix={verContrasenaBoton}
      />

      <Button
        type="submit"
        variant="primary"
        loading={submitting}
        className="relative mt-[3px] w-full overflow-hidden py-3.5"
      >
        <span className="login-barrido absolute inset-y-0 w-[74px]" aria-hidden="true" />
        {submitting
          ? "Verificando…"
          : esCiudadano
            ? "Ingresar al Portal"
            : `Entrar a ${espacio.label}`}
        {!submitting && (
          <span className="login-flecha" aria-hidden="true">
            →
          </span>
        )}
      </Button>
    </div>
  );

  return (
    <main className="flex min-h-screen flex-col items-center gap-3.5 bg-[#FAFAFA] px-10 pt-12 pb-14">
      <div
        className="hoja-entrada grid w-full max-w-[460px] grid-cols-1 overflow-hidden rounded-[14px]
                   border border-neutral-200 bg-white shadow-[0_34px_76px_-38px_rgba(15,44,89,.5)]
                   min-[880px]:max-w-[920px] min-[880px]:grid-cols-[352px_1fr]"
      >
        {/* ------------------------------------------------- Panel institucional */}
        <section className="relative flex flex-col items-center overflow-hidden bg-[#0F2C59] px-[30px] pt-7 pb-[26px] text-center">
          <div className="login-trama pointer-events-none absolute inset-0" aria-hidden="true" />
          <div
            className="login-halo pointer-events-none absolute -top-[90px] -right-20 h-[280px] w-[280px]"
            aria-hidden="true"
          />

          <div className="relative flex items-center gap-2.5 self-start">
            <img src={logo} alt="Ciudad UADE" className="h-[26px] w-auto object-contain" />
            <div className="text-left leading-[1.15]">
              <p className="text-[13px] font-bold text-white">Ciudad UADE</p>
              <p className="text-[10px] font-semibold uppercase tracking-[0.15em] text-white/45">
                Dirección General de Rentas
              </p>
            </div>
          </div>

          {/* El medallón: aros fijos y una placa que se remonta al cambiar de área. */}
          <div
            className="relative mt-[30px] flex h-[120px] w-[120px] items-center justify-center
                       min-[880px]:h-[184px] min-[880px]:w-[184px]"
            aria-hidden="true"
          >
            <div className="flex h-[184px] w-[184px] scale-[0.652] items-center justify-center min-[880px]:scale-100">
              <span className="login-aro-giro absolute h-[184px] w-[184px] rounded-full border border-dashed border-white/[0.16]" />
              <span className="login-aro-pulso absolute h-[152px] w-[152px] rounded-full border border-[#D63031]/45" />
              <span className="login-aro-pulso-tardio absolute h-[152px] w-[152px] rounded-full border border-[#D63031]/45" />
              <span className="absolute h-32 w-32 rounded-full border border-white/[0.13]" />

              <div
                key={areaActiva}
                className="login-placa relative flex h-25 w-25 items-center justify-center overflow-hidden
                           rounded-full border border-white/20 shadow-[0_14px_30px_-14px_rgba(0,0,0,.7)]"
              >
                <span className="login-tajo absolute -top-6 left-0 h-[150px] w-[13px]" />
                <Emblema role={areaActiva} />
              </div>
            </div>
          </div>

          <p
            key={`${areaActiva}-label`}
            className="login-cruce relative mt-6 max-w-[252px] text-[22px] font-extrabold leading-[1.22] tracking-[-0.02em] text-white"
          >
            {espacio.label}
          </p>
          <p
            key={`${areaActiva}-nota`}
            className="login-cruce relative mt-2.5 max-w-[240px] text-[12.5px] leading-[1.6] text-white/[0.52]"
            style={{ animationDelay: "60ms" }}
          >
            {emblema.nota}
          </p>
          <p
            key={`${areaActiva}-path`}
            className="login-cruce relative mt-3.5 inline-flex items-center gap-[7px] rounded-full border border-white/[0.16]
                       px-[11px] py-[5px] font-mono text-[11px] text-white/55"
            style={{ animationDelay: "90ms" }}
          >
            <span className="login-punto h-1 w-1 rounded-full bg-[#D63031]" aria-hidden="true" />
            {espacio.home}
          </p>

          <div className="relative mt-auto w-full pt-[30px]">
            <div className="h-px bg-white/[0.13]" />
            <p className="mt-4 text-[9.5px] font-bold uppercase tracking-[0.18em] text-white/[0.38]">
              {esCiudadano ? "Acceso ciudadano · una sola puerta" : "Elegí tu área"}
            </p>

            <div
              className="mt-3 flex min-h-[38px] justify-center gap-2"
              style={{ visibility: esCiudadano ? "hidden" : "visible" }}
            >
              {AREAS_AGENTE.map((clave) => {
                const activa = !esCiudadano && clave === area;
                return (
                  <button
                    key={clave}
                    type="button"
                    onClick={() => elegirArea(clave)}
                    aria-label={WORKSPACES[clave].label}
                    aria-pressed={activa}
                    tabIndex={esCiudadano ? -1 : 0}
                    className={`flex h-[38px] w-[38px] items-center justify-center rounded-[10px] border font-mono text-[11px]
                                transition-all duration-200 ease-[cubic-bezier(0.4,0,0.2,1)] hover:-translate-y-[3px]
                                focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/40 ${
                                  activa
                                    ? "border-[#D63031] bg-[#D63031] text-white"
                                    : "border-white/[0.14] bg-white/[0.09] text-white/60"
                                }`}
                  >
                    {EMBLEMAS[clave].mono}
                  </button>
                );
              })}
            </div>
          </div>
        </section>

        {/* --------------------------------------------------------- Formulario */}
        <section className="flex min-w-0 flex-col">
          <div className="h-0.5 bg-[#D63031]" />
          <div className="flex flex-1 flex-col px-6 pt-[30px] pb-7 min-[880px]:px-10">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-[9px]">
                <span
                  className={`rounded-full px-[11px] py-[5px] text-[10.5px] font-bold uppercase tracking-[0.14em] transition-colors ${
                    esCiudadano
                      ? "bg-[#D63031]/[0.08] text-[#D63031]"
                      : "bg-[#0F2C59]/[0.07] text-[#0F2C59]"
                  }`}
                >
                  {esCiudadano ? "Ciudadanía" : "Agentes municipales"}
                </span>
                <span className="text-[11px] text-neutral-400">Sin registro público</span>
              </div>
              <span className="text-[11px] tabular-nums text-neutral-400">Gestión 2026</span>
            </div>

            <form
              key={door}
              onSubmit={onSubmit}
              noValidate
              className="login-cruce-lento flex flex-1 flex-col"
            >
              {esCiudadano ? (
                <>
                  <h2 className="mt-5 text-[30px] font-extrabold leading-[1.02] tracking-[-0.025em] text-[#0F2C59]">
                    Entrar al Portal
                  </h2>

                  <div className="mt-4 flex gap-[11px] rounded-[10px] border border-[#D63031]/[0.26] bg-[#D63031]/[0.05] px-[15px] py-[13px]">
                    <span
                      className="mt-px flex h-5 w-5 flex-none items-center justify-center rounded-full bg-[#D63031] text-[11px] font-bold text-white"
                      aria-hidden="true"
                    >
                      ✓
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="text-[13px] font-semibold text-[#0F2C59]">
                        Estás en el acceso para ciudadanos
                      </p>
                      <p className="mt-[3px] text-[12.5px] leading-[1.6] text-neutral-600">
                        Este es el Portal del Contribuyente: consultás tu propio legajo, tus
                        deudas y tus boletas. Si trabajás en el municipio, tu acceso es otro.
                      </p>
                    </div>
                  </div>

                  <p className="mt-4 max-w-[430px] text-[13px] leading-[1.6] text-neutral-500">
                    Usá el usuario que te dio el municipio: no hay registro público, la cuenta
                    se da de alta en la Dirección de Rentas. Los dos campos son obligatorios.
                  </p>
                </>
              ) : (
                <>
                  <h2 className="mt-5 text-[30px] font-extrabold leading-[1.02] tracking-[-0.025em] text-[#0F2C59]">
                    Iniciar sesión
                  </h2>
                  <p className="mt-3.5 max-w-[430px] text-[13px] leading-[1.6] text-neutral-500">
                    Elegí tu área a la izquierda y confirmá con tu contraseña. Cada rol entra al
                    panel de su propia área. Los dos campos son obligatorios.
                  </p>

                  <div
                    key={area}
                    className="login-cruce mt-5 flex items-center gap-3 rounded-[10px] border border-neutral-200 bg-neutral-50 px-3.5 py-[13px]"
                  >
                    <span className="flex h-[38px] w-[38px] flex-none items-center justify-center rounded-[10px] bg-[#0F2C59] font-mono text-[12px] text-white">
                      {emblema.mono}
                    </span>
                    <div className="min-w-0 flex-1 text-left">
                      <p className="text-[13.5px] font-semibold text-neutral-900">
                        {espacio.label}
                      </p>
                      <p className="text-[11.5px] text-neutral-500">{emblema.nota}</p>
                    </div>
                    <span className="font-mono text-[11px] text-neutral-400">{espacio.home}</span>
                  </div>
                </>
              )}

              {campos}

              {/* La puerta de al lado, siempre a un click. */}
              <div className="mt-auto pt-6">
                <div className="hoja-corte" aria-hidden="true" />
                <button
                  type="button"
                  onClick={() => cambiarPuerta(esCiudadano ? "agente" : "ciudadano")}
                  className={`mt-4 flex w-full items-center justify-between gap-3 rounded-[10px] border border-neutral-200
                              bg-white px-4 py-[13px] text-left transition-all duration-200 ease-[cubic-bezier(0.4,0,0.2,1)]
                              hover:-translate-y-px focus-visible:outline-none focus-visible:ring-2 ${
                                esCiudadano
                                  ? "hover:border-[#0F2C59] hover:bg-neutral-50 focus-visible:ring-[#0F2C59]/25"
                                  : "hover:border-[#D63031] hover:bg-[#D63031]/[0.04] focus-visible:ring-[#D63031]/25"
                              }`}
                >
                  <span>
                    <span className="block text-[13px] font-semibold text-[#0F2C59]">
                      {esCiudadano ? "Trabajo en el municipio" : "Soy ciudadano o contribuyente"}
                    </span>
                    <span className="mt-0.5 block text-[11.5px] text-neutral-500">
                      {esCiudadano
                        ? "Ir al acceso para agentes municipales"
                        : "Ir al Portal del Contribuyente"}
                    </span>
                  </span>
                  <span
                    className={`text-[15px] ${esCiudadano ? "text-[#0F2C59]" : "text-[#D63031]"}`}
                    aria-hidden="true"
                  >
                    →
                  </span>
                </button>
              </div>
            </form>

            <div className="mt-[18px] flex items-end justify-between gap-4">
              <p className="text-[12px] leading-[1.6] text-neutral-400">
                ¿No podés entrar? Escribí a{" "}
                <a
                  href="mailto:soporte@ciudaduade.gob.ar"
                  className="font-medium text-neutral-600 underline decoration-neutral-300 underline-offset-2
                             transition-colors hover:text-[#0F2C59] focus-visible:outline-none
                             focus-visible:ring-2 focus-visible:ring-[#D63031]/25"
                >
                  soporte@ciudaduade.gob.ar
                </a>
                .
              </p>
              <div
                className="relative hidden h-6 w-[132px] flex-none overflow-hidden min-[880px]:block"
                aria-hidden="true"
              >
                <div className="hoja-codigo absolute inset-0" />
                <div className="login-scan absolute inset-y-0 w-6" />
              </div>
            </div>
          </div>
        </section>
      </div>

      <p className="m-0 max-w-[560px] text-center text-[11.5px] text-neutral-400">
        Dos puertas: los agentes municipales eligen su área por emblema; la ciudadanía entra al
        Portal del Contribuyente.
      </p>
    </main>
  );
}

/**
 * El emblema del área: figuras geométricas sobre la placa, en blanco con acento coral.
 * Entran escalonadas cada vez que la placa se remonta.
 */
function Emblema({ role }) {
  if (role === "PERSONAL") {
    return (
      <div className="relative flex flex-col items-center">
        <span className="login-figura h-1 w-[38px] rounded-[2px] bg-white" />
        <span
          className="login-figura mt-[7px] h-1 w-[38px] rounded-[2px] bg-white/60"
          style={{ animationDelay: "70ms" }}
        />
        <span
          className="login-figura mt-[7px] h-1 w-[22px] rounded-[2px] bg-[#D63031]"
          style={{ animationDelay: "140ms" }}
        />
      </div>
    );
  }

  if (role === "SUPERVISOR") {
    return (
      <div className="relative flex flex-col items-center">
        <span className="login-figura h-[34px] w-[34px] rotate-45 rounded border-[3px] border-white" />
        <span
          className="login-figura mt-[-23px] h-3 w-3 rounded-full bg-[#D63031]"
          style={{ animationDelay: "120ms" }}
        />
      </div>
    );
  }

  if (role === "CAJERO") {
    return (
      <div className="relative flex flex-col items-center">
        <span className="login-figura h-[30px] w-[30px] rounded-full border-[3px] border-white" />
        <span
          className="login-figura mt-[-16px] h-[3px] w-3 rounded-[2px] bg-[#D63031]"
          style={{ animationDelay: "110ms" }}
        />
        <span
          className="login-figura mt-[11px] h-[3px] w-11 rounded-[2px] bg-white/55"
          style={{ animationDelay: "180ms" }}
        />
      </div>
    );
  }

  if (role === "AUDITOR") {
    return (
      <div className="relative flex flex-col items-center">
        <span className="login-figura h-[38px] w-[38px] rounded-full border-[3px] border-white" />
        <span
          className="login-figura mt-[-26px] h-[14px] w-[14px] rounded-full border-[3px] border-[#D63031]"
          style={{ animationDelay: "120ms" }}
        />
      </div>
    );
  }

  return (
    <div className="relative flex flex-col items-center">
      <span className="login-figura h-[13px] w-[13px] rounded-full bg-[#D63031]" />
      <span
        className="login-figura mt-[5px] h-[22px] w-[36px] border-[3px] border-white"
        style={{ borderRadius: "4px 4px 3px 3px", animationDelay: "110ms" }}
      />
    </div>
  );
}
