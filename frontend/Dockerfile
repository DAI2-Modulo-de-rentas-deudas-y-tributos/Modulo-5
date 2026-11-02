# Imagen para el stack local de compose.yaml: build de producción servido con `vite preview`,
# que reenvía /api al backend (API_PROXY_TARGET). Amplify publica el mismo `dist/` en AWS.
FROM node:22-alpine
WORKDIR /app
# Usuario sin privilegios dueño de /app: Vite escribe node_modules/.vite-temp al cargar la config.
RUN chown node:node /app
USER node
COPY --chown=node:node package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY --chown=node:node . .
RUN npm run build
EXPOSE 4173
CMD ["npm", "run", "preview", "--", "--host", "0.0.0.0", "--port", "4173", "--strictPort"]
