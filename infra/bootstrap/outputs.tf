output "terraform_state_bucket" {
  description = "Bucket S3 que almacena los estados remotos."
  value       = aws_s3_bucket.terraform_state.id
}

output "github_oidc_provider_arn" {
  description = "ARN del proveedor OIDC de GitHub Actions."
  value       = aws_iam_openid_connect_provider.github.arn
}

output "github_dev_role_arn" {
  description = "Rol que utilizara el GitHub Environment dev."
  value       = aws_iam_role.github_dev.arn
}

output "github_test_role_arn" {
  description = "Rol que utilizara el GitHub Environment test."
  value       = aws_iam_role.github_test.arn
}

output "github_dev_runtime_compute_policy_arn" {
  description = "Politica administrada para despliegues ECR y ECS DEV."
  value       = aws_iam_policy.github_dev_runtime_compute.arn
}

output "github_dev_runtime_infrastructure_policy_arn" {
  description = "Politica administrada para la infraestructura de ejecucion DEV."
  value       = aws_iam_policy.github_dev_runtime_infrastructure.arn
}

output "dev_state_key" {
  description = "Clave S3 del estado DEV."
  value       = local.dev_state_key
}

output "test_state_key" {
  description = "Clave S3 del estado TEST."
  value       = local.test_state_key
}

output "github_test_runtime_compute_policy_arn" {
  description = "Politica administrada para despliegues ECR y ECS TEST."
  value       = aws_iam_policy.github_test_runtime_compute.arn
}

output "github_test_runtime_infrastructure_policy_arn" {
  description = "Politica administrada para la infraestructura de ejecucion TEST."
  value       = aws_iam_policy.github_test_runtime_infrastructure.arn
}

output "github_prod_role_arn" {
  description = "Rol que utilizara el GitHub Environment prod."
  value       = aws_iam_role.github_prod.arn
}

output "prod_state_key" {
  description = "Clave S3 del estado PROD."
  value       = local.prod_state_key
}

output "route53_zone_id" {
  description = "Zona Route 53 del dominio propio."
  value       = aws_route53_zone.main.zone_id
}

output "route53_name_servers" {
  description = "Nameservers que hay que cargar como delegación del dominio en NIC.ar."
  value       = aws_route53_zone.main.name_servers
}

output "access_log_buckets" {
  description = "Bucket de access logs del bootstrap y de cada ambiente."
  value       = { for key, bucket in aws_s3_bucket.access_logs : key => bucket.bucket }
}
