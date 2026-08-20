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
