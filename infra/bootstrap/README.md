# Bootstrap de Terraform

Este directorio contendrá la configuración aplicada una única vez para crear:

- bucket S3 versionado y cifrado para estados remotos;
- locking nativo de S3 mediante `use_lockfile = true`;
- proveedor OIDC de GitHub Actions;
- roles IAM separados para plan y despliegue;
- políticas mínimas para DEV y TEST.

No almacenar credenciales AWS permanentes en GitHub.
