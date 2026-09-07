output "alb_arn" {
  description = "ARN del Application Load Balancer."
  value       = aws_lb.api.arn
}

output "alb_dns_name" {
  description = "DNS de origen del Application Load Balancer."
  value       = aws_lb.api.dns_name
}

output "target_group_arn" {
  description = "Target group utilizado por el servicio ECS."
  value       = aws_lb_target_group.backend.arn
}

output "listener_arn" {
  description = "Listener HTTP del ALB."
  value       = aws_lb_listener.http.arn
}

output "cloudfront_distribution_id" {
  description = "Identificador de la distribucion CloudFront de la API."
  value       = aws_cloudfront_distribution.api.id
}

output "api_domain_name" {
  description = "Dominio HTTPS generado por CloudFront."
  value       = aws_cloudfront_distribution.api.domain_name
}

output "api_base_url" {
  description = "URL publica HTTPS de la API DEV."
  value       = "https://${aws_cloudfront_distribution.api.domain_name}"
}
