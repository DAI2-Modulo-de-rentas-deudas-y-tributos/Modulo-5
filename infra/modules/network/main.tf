locals {
  public_subnets   = zipmap(var.availability_zones, var.public_subnet_cidrs)
  database_subnets = zipmap(var.availability_zones, var.database_subnet_cidrs)
  app_subnets      = zipmap(var.availability_zones, var.app_subnet_cidrs)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name_prefix}-vpc"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-igw"
  }
}

resource "aws_subnet" "public" {
  for_each = local.public_subnets

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.name_prefix}-public-${each.key}"
    Tier = "public"
  }
}

resource "aws_subnet" "app" {
  for_each = local.app_subnets

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-app-${each.key}"
    Tier = "app"
  }
}

resource "aws_subnet" "database" {
  for_each = local.database_subnets

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-database-${each.key}"
    Tier = "database"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-public-rt"
    Tier = "public"
  }
}

resource "aws_route" "public_ipv4" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  # Claves estáticas: `terraform import` evalúa la config con el estado vacío y
  # no puede iterar sobre aws_subnet.public (falla "Invalid for_each argument").
  for_each = local.public_subnets

  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "database" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-database-rt"
    Tier = "database"
  }
}

resource "aws_route_table_association" "database" {
  # Claves estáticas: `terraform import` evalúa la config con el estado vacío y
  # no puede iterar sobre aws_subnet.database (falla "Invalid for_each argument").
  for_each = local.database_subnets

  subnet_id      = aws_subnet.database[each.key].id
  route_table_id = aws_route_table.database.id
}

# Salida de las tareas Fargate con una IP pública fija, la que Core autoriza.
# ponytail: un solo NAT en la primera zona; si esa zona cae, las tareas pierden
# la salida a Internet. Un NAT por zona duplica el costo y la cantidad de IPs que
# Core tendría que autorizar.
resource "aws_eip" "nat" {
  domain = "vpc"

  tags = {
    Name    = "${var.name_prefix}-nat-eip"
    Purpose = "egress-ip-allowlisted-by-core"
  }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[var.availability_zones[0]].id

  tags = {
    Name = "${var.name_prefix}-nat"
  }

  depends_on = [aws_route.public_ipv4]
}

resource "aws_route_table" "app" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-app-rt"
    Tier = "app"
  }
}

resource "aws_route" "app_ipv4" {
  route_table_id         = aws_route_table.app.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this.id
}

resource "aws_route_table_association" "app" {
  # Claves estáticas por el mismo motivo que las demás asociaciones.
  for_each = local.app_subnets

  subnet_id      = aws_subnet.app[each.key].id
  route_table_id = aws_route_table.app.id
}
