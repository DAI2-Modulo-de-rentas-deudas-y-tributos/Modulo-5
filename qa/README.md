# QA

Validación end-to-end de M5 Rentas contra frontend, backend y PostgreSQL reales (sin `mockDb`).

- `validacion-e2e-m5.xlsx` — los 52 casos de prueba ejecutados: preparación del ambiente, resultado esperado/obtenido, evidencia y severidad de cada hallazgo.
- `insomnia-m5-rentas-qa.json` — colección de Insomnia con las 80 peticiones reales usadas para reproducir cada caso, organizadas en carpetas por módulo. Importar en Insomnia con `Import → From File`; la variable de entorno `baseUrl` ya apunta a `http://localhost:8080/api/v1`.
- `tickets-qa-m5.html` — resumen de los 7 hallazgos en formato ticket (severidad, esperado/obtenido, pasos para reproducir). Abrir directamente en el navegador.
- `guia-presentacion-qa.html` — guión de qué mostrar y qué decir al presentar esta validación.
- `levantar-demo.sh` — levanta PostgreSQL + backend + frontend local con un solo comando (`./qa/levantar-demo.sh`).
