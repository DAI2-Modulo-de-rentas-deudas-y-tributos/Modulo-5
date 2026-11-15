# SCRUM-806/807 — Cobertura de dominio y mutaciones

## Evidencia de SCRUM-806

Verificación local sobre la candidata integrada, el 28/10/2026, mediante
`./mvnw.cmd clean verify` con Temurin 21.0.12 y compilación `--release 17`.
Resultado actual: **565 tests**, 0 fallos, 0 errores, 0 omitidos; Testcontainers
usó PostgreSQL 17.11, Flyway validó y aplicó 29 migraciones en bases efímeras, y
JaCoCo aprobó su gate global de líneas >=85%.

| Perímetro medido | Líneas | Instrucciones | Ramas |
|---|---:|---:|---:|
| Global de producción | 91,80% | 89,46% | 66,70% |
| Dominio de negocio | 98,54% | 94,09% | 72,71% |

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
exenciones y transiciones. Las pruebas adicionales de esta revisión verifican
redondeo HALF_UP y no mutación de deuda en la vista previa de recargos, el límite
del vencimiento, conservación del principal distribuido, rechazo de shares
duplicadas/no positivas y rechazo del registro repetido del ledger.

## Estado de SCRUM-807

El perfil PIT usa JUnit 5 y mantiene el mismo perímetro de 14 familias, con
umbral configurado de 60%; no se redujo el alcance ni se ignoraron mutantes.
El perfil conserva los mutadores predeterminados de PIT (14 familias),
`timeoutFactor` predeterminado 1,25 y `timeoutConstant` de 60 s.

Se agregaron pruebas de comportamiento para estos cambios que deberían detectar
mutaciones equivalentes, sin afirmar que fueron mutantes supervivientes
observados en un reporte PIT completo:

- `LateChargeServiceTests.previewRoundsHalfCentSurchargeAndInterestUpWithoutMutatingDebt`:
  detecta cambiar HALF_UP por redondeo hacia abajo, suprimir recargo/interés o
  mutar la deuda durante preview.
- `LateChargeServiceTests.rejectsDebtOnItsDueDateBecauseItIsNotOverdueYet`:
  detecta invertir/relajar el límite estricto de vencimiento.
- `PaymentPlanPrincipalLedgerTests`:
  detecta omitir la conservación del monto, aceptar shares repetidas o no
  positivas, y permitir grabar dos veces la distribución de una asignación.

PIT encontró 473 clases de test. El pre-scan informó 18 unidades de test y,
después del cálculo de cobertura, creó 14 unidades de mutación; esa etapa tardó
156–168 s. Dos corridas controladas llegaron a mutación y se detuvieron antes
del score con `Minion exited abnormally due to MEMORY_ERROR`: la primera tuvo
cuatro minions efectivos (el `<threads>4</threads>` literal del POM prevaleció
sobre `-Dthreads=2`) y heap de 1 GiB; la segunda usó un minion y heap de 2 GiB.
PIT 1.18.2 asocia ese código con su `MemoryWatchdog`/`OUT_OF_MEMORY`; no hay
evidencia de un kill del sistema operativo. Reducir concurrencia y elevar el
heap no bastó. El reporte anterior registraba al menos cuatro `TIMED_OUT` en
una corrida de ~34 minutos; como no se conservó su log detallado, no se puede
probar que esos timeouts y el `MEMORY_ERROR` reproducido tengan la misma causa.
No se generaron reportes globales HTML/XML/CSV, denominador de mutantes ni lista
confiable de supervivientes. Sólo queda una página HTML parcial de
`PlanWorkflowService` y logs de diagnóstico. Por ello **no puede afirmarse que
se alcanza el 60%**; SCRUM-807 sigue **NO VERIFICABLE/BLOQUEADO**.

El workflow actual publica JaCoCo, pero todavía no ejecuta PIT ni publica
informes PIT. No se agregó esa ejecución al CI porque las corridas locales no
terminaron ni produjeron un resultado validable. Retomar ese cambio cuando una
ejecución local completa establezca score >=60% y una duración compatible con CI.

## Reproducción

```powershell
cd backend
./mvnw.cmd clean verify
```

El CSV usado para el cálculo queda en `backend/target/site/jacoco/jacoco.csv`.
Los reportes generados en `target/` no se versionan.
