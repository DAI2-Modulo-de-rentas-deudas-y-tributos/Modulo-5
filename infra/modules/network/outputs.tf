output "vpc_id" {
  description = "ID de la VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "Bloque CIDR de la VPC."
  value       = aws_vpc.this.cidr_block
}

output "availability_zones" {
  description = "Zonas de disponibilidad utilizadas."
  value       = var.availability_zones
}

output "public_subnet_ids" {
  description = "IDs de las subredes públicas, en el orden de availability_zones."
  value       = [for zone in var.availability_zones : aws_subnet.public[zone].id]

  # El ALB y las tareas Fargate mapean IPs públicas: deben crearse después del
  # Internet Gateway y destruirse antes, o el detach del IGW falla con
  # DependencyViolation ("has some mapped public address(es)").
  depends_on = [
    aws_route.public_ipv4,
    aws_route_table_association.public
  ]
}

output "database_subnet_ids" {
  description = "IDs de las subredes aisladas, en el orden de availability_zones."
  value       = [for zone in var.availability_zones : aws_subnet.database[zone].id]
}
