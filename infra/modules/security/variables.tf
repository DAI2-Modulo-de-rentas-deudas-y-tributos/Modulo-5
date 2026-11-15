variable "name_prefix" {
  description = "Prefijo comun para los grupos de seguridad."
  type        = string
}

variable "vpc_id" {
  description = "VPC donde se crean los grupos de seguridad."
  type        = string
}

variable "backend_container_port" {
  description = "Puerto HTTP expuesto por el backend."
  type        = number
  default     = 8080
}

variable "database_port" {
  description = "Puerto de PostgreSQL."
  type        = number
  default     = 5432
}
variable "alb_ingress_port" {
  description = "Puerto del listener del ALB al que se conecta CloudFront."
  type        = number
  default     = 80
}
