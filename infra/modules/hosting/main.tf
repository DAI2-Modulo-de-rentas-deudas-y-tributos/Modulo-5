resource "aws_amplify_app" "frontend" {
  name                        = "${var.name_prefix}-frontend"
  description                 = "Frontend web DEV desplegado desde GitHub Actions"
  platform                    = "WEB"
  enable_branch_auto_build    = false
  enable_branch_auto_deletion = false

  environment_variables = {
    VITE_API_BASE_URL = var.api_base_url
  }

  custom_rule {
    source = "</^[^.]+$|\\.(?!(css|gif|ico|jpg|jpeg|js|png|txt|svg|woff|woff2|ttf|map|json|webp)$)([^.]+$)/>"
    target = "/index.html"
    status = "200"
  }

  tags = {
    Name    = "${var.name_prefix}-frontend"
    Purpose = "frontend-hosting"
  }
}

resource "aws_amplify_branch" "develop" {
  app_id      = aws_amplify_app.frontend.id
  branch_name = var.branch_name
  description = "Despliegue continuo del ambiente DEV"
  framework   = "React"
  stage       = "DEVELOPMENT"

  enable_auto_build = false

  environment_variables = {
    VITE_API_BASE_URL = var.api_base_url
  }
}
