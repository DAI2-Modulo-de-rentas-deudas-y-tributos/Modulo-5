# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Contexto del repositorio

Monorepo del **Módulo 5 - Rentas** (tributos, deudas, planes de pago) de un sistema municipal
multi-módulo. El proyecto está en **fase de inicio**: la estructura, los contratos y la base de
Terraform existen, pero `frontend/` y `backend/` todavía no tienen aplicación ni tecnología
elegida (solo README). Al crear código nuevo hay que respetar el modelo de despliegue ya decidido
en los ADR, no inventar uno distinto.

El repositorio y su documentación están **en español**; mantener ese idioma en docs, ADR,
commits y comentarios.

## Comandos

```shell
docker compose up -d postgres     # PostgreSQL 17 local (copiar antes .env.example a .env)

# Frontend (React + Vite) — desde frontend/
npm install
npm run dev                       # http://localhost:5173
npm test                          # vitest run, lo que corre el CI
npm test -- src/services/rentasService.test.js   # un archivo
npm test -- -t "cancela la deuda"                # un test puntual
npm run build                     # dist/ — lo que publica Amplify

# Terraform — lo que valida el CI; ejecutar desde la raíz / cada ambiente
terraform fmt -check -recursive infra
cd infra/environments/dev  && terraform init -backend=false && terraform validate
cd infra/environments/test && terraform init -backend=false && terraform validate
```

`backend/` todavía no tiene aplicación: agregar aquí sus comandos al scaffoldearlo.

## Arquitectura

- **Backend**: monolito modular; el **mismo artefacto** se despliega en dos modos, `api`
  (HTTP detrás de ALB) y `worker` (eventos y procesos masivos). No separar en servicios.
- **Datos**: PostgreSQL es la fuente de verdad transaccional; los binarios (boletas,
  comprobantes, documentación de exenciones) van a S3, nunca a la base.
- **Eventos**: integración vía el broker del Módulo 9 - Core. La publicación usa
  **transactional outbox** y los consumidores son **idempotentes** vía registro **inbox**.
  Cualquier feature que emita o consuma eventos debe seguir estos dos patrones.
- **Contratos**: el catálogo de eventos, sus payloads y las reglas de integración viven en la
  sección "Memoria del proyecto — M5 Rentas / PO" de este archivo, que es la fuente de verdad.
  En el repo quedan `contracts/asyncapi.yaml` (AsyncAPI 3.0, con `channels`/`operations` todavía
  vacíos) y `contracts/schemas/common-event-envelope.schema.json`. **Atención**: ese schema
  todavía describe el envelope viejo (`producer: "rentas"`, `payload`, `eventVersion`,
  `aggregateId`, `correlationId`, `additionalProperties: false`), que **no coincide** con el
  envelope acordado más abajo (`sourceModule: "M5"`, `data`). Hay que unificarlo con el Módulo 9
  antes de generar código: un cambio de contrato no es una decisión local.
- **Infra**: `infra/` es AWS solo por Terraform. `bootstrap/` (estado remoto S3 con
  `use_lockfile = true`, OIDC de GitHub, roles plan/deploy) se aplica una vez; `modules/` por
  capacidad (network, database, ecs, edge, messaging, storage); `environments/dev` y
  `environments/test` son composiciones con **estado remoto independiente** — no usar Terraform
  workspaces para representar ambientes. Región por defecto `sa-east-1`, `project_name`
  `modulo-5-rentas`. No ejecutar `terraform apply` antes del bootstrap y la revisión del plan.

## Flujo de trabajo

- Ramas: desarrollo en `feature/*` → PR a `develop` (ambiente `dev`) → promoción del **mismo
  artefacto inmutable** a `test`. `main` es la versión estable.
- CI (`.github/workflows/ci.yml`): job `repository-structure` verifica que existan
  `frontend`, `backend`, `contracts`, `infra/environments/{dev,test}` y `docs/adr`; job
  `terraform` corre fmt/validate; `ci-required` es el check final obligatorio — si se agregan
  jobs con filtros por carpeta, deben seguir colgando de `ci-required`.
- Las decisiones arquitectónicas relevantes se documentan como ADR numerados en `docs/adr/`
  con contexto, decisión, alternativas y consecuencias.
- Configuración por ambiente siempre por variables de entorno; nada de URLs ni credenciales
  en el código.
- `.editorconfig`: LF, UTF-8, indent 2 espacios (4 para `.java/.kt/.kts`), newline final.

## Frontend — design system obligatorio

El frontend (portal "Ciudad UADE") tiene un design system y un UI kit ya definidos por el equipo.
**Usar siempre estos tokens y reutilizar estos componentes** en vez de escribir markup o colores
nuevos; si falta algo, primero ver si se cubre con props de un componente existente.

**Tipografía**: Inter (Google Fonts), fallback `system-ui, -apple-system, "Segoe UI", Roboto,
sans-serif`. Pesos: 400 texto, 500 subtítulos e inputs, 600 etiquetas y botones, 700 títulos,
800 encabezados destacados. En `index.html`:

```html
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet" />
```

