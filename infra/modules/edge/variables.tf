variable "name_prefix" {
  description = "Prefijo comun para ALB y CloudFront."
  type        = string
}

variable "vpc_id" {
  description = "VPC del ambiente."
  type        = string
}

variable "public_subnet_ids" {
  description = "Subredes publicas en al menos dos zonas."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Grupo de seguridad asignado al ALB."
  type        = string
}

variable "backend_container_port" {
  description = "Puerto HTTP del backend."
  type        = number
  default     = 8080
}

variable "health_check_path" {
  description = "Ruta de salud comprobada por el target group."
  type        = string
  default     = "/actuator/health"
}

variable "deletion_protection" {
  description = "Proteccion contra eliminacion accidental del ALB."
  type        = bool
  default     = false
}
variable "https_origin" {
  description = "Dominio de origen del ALB (origin-<ambiente>) con certificado ACM ya emitido en su región, y su zona de Route 53."
  type = object({
    domain = string
    zone   = string
  })
  nullable = false
}

variable "access_logs_bucket" {
  description = "Nombre del bucket existente que recibe logs de ALB y CloudFront."
  type        = string
  default     = null
}
