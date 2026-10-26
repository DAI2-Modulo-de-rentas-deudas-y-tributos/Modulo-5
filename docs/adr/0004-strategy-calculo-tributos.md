# ADR 0004: Estrategias para el cálculo de tributos

- Estado: aceptado
- Fecha: 2026-10-26

## Contexto

`LiquidationService` calculaba los importes base con un `switch` por tipo de
configuración. Las reglas `FIXED`, `PERCENTAGE` y `EXTERNAL` estaban mezcladas
en el mismo método que aplica topes y normaliza importes.

## Decisión

Delegar el cálculo base a una estrategia por cada `CalculationType`. `FIXED`
retorna el importe configurado; `PERCENTAGE` conserva escala 2 y redondeo
`HALF_UP`; `EXTERNAL` mantiene el rechazo de negocio existente. La aplicación
de mínimos/máximos, normalización monetaria, exenciones y beneficios permanece
en el flujo actual de `LiquidationService`.

La estrategia se inyecta mediante la interfaz `TaxCalculationStrategy`; el
servicio arma un registro por tipo. Esto aplica OCP al cálculo base y DIP entre
el caso de uso y sus reglas. `LiquidationService` conserva coordinación,
validación, reducciones, persistencia e historial; no se delega la liquidación
completa ni se agrega un patrón State.

## Alternativas

- Mantener el `switch` centralizado: menos tipos, pero agrega ramas al servicio
  cada vez que se incorpora una regla de cálculo.
- Delegar todo el proceso de liquidación: se descarta porque ampliaría el
  alcance y mezclaría cálculo base con topes y reducciones.

## Implementación y evidencia

- `backend/src/main/java/ar/gob/municipalidad/rentas/TaxCalculationStrategies.java`:
  interfaz e implementaciones FIXED, PERCENTAGE y EXTERNAL.
- `backend/src/main/java/ar/gob/municipalidad/rentas/DomainServices.java`:
  `LiquidationService` delega el cálculo base; los topes y la normalización
  permanecen centralizados.
- `backend/src/test/java/ar/gob/municipalidad/rentas/TaxCalculationStrategiesTests.java`:
  reglas unitarias, redondeo y contrato de rechazo EXTERNAL.
- `backend/src/test/java/ar/gob/municipalidad/rentas/PercentageBenefitRegressionTests.java`:
  redondeo, beneficio, preview y creación persistida en H2.
- `backend/src/test/java/ar/gob/municipalidad/rentas/PostgreSqlIntegrationTest.java`:
  comparación de preview, liquidación persistida y deuda en PostgreSQL 17.

## Consecuencias

- Cada tipo de cálculo queda aislado y verificable sin cambiar el contrato API.
- Agregar un nuevo tipo requiere registrar una estrategia y probarla.
- Los resultados, la precedencia de topes y las reducciones existentes no
  cambian.
- Costo: una implementación y un registro más por tipo de cálculo; el servicio
  falla ante un tipo sin estrategia registrada, lo que debe cubrirse al añadir
  nuevos valores al enum.
