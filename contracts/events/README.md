# Catálogo de eventos

Este directorio contendrá un contrato versionado por evento. Los nombres y
payloads definitivos deben acordarse con el Módulo 9 - Core y con cada productor
o consumidor.

## Rentas publica

- `LiquidacionGenerada`
- `BoletaEmitida`
- `DeudaGenerada`
- `DeudaVencida`
- `PagoRegistrado`
- `PagoRevertido`
- `DeudaCancelada`
- `PlanPagoSolicitado`
- `PlanPagoOtorgado`
- `PlanPagoIncumplido`
- `ExencionSolicitada`
- `ExencionAprobada`
- `ExencionRechazada`
- `SaldoFavorGenerado`

## Rentas consume

- `CiudadanoRegistrado`
- `OrganizacionRegistrada`
- `TasaHabilitacionGenerada`
- `MultaComercialGenerada`
- `InfraccionConfirmada`
- `InfraccionAnulada`
- `BeneficioSocialAprobado`
- `HabilitacionSuspendida`

Cada contrato deberá incluir ejemplos válidos, versión, productor, consumidores,
reglas de idempotencia y compatibilidad.
