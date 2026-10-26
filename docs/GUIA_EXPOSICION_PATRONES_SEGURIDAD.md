# Guía de demostración — patrones y seguridad M5

## Preparación segura

1. Usar una base PostgreSQL 17 temporal y aislada (Testcontainers o contenedor sin volumen), nunca la base compartida `rentas`.
2. Levantar el backend con perfil `dev`, `RENTAS_SECURITY_DEV_MODE=true` y un secreto local temporal de bootstrap. Los servicios DEMO están excluidos de `prod`; no reutilizar esas credenciales fuera de local.
3. Levantar Vite con `VITE_API_BASE_URL` apuntando a ese backend y `VITE_AUTH_MODE=mock` (en este repositorio, este modo llama al login de sesión DEMO real, no al `fetch` simulado de Vitest).
4. Crear cuentas de demo separadas para `RENTAS`, `SUPERVISOR`, `CASHIER` y `TAXPAYER`; asociar el usuario contribuyente a un contribuyente sintético creado por el inicializador DEV o por una fuente M1 autorizada. No usar DNI/CUIT real.

## Recorrido sugerido: Strategy + flujo económico

1. Como RENTAS, crear/seleccionar concepto y configuración de tipo fijo o porcentual; presentar para aprobación.
2. Como SUPERVISOR, aprobar la versión. El monto externo no se liquida en M5.
3. Como RENTAS, generar preview y liquidación para un período de prueba. Comparar base, topes y descuento con la regla configurada.
4. Como TAXPAYER, abrir Pago electrónico, revisar preview y realizar pago parcial. El frontend envía `Idempotency-Key`; la API registra Payment/Allocation.
5. Actualizar la consulta de deudas y Mis pagos: el saldo debe disminuir y el pago debe aparecer. Repetir una solicitud con la misma clave no debe duplicar el pago; comenzar otro intento de pago genera otra clave.
6. Reiniciar Spring Boot manteniendo la misma base local, volver a iniciar sesión y verificar que saldo e historial persisten.
7. Como AUDITOR, probar lectura; intentar acciones de escritura con ese rol y confirmar denegación. Como TAXPAYER, intentar cambiar IDs a los de otro contribuyente y confirmar 403.

## Qué explicar

- **GRASP Controller / Information Expert:** el controlador traduce HTTP y delega; el servicio que coordina estado/reglas decide la operación.
- **Strategy:** cada tipo de cálculo implementa `TaxCalculationStrategy`. Porcentaje conserva `BigDecimal`, escala 2 y `HALF_UP`; mínimos/máximos y reducciones siguen en el flujo central.
- **Repository:** repositorios tipados encapsulan persistencia; los bloqueos se usan donde el saldo compartido lo requiere.
- **Transactional Outbox e Inbox:** cambios económicos y evento local quedan consistentes/idempotentes; la persistencia local no es confirmación de Core.
- **Seguridad por capas:** React orienta la navegación; Spring Security y ownership del servidor son la barrera real. OpenAPI/Swagger requiere autenticación en `prod`.

## Evidencia y límites

La revisión automatizada ejecutó 434 tests de backend (0 fallos, errores u omitidos), PostgreSQL 17.11/Testcontainers, Flyway V28 y JaCoCo con gate aprobado (líneas 91,61%, instrucciones 90,32%, ramas 65,51%). Frontend: 319 tests en 35 archivos, `npm run build` exitoso y `npm audit` sin vulnerabilidades. Playwright recorrió visualmente el portal real: pagó $40 de una deuda de $100, reintentó con la misma clave y observó el mismo pago; luego verificó el saldo de $60 en sesiones de TAXPAYER, RENTAS y CASHIER. Hubo dos 404 del favicon, sin errores JavaScript. Este E2E usa sistemas locales/efímeros y no valida interoperabilidad con Core u otros módulos.

No presentar `X-Demo-Session` como JWT/Core; no afirmar que `PUBLISHED` signifique ACK; no afirmar integración E2E con M1/M2/M4/M7/M8/Core. Para cerrar la exposición, mostrar la matriz de flujos y la tabla de niveles de [integración](M5_NIVELES_INTEGRACION.md).
