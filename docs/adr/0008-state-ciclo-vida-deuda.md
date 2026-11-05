# ADR-0008: State para el ciclo de vida de la deuda

- Estado: propuesto, pendiente de revisión arquitectónica.
- Fecha: 2026-11-04.
- Relacionado: SCRUM-624, SCRUM-808.

## Contexto

La entidad `Debt` persiste `DebtStatus`, mientras que los servicios calculan y
asignan el estado después de pagos, ajustes y reversiones. Las validaciones de
transición están dispersas y algunas respuestas de conflicto históricamente
usaron HTTP 422.

## Decisión propuesta

Derivar un `DebtLifecycleState` sellado del enum persistido, sin cambiar el
esquema. Los estados `PENDING`, `PARTIALLY_PAID`, `PAID` y `CANCELLED` delegan
las operaciones `imputar`, `revertirImputacion`, `ajustar`, `anular` y
`admiteCobro`. Una transición de estado no permitida usa HTTP 409 y conserva el
código de negocio de la operación.

Reglas propuestas para esta candidata:

- PENDING y PARTIALLY_PAID admiten cobro si conservan saldo; ambos pueden
  ajustarse sujeto a las validaciones económicas existentes.
- Una reversión de imputación sólo parte de PARTIALLY_PAID o PAID y reconstruye
  PENDING o PARTIALLY_PAID según el saldo restaurado.
- Sólo una deuda PENDING sin pagos admite anulación; una deuda cancelada es
  terminal.
- El estado continúa almacenándose como enum; `@PostLoad` lo reconstruye desde
  la columna actual. No requiere migración.

Estas reglas se implementan como propuesta técnica de M5. Este ADR no afirma
aprobación del Product Owner ni ratificación de Seguridad/QA. La regla de
anulación requiere revisión antes de exponerse como una operación API.

## Alternativas

- Mantener guardas y ternarios en cada servicio: se descarta para evitar que
  estados idénticos tengan reglas divergentes.
- Persistir una clase concreta de estado o agregar una tabla: se descarta
  porque el enum actual conserva el contrato y permite reconstrucción sin
  migración.

## Consecuencias

- El estado se deriva del dato persistido y las transiciones se prueban sin
  inicializar Spring.
- Los importes, fórmulas, orden transaccional y eventos se mantienen en los
  servicios existentes.
- Las transiciones inválidas de deuda se normalizan a 409; los errores
  semánticos de datos permanecen en 422.
- El ADR queda pendiente de revisión antes de integrar el cambio en develop.
