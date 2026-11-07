# ADR-0008: State para el ciclo de vida de la deuda

- Estado: decisión funcional adoptada por delegación del Product Owner.
- Fecha: 2026-11-07.
- Relacionado: SCRUM-624, SCRUM-808.

## Contexto

La entidad `Debt` persiste `DebtStatus`, mientras que los servicios calculan y
asignan el estado después de pagos, ajustes y reversiones. Las validaciones de
transición están dispersas y algunas respuestas de conflicto históricamente
usaron HTTP 422.

## Decisión

Se adopta el patrón `DebtLifecycleState` sellado, derivado del enum persistido,
sin cambiar el esquema. Los estados `PENDING`, `PARTIALLY_PAID`, `PAID` y
`CANCELLED` delegan las operaciones `imputar`, `revertirImputacion`, `ajustar`,
`anular` y `admiteCobro`. Una transición de estado no permitida usa HTTP 409 y
conserva el código de negocio de la operación.

Reglas adoptadas:

- PENDING y PARTIALLY_PAID admiten cobro si conservan saldo; ambos pueden
  ajustarse sujeto a las validaciones económicas existentes.
- Una reversión de imputación sólo parte de PARTIALLY_PAID o PAID y reconstruye
  PENDING o PARTIALLY_PAID según el saldo restaurado.
- Sólo una deuda PENDING sin pagos admite anulación; una deuda cancelada es
  terminal.
- El estado continúa almacenándose como enum; `@PostLoad` lo reconstruye desde
  la columna actual. No requiere migración.

La implementación conserva los códigos y el enum de persistencia existentes;
no agrega una ruta API para anular deudas. Si se incorpora una operación de
anulación en el futuro, deberá mantener las reglas de este estado y definir su
autorización y contrato HTTP antes de exponerse.

La decisión funcional fue delegada por el Product Owner y adoptada por M5 el
2026-11-07. No implica aprobación de Seguridad, QA ni de una revisión externa
de arquitectura.

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
- La implementación de State ya forma parte de `develop`; esta actualización
  registra la decisión funcional delegada y no cambia el código productivo.
