# La CSP sólo admite la API y Google Fonts; `data:` en img-src cubre los íconos SVG
# embebidos en el CSS.
locals {
  content_security_policy = join("; ", [
    "default-src 'self'",
    "connect-src 'self' ${var.api_base_url}",
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data:",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
  ])
}

resource "aws_amplify_app" "frontend" {
  name                        = "${var.name_prefix}-frontend"
  description                 = "Frontend web DEV desplegado desde GitHub Actions"
  platform                    = "WEB"
  enable_branch_auto_build    = false
  enable_branch_auto_deletion = false

  environment_variables = merge({
    VITE_AUTH_MODE = "mock"
    }, var.environment_variables, {
    VITE_API_BASE_URL = var.api_base_url
  })

  custom_rule {
    source = "</^[^.]+$|\\.(?!(css|gif|ico|jpg|jpeg|js|png|txt|svg|woff|woff2|ttf|map|json|webp)$)([^.]+$)/>"
    target = "/index.html"
    status = "200"
  }

  # Cabeceras de seguridad del portal (SCRUM-798).
  custom_headers = yamlencode({
    customHeaders = [{
      pattern = "**"
      headers = [
        { key = "Strict-Transport-Security", value = "max-age=31536000; includeSubDomains" },
        { key = "X-Frame-Options", value = "DENY" },
        { key = "X-Content-Type-Options", value = "nosniff" },
        { key = "Referrer-Policy", value = "strict-origin-when-cross-origin" },
        { key = "Content-Security-Policy", value = local.content_security_policy },
      ]
    }]
  })

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

  environment_variables = merge({
    VITE_AUTH_MODE = "mock"
    }, var.environment_variables, {
    VITE_API_BASE_URL = var.api_base_url
  })
}
