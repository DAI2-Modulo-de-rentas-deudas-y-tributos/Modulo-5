# Publicaciones M5 para Ciudadanos

M1 informó que implementó los cuatro consumidores y probó localmente el UUID canónico del ciudadano. La migración y el despliegue de M1 siguen pendientes. Esta lista prepara la declaración desde M5; **no acredita un registro ni una entrega en Core**.

| `eventType` definitivo | Consumidor informado | Estado informado por M1 en Core | Productor local M5 |
|---|---|---|---|
| `exemptionRequested` | M1 y M8 | Suscripción M1 activa | Sí |
| `updateExemptionStatus` | M1 y M8 | Publicación M5 no encontrada | Sí, APPROVED/REJECTED |
| `paymentPlanRequested` | M1 | Suscripción M1 activa | Sí |
| `updatePaymentPlanStatus` | M1 | Publicación M5 no encontrada | Sí, GRANTED/REJECTED |

## Para declarar en el entorno conjunto

1. Solicitar a Core/M9 el esquema vigente y la autorización de `POST /api/v1/publications` para el módulo **rentas**. No hay un cliente ni una configuración de registro de publicaciones en este repositorio.
2. Declarar desde la identidad propia de M5 los cuatro `eventType` exactos, con `eventVersion=1.0`; verificar en particular que Core registre `updateExemptionStatus` y `updatePaymentPlanStatus`. No usar credenciales de M1 ni modificar sus suscripciones.
3. Leer el catálogo de Core después de la operación y conservar su respuesta como evidencia. Confirmar el ruteo a M1 y, para los dos eventos de exención, también a M8.
4. Usar un ciudadano real con UUID canónico emitido por M1 y vinculación verificada con la referencia local. Hasta que M1 entregue el nombre del claim y el formato final del login/JWT, M5 no interpreta campos nuevos ni infiere equivalencias desde el ID numérico.
5. Probar solicitud y resolución en casos separados, comparar `requestId`, `citizenId`, `correlationId`, `causationId`, `eventId` y el JSON recibido por M1. Una respuesta de Core o `BROKER_CONFIRMED` no prueba consumo de M1.

Las publicaciones sin `canonical_citizen_id` quedan aplazadas en `integration_event_log` y se preparan mediante la conciliación controlada una vez acreditada la vinculación. El outbox conserva el evento preparado y su `eventId` para reintentos.
