# Backend

Directorio reservado para la aplicación Spring Boot del Módulo 5.

El código fuente será provisto por el equipo de desarrollo. El contrato DevOps
esperado es:

- Java 17 y Maven con un `pom.xml` versionado;
- `mvn clean verify` ejecuta las pruebas y genera
  `target/rentas-backend.jar`;
- `GET /api/v1/health` devuelve un estado funcional;
- `GET /actuator/health` sirve como health check de infraestructura;
- la configuración PostgreSQL se recibe mediante variables de entorno;
- las migraciones de esquema se administran con Flyway.

Cuando `pom.xml` exista, el CI activará automáticamente pruebas, integración
con PostgreSQL y construcción de la imagen. El `Dockerfile` multi-stage incluido
materializa este contrato y será ajustado junto con el equipo si cambia el
nombre final del JAR.
