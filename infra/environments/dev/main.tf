data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  project_name       = var.project_name
  environment        = "dev"
  name_prefix        = "${local.project_name}-${local.environment}"
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 2)
}

module "network" {
  source = "../../modules/network"

  name_prefix           = local.name_prefix
  vpc_cidr              = var.vpc_cidr
  availability_zones    = local.availability_zones
  public_subnet_cidrs   = var.public_subnet_cidrs
  database_subnet_cidrs = var.database_subnet_cidrs
}

module "ecs" {
  source = "../../modules/ecs"

  name_prefix               = local.name_prefix
  ecr_images_to_keep        = var.ecr_images_to_keep
  enable_container_insights = var.enable_container_insights
  log_retention_days        = var.log_retention_days
}