**Paleta**: navy institucional `#0F2C59` (principal: fondos oscuros, títulos, botones primarios,
navbar) y `#163d75`; coral `#D63031` **solo como acento** (CTA, alertas, focus rings, asterisco de
campo requerido) y `#e74c3c`; fondo general `#FAFAFA`; cards `#FFFFFF` con `border-neutral-200`;
texto `#1A1A1A` y secundario `#525252`.

**Tailwind v4** — en el CSS de entrada:

```css
@import "tailwindcss";

@theme {
  --color-navy: #0F2C59;
  --color-navy-light: #163d75;
  --color-coral: #D63031;
  --color-coral-light: #e74c3c;
  --font-sans: "Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}

body {
  margin: 0;
  font-family: var(--font-sans);
  background: #fafafa;
  color: #1a1a1a;
}
```

### Componentes compartidos

**Resumen** (JSX, `export default`, iconos de `lucide-react` con `strokeWidth`
1.5 decorativo / 2 en UI): `Alert` (`variant` success|error|info, `title`, `onDismiss`),
`Breadcrumb` (`items`, `onNavigate`; índice `-1` = home), `FormField` (text/textarea/select con
`error`, `required` y, opcionalmente, `prefix`/`suffix` dentro del campo), `Spinner` (sm|md|lg), `FeatureCard` (`iconName` de lucide, `badge`,
`colors`), `StepIndicatorGeneric` (`steps`, `currentStep`), `PageHeader` (`label`, `title`,
`highlight`, `description`) y `Footer` (`areaName`, `areaEmail`; usa `assets/logo.png`).
Convenciones visuales del kit: tamaños de texto en px arbitrarios (`text-[13px]`, `text-[15px]`),
`rounded-lg`/`rounded-xl`, transiciones `transition-colors` / `duration-300`.

Código fuente canónico — al scaffoldear el frontend estos archivos van en
`frontend/src/components/` (`ui/` y `layout/Footer.jsx`); usarlos tal cual, extendiéndolos por props.

### Alert.jsx
```jsx
import { CheckCircle, AlertCircle, Info, X } from "lucide-react";

const VARIANTS = {
  success: {
    bg: "bg-emerald-50 border-emerald-200",
    icon: CheckCircle,
    iconColor: "text-emerald-500",
    titleColor: "text-emerald-800",
    textColor: "text-emerald-700",
  },
  error: {
    bg: "bg-red-50 border-red-200",
    icon: AlertCircle,
    iconColor: "text-red-500",
    titleColor: "text-red-800",
    textColor: "text-red-700",
  },
  info: {
    bg: "bg-blue-50 border-blue-200",
    icon: Info,
    iconColor: "text-blue-500",
    titleColor: "text-blue-800",
    textColor: "text-blue-700",
  },
};

export default function Alert({ variant = "info", title, children, onDismiss }) {
  const config = VARIANTS[variant];
  const Icon = config.icon;

  return (
    <div className={`relative flex gap-3 rounded-lg border p-4 ${config.bg}`}>
      <Icon className={`h-5 w-5 shrink-0 mt-0.5 ${config.iconColor}`} strokeWidth={2} />
      <div className="flex-1 min-w-0">
        {title && (
          <p className={`text-[14px] font-semibold ${config.titleColor}`}>{title}</p>
        )}
        <div className={`text-[13px] ${config.textColor} ${title ? "mt-1" : ""}`}>
          {children}
        </div>
      </div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="shrink-0 p-0.5 rounded hover:bg-black/5 transition-colors"
          aria-label="Cerrar"
        >
          <X className="h-4 w-4 text-neutral-400" strokeWidth={2} />
        </button>
      )}
    </div>
  );
}
```

### Breadcrumb.jsx
```jsx
import { ChevronRight, Home } from "lucide-react";

export default function Breadcrumb({ items = [], onNavigate }) {
  return (
    <nav aria-label="Breadcrumb" className="flex items-center gap-1 flex-wrap">
      <button
        type="button"
        onClick={() => onNavigate(-1)}
        className="flex items-center gap-1 text-[13px] text-neutral-400 hover:text-[#0F2C59] transition-colors"
      >
        <Home className="h-3.5 w-3.5" strokeWidth={2} />
        <span>Portal de Ayuda</span>
      </button>

      {items.map((item, index) => {
        const isLast = index === items.length - 1;
        return (
          <span key={item.id || index} className="flex items-center gap-1">
            <ChevronRight className="h-3 w-3 text-neutral-300" strokeWidth={2} />
            {isLast ? (
              <span className="text-[13px] font-medium text-neutral-700">
                {item.label}
              </span>
            ) : (
              <button
                type="button"
                onClick={() => onNavigate(index)}
                className="text-[13px] text-neutral-400 hover:text-[#0F2C59] transition-colors"
              >
                {item.label}
              </button>
            )}
          </span>
        );
      })}
    </nav>
  );
}
```

