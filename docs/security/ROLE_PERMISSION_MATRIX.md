# Matriz de permisos HTTP vigente

Esta matriz describe las reglas que hoy implementa el backend. Es una extracción de los `@PreAuthorize` de los controladores y de los chequeos de titularidad en servicios/controladores; no representa una aprobación independiente de una política de negocio. Los cambios de política deben actualizar esta matriz y sus pruebas.

## Roles

| Rol | Alcance implementado |
|---|---|
| `RENTAS` | Operación administrativa: crear y mantener conceptos/configuraciones, liquidaciones, ajustes, pagos e imputaciones, planes, exenciones, tickets y obligaciones externas; lectura operativa y reportes. |
| `SUPERVISOR` | Aprobaciones, rechazos y desactivaciones; lectura de supervisión/auditoría/integraciones y operaciones de conciliación autorizadas. En la autenticación DEMO el usuario Supervisor recibe además la autoridad `RENTAS`; por ello accede también a endpoints `hasRole('RENTAS')`. |
| `CASHIER` | Lecturas y cobros habilitados para caja: deudas, boletas, pagos, recibos, conciliaciones y flujos de cuotas. No recibe permisos administrativos generales. |
| `AUDITOR` | Lecturas de auditoría, indicadores, operaciones e historial. No recibe permisos de escritura; algunas lecturas de una entidad personal también aplican titularidad para `TAXPAYER`. |
| `TAXPAYER` | Catálogos públicos del trámite y consultas/solicitudes propias: pagos, deuda, boletas, beneficios, exenciones y planes. Los recursos asociados a una persona requieren titularidad. |
| `TECHNICAL` | Acceso limitado a diagnóstico y reintento de integraciones, junto con endpoints internos de conciliación. No es un rol de operación económica general. |

## Matriz por área y operación

Los roles indicados son las autoridades requeridas por las anotaciones de los endpoints. `RENTAS` significa sólo `ROLE_RENTAS`, salvo que la sesión DEMO de Supervisor incluya esa autoridad como se describe arriba.

| Área / endpoints | Lectura | Escritura / operación | Titularidad adicional |
|---|---|---|---|
| Contribuyentes (`/taxpayers`) | RENTAS, SUPERVISOR, CASHIER, AUDITOR | Vincular UUID canónico: SUPERVISOR | El endpoint de vínculo valida conflictos e idempotencia en dominio. |
| Conceptos (`/tax-concepts`) | RENTAS, SUPERVISOR, AUDITOR, TAXPAYER | Crear/editar: RENTAS | — |
| Configuración tributaria (`/tax-configurations`) | RENTAS, SUPERVISOR, AUDITOR | Crear, editar y enviar: RENTAS; aprobar, rechazar y desactivar: SUPERVISOR | Segregación entre envío y resolución en el servicio. |
| Liquidaciones (`/liquidations`, `/liquidation-runs`) | RENTAS, SUPERVISOR, AUDITOR | Vista previa, crear, enviar/ejecutar: RENTAS; aprobar/rechazar: SUPERVISOR | Segregación de autoría en operaciones que lo requieren. |
| Ajustes (`/adjustments`) | RENTAS, SUPERVISOR, AUDITOR | Crear: RENTAS; aprobar/rechazar: SUPERVISOR | Segregación de autoría en el servicio. |
| Deudas e historial (`/debts`, `/taxpayers/{id}/debts`) | Listados: RENTAS, SUPERVISOR, CASHIER, AUDITOR; detalle/historial: también TAXPAYER | Operaciones fiscales específicas según endpoint | TAXPAYER sólo accede a su `taxpayerId`. |
| Boletas (`/bills`) | Lista: RENTAS, CASHIER, AUDITOR; detalle/documento: también TAXPAYER | Crear: RENTAS | TAXPAYER sólo accede a la boleta propia. |
| Pagos e imputaciones (`/payments`, `/payment-allocations`) | Pagos: RENTAS, SUPERVISOR, CASHIER, AUDITOR; detalles personales también TAXPAYER | Registrar: RENTAS/CASHIER; imputar o administrar saldo: RENTAS | TAXPAYER sólo accede a pagos propios. |
| Planes, solicitudes y refinanciaciones | Lecturas operativas: RENTAS, SUPERVISOR, AUDITOR; algunas solicitudes también TAXPAYER | Solicitudes del contribuyente y cambios administrativos según ruta; concesión/rechazo y excepciones tienen reglas diferenciadas | Solicitudes y planes de TAXPAYER se filtran por titularidad. |
| Exenciones y beneficios (`/exemption-requests`, `/exemptions`, `/social-benefits`) | RENTAS, SUPERVISOR, AUDITOR; vistas propias también TAXPAYER | Crear solicitud/documentación: TAXPAYER; revisión: RENTAS; resolución final: SUPERVISOR | Solicitud/beneficio consultado por TAXPAYER debe ser propio. |
| Tickets (`/tickets`) | RENTAS, AUDITOR | Asignar, responder, pedir información, devolver, completar/rechazar: RENTAS | No hay escritura de AUDITOR ni acceso de TAXPAYER. |
| Obligaciones e indicadores | Obligaciones: RENTAS/AUDITOR; indicadores: RENTAS/SUPERVISOR/AUDITOR | Reintento de obligación: RENTAS | — |
| Auditoría (`/audit`) | SUPERVISOR, AUDITOR | Ninguna | — |
| Integraciones y Outbox | Lectura: SUPERVISOR/AUDITOR/TECHNICAL según recurso | Reprocesar: SUPERVISOR/TECHNICAL; conciliaciones según endpoint | — |
| Dashboards | Supervisor: SUPERVISOR; Rentas: RENTAS | Ninguna | — |

## Controles transversales

- Los endpoints de negocio requieren sesión autenticada y sus anotaciones de rol se evalúan antes de enlazar el cuerpo HTTP. Las únicas rutas de API sin `@PreAuthorize` son las rutas explícitas de salud y autenticación DEMO (`health`, `login`, `bootstrap`, `logout`, `me`); login/bootstrap son públicas y logout/me requieren autenticación.
- La autorización por rol no sustituye autorización por recurso. `CurrentIdentity.requireOwnership` y las comprobaciones equivalentes del servicio impiden al contribuyente consultar o modificar recursos ajenos. También se validan ownership de pagos, boletas, deuda, planes, beneficios y solicitudes.
- Las operaciones de aprobación usan roles separados de las operaciones de preparación donde el flujo lo exige. `AuditService` aplica controles de actor distinto; esas reglas de negocio no se reemplazan con `@PreAuthorize`.
- La sesión DEMO está condicionada al modo dev y no constituye autenticación productiva. La autoridad `TECHNICAL` aparece en endpoints de integración y no debe asignarse a usuarios de negocio.
- `SecurityConfig` aplica 401 a solicitudes no autenticadas y 403 a solicitudes autenticadas sin autoridad. La protección de rutas y ownership se verifica por separado.

## Fuente y mantenimiento

La fuente vigente son las anotaciones de los controladores bajo `backend/src/main/java/ar/gob/municipalidad/rentas`, `SecurityConfig`, `CurrentIdentity` y los servicios que aplican ownership. `AuthorizationMatrixTests` comprueba que cada mapping de `/api/v1/**` tenga una política conocida o una excepción de autenticación explícita, y evalúa las autoridades permitidas y denegadas para cada política.

La matriz no afirma que una integración externa haya autenticado una identidad real ni que los roles DEMO sean el contrato de identidad de M1/Core.
