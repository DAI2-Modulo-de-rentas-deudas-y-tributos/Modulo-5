# SCRUM-806/807 — Cobertura de dominio y mutaciones

## Evidencia de SCRUM-806

Verificación local sobre la candidata integrada, el 28/10/2026, mediante
`backend/./mvnw.cmd clean verify` con Temurin 21.0.12 y compilación `--release 17`.
Resultado: **559 tests**, 0 fallos, 0 errores, 0 omitidos; Testcontainers usó
PostgreSQL 17.11 y Flyway aplicó hasta V31. JaCoCo aprobó su gate global.

| Perímetro medido | Líneas | Instrucciones | Ramas |
|---|---:|---:|---:|
| Global de producción | 91,44% | 89,45% | 66,59% |
| Dominio de negocio | 98,54% | 94,07% | 72,36% |

El perímetro de dominio se declara explícitamente mediante las 14 familias de
clases incluidas en el perfil `mutation-testing` de `backend/pom.xml`:

- `BillingService`
- `CreditBalanceService`
- `AdjustmentService`
- `ElectronicPaymentService`
- `ExemptionService`
- `LateChargeService`
- `LiquidationRunService`
- `LiquidationService`
- `PaymentPlanPrincipalLedger`
- `PaymentPlanService`
- `PaymentService`
- `PlanWorkflowService`
- `ReversalService`
- `DueDateProcessor`

JaCoCo no excluye las demás clases productivas; el cálculo por dominio filtra
su CSV generado por esos prefijos y cuenta también las clases internas
correspondientes (19 filas en la ejecución reportada). La cobertura global
continúa siendo el gate académico independiente de líneas >=85%.

La suite incluye escenarios de liquidaciones, pagos, recargos, cuotas y planes,
exenciones y transiciones. Las pruebas de SCRUM-806 agregan vencimientos y
recargos, además de artefactos de JaCoCo al artefacto normal del CI.

## Estado de SCRUM-807

El perfil PIT usa JUnit 5 y mantiene el mismo perímetro de 14 familias, con
umbral configurado de 60%; no se redujo el alcance ni se ignoraron mutantes.
La ejecución local completa duró aproximadamente 34 minutos y reportó al
menos cuatro minions terminados por `TIMED_OUT` con el límite de 60 segundos.
PIT no alcanzó a generar el índice ni un score global. Por lo tanto, **no hay
evidencia de que se alcance el 60%**, ni una lista confiable de mutantes
supervivientes. SCRUM-807 queda **BLOQUEADO**, pendiente de diagnosticar el
tiempo de los mutantes y obtener un reporte completo sin reducir el perímetro.

El workflow actual publica JaCoCo, pero todavía no ejecuta PIT ni publica
informes PIT. No se agregó esa ejecución al CI porque la corrida disponible no
terminó ni produjo un resultado validable; incorporarla ahora añadiría un paso
largo sin cumplir el gate solicitado. Retomar ese cambio cuando una ejecución
local completa establezca que la duración es compatible con el CI.

## Reproducción

```powershell
cd backend
./mvnw.cmd clean verify
```

El CSV usado para el cálculo queda en `backend/target/site/jacoco/jacoco.csv`.
Los reportes generados en `target/` no se versionan.
