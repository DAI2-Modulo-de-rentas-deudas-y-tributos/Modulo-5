# Decisión vigente de M5: normalizar SALIDAS propias a UUID primero; entregar contratos a consumidores después

**Decisión aclarada por el responsable de Rentas (25/10/2026).** Esta política prevalece sobre las advertencias anteriores de «esperar ratificación antes de tocar salidas». El código de M5 puede **preparar primero** su versión de salida y sus pruebas; **después** se comunica formalmente a los módulos consumidores para que adapten sus entradas. **No equivale a autorizar el envío real a Core antes de que los demás estén preparados.**

## 1. Tres reglas independientes, no intercambiables

1. **ENTRANTES a M5: NO cambiar el contrato que emite el módulo propietario.** `citizenUpdated` (M1), `ticketUpdated` (M2), `permitFeeGenerated`/`commercialFineGenerated` (M4), `infractionConfirmed` (M7), `socialBenefitUpdated` (M8) mantienen sus nombres, campos, tipos, IDs y semántica **tal como los publique cada dueño en su contrato efectivo**. Dentro de M5 sí se pueden corregir parsers, validadores, adaptadores, proyecciones y pruebas para aceptar esos mensajes, sin transformar lo que el otro módulo realmente manda ni atribuirle un cambio no acordado. Si un productor difiere del envelope Core o hay versiones contradictorias, registrar la incompatibilidad y consultarla con su dueño/Core; **no inventar un envelope o UUID entrante**.
2. **SALIENTES de M5: podemos modificarlos en nuestro código, y luego comunicar el contrato resultante a cada consumidor.** Estandarizar a UUID persistentes los *identificadores técnicos de entidades que pertenecen a M5*: `debtId`, `paymentId`, `requestId`, `exemptionId`, `planId`, `liquidationId` cuando exista en un payload, y `debtIds[]`. Mantener PK `Long` de BD. **Secuencia acordada: desarrollar M5 → validar JSON y regresiones locales → documentar contrato final/versionado → pasar JSON y esquemas a todos los consumidores → coordinar adaptación y prueba conjunta → recién después conectar/activar por Core.** No esperar aceptación externa para **preparar el código local**, pero tampoco afirmar que un consumidor ya soporta la nueva versión.
3. **ENVELOPE Core: INVARIABLE** para cualquier evento que **M5 publique** o reciba en el canal Core. Ocho campos exactos: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `sourceModule`, `correlationId`, `causationId`, `data`. Para salientes `sourceModule="rentas"`, `eventId` UUIDv4 nuevo por hecho (mismo ID en reintento), `occurredAt` ISO 8601 con zona, `correlationId`/`causationId` UUID o null cuando el contrato lo permita y `data` payload. No agregar `producer`, `subject`, `payload`, `targetModule` a nivel raíz. **Cambiar el tipo de un ID dentro de `data` NO implica cambiar el envelope**; si requiere nueva `eventVersion`, definir y documentar con Core antes de activarlo, sin crear versiones o rutas fantasma.

## 2. Tabla práctica — estado conocido vs cambio a implementar en salidas

