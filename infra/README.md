# Infraestructura

Infraestructura AWS del módulo, administrada exclusivamente con Terraform.

## Organización

- `bootstrap/`: estado remoto, OIDC de GitHub y roles iniciales.
- `modules/`: módulos reutilizables por capacidad.
- `environments/dev/`: composición y variables de DEV.
- `environments/test/`: composición y variables de TEST.

Cada ambiente utiliza un estado remoto independiente. No se usarán Terraform
workspaces para representar ambientes.

## Flujo previsto

1. Ejecutar `bootstrap` una vez con credenciales temporales de administrador.
2. Configurar el backend S3 de `dev` y `test`.
3. Ejecutar `terraform fmt`, `terraform validate` y `terraform plan` en los PR.
4. Aplicar DEV automáticamente luego del merge a `develop`.
5. Aplicar TEST mediante promoción aprobada.

No ejecutar `terraform apply` hasta completar el bootstrap, las políticas IAM y
la revisión del plan de costos.
