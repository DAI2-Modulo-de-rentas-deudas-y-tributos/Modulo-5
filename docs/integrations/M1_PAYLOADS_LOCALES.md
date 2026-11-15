# Payloads M5 → M1 generados localmente

Estos seis envelopes salieron del outbox de las pruebas H2 `ExemptionHistoryTests` y `PaymentPlanFlowTests` el 29/10/2026. Los UUID de ciudadano son datos de fixture, no identidades obtenidas de un despliegue de M1. Ningún evento se envió a Core. Los valores de `eventId` y `occurredAt` pertenecen únicamente a esa ejecución local.

## exemptionRequested

```json
{
  "eventId": "17e60227-4e2e-4afd-9789-51baae0ecd5a",
  "eventType": "exemptionRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-29T16:43:26.3166677-03:00",
  "sourceModule": "rentas",
  "correlationId": "5fb0a91e-a7a2-4db9-a28c-430ec505a07d",
  "causationId": null,
  "data": {
    "requestId": "5fb0a91e-a7a2-4db9-a28c-430ec505a07d",
    "citizenId": "0f5e8497-97c3-4e37-a85d-2e767961d949",
    "conceptCode": "EXEMPTION-HISTORY",
    "reason": "Motivo de la solicitud",
    "requestedPercentage": 50,
    "requestedFrom": "2026-10-01",
    "requestedUntil": "2026-12-31"
  }
}
```

## updateExemptionStatus APPROVED

```json
{
  "eventId": "8bf7bd96-4372-48b5-ba40-e277a3fd749f",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-29T16:43:26.367668-03:00",
  "sourceModule": "rentas",
  "correlationId": "5fb0a91e-a7a2-4db9-a28c-430ec505a07d",
  "causationId": "17e60227-4e2e-4afd-9789-51baae0ecd5a",
  "data": {
    "requestId": "5fb0a91e-a7a2-4db9-a28c-430ec505a07d",
    "citizenId": "0f5e8497-97c3-4e37-a85d-2e767961d949",
    "status": "APPROVED",
    "exemptionId": "7330b69b-1557-4853-bdc3-d0b63e8f6f9e",
    "conceptCode": "EXEMPTION-HISTORY",
    "percentage": 50,
    "validFrom": "2026-10-01",
    "validUntil": "2026-12-31"
  }
}
```

## updateExemptionStatus REJECTED

```json
{
  "eventId": "f215a436-7c18-4124-87c2-77bf0190cea0",
  "eventType": "updateExemptionStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-29T16:43:25.0483495-03:00",
  "sourceModule": "rentas",
  "correlationId": "1067972e-4855-4446-8cf2-dec63f884378",
  "causationId": "547a3c48-fba0-47f4-a8ea-0ed45d5fe46e",
  "data": {
    "requestId": "1067972e-4855-4446-8cf2-dec63f884378",
    "citizenId": "26cb14fd-e8e1-494b-b238-4b65ca21d0c4",
    "status": "REJECTED",
    "conceptCode": "EXEMPTION-HISTORY",
    "reason": "No cumple los requisitos"
  }
}
```

## paymentPlanRequested

```json
{
  "eventId": "0dc91ac7-7e30-4044-b84d-ef1b6e6e2614",
  "eventType": "paymentPlanRequested",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-29T16:43:34.4070702-03:00",
  "sourceModule": "rentas",
  "correlationId": "fce94e2c-f1c2-4e7f-963a-2c299a44b532",
  "causationId": null,
  "data": {
    "requestId": "fce94e2c-f1c2-4e7f-963a-2c299a44b532",
    "citizenId": "d5d649f6-dbb4-46a8-8b41-ba68a29d60bd",
    "debtIds": [
      "0de6ecb8-928b-46e6-9c31-c67b54f9aa01",
      "801627f1-510c-414d-9c19-ec0698f7d010"
    ],
    "totalDebt": 150.0,
    "installments": 2
  }
}
```

## updatePaymentPlanStatus GRANTED

```json
{
  "eventId": "9ee2a31a-1422-455e-b9a0-6feef3ddea14",
  "eventType": "updatePaymentPlanStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-29T16:43:34.4749693-03:00",
  "sourceModule": "rentas",
  "correlationId": "fce94e2c-f1c2-4e7f-963a-2c299a44b532",
  "causationId": "0dc91ac7-7e30-4044-b84d-ef1b6e6e2614",
  "data": {
    "requestId": "fce94e2c-f1c2-4e7f-963a-2c299a44b532",
    "citizenId": "d5d649f6-dbb4-46a8-8b41-ba68a29d60bd",
    "status": "GRANTED",
    "planId": "5e1d791a-c146-4cfa-a9c6-0e7cad559015",
    "installments": 2,
    "totalAmount": 166.2
  }
}
```

## updatePaymentPlanStatus REJECTED

```json
{
  "eventId": "b77ab00e-a1a6-4947-96ed-455a3a0cd88f",
  "eventType": "updatePaymentPlanStatus",
  "eventVersion": "1.0",
  "occurredAt": "2026-10-29T16:43:34.7376421-03:00",
  "sourceModule": "rentas",
  "correlationId": "da94e6b4-d764-4533-9e3a-86d5972d2bd4",
  "causationId": "68d0562b-2204-4aed-a459-d826addc751b",
  "data": {
    "requestId": "da94e6b4-d764-4533-9e3a-86d5972d2bd4",
    "citizenId": "263c912c-c6a2-4443-86ce-9a19f6bd9872",
    "status": "REJECTED",
    "reason": "Deuda no elegible"
  }
}
```

En cada solicitud, `correlationId` coincide con su `requestId` persistente y `causationId` es nulo. La resolución conserva ese hilo y usa como `causationId` el `eventId` de la solicitud correspondiente. Los ejemplos de rechazo pertenecen a otra solicitud del fixture; su `eventId` causante existe en el outbox de esa prueba.