### FormField.jsx
```jsx
export default function FormField({
  label,
  name,
  type = "text",
  placeholder,
  value,
  onChange,
  error,
  required = false,
  options = [],
  disabled = false,
  prefix,
  suffix,
  autoComplete,
}) {
  const baseClasses =
    "w-full rounded-lg border bg-neutral-50 px-3.5 py-2.5 text-[14px] text-neutral-900 placeholder-neutral-400 outline-none transition-colors " +
    "focus:border-[#D63031]/40 focus:bg-white focus:ring-2 focus:ring-[#D63031]/10 " +
    "disabled:opacity-50 disabled:cursor-not-allowed";

  const borderClass = error ? "border-red-300" : "border-neutral-200";

  if (type === "textarea") {
    return (
      <div className="flex flex-col gap-1.5">
        <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
          {label}
          {required && <span className="text-[#D63031] ml-0.5">*</span>}
        </label>
        <textarea
          id={name}
          name={name}
          rows={4}
          placeholder={placeholder}
          value={value}
          onChange={onChange}
          required={required}
          disabled={disabled}
          className={`${baseClasses} ${borderClass} resize-none`}
        />
        {error && <p className="text-[12px] text-red-500">{error}</p>}
      </div>
    );
  }

  if (type === "select") {
    return (
      <div className="flex flex-col gap-1.5">
        <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
          {label}
          {required && <span className="text-[#D63031] ml-0.5">*</span>}
        </label>
        <select
          id={name}
          name={name}
          value={value}
          onChange={onChange}
          required={required}
          disabled={disabled}
          className={`${baseClasses} ${borderClass} appearance-none bg-[url('data:image/svg+xml;charset=UTF-8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%2212%22%20height%3D%2212%22%20viewBox%3D%220%200%2024%2024%22%20fill%3D%22none%22%20stroke%3D%22%239ca3af%22%20stroke-width%3D%222%22%3E%3Cpath%20d%3D%22m6%209%206%206%206-6%22%2F%3E%3C%2Fsvg%3E')] bg-no-repeat bg-[right_0.75rem_center]`}
        >
          <option value="">Seleccionar...</option>
          {options.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        {error && <p className="text-[12px] text-red-500">{error}</p>}
      </div>
    );
  }

  if (prefix || suffix) {
    return (
      <div className="flex flex-col gap-1.5">
        <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
          {label}
          {required && <span className="text-[#D63031] ml-0.5">*</span>}
        </label>
        <div
          className={`flex items-center gap-2.5 rounded-lg border bg-neutral-50 px-3.5 py-2.5 transition-colors
                      focus-within:border-[#D63031]/40 focus-within:bg-white focus-within:ring-2 focus-within:ring-[#D63031]/10
                      ${borderClass} ${disabled ? "opacity-50" : ""}`}
        >
          {prefix}
          <input
            id={name}
            name={name}
            type={type}
            placeholder={placeholder}
            value={value}
            onChange={onChange}
            required={required}
            disabled={disabled}
            autoComplete={autoComplete}
            className="min-w-0 flex-1 bg-transparent text-[14px] text-neutral-900 placeholder-neutral-400
                       outline-none disabled:cursor-not-allowed"
          />
          {suffix}
        </div>
        {error && <p className="text-[12px] text-red-500">{error}</p>}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
        {label}
        {required && <span className="text-[#D63031] ml-0.5">*</span>}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        placeholder={placeholder}
        value={value}
        onChange={onChange}
        required={required}
        disabled={disabled}
        autoComplete={autoComplete}
        className={`${baseClasses} ${borderClass}`}
      />
      {error && <p className="text-[12px] text-red-500">{error}</p>}
    </div>
  );
}
```

### Spinner.jsx
```jsx
export default function Spinner({ size = "md", className = "" }) {
  const sizes = {
    sm: "h-4 w-4 border-2",
    md: "h-6 w-6 border-2",
    lg: "h-8 w-8 border-[3px]",
  };

  return (
    <div
      className={`${sizes[size]} rounded-full border-neutral-200 border-t-[#D63031] animate-spin ${className}`}
      role="status"
      aria-label="Cargando"
    />
  );
}
```

### FeatureCard.jsx
```jsx
import { ArrowUpRight, icons } from "lucide-react";

