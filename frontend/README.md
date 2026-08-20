# Frontend

Directorio reservado para la aplicación web React del Módulo 5.

El código fuente será provisto por el equipo de desarrollo. El contrato DevOps
esperado es:

- `package.json` y `package-lock.json` versionados;
- `npm test` ejecuta las pruebas sin modo interactivo;
- `npm run build` genera un build estático en `dist/`;
- `VITE_API_BASE_URL` configura la URL pública del backend;
- ninguna variable `VITE_*` contiene secretos.

Cuando ambos manifests existan, el CI detectará automáticamente el frontend y
activará instalación, pruebas y build. `amplify.yml` publicará `dist/` mediante
AWS Amplify Hosting.
