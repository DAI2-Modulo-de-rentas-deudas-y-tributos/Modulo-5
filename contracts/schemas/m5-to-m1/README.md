# Propuestas M5 → Core → Ciudadanos M1

Estos schemas describen **sólo `data`**, no el envelope Core completo. Se basan en los records y validaciones productivas de `ConfirmedOutboundEvents.java`; los seis ejemplos de [M1_PAYLOADS_LOCALES.md](../../../docs/integrations/M1_PAYLOADS_LOCALES.md) son salidas sintéticas del outbox/Jackson. No son datos de M1 ni evidencia de entrega. Las propuestas conservan `eventVersion: "1.0"`; eso refleja el código actual, no una ratificación de Core/M1.

El envelope común continúa definido por [common-event-envelope.schema.json](../common-event-envelope.schema.json). Estos cuatro schemas independientes no deben usarse para validar el envelope entero.

## Matriz de contratos propuestos

| eventType | Versión local | Schema de `data` | Campos requeridos | Opcionales / variantes | Origen Java | Ejemplos sintéticos | Estado informado por Core | Pendiente de ratificación |
|---|---|---|---|---|---|---|---|---|
| `exemptionRequested` | `1.0` | [exemptionRequested.data.schema.json](exemptionRequested.data.schema.json) | `requestId` UUID, `citizenId` UUID, `conceptCode`, `reason`, `requestedPercentage` >0 y ≤100, `requestedFrom`, `requestedUntil` | Ninguno | `ConfirmedOutboundEvents.ExemptionRequestedPayload` | [solicitud](../../../docs/integrations/M1_PAYLOADS_LOCALES.md#exemptionrequested) | Publicación Rentas activa; Ciudadanos suscripto; maxAttempts=4; sin schema cargado | Asociar schema a la publicación existente; confirmar consumidor y significado de porcentaje/vigencia. No volver a registrar la publicación. |
| `updateExemptionStatus` | `1.0` | [updateExemptionStatus.data.schema.json](updateExemptionStatus.data.schema.json) | Comunes: `requestId`, `citizenId`, `status`, `conceptCode`. `APPROVED`: además `exemptionId`, `percentage`, `validFrom`. `REJECTED`: además `reason` | `APPROVED`: `validUntil` opcional. `REJECTED` prohíbe los campos de aprobación. Jackson omite valores nulos. | `ConfirmedOutboundEvents.UpdateExemptionStatusPayload` | [aprobación](../../../docs/integrations/M1_PAYLOADS_LOCALES.md#updateexemptionstatus-approved), [rechazo](../../../docs/integrations/M1_PAYLOADS_LOCALES.md#updateexemptionstatus-rejected) | Publicación Rentas no registrada; Ciudadanos solicitó suscripción; maxAttempts=4; sin schema cargado | Registrar publicación y cargar schema con Core; confirmar ambos estados y que M1 interpreta rechazo/motivo. |
| `paymentPlanRequested` | `1.0` | [paymentPlanRequested.data.schema.json](paymentPlanRequested.data.schema.json) | `requestId` UUID, `citizenId` UUID, `debtIds` (array no vacío de UUID), `totalDebt` >0, `installments` entero positivo | Ninguno | `ConfirmedOutboundEvents.PaymentPlanRequestedPayload` | [solicitud](../../../docs/integrations/M1_PAYLOADS_LOCALES.md#paymentplanrequested) | Publicación Rentas activa; Ciudadanos suscripto; maxAttempts=4; sin schema cargado | Asociar schema a la publicación existente; confirmar el significado/base de `totalDebt` y cuotas. No volver a registrar la publicación. |
| `updatePaymentPlanStatus` | `1.0` | [updatePaymentPlanStatus.data.schema.json](updatePaymentPlanStatus.data.schema.json) | Comunes: `requestId`, `citizenId`, `status`. `GRANTED`: además `planId`, `installments`, `totalAmount`. `REJECTED`: además `reason` | `GRANTED` y `REJECTED` son excluyentes; rechazo prohíbe datos del plan concedido. Jackson omite valores nulos. | `ConfirmedOutboundEvents.UpdatePaymentPlanStatusPayload` | [otorgado](../../../docs/integrations/M1_PAYLOADS_LOCALES.md#updatepaymentplanstatus-granted), [rechazado](../../../docs/integrations/M1_PAYLOADS_LOCALES.md#updatepaymentplanstatus-rejected) | Publicación Rentas no registrada; Ciudadanos solicitó suscripción; maxAttempts=4; sin schema cargado | Registrar publicación y cargar schema con Core; confirmar semántica de `GRANTED`/`REJECTED`, `totalAmount` y cuotas. |

### Alcance y validaciones

- `UUID` refleja la representación textual producida por Jackson para `java.util.UUID`; no fuerza UUIDv4 para identificadores de entidad. `eventId` del envelope mantiene su contrato separado.
- Los porcentajes reflejan `signum() > 0` y `compareTo(100) <= 0`. Los importes de plan reflejan `signum() > 0`; el DTO no limita escala decimal ni establece una regla de negocio adicional.
- Los arrays de deudas requieren al menos un elemento y cada elemento UUID; el constructor también rechaza elementos nulos. No se impone unicidad de `debtIds`, porque el código no lo exige.
- Las fechas usan el formato ISO `date`. El schema fija la forma `YYYY-MM-DD`; `format: date` es una anotación cuya aserción depende del validador JSON Schema. El constructor Java usa `LocalDate`, por lo que no puede serializar fechas calendario inválidas. También rechaza `requestedUntil < requestedFrom`; JSON Schema estándar no compara dos valores de propiedades, así que la validación de orden sigue siendo responsabilidad del productor.
- Las fechas del schema también restringen el calendario gregoriano (incluidos años bisiestos), porque algunos validadores tratan `format` sólo como anotación. El productor sigue siendo la autoridad para exigir `requestedUntil >= requestedFrom`.
- En ambos DTO de actualización `@JsonInclude(NON_NULL)` hace que el productor omita los campos nulos. Por eso el schema prohíbe `null` explícito para campos opcionales y diferencia campos omitidos de campos presentes. El constructor de `UpdateExemptionStatusPayload` no prohíbe `reason` en `APPROVED`, pero el outbox productivo lo pasa como `null`; el schema refleja la salida real de ese productor, no todas las instancias arbitrarias que el record permitiría construir.
- Se exigen objetos cerrados (`additionalProperties: false`) para detectar errores de nombre. Validar contra estas propuestas no declara aceptación externa.

### Validación local

Con PowerShell 7:

```powershell
pwsh -File contracts/tests/Validate-M5M1RequestSchemas.ps1
```

El script valida los cuatro documentos como JSON, los seis ejemplos existentes y 23 casos negativos de UUID, porcentajes/importes, fechas, lista vacía, estados desconocidos, propiedades adicionales y campos exclusivos de otra variante. También comprueba campos nulos frente a omitidos y la variante aprobada sin `validUntil`. Los ejemplos son salidas locales de Jackson; el test no accede a Core ni a M1.

## Registro y consulta en Core/M9

La documentación local menciona `POST /api/v1/publications` como path comunicado, pero no incluye su OpenAPI ni el contrato de request/response. Por eso el endpoint no está verificado como interfaz operable y no se debe invocar todavía. Tampoco se documentan en este repositorio el mecanismo de autenticación del módulo, el campo exacto para asociar/subir un schema a una publicación ya existente, ni endpoints confirmados para consultar publicaciones y suscripciones después del cambio.

Core debe proporcionar, para la versión vigente:

1. OpenAPI o especificación equivalente del registro de publicaciones, request/response, errores y autenticación/claims requeridos.
2. Procedimiento/endpoint y payload para adjuntar o versionar schemas de `data` en publicaciones existentes sin volver a registrarlas.
3. Procedimiento/endpoint de lectura para comprobar estado de publicación, versión/schema asociados, suscripciones/ruteo y `maxAttempts`.
4. Contrato para crear las dos publicaciones pendientes y solicitar/confirmar suscripciones M1, incluyendo quién autoriza cada operación.

Estado conocido: `exemptionRequested` y `paymentPlanRequested` ya están publicadas y M1 suscripto (`maxAttempts=4`); sólo falta cargar/asociar schema. `updateExemptionStatus` y `updatePaymentPlanStatus` aún no están registradas; M1 solicitó suscripción. No se ejecutó ninguna operación en Core.

## Procedimiento E2E conjunto (requiere autorización y credenciales de prueba)

1. Acordar con Core/M9 el ambiente, las publicaciones existentes, las dos publicaciones pendientes, schema registry, ruteo, `maxAttempts=4`, autenticación de módulo, redelivery y el canal para obtener la confirmación final de consumo. Usar un ciudadano sintético de prueba ya vinculado a un UUID canónico confiable; no derivarlo de DNI/CUIT/Long.
2. Crear una solicitud de exención en M5 y capturar, desde el registro de outbox sanitizado, `eventId`, `requestId`, `citizenId`, `correlationId`, `causationId`, `occurredAt`, `eventType`, versión y hash/payload sin PII innecesaria. Correlacionar con la respuesta de Core, destino/identificador de ruteo, ACK técnico y recibo/estado durable de M1.
3. Resolver esa solicitud y repetir la captura para `updateExemptionStatus`; verificar `causationId` igual al `eventId` original y la misma correlación/hilo. Confirmar en M1 el resultado de procesamiento, no sólo aceptación HTTP o confirmación del publisher.
4. Repetir solicitud y resolución para `paymentPlanRequested` y `updatePaymentPlanStatus`, registrando por separado los cuatro checkpoints: outbox M5, ruteo Core, ACK/transporte y resultado durable consultable en M1.
5. Reintentar una entrega con el mismo `eventId`; comprobar deduplicación en destino. Probar también el orden/duplicación acordados y conservar evidencias con identidades ficticias y secretos redactados.
6. Criterio de cierre: cada evento tiene ruteo confirmado, ACK técnico identificable y confirmación de procesamiento de Ciudadanos correlacionada con `eventId`; `202` o `BROKER_CONFIRMED` solos no alcanzan.

## Entrega a Core/M1 y enlaces permanentes

Estado: **propuesta técnica de M5; pendiente de ratificación de M1/Core y revisión posterior del PO**. Tras el commit publicado, los enlaces permanentes de los cuatro schemas se compartirán fijados al SHA completo de ese commit; no usar enlaces a `main` ni rutas locales.
