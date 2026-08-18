# ADR 0001: Monorepo y modelo de despliegue

- Estado: aceptado
- Fecha: 2026-08-18

## Contexto

Frontend, backend, contratos e infraestructura pertenecen al mismo módulo y
requieren cambios coordinados durante la fase inicial.

## Decisión

Mantener todos los componentes del Módulo 5 en un monorepo. Implementar el
backend como monolito modular y desplegar el mismo artefacto como API y worker.

## Consecuencias

- Los cambios transversales pueden revisarse en un único pull request.
- El CI debe usar filtros por carpeta sin omitir el check final obligatorio.
- Cada componente conserva build y despliegue independientes.
