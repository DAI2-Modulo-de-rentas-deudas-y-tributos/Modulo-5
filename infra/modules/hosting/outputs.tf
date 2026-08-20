output "app_id" {
  description = "Identificador de la aplicacion Amplify."
  value       = aws_amplify_app.frontend.id
}

output "app_arn" {
  description = "ARN de la aplicacion Amplify."
  value       = aws_amplify_app.frontend.arn
}

output "branch_name" {
  description = "Rama de despliegue DEV en Amplify."
  value       = aws_amplify_branch.develop.branch_name
}

output "frontend_url" {
  description = "URL HTTPS del frontend DEV."
  value       = "https://${aws_amplify_branch.develop.branch_name}.${aws_amplify_app.frontend.default_domain}"
}
