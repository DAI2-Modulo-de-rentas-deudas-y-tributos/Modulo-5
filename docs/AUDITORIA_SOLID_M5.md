# Auditoría breve SOLID — backend M5

Alcance de esta revisión: estructura relevante para liquidaciones, seguridad e integración. No es una certificación de todo el código.

| Principio | Evidencia concreta | Evaluación / límite |
|---|---|---|
| SRP | `ApiController` adapta HTTP; servicios como `LiquidationService`, `BillingService`, `PlanWorkflowService` coordinan casos de uso; repositorios Spring Data manejan persistencia | Separación útil de responsabilidades, aunque varios servicios/clases comparten archivos Java y algunos coordinadores siguen siendo extensos. No propongo reescritura en esta tarea. |
| OCP | `TaxCalculationStrategy` y estrategias por `CalculationType` | Nuevo cálculo base puede añadirse registrando una estrategia en vez de ampliar el switch central. El resto de reglas de liquidación sigue deliberadamente en `LiquidationService`. |
| LSP | No se detectó jerarquía de clases de dominio que justifique evaluar sustitución como cambio de esta tarea | No se declara cumplimiento formal; las estrategias se verifican contra la misma interfaz y contrato. |
| ISP | Interfaces de repositorio y estrategia enfocadas en las operaciones necesarias | No se introdujeron interfaces de servicio amplias ni abstracciones vacías. La auditoría no cubrió exhaustivamente cada interfaz. |
| DIP | `LiquidationService` recibe `List<TaxCalculationStrategy>` y construye el registro; los servicios usan interfaces/repositorios Spring Data | La política de cálculo depende de una abstracción; Spring resuelve implementaciones. El nuevo registro requiere estrategia para cada valor de enum. |

## Patrones verificados

- **GoF Strategy (nuevo en esta rama):** `TaxCalculationStrategies.java`; fija y porcentual, con rechazo externo conservado. Tests unitarios y test PostgreSQL de preview/creación.
- **Repository (Spring Data):** aisla consultas/persistencia; `DebtRepository.findByIdForUpdate` apoya exclusión mutua en reglas monetarias. No se atribuye GoF Repository a interfaces que sólo representan DTOs.
- **Transactional Outbox / Inbox idempotente (integración):** persistencia local en transacción y deduplicación por `eventId`. No implica entrega confirmada a Core.
- **Adapter (integración):** adaptadores/event publishers aíslan productores y consumidores del modelo local. No implica que el broker real esté conectado.
- **No se implementó State:** estados y flujos existentes no justificaron reemplazar sus transiciones en esta tarea.

ADRs relacionados: [0003](adr/0003-grasp-repository-separacion-responsabilidades.md), [0004](adr/0004-strategy-calculo-tributos.md) y [0006](adr/0006-outbox-transaccional-y-entrega.md).
