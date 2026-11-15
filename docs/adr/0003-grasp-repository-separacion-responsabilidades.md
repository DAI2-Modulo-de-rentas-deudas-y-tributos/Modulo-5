# ADR 0003: Repositorios y separación de responsabilidades (GRASP)

- **Estado:** Aceptado para la implementación local de M5.
- **Fecha:** 2026-10-26.

## Contexto

M5 debe mantener las reglas del dominio fuera de los controladores HTTP y aislar el acceso a PostgreSQL. Las operaciones económicas requieren límites transaccionales, consultas tipadas y, en casos puntuales, bloqueos explícitos.

## Problema

Si los controladores contienen consultas y mutaciones, o si las reglas de negocio dependen de detalles de persistencia, crecen el acoplamiento y la dificultad de probarlas. No se busca introducir capas ceremoniales ni duplicar las abstracciones que Spring Data ya provee.

## Decisión

Se conserva el monolito modular: los controladores adaptan HTTP y autorización; los servicios coordinan reglas y transacciones; repositorios Spring Data encapsulan consultas y persistencia. Se agregan consultas específicas al repositorio cuando son necesarias para una regla, una agregación o una garantía de concurrencia.

## Implementación observada

- `backend/src/main/java/ar/gob/municipalidad/rentas/ApiController.java`: endpoints HTTP delegan la operación a servicios y aplican autorización por rol.
- `backend/src/main/java/ar/gob/municipalidad/rentas/DomainServices.java` y `PlanWorkflowService.java`: concentran reglas de catálogo, liquidación y planes; operaciones de escritura definen límites `@Transactional`.
- `backend/src/main/java/ar/gob/municipalidad/rentas/Repositories.java`: repositorios tipados extienden Spring Data; `DebtRepository` expone, por ejemplo, `findByIdForUpdate` con bloqueo pesimista para operaciones que necesitan serializar el saldo.
- `backend/src/test/java/ar/gob/municipalidad/rentas/ConcurrencyTests.java` y `PaymentPlanFlowTests.java`: prueban reglas y flujos sobre servicios y API.

## GRASP y SOLID

- **Controller (GRASP):** el controlador recibe y traduce solicitudes; la regla permanece en el servicio.
- **Information Expert:** el servicio que conoce estado y dependencias valida las transiciones.
- **Low Coupling / High Cohesion:** repositorios exponen acceso a datos; servicios mantienen reglas del caso de uso.
- **SRP:** responsabilidades separadas por operación/capacidad, sin forzar que cada método o entidad tenga una clase adicional.
- **DIP:** los servicios dependen de interfaces de repositorio administradas por Spring Data, no de SQL embebido en HTTP.

## Alternativas consideradas

1. Lógica y consultas dentro de `ApiController`: descartada por mezclar transporte, autorización y reglas económicas.
2. Repositorio genérico que resuelva todo mediante reflexión: descartado porque dificulta validar consultas y restricciones.
3. Crear una capa adicional de interfaces/implementaciones para cada repositorio: descartada mientras no aporte una abstracción de persistencia alternativa concreta.

## Consecuencias

**Positivas:** límites transaccionales visibles; pruebas focalizadas; consultas y locks nombrados; menos acoplamiento entre HTTP y persistencia.

**Costos:** varios servicios comparten archivos Java del paquete por la organización actual; las pruebas deben cubrir tanto reglas como integración con PostgreSQL para validar consultas y locks.

**Límites:** la separación observada no demuestra por sí sola aislamiento entre módulos ni autorización correcta. Esos aspectos requieren pruebas de seguridad e integración independientes.

## Evidencia

Los archivos y pruebas listados arriba respaldan la estructura. La validación final de esta rama debe registrar qué suites se ejecutaron; este ADR no afirma una prueba nueva ni una entrega externa.