| Campo | Estado local reportado antes de traspaso | Acción decidida para productores M5 | Salvedad obligatoria |
|---|---|---|---|
| `eventId` | UUIDv4 outbox | Conservar patrón; mismo ID en reintentos | ID de evento ≠ ID entidad |
| `correlationId` / `causationId` | Core de 8 campos; M7 ya conserva correlación del origen | No inventar correlación desde PK `Long` | Conservar identidad causal verificable |
| `ticketId` (`updateTicketStatus`) | UUID de M2 conservado | **No cambiar** | Identidad propiedad M2 |
| `updatedBy.id` (M2) | UUID de empleado M5 V24 | **No cambiar** | UUID actor persistente |
| `requestId`, `exemptionId` | UUID M5 estables (V25) | Conservar como están en eventos salientes | `citizenId` verificado o `DEFERRED` |
| `debtId` (`overdueDebt`, económicos compartidos, solicitudes de planes) | UUID V25 disponible pero pagos M4/M7 todavía emitían `debt-<Long>` | Usar **mismo UUID persistido V25** en todas las salidas M5 en que represente la deuda | Compatibilidad externa queda pendiente de distribuir/validar |
| `paymentId` (`paymentRegistered`, `paymentReversed`) | `payment-<Long>`; no se informó UUID pago persistente en V25 | Diseñar/agregar UUID M5 estable por pago, sin tocar PK `Long` | Migración nueva: probar aislada y pedir autorización antes de `rentas` |
| `planId` (`updatePaymentPlanStatus`) | UUID persistente no confirmado | Verificar si existe; si no, crear UUID propio estable | No presumir que V25 lo incluyó |
| `debtIds[]` (`paymentPlanRequested`) | contrato M5 propuesto | UUID existentes V25 de cada deuda M5 | Respetar misma entidad en todos los eventos |
| `citizenId` | correspondencia M1 no confirmada; valores anteriores `Long` | Sólo UUID **canónico real de M1** cuando se conozca | Si falta, `DEFERRED`; no inventar por DNI/CUIT/Long |
| `originId` para `TRAFFIC_FINE` | `infractionId` original UUID M7 | **No cambiarlo** | Siempre mismo UUID externo recibido |
| `originId` para `PERMIT_FEE`/`COMMERCIAL_FINE` | `data.id` original M4, incluso si es `FEE-001` / `FINE-001` | **No cambiarlo**; no pertenece a M5 | Sólo M4 puede sustituir su identidad de origen y comunicar correspondencia |
| `originId` para `MUNICIPAL_TAX` | `debtSettled` diferido | Propuesta: UUID estable de liquidación propia de M5, verificar el modelo | Sin `citizenId` canónico no emitir un mensaje inválido |
| `conceptCode`, `originType`, `status`, DNI/CUIT, fechas, importes, `reason` | Datos de negocio | **No convertir a UUID** | Mantener campos/semántica pactados salvo ajuste explícito del productor M5 |

**Matiz importante M7 V4:** `paymentId` y `debtId` están tipados como **string** (no exige UUID). Un UUID en forma JSON string sigue siendo string, pero **compatibilidad de tipo no garantiza compatibilidad semántica** si M7 guarda o valida formatos anteriores. El nuevo formato se implementa en M5 primero, luego se entrega el contrato a M7; su aceptación y adaptación siguen pendientes. No confundirlo con prohibición de cambiar los salientes de M5.

## 3. Evento compartido = UNA especificación emitida por M5

- `paymentRegistered` → M4 y M7.
- `debtSettled` → M4, M7 y M8.
- `paymentReversed` → M4 y M7.
- `exemptionRequested` y `updateExemptionStatus` → M1 y M8.
- `paymentPlanRequested` / `updatePaymentPlanStatus` → M1.
- `overdueDebt` → M8; `updateTicketStatus` → M2.

**M5 puede actualizar el esquema saliente compartido sin esperar a que los otros equipos hagan el cambio**, pero debe hacerlo **una sola vez de forma consistente**: mismo `eventType`, mismas claves de `data` y tipos para todos los destinos; conservar `originType`, `originId`, `infractionId` y los datos económicos reales. No crear nombres diferentes por consumidor ni derivar dos identidades de deuda para el mismo hecho.

La distribución a consumidores es un hito posterior **obligatorio**. Si M4/M7/M8/M1 muestran incompatibilidades, se coordinan y corrigen *sus* receptores o se acuerda transición/versionado antes de **activar Core**. No se atribuye aceptación por anticipado.

## 4. Ejemplo ilustrativo: JSON final objetivo M5, NO evento ya emitido

Ejemplo de `paymentRegistered` de una multa de M7. UUID de pago y deuda son **propios y persistentes de M5**; `originId` y `infractionId` son **UUID ajeno idéntico, emitido por M7**. No se altera envelope:

