# Guía de aprendizaje - cómo se construyó M5 Rentas

Este documento queda solamente en esta PC, dentro del proyecto. Explica decisiones y pasos para que puedas estudiar el backend mientras evoluciona.

## 1. Qué había al comenzar

El ZIP tenía el esqueleto que genera Spring Initializr. Eso sirve para arrancar, pero todavía no representa un módulo: faltaban tablas, reglas, endpoints y pruebas.

## 2. Cómo leer el alcance

Separé tres tipos de información:

- Contrato: endpoints, roles y eventos que el PDF exige.
- Regla de negocio: por ejemplo, una configuración no pasa de `DRAFT` a `ACTIVE` sin aprobación.
- Propuesta v1: decisiones sugeridas que todavía deben validarse con docente u otros módulos.

Esta separación evita tratar una sugerencia como contrato definitivo. Es especialmente importante con eventos y autenticación.

## 3. Capas usadas

- `ApiController`: recibe HTTP, valida DTO y aplica roles.
- Servicios en `DomainServices`: ejecutan casos de uso y reglas económicas.
- Entidades en `DomainEntities`: representan datos persistidos.
- Repositorios: Spring Data genera las consultas comunes.
- `IntegrationServices`: recibe eventos desde un adapter y publica el Outbox.
- Flyway: crea el mismo esquema en cada instalación.

No se agregó Node ni se consultan bases de otros módulos.

## 4. Por qué se usa BigDecimal

`double` representa números binarios aproximados. En dinero, `0.1 + 0.2` puede no ser exactamente `0.3`. `BigDecimal` permite escala y redondeo explícitos. En este proyecto los importes se normalizan a dos decimales con `HALF_UP`.

## 5. La transacción del flujo principal

Al crear una liquidación, el servicio guarda `Liquidation` y `Debt` dentro de un método `@Transactional`. Si falla la deuda, Spring revierte también la liquidación. Así no queda una liquidación emitida sin deuda.

El pago hace algo similar:

1. crea `Payment`;
2. valida que pago y deuda pertenezcan al mismo contribuyente;
3. crea `PaymentAllocation`;
4. reduce el saldo de `Debt`;
5. crea `CreditBalance` si hubo sobrepago;
6. guarda `OutboxEvent`;
7. confirma todo junto.

## 6. Estados y no borrado

Los movimientos económicos no se eliminan. Una reversión marca el pago `REVERSED` y las imputaciones `REVERSED`, luego restaura el saldo de deuda. Conservar la historia permite auditoría y evita que desaparezcan operaciones financieras.

## 7. Idempotencia de eventos

Hay dos defensas:

- `ProcessedEvent.eventId`: evita procesar dos veces la misma entrega.
- `sourceModule + externalType + externalReferenceId`: evita duplicar la obligación aunque el emisor reintente con otro UUID.

El flujo `infractionConfirmed` no tiene endpoint REST. Un adapter de broker debe llamar `ExternalObligationService.consumeInfraction`. El adapter actual de salida sólo registra eventos en logs porque el broker real no fue acordado.

## 8. Outbox explicado simple

Publicar al broker dentro de la transacción sería frágil: la base podría confirmar y el broker fallar, o al revés. Por eso el caso de uso guarda un `OutboxEvent` junto con el pago. Después, `OutboxPublisher` publica pendientes y registra reintentos. La operación local no depende de que otro módulo esté disponible.

## 9. Seguridad local y real

El perfil `dev` acepta headers `X-Dev-User`, `X-Dev-Roles` y `X-Dev-Taxpayer-Id`. Es un adapter visible y aislado para aprender/probar. El perfil normal tiene `dev-mode: false`; allí debe integrarse el JWT del Core. Nunca hay contraseñas propias de M5.

El rol `AUDITOR` sólo aparece en endpoints de lectura. El rol `TAXPAYER` además pasa por `requireOwnership`: no alcanza con mandar otro `taxpayerId` en la URL.

## 10. Cómo seguir el código

Para estudiar un flujo, empezá en la ruta de `ApiController`, buscá el método del servicio y luego las entidades/repositories que usa. Por ejemplo:

`POST /api/v1/payments` → `PaymentService.register` → `Payment`, `PaymentAllocation`, `Debt`, `CreditBalance`, `OutboxEvent`, `AuditEntry`.

## 11. Qué validaron los tests

- liquidación con configuración vigente y rechazo sin configuración;
- pago total y pago parcial prohibido;
- sobrepago y reversión aprobada;
- duplicado técnico y duplicado de negocio de `infractionConfirmed`;
- doble plan activo;
- exención vigente;
- auditor intentando escribir;
- contribuyente accediendo a información ajena.

## 12. Próximos aprendizajes

Los siguientes bloques útiles para practicar son: Testcontainers con PostgreSQL real, DTO de respuesta separados, pruebas de contrato de eventos y un adapter concreto de Kafka o RabbitMQ cuando el equipo defina cuál usar.

## 13. Boletas, pago electrónico y saldo a favor

Una boleta no es una deuda nueva. Es un documento que agrupa deudas ya emitidas y conserva el importe que tenía cada una al momento de emitirla (`BillDebt.amountAtIssue`). Por eso crear una boleta valida las deudas y calcula el total, pero no modifica sus saldos.

