# Resumen local de Graphify

**Fecha:** 27/10/2026
**Herramienta:** `graphifyy 0.9.96`
**Modo:** sólo código, backend `none`; sin Graphify Cloud ni extracción semántica.

## Resultado

- 407 archivos de código procesados.
- 4.262 nodos, 17.165 relaciones y 165 comunidades.
- Coste registrado: 0 tokens de entrada y 0 de salida.
- `ApiController` fue el nodo con más relaciones (193). Esto es una pista de navegación, no una evaluación de calidad.
- No se detectaron ciclos de importación en el grafo procesado.

## Límite del análisis

Faltaban parsers opcionales para SQL y Terraform/HCL: quedaron fuera 40 archivos SQL y 42 archivos HCL. Documentos, capturas y PDF también se excluyeron deliberadamente al usar el modo sólo código. El resultado no reemplaza revisión de código, tests ni análisis de seguridad.

La salida completa (`graph.json`, `GRAPH_REPORT.md` y análisis intermedio) se conservó sólo en la carpeta temporal local y no se incluye en el repositorio. Este resumen conserva los resultados relevantes sin copiar el grafo completo.
