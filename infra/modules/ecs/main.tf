resource "aws_ecr_repository" "backend" {
  name                 = "${var.name_prefix}-backend"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  encryption_configuration {
    encryption_type = "AES256"
  }

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name    = "${var.name_prefix}-backend"
    Purpose = "backend-images"
  }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Conservar las ultimas ${var.ecr_images_to_keep} imagenes"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_images_to_keep
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }

  tags = {
    Name = "${var.name_prefix}-cluster"
  }
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${var.name_prefix}/backend"
  retention_in_days = var.log_retention_days

  tags = {
    Name    = "${var.name_prefix}-backend-logs"
    Purpose = "backend-containers"
  }
}

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backend_execution" {
  name               = "${var.name_prefix}-backend-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json

  tags = {
    Name    = "${var.name_prefix}-backend-execution"
    Purpose = "ecs-task-execution"
  }
}

resource "aws_iam_role_policy_attachment" "backend_execution" {
  role       = aws_iam_role.backend_execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_partition" "current" {}

data "aws_iam_policy_document" "backend_secret" {
  statement {
    sid       = "ReadDatabaseCredentials"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.database_secret_arn]
  }
}

resource "aws_iam_role_policy" "backend_secret" {
  name   = "read-database-credentials"
  role   = aws_iam_role.backend_execution.id
  policy = data.aws_iam_policy_document.backend_secret.json
}

resource "aws_iam_role" "backend_task" {
  name               = "${var.name_prefix}-backend-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json

  tags = {
    Name    = "${var.name_prefix}-backend-task"
    Purpose = "backend-runtime"
  }
}

data "aws_iam_policy_document" "backend_exec" {
  statement {
    sid = "EcsExecChannels"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel"
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "backend_exec" {
  name   = "ecs-exec"
  role   = aws_iam_role.backend_task.id
  policy = data.aws_iam_policy_document.backend_exec.json
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${var.name_prefix}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_cpu)
  memory                   = tostring(var.task_memory)
  execution_role_arn       = aws_iam_role.backend_execution.arn
  task_role_arn            = aws_iam_role.backend_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = "${aws_ecr_repository.backend.repository_url}:bootstrap"
      essential = true

      portMappings = [
        {
          name          = "backend-http"
          containerPort = var.container_port
          hostPort      = var.container_port
          protocol      = "tcp"
          appProtocol   = "http"
        }
      ]

      environment = [
        {
          name  = "SPRING_PROFILES_ACTIVE"
          value = "dev"
        },
        {
          name  = "APP_MODE"
          value = "api"
        },
        {
          name  = "SPRING_DATASOURCE_URL"
          value = "jdbc:postgresql://${var.database_address}:${var.database_port}/${var.database_name}"
        }
      ]

      secrets = [
        {
          name      = "SPRING_DATASOURCE_USERNAME"
          valueFrom = "${var.database_secret_arn}:username::"
        },
        {
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = "${var.database_secret_arn}:password::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.backend.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "backend"
        }
      }
    }
  ])

  tags = {
    Name    = "${var.name_prefix}-backend"
    Purpose = "backend-task-definition"
  }
}

resource "aws_ecs_service" "backend" {
  name             = "${var.name_prefix}-backend"
  cluster          = aws_ecs_cluster.this.id
  task_definition  = aws_ecs_task_definition.backend.arn
  desired_count    = 0
  launch_type      = "FARGATE"
  platform_version = "LATEST"

  enable_ecs_managed_tags = true
  enable_execute_command  = true
  propagate_tags          = "SERVICE"

  health_check_grace_period_seconds = var.health_check_grace_period_seconds

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "backend"
    container_port   = var.container_port
  }

  lifecycle {
    ignore_changes = [
      desired_count,
      task_definition
    ]
  }

  depends_on = [
    aws_iam_role_policy_attachment.backend_execution,
    aws_iam_role_policy.backend_secret
  ]

  tags = {
    Name    = "${var.name_prefix}-backend"
    Purpose = "backend-service"
  }
}
