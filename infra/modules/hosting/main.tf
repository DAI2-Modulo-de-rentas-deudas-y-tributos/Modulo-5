# `data:` en img-src cubre los íconos SVG embebidos en el CSS.
locals {
  content_security_policy = join("; ", [
    "default-src 'self'",
    "connect-src 'self' ${var.api_base_url}",
    "form-action 'self'",
    "style-src 'self' 'unsafe-inline'",
    "font-src 'self'",
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

data "aws_route53_zone" "frontend" {
  for_each = var.custom_domains
  name     = each.value
}

resource "aws_amplify_domain_association" "frontend" {
  for_each              = var.custom_domains
  app_id                = aws_amplify_app.frontend.id
  domain_name           = each.key
  wait_for_verification = false

  sub_domain {
    branch_name = aws_amplify_branch.develop.branch_name
    prefix      = ""
  }
}

resource "aws_route53_record" "frontend" {
  for_each        = aws_amplify_domain_association.frontend
  zone_id         = data.aws_route53_zone.frontend[each.key].zone_id
  name            = each.key
  type            = "A"
  allow_overwrite = true

  alias {
    name                   = split(" ", trimspace(one(each.value.sub_domain).dns_record))[1]
    zone_id                = "Z2FDTNDATAQYW2"
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "frontend_certificate" {
  for_each        = aws_amplify_domain_association.frontend
  zone_id         = data.aws_route53_zone.frontend[each.key].zone_id
  name            = split(" ", each.value.certificate_verification_dns_record)[0]
  type            = "CNAME"
  ttl             = 60
  records         = [split(" ", each.value.certificate_verification_dns_record)[2]]
  allow_overwrite = true
}
