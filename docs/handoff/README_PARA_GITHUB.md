# Cómo instalar esta entrega dentro del repositorio GitHub

## Contenido

- `M5_RENTAS_TRASPASO_COMPLETO_2026-10-25.md`: documento unificado recibido del PO; los ejemplos CUIT de 11 dígitos fueron redactados en esta copia.
- `ESTADO_VERIFICADO_P0_P1_2026-10-26.md`: separación entre estado documentado, código, pruebas locales, aceptación del destino y entrega de Core.
- `00_LEER_PRIMERO.md`: traspaso ejecutivo, estado y mapa de archivos.
- `01_ESTADO_Y_ARQUITECTURA.md`: qué se implementó en M2, M8 y M7, decisiones y pendientes.
- `02_CONTRATOS_DE_EVENTOS.md`: catálogo de entradas/salidas M5, tipos, estados, comparativas, compatibilidad y ejemplos identificados como ilustrativos.
- `03_BACKLOG_PRIORIZADO.md`: próximo desarrollo M4, acuerdos M1/M7/M8, Core y checklist.
- `04_PLAN_DE_PRUEBAS_E2E.md`: pruebas futuras backend, broker y actualización real de portales.
- `AGENTS.md`: instrucciones persistentes de trabajo para Codex.
- `contratos_originales/`: documentos M2 v1.78 WIP, M4 octubre trabajo, M7 V4 19/10, M8 y alcance funcional M5. Se omitió M1 v2.1 porque contiene identificadores personales y valores con forma de credenciales en sus ejemplos; solicitar al PO una copia saneada antes de versionarlo.
- `propuestas_no_ratificadas/`: propuestas de estandarización UUID/implementación, no prueba de aceptación por equipos externos.

- `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md`: **decisión vigente**: modificar primero eventos propios M5 a UUID estables, luego pasar contratos a consumidores; nunca alterar entradas ajenas ni el envelope de Core.
- `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`: manual operativo de autoría, entrega de contratos salientes y orden de validación/activación.

## Instalación recomendada

1. El traspaso Markdown y los contratos originales seleccionados ya están bajo `docs/handoff/`; `AGENTS.md` queda dentro de esa carpeta como documento de referencia y no reemplaza `CLAUDE.md` ni instrucciones de raíz.
2. Los ejemplos de CUIT que podían identificar a una organización se sustituyeron por placeholders. No se incorporaron bases, respaldos, credenciales ni datos personales.
3. `M1_Catalogo_Eventos_v2.1.docx` se retuvo fuera del repo hasta que el PO proporcione una versión saneada.
4. Publicar **el código real y sus migraciones y pruebas** mediante git desde la computadora que contiene los cambios. Este ZIP **no contiene código fuente ni la base de datos**.
5. Pedir al desarrollador amigo que lea primero el estado verificado y la documentación, y luego audite la rama (no hacer cambios masivos a ciegas).

## Seguridad Git

- `git status --short`; revisar `git diff --stat`, `git diff --check`, archivos no versionados y `git diff --cached` antes del commit.
- No incluir credenciales (`.env`, secrets, JWT, API keys), archivos de respaldo `.dump`, datos personales, bases, dependencias pesadas, `target`, `node_modules`, temporales, logs o caches de Codex.
- Ejecutar suites relevantes; subir cambios por branch/PR revisable.

## Limitación temporal

Los informes aquí reflejan el estado transmitido el **25/10/2026**; no implican acceso al contenido real del repositorio publicado después de esa fecha. Cualquier respuesta nueva de M1/M4/M7/M8/Core debe adjuntarse y registrarse con fecha, versión, responsable y cambios de contrato.
