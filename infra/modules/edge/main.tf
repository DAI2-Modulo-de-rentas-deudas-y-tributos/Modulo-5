data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host_header" {
  name = "Managed-AllViewerExceptHostHeader"
}

# HSTS, nosniff, X-Frame-Options y Referrer-Policy en las respuestas de la API (SCRUM-798).
data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

# El security group admite a cualquier distribución de CloudFront; el ALB sólo reenvía los
# requests que traen este header, que únicamente nuestra distribución conoce (SCRUM-791).
resource "random_password" "origin_verify" {
  length  = 48
  special = false
}

resource "aws_lb" "api" {
  name                       = "${var.name_prefix}-api"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [var.alb_security_group_id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
  xff_header_processing_mode = "append"
  enable_xff_client_port     = false

  access_logs {
    bucket  = var.access_logs_bucket == null ? "" : var.access_logs_bucket
    prefix  = var.access_logs_bucket == null ? null : "alb"
    enabled = var.access_logs_bucket != null
  }

  tags = {
    Name    = "${var.name_prefix}-api"
    Purpose = "backend-entrypoint"
  }
}

resource "aws_lb_target_group" "backend" {
  name             = "${var.name_prefix}-backend"
  port             = var.backend_container_port
  protocol         = "HTTP"
  protocol_version = "HTTP1"
  vpc_id           = var.vpc_id
  target_type      = "ip"

  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = var.health_check_path
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200-399"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.name_prefix}-backend"
  }
}

data "aws_route53_zone" "origin" {
  name = var.https_origin.zone
}

data "aws_acm_certificate" "origin" {
  domain   = var.https_origin.domain
  statuses = ["ISSUED"]
}

# CloudFront llega al ALB sólo por HTTPS: el header X-Origin-Verify nunca viaja en texto plano.
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = data.aws_acm_certificate.origin.arn

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

# El listener y el registro tenían count mientras existía el origen HTTP. El estado de TEST
# (y el de PROD una vez aplicado el PR #173) los guarda como [0]: así no se recrean.
moved {
  from = aws_lb_listener.https[0]
  to   = aws_lb_listener.https
}

resource "aws_route53_record" "origin" {
  zone_id = data.aws_route53_zone.origin.zone_id
  name    = var.https_origin.domain
  type    = "A"

  alias {
    name                   = aws_lb.api.dns_name
    zone_id                = aws_lb.api.zone_id
    evaluate_target_health = false
  }
}

moved {
  from = aws_route53_record.origin[0]
  to   = aws_route53_record.origin
}

resource "aws_lb_listener_rule" "from_cloudfront" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 1

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin_verify.result]
    }
  }
}

resource "aws_cloudfront_distribution" "api" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "API DEV de ${var.name_prefix}"
  price_class         = "PriceClass_All"
  http_version        = "http2and3"
  wait_for_deployment = false

  dynamic "logging_config" {
    for_each = var.access_logs_bucket[*]
    content {
      bucket          = "${logging_config.value}.s3.amazonaws.com"
      include_cookies = false
      prefix          = "cloudfront/"
    }
  }

  origin {
    domain_name = aws_route53_record.origin.fqdn
    origin_id   = "${var.name_prefix}-alb"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_verify.result
    }
  }

  default_cache_behavior {
    target_origin_id       = "${var.name_prefix}-alb"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    allowed_methods = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods  = ["GET", "HEAD", "OPTIONS"]

    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host_header.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Name    = "${var.name_prefix}-api"
    Purpose = "api-https-endpoint"
  }
}