```json
{
  "eventId": "4c712abe-b51f-4e09-9d32-b2ff31ce97b4",
  "eventType": "paymentRegistered",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-25T18:30:00-03:00",
  "sourceModule": "rentas",
  "correlationId": "ca16af37-771a-4912-8e6a-1919c341a113",
  "causationId": null,
  "data": {
    "paymentId": "78168ad3-372b-49bc-a08e-fbf69a2bfd5a",
    "debtId": "02c9affc-dcef-4d28-807b-fb530fd5f5f4",
    "originType": "TRAFFIC_FINE",
    "originId": "2a946899-a23a-40fb-af43-c70bfe66119a",
    "infractionId": "2a946899-a23a-40fb-af43-c70bfe66119a",
    "amountApplied": 50000,
    "paidAt": "2026-10-25T18:30:00-03:00",
    "remainingBalance": 25000
  }
}
```

**No copiar los UUID de este ejemplo a datos reales**. Todas las claves reales de entidad salen de registros persistidos y referencias oficiales. Si el tipo/semántica de `causationId` exige valor en un contrato actualizado, obtener el real; no simularlo.

## 5. Secuencia obligatoria para el nuevo Codex

1. Auditar el repositorio clonado y las últimas salidas actuales; comprobar si V25 ya da UUID en deuda, y si `payment` y `plan` necesitan UUID nuevos.
2. Continuar con **M4**: diagnosticar eventos *entrantes* y conservar su formato tal cual. Corregir consumidores/proyecciones internos de M5 si hace falta, sin modificar el payload enviado por M4.
3. **Preparar cambios salientes M5** a UUID estables: diseñar DTO, persistencia mínima y reglas, con pruebas por `originType` y por consumidor. Es un cambio que **sí puede desarrollarse localmente antes de ratificación externa**. No cambiar estructura/nombres del envelope Core.
4. Mantener `DEFERRED` para falta de UUID M1 o referencia externa legítima, y no reactivar `debtSettled` municipal basado en una identidad inventada. Documentar qué casos están listos y cuáles aguardan vínculos verificados.
5. Entregar **contratos M5 emitidos** a consumidores: JSON completo + JSON Schema/DTO si existe + tabla `campo/tipo/required/nullable` + casos de pago parcial, total, reversión, exención aprobada/rechazada + `eventVersion` + comportamiento de idempotencia y filtrado por `originType`. Consumidores: M1, M2, M4, M7, M8 según evento.
6. Registrar quién confirmó recepción/adaptación del contrato y sus diferencias, y preparar regresiones por consumidor; si hay cambios incompatibles activos, coordinar versión/transición **antes de habilitar Core**.
7. Con M1 confirmado, conciliar `citizenId`; después conectar Core/RabbitMQ y finalmente ejecutar pruebas E2E de portales. No etiquetar `LocalLog` como entrega real.

## 6. Instrucción resumida lista para Codex

> En este proyecto diferenciamos autoría de eventos: **NO modificamos los contratos ENTRANTES de M1/M2/M4/M7/M8**, sólo adaptamos nuestro consumer interno a lo que ellos publican. **SÍ modificamos nuestros eventos SALIENTES** para llevar los identificadores técnicos propiedad M5 a **UUID persistentes**, primero en el código y las pruebas de M5; luego entregamos los contratos actualizados a **todos** los consumidores para que adapten sus receptores. Mantenemos `originId` externo exactamente como llegó y el `citizenId` sólo cuando M1 proporcione su UUID canónico. **SIEMPRE usamos los ocho campos del envelope oficial Core, sin agregar/quitar/renombrar campos.** Las migraciones sobre `rentas`, el despliegue real por Core y el envío a consumidores requieren sus pasos de validación/autorización separados. Un solo payload por evento compartido, con regresión de M4/M7/M8/M1.
