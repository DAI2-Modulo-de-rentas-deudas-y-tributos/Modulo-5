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