export default function FeatureCard({
  title,
  description,
  iconName,
  itemCount,
  badge,
  onClick,
  colors = {
    accentHover: "#D63031",
    iconHover: "#D63031",
    ringFocus: "#D63031",
    badgeDefault: "bg-neutral-100 text-neutral-500",
  },
  showArrow = true,
  showItemCount = true,
  className = "",
}) {
  const IconComponent = icons[iconName];

  const getBadgeStyle = (badgeObj) => {
    if (!badgeObj) return "";
    return badgeObj.className || colors.badgeDefault;
  };

  return (
    <button
      type="button"
      onClick={onClick}
      className={`group relative flex flex-col gap-4 rounded-xl border border-neutral-200/80 bg-white p-5 text-left
                   transition-all duration-300 ease-[cubic-bezier(0.4,0,0.2,1)]
                   hover:shadow-[0_4px_24px_-6px_rgba(0,0,0,0.1)]
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2
                   ${className}`}
      style={{
        "--hover-accent": colors.accentHover,
        "--icon-hover": colors.iconHover,
        "--ring-focus": colors.ringFocus,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = `${colors.accentHover}33`;
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = "rgba(229, 231, 235, 0.8)";
      }}
    >
      {/* Accent line on hover — top edge */}
      <div
        className="absolute top-0 left-3 right-3 h-0.5 rounded-full scale-x-0 origin-left transition-transform duration-300 group-hover:scale-x-100"
        style={{ backgroundColor: colors.accentHover }}
      />

      <div className="flex items-start justify-between">
        <div
          className="text-neutral-400 transition-colors duration-300"
          style={{ "--hover-color": colors.iconHover }}
        >
          {IconComponent ? (
            <IconComponent className="h-5 w-5 group-hover:text-current" strokeWidth={1.5} style={{ color: "inherit" }} />
          ) : (
            <span className="text-xs">?</span>
          )}
        </div>
        {badge && (
          <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${getBadgeStyle(badge)}`}>
            {badge.text}
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <h3 className="text-[15px] font-semibold text-neutral-900 leading-snug transition-colors">
          {title}
        </h3>
        <p className="text-[13px] text-neutral-400 leading-relaxed line-clamp-2">
          {description}
        </p>
      </div>

      <div className="mt-auto flex items-center justify-between pt-1">
        {showItemCount && itemCount !== undefined && (
          <span className="text-[11px] text-neutral-300 tabular-nums">
            {itemCount} activos
          </span>
        )}
        {showArrow && (
          <ArrowUpRight
            className="h-4 w-4 text-neutral-300 transition-all duration-300 group-hover:-translate-y-0.5 group-hover:translate-x-0.5"
            strokeWidth={1.5}
            style={{ color: "inherit" }}
          />
        )}
      </div>
    </button>
  );
}
```

### StepIndicatorGeneric.jsx
```jsx
import { Check } from "lucide-react";

export default function StepIndicatorGeneric({
  steps = [],
  currentStep = 0,
  colors = {
    completed: "#D63031",
    current: "#0F2C59",
    pending: "#f3f4f6",
    completedText: "white",
    currentText: "white",
    pendingText: "#9ca3af",
    connectorCompleted: "#D63031",
    connectorPending: "#e5e7eb",
  },
  hideLabelsOnMobile = true,
  showConnectors = true,
}) {
  if (steps.length === 0) {
    return null;
  }

  return (
    <div className="flex items-center gap-2">
      {steps.map((step, index) => {
        const isCompleted = index < currentStep;
        const isCurrent = index === currentStep;

        const circleColor = isCompleted ? colors.completed : isCurrent ? colors.current : colors.pending;
        const textColor = isCompleted ? colors.completedText : isCurrent ? colors.currentText : colors.pendingText;

        return (
          <div key={`${step.label}-${index}`} className="flex items-center gap-2">
            <div className="flex items-center gap-1.5">
              <div
                className="flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-semibold transition-all duration-300"
                style={{
                  backgroundColor: circleColor,
                  color: textColor,
                  boxShadow: isCurrent ? `0 0 0 2px ${colors.current}33` : "none",
                }}
              >
                {isCompleted ? (
                  <Check className="h-3.5 w-3.5" strokeWidth={3} />
                ) : (
                  index + 1
                )}
              </div>
              {step.label && (
                <span
                  className={`text-[12px] transition-colors ${
                    hideLabelsOnMobile ? "hidden sm:inline" : "inline"
                  } ${
                    isCurrent
                      ? "font-semibold"
                      : isCompleted
                        ? "font-normal"
                        : "font-normal"
                  }`}
                  style={{
                    color: isCompleted
                      ? "#6b7280"
                      : isCurrent
                        ? "#1f2937"
                        : "#9ca3af",
                  }}
                >
                  {step.label}
                </span>
              )}
            </div>
            {showConnectors && index < steps.length - 1 && (
              <div
                className="h-px w-6 sm:w-10 transition-colors"
                style={{
                  backgroundColor: isCompleted
                    ? colors.connectorCompleted
                    : colors.connectorPending,
                }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}
```

### PageHeader.jsx
```jsx
export default function PageHeader({ label, title, highlight, description }) {
  return (
    <section className="relative w-full bg-white border-b border-neutral-200 overflow-hidden">
      {/* Top accent line */}
      <div className="absolute top-0 left-0 w-full h-1.5 bg-gradient-to-r from-[#0F2C59] to-[#D63031]" />

      <div className="relative mx-auto max-w-6xl px-5 py-10 sm:py-14">
        <div className="flex items-center gap-3 mb-4">
          <div className="w-8 h-[2px] bg-[#D63031]" />
          <span className="text-[#D63031] text-[12px] font-bold tracking-[0.2em] uppercase">
            {label}
          </span>
        </div>
        <h1 className="text-[2.5rem] sm:text-[3rem] font-extrabold text-[#0F2C59] tracking-[-0.02em] leading-tight mb-3">
          {title}{" "}
          {highlight && (
            <span className="text-transparent bg-clip-text bg-gradient-to-r from-[#D63031] to-[#e74c3c]">
              {highlight}
            </span>
          )}
        </h1>
        {description && (
          <p className="text-[16px] text-neutral-500 max-w-xl font-medium leading-relaxed">
            {description}
          </p>
        )}
      </div>
    </section>
  );
}
```

### Footer.jsx
```jsx
import { Phone, Mail, MapPin } from "lucide-react";
import logo from "../../assets/logo.png";

function Footer({ areaName, areaEmail }) {
  return (
    <footer className="border-t border-neutral-200/60 bg-white">
      <div className="h-px bg-gradient-to-r from-transparent via-[#D63031]/20 to-transparent" />

      <div className="mx-auto max-w-6xl px-5 py-10">
        <div className="flex flex-col md:flex-row md:items-start md:justify-between gap-8">
          {/* Brand + contact */}
          <div className="max-w-xs">
            <div className="flex items-center gap-2 mb-3">
              <img src={logo} alt="Ciudad UADE Logo" className="h-5 w-auto object-contain" />
              <span className="text-[13px] font-bold text-[#0F2C59]">Ciudad UADE</span>
            </div>
            <p className="text-[12px] text-neutral-400 leading-relaxed">
              {areaName}
              <br />
              Municipalidad de Ciudad UADE — Gestión 2026.
            </p>
            <div className="mt-4 flex flex-col gap-1.5">
              <span className="flex items-center gap-2 text-[12px] text-neutral-400">
                <Phone className="h-3 w-3 text-[#D63031]/50" strokeWidth={1.5} /> 147 — Línea Municipal
              </span>
              <span className="flex items-center gap-2 text-[12px] text-neutral-400">
                <Mail className="h-3 w-3 text-[#D63031]/50" strokeWidth={1.5} /> {areaEmail}
              </span>
              <span className="flex items-center gap-2 text-[12px] text-neutral-400">
                <MapPin className="h-3 w-3 text-[#D63031]/50" strokeWidth={1.5} /> Av. Independencia 1100, CABA
              </span>
            </div>
          </div>

          {/* Link columns */}
          <div className="flex gap-14">
            <div>
              <p className="text-[10px] font-semibold uppercase tracking-[0.15em] text-neutral-400 mb-3">
                Trámites
              </p>
              <ul className="flex flex-col gap-2">
                {["Iniciar Reclamo", "Consultar Ticket", "Habilitaciones", "Turnos Online"].map(
                  (label) => (
                    <li key={label}>
                      <a href="#" className="link-hover text-[12px] text-neutral-500 hover:text-[#0F2C59] transition-colors">
                        {label}
                      </a>
                    </li>
                  )
                )}
              </ul>
            </div>
          </div>
        </div>

        {/* Bottom bar */}
        <div className="mt-8 pt-5 border-t border-neutral-100 flex items-center justify-between">
          <p className="text-[11px] text-neutral-300">
            © {new Date().getFullYear()} Municipalidad de Ciudad UADE. Todos los derechos reservados.
          </p>
          <div className="flex items-center gap-1">
            <div className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
            <span className="text-[10px] text-neutral-300">Todos los servicios operativos</span>
          </div>
        </div>
      </div>
    </footer>
  );
}

export default Footer;
```

## Memoria del proyecto — M5 Rentas / PO

### Contexto general

El proyecto se divide en módulos independientes. Cada módulo tiene su propio frontend, backend y base de datos. Los módulos **no pueden acceder directamente a las bases de datos de otros equipos**. La integración entre módulos se realiza principalmente mediante **eventos asincrónicos**; REST se reserva para operaciones donde sea necesaria una respuesta inmediata.

M5 corresponde al **Módulo de Rentas**. El usuario trabaja como **Product Owner (PO)** del módulo.

Principios acordados:

* Cada módulo es dueño de sus propios datos.
* No existen FK físicas entre bases de datos de distintos módulos.
* Las referencias a entidades externas se mantienen mediante IDs/referencias lógicas.
* Los eventos deben contener la información mínima necesaria para que el consumidor procese la operación.
* Los eventos usan un envelope común.
* Los eventos deben contemplar idempotencia mediante `eventId`.
* Deben existir reintentos y DLQ para eventos que no puedan procesarse.
* Debe conservarse evidencia/auditoría de eventos publicados y procesados.
* La indisponibilidad temporal de otro módulo no debería derribar el procesamiento local.

---

## Historias de Usuario

Se decidió separar las funcionalidades en HU relativamente pequeñas y evitar historias excesivamente grandes.

Los criterios de aceptación deben seguir el formato:

> **Dado que**, **cuando**, **entonces**.

Las prioridades se manejan por separado de Story Points. Story Points y prioridad no son lo mismo.

Si una HU se repite entre roles/módulos, se puede indicar el rol como:

> Personal de Rentas / Supervisor

cuando corresponda.

También se acordó que si una HU depende de un evento de otro módulo, los criterios deben reflejar el contrato de integración real y no asumir acceso directo a la base de datos externa.

---

## Eventos externos de M5

### Eventos consumidos

#### M1 — Ciudadanos / Organizaciones

M5 consume:

* `citizenRegistered`
* `citizenUpdated`
* `citizenBlocked`
* `citizenDeceased`
* `organizationRegistered`
* `representationGranted`
* `representationExpired`

M5 no modifica directamente ciudadanos, organizaciones ni representaciones.

#### M2 — Atención Ciudadana

Actualmente M2 publica únicamente:

* `ticketCreated`
* `ticketUpdated`

Los estados como `ticketInProgress`, etc. **no son eventos separados**; son estados dentro de los eventos.

M5 publica hacia M2:

* `updateTicketStatus`

#### M4 — Habilitaciones

M5 consume:

* `permitFeeGenerated`
* `commercialFineGenerated`
* `permitUpdate`

`permitUpdate` contiene el estado:

* `PROVISIONAL`
* `APPROVED`
* `REJECTED`
* `SUSPENDED`
* `EXPIRED`

Los eventos antiguos:

* `enablingFeeGenerated`
* `enablingSuspended`

se consideran reemplazados y no deben mantenerse como contratos externos.

##### Tratamiento de `permitUpdate`

Todos los estados actualizan la referencia local del permiso.

`PROVISIONAL`, `APPROVED` y `REJECTED` no generan una deuda por sí mismos.

`SUSPENDED` y `EXPIRED` pueden generar restricciones hacia adelante sobre operaciones que requieran un permiso activo/vigente.

Un `permitUpdate` no genera automáticamente una deuda.

La generación económica se produce mediante eventos específicos como:

* `permitFeeGenerated`
* `commercialFineGenerated`

M5 no publica un evento de vuelta simplemente porque recibió `SUSPENDED` o `EXPIRED`.

#### M7 — Tránsito

M5 consume:

* `infractionConfirmed`

M5 publica eventos relacionados con pagos/deudas que M7 necesite conocer.

#### M8 — Desarrollo Social

M5 consume:

* `socialBenefitUpdated`

M5 publica:

* `overdueDebt`
* `exemptionRequested`
* `updateExemptionStatus`

---

## Eventos publicados por M5

Lista simplificada actual:

* `paymentRegistered`
* `paymentReversed`
* `debtSettled`
* `overdueDebt`
* `paymentPlanRequested`
* `updatePaymentPlanStatus`
* `exemptionRequested`
* `updateExemptionStatus`
* `updateTicketStatus`

### Estados

#### `updatePaymentPlanStatus`

Reemplaza:

* `paymentPlanGranted`
* `paymentPlanRejected`

Estados:

* `GRANTED`
* `REJECTED`

#### `updateExemptionStatus`

Reemplaza:

* `exemptionApproved`
* `exemptionRejected`

Estados:

* `APPROVED`
* `REJECTED`

`exemptionRequested` se mantiene separado porque representa el inicio del flujo.

#### `updateTicketStatus`

Mantiene los estados del ticket dentro del evento, en lugar de crear un evento por estado.

Ejemplos de estados posibles:

* `IN_PROGRESS`
* `WAITING_FOR_INFORMATION`
* `COMPLETED`
* `REJECTED`

Los valores definitivos deben coincidir con M2.

---

## Eventos que NO son contratos externos

Los siguientes se consideran eventos internos de dominio de M5:

* `eSettlementGenerated`
* `receiptIssued`
* `debtGenerated`
* `creditBalanceGenerated`

No deben aparecer como eventos publicados hacia otros módulos mientras no exista un consumidor externo confirmado.

La regla acordada es:

> Si no se puede identificar quién consume un evento y para qué, no debe considerarse un contrato de integración externo.

También se descartaron por ahora:

* `notificationSent`
* `notificationFailed`

porque no se identificó un consumidor/uso externo concreto para Rentas.

---

## Simplificación de eventos

No se deben unificar eventos solamente para reducir cantidad.

Se unifican cuando:

1. corresponden a la misma entidad;
2. representan principalmente un cambio de estado;
3. tienen los mismos consumidores;
4. el payload es muy similar.

Ejemplos que se simplificaron:

```text
paymentPlanGranted
paymentPlanRejected
        ↓
updatePaymentPlanStatus
```

```text
exemptionApproved
exemptionRejected
        ↓
updateExemptionStatus
```

Ya estaban correctamente simplificados:

```text
permitUpdate
updateTicketStatus
```

No se recomienda unificar:

* `paymentRegistered` + `paymentReversed`
* `debtSettled` + `overdueDebt`
* `exemptionRequested` + `updateExemptionStatus`

porque representan hechos de negocio diferentes.

---

## Envelope común de eventos

Todos los eventos externos deben seguir una estructura común:

```json
{
  "eventId": "uuid",
  "eventType": "nombreDelEvento",
  "occurredAt": "2026-08-24T14:30:00-03:00",
  "sourceModule": "M5",
  "data": {}
}
```

Campos mínimos:

* `eventId`: identificador único del evento.
* `eventType`: nombre del evento.
* `occurredAt`: fecha/hora de ocurrencia.
* `sourceModule`: módulo productor.
* `data`: payload específico del evento.

`eventId` permite implementar idempotencia.

---

## Payloads acordados

### M1 → M5

#### `citizenRegistered`

```json
{
  "citizenId": 123,
  "dni": "40111222",
  "cuit": "20-40111222-3",
  "firstName": "Juan",
  "lastName": "Perez"
}
```

#### `citizenUpdated`

Mismo conjunto de datos relevante para Rentas.

#### `citizenBlocked`

```json
{
  "citizenId": 123,
  "blockedAt": "2026-08-24T14:40:00-03:00"
}
```

#### `citizenDeceased`

```json
{
  "citizenId": 123,
  "dateOfDeath": "2026-08-20"
}
```

#### `organizationRegistered`

```json
{
  "organizationId": 78,
  "cuit": "30-71234567-8",
  "legalName": "Comercial ABC S.A."
}
```

#### `representationGranted`

```json
{
  "representationId": 456,
  "representativeCitizenId": 123,
  "representedType": "ORGANIZATION",
  "representedId": 78,
  "validFrom": "2026-08-24",
  "validUntil": "2027-08-24"
}
```

#### `representationExpired`

```json
{
  "representationId": 456,
  "expiredAt": "2027-08-24T00:00:00-03:00"
}
```

---

## M2 → M5

#### `ticketCreated`

```json
{
  "ticketId": 1001,
  "citizenId": 123,
  "category": "RENTAS",
  "description": "El pago no aparece imputado",
  "priority": "HIGH"
}
```

#### `ticketUpdated`

```json
{
  "ticketId": 1001,
  "additionalInformation": "El ciudadano adjuntó información"
}
```

---

## M5 → M2

#### `updateTicketStatus`

```json
{
  "ticketId": 1001,
  "status": "IN_PROGRESS"
}
```

Si se rechaza, puede incluir:

```json
{
  "ticketId": 1001,
  "status": "REJECTED",
  "reason": "El caso no corresponde al módulo de Rentas"
}
```

---

## M4 → M5

#### `permitFeeGenerated`

```json
{
  "permitFeeId": 501,
  "permitId": 250,
  "taxpayerType": "ORGANIZATION",
  "taxpayerId": 78,
  "amount": 50000.00,
  "dueDate": "2026-09-10"
}
```

#### `commercialFineGenerated`

```json
{
  "fineId": 700,
  "permitId": 250,
  "taxpayerType": "ORGANIZATION",
  "taxpayerId": 78,
  "amount": 150000.00,
  "dueDate": "2026-09-15",
  "reason": "Incumplimiento comercial"
}
```

#### `permitUpdate`

```json
{
  "permitId": 250,
  "taxpayerType": "ORGANIZATION",
  "taxpayerId": 78,
  "status": "SUSPENDED"
}
```

---

## M7 → M5

#### `infractionConfirmed`

```json
{
  "infractionId": 850,
  "taxpayerType": "CITIZEN",
  "taxpayerId": 123,
  "amount": 75000.00,
  "dueDate": "2026-09-20",
  "confirmedAt": "2026-08-24T14:30:00-03:00"
}
```

---

## M5 → M4 / M7

Los eventos de pagos usan referencias lógicas al origen.

#### `paymentRegistered`

```json
{
  "paymentId": 9001,
  "originType": "PERMIT_FEE",
  "originId": 501,
  "amountPaid": 50000.00,
  "paidAt": "2026-08-24T15:30:00-03:00",
  "remainingBalance": 0.00
}
```

Para Tránsito:

```text
originType = TRAFFIC_INFRACTION
originId = infractionId
```

#### `debtSettled`

```json
{
  "debtId": 3001,
  "originType": "PERMIT_FEE",
  "originId": 501,
  "settledAt": "2026-08-24T15:30:00-03:00"
}
```

#### `paymentReversed`

```json
{
  "paymentId": 9001,
  "originType": "PERMIT_FEE",
  "originId": 501,
  "reversedAmount": 50000.00,
  "remainingBalance": 50000.00,
  "reason": "Pago registrado por error"
}
```

El mismo contrato puede utilizarse para M4, M7 u otros módulos, cambiando `originType` y `originId`.

---

## M8

### M8 → M5

#### `socialBenefitUpdated`

```json
{
  "benefitId": 400,
  "citizenId": 123,
  "status": "ACTIVE",
  "benefitType": "TAX_DISCOUNT",
  "discountPercentage": 50.00,
  "validFrom": "2026-08-01",
  "validUntil": "2027-07-31",
  "applicableConceptCodes": [
    "TASA_SERVICIOS"
  ]
}
```

Debe confirmarse con M8 si Rentas recibe directamente el porcentaje/descuento o si existe una regla compartida que permita determinarlo.

### M5 → M8

#### `overdueDebt`

```json
{
  "debtId": 3200,
  "citizenId": 123,
  "conceptCode": "TASA_SERVICIOS",
  "outstandingAmount": 85000.00,
  "dueDate": "2026-08-15"
}
```

#### `exemptionRequested`

```json
{
  "requestId": 600,
  "citizenId": 123,
  "conceptCode": "TASA_SERVICIOS",
  "reason": "Situación socioeconómica",
  "requestedPercentage": 100.00,
  "requestedFrom": "2026-09-01",
  "requestedUntil": "2027-08-31"
}
```

#### `updateExemptionStatus`

Aprobada:

```json
{
  "requestId": 600,
  "status": "APPROVED",
  "exemptionId": 700,
  "citizenId": 123,
  "conceptCode": "TASA_SERVICIOS",
  "percentage": 100.00,
  "validFrom": "2026-09-01",
  "validUntil": "2027-08-31"
}
```

Rechazada:

```json
{
  "requestId": 600,
  "status": "REJECTED",
  "citizenId": 123,
  "conceptCode": "TASA_SERVICIOS",
  "reason": "No cumple los requisitos"
}
```

---

## Planes de pago

Se mantienen como candidatos hasta confirmar el consumidor externo.

#### `paymentPlanRequested`

```json
{
  "requestId": 800,
  "taxpayerType": "CITIZEN",
  "taxpayerId": 123,
  "debtIds": [3001, 3002],
  "totalDebt": 200000.00,
  "installments": 6
}
```

#### `updatePaymentPlanStatus`

Aprobado:

```json
{
  "requestId": 800,
  "status": "GRANTED",
  "planId": 850,
  "taxpayerId": 123,
  "installments": 6,
  "totalAmount": 220000.00
}
```

Rechazado:

```json
{
  "requestId": 800,
  "status": "REJECTED",
  "taxpayerId": 123,
  "reason": "La deuda no cumple las condiciones"
}
```

Si ningún módulo externo consume estos eventos, deben considerarse eventos internos y no contratos de integración.

---

## Arquitectura y restricciones

### Propiedad de datos

Cada módulo es dueño de sus entidades.

Ejemplo:

* M1 → ciudadanos, organizaciones, representaciones.
* M4 → permisos/habilitaciones.
* M7 → infracciones.
* M8 → beneficios sociales.
* M5 → liquidaciones, deudas, pagos, planes, exenciones, etc.

M5 no consulta directamente la BD de M1/M4/M7/M8.

En su lugar, recibe eventos y mantiene referencias locales.

Ejemplo:

```text
M7
  infractionId = 850
        ↓
infractionConfirmed
        ↓
M5
  Debt
    originType = TRAFFIC_INFRACTION
    originId = 850
```

No existe FK física hacia M7.

---

## Idempotencia

Todo evento externo debe incluir `eventId`.

M5 debe registrar eventos procesados para evitar duplicaciones.

Conceptualmente:

```text
ProcessedEvent
----------------
eventId
eventType
processedAt
status
```

Si llega dos veces el mismo `eventId`:

```text
Primera vez → procesar
Segunda vez → ignorar
```

Esto es especialmente importante para eventos que pueden generar deudas.

---

## Reintentos y DLQ

Si un evento no puede procesarse:

```text
Evento
  ↓
Intento 1
  ↓
Intento 2
  ↓
Intento 3
  ↓
DLQ
```

El evento no debe perderse ni provocar una caída general del sistema.

---

## Auditoría / evidencia

Debe existir trazabilidad de eventos publicados y procesados.

Conceptualmente:

```text
EventLog
----------------
eventId
eventType
direction
sourceModule
destinationModule
occurredAt
processedAt
status
payload
```

No es obligatorio utilizar exactamente ese nombre/modelo; lo importante es conservar evidencia suficiente.

---

## REST vs eventos

Los eventos son el mecanismo principal para integración desacoplada.

REST puede utilizarse cuando una operación necesita una respuesta inmediata.

No se debe usar REST para acceder directamente a la BD de otro módulo.

Ejemplo permitido:

```text
M5 → API M4
```

Ejemplo prohibido:

```text
M5 → BD M4
```

---

## Próximo trabajo

Una vez cerrados los eventos, el siguiente paso acordado es definir **Request/Response de las operaciones de M5**.

No conviene crear simplemente un Request/Response genérico por entidad. Es mejor definir DTOs por operación/caso de uso.

Ejemplo:

```text
RegisterPaymentRequest
PaymentResponse

RequestPaymentReversalRequest
PaymentReversalResponse

AllocatePaymentRequest
PaymentAllocationResponse
```

Orden recomendado:

1. Liquidación
2. Deuda
3. Boleta
4. Pago
5. Plan de pago
6. Exenciones
7. Tickets
8. Consultas y operaciones auxiliares

La idea es definir primero qué necesita cada operación y luego derivar los endpoints REST.

---

## Regla general para el PO

Antes de agregar un evento externo al Miro, preguntar:

> **¿Quién lo consume/publica y para qué?**

Si no existe un productor/consumidor confirmado, no debe considerarse un contrato externo.

Antes de agregar un campo a un payload:

> **¿El consumidor realmente necesita este dato para procesar el evento?**

Si no, no se agrega.

Antes de crear un nuevo evento:

> **¿Es un hecho de negocio diferente o solamente un cambio de estado de la misma entidad?**

Si es solamente un cambio de estado y mantiene el mismo consumidor, evaluar un evento `update...Status`.

---

## Estado actual

M5 tiene definidos:

* HU y criterios de aceptación en formato Dado/Cuando/Entonces.
* Prioridades separadas de Story Points.
* Eventos consumidos y publicados.
* Estados de eventos.
* Payload mínimo de integración.
* Distinción entre eventos externos e internos.
* Regla de no acceso a BD de otros módulos.
* Idempotencia como requisito.
* Reintentos/DLQ como requisito.
* Auditoría de eventos como requisito.
* Simplificación de eventos por cambio de estado.

Pendiente:

* Confirmar definitivamente consumidores de algunos eventos con los demás grupos.
* Terminar Request/Response de las operaciones REST de M5.
* Revisar las HU contra los contratos finales de eventos.
* Comparar Miro final vs Jira.
* Mantener la bitácora del PO con decisiones, problemas, cambios y aprendizajes.