El pago electrónico reutiliza el mismo motor transaccional de pagos del cajero. La diferencia queda en `Payment.origin = ELECTRONIC` y en `ElectronicPaymentAttempt`, que guarda el resultado y la referencia del proveedor simulado. Reutilizar el caso de uso evita tener dos reglas distintas para reducir una deuda.

En un sobrepago hay que evitar contar el mismo dinero dos veces. La parte aplicada queda en `allocatedAmount`; el excedente se convierte en `CreditBalance`; y `unallocatedAmount` vuelve a cero porque ese excedente ya tiene un destino contable. Las pruebas nuevas fijan esta regla para evitar regresiones.

El saldo a favor sólo puede aplicarse a una deuda del mismo contribuyente, nunca por encima del saldo disponible ni del saldo de deuda. La operación actualiza ambos registros en una única transacción y genera auditoría.

Las seis pruebas de `BillingFlowTests` cubren emisión sin mutar deuda, PDF, rechazo de mezcla de contribuyentes, imputación posterior, sobrepago, aplicación de crédito y pago electrónico con ownership.

## 14. Planes de pago completos

Un plan no apunta simplemente a una deuda: puede agrupar varias mediante `PaymentPlanDebt`. Esa tabla conserva cuánto capital entró al plan, cuánto se pagó y cuánto resta por cada deuda. Mientras la relación está `ACTIVE`, el pago directo y la aplicación directa de saldo a favor se bloquean; el cobro debe apuntar a una `Installment`.

Cada plan guarda el `configurationId` y la versión usados al otorgarlo. Así, cambiar tasas o políticas futuras no reescribe planes existentes. La simulación no persiste nada; la solicitud vuelve a calcular y el otorgamiento comprueba que las deudas no hayan cambiado desde que se solicitó.

El anticipo se representa como cuota `DOWN_PAYMENT`. Las demás son `REGULAR`. Al pagar una cuota, sólo su parte de capital reduce las deudas; el interés reduce el saldo del plan, no el capital tributario. Si hay varias deudas, el capital se distribuye proporcionalmente y la última absorbe centavos de redondeo.

Caducar y refinanciar no borran historia. La caducidad libera las deudas y cancela cuotas impagas; la refinanciación marca el plan anterior `REFINANCED` y crea otro por el capital pendiente.

## 15. Ajustes y corridas masivas

`AdjustmentRequest` aplica separación de funciones: Rentas solicita y Supervisor resuelve. Hasta la aprobación, la deuda permanece idéntica. `originalAmount` nunca cambia; sólo se actualizan `currentAmount` y `outstandingBalance`, y se impide reducir el total por debajo de lo ya pagado.

Una corrida primero persiste ítems `PENDING`. La previsualización evalúa cada uno, marca `VALID` o `ERROR` y calcula el resumen sin crear liquidaciones. Luego sigue `DRAFT → PENDING_APPROVAL → APPROVED → EXECUTED`. La ejecución crea liquidación y deuda sólo para ítems válidos dentro de una transacción.

## 16. Integraciones restantes

M1, M2, M4, M7 y M8 siguen siendo dueños de sus datos. M5 sólo persiste referencias locales. Los handlers reciben un `EventEnvelope`, registran el payload, verifican `eventId` y recién marcan `ProcessedEvent` cuando el procesamiento terminó bien.

Las obligaciones externas agregan una segunda idempotencia por módulo, tipo y referencia externa. Si falta un contribuyente o concepto, la obligación queda `ERROR` con información suficiente para reintentar. El endpoint técnico de reproceso vuelve a ejecutar el handler usando el payload originalmente recibido; no inventa un evento nuevo.

Los tickets locales permiten trabajo de Rentas, pero cada cambio de estado genera `updateTicketStatus` en Outbox para M2. Los beneficios M8 se vinculan a conceptos y su porcentaje vigente se usa al liquidar, sin copiar la regla social a otra tabla de negocio.

## 17. JaCoCo y cómo leer cobertura

`mvnw clean verify` ejecuta pruebas, empaqueta el JAR y genera `target/site/jacoco/index.html`. JaCoCo mide qué instrucciones, ramas y líneas fueron ejecutadas. Una línea cubierta no demuestra por sí sola que la aserción sea buena; por eso las pruebas se concentran en invariantes económicas y transiciones.

La corrida final obtuvo 66,05 % de líneas totales y 86,18 % de líneas en las 26 clases lógicas de servicio/handlers. El total es menor porque `ApiController` concentra 136 métodos de una línea con poca cobertura HTTP directa. No se excluyó dominio ni services para inflar el número. El PDF entregado menciona una cobertura mínima de la consigna, pero no fija un porcentaje numérico verificable; por eso no se configuró un umbral artificial.

## 18. Cómo conocer el estado real

`BACKEND_COMPLETENESS.md` enumera la matriz REST completa. `PARTIAL` no significa que la ruta no funcione: indica una diferencia concreta, principalmente filtros específicos pendientes o el desglose de componentes de liquidación. `BLOCKED_EXTERNAL_CONTRACT` señala contratos que deben acordarse con el Core u otro módulo antes de conectar un broker/JWT real.
