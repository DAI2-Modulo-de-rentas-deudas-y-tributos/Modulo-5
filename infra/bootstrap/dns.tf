# El dominio está registrado en NIC Argentina, y Route 53 no puede registrar ni transferir
# .com.ar: NIC.ar delega el DNS en los nameservers de esta zona (output route53_name_servers).
# Vive en el bootstrap porque recrearla cambia los nameservers y rompe la delegación.
resource "aws_route53_zone" "main" {
  name = var.domain_name

  lifecycle {
    prevent_destroy = true
  }
}

locals {
  test_domain_names = ["test.${var.domain_name}", "*.test.${var.domain_name}"]

  # Rol de despliegue de cada ambiente; también lo usan los access logs (access_logs.tf).
  github_deploy_roles = {
    dev  = aws_iam_role.github_dev
    test = aws_iam_role.github_test
    prod = aws_iam_role.github_prod
  }
}

# Certificados ACM, registros DNS y dominio de Amplify de TEST y PROD. TEST sólo puede
# escribir test.<dominio> y sus subdominios; PROD, todo lo demás.
data "aws_iam_policy_document" "github_domain" {
  for_each = {
    test = "ForAllValues:StringLike"
    prod = "ForAllValues:StringNotLike"
  }

  statement {
    sid = "ReadDnsZone"
    actions = [
      "route53:GetChange",
      "route53:GetHostedZone",
      "route53:ListHostedZones",
      "route53:ListHostedZonesByName",
      "route53:ListResourceRecordSets",
      "route53:ListTagsForResource"
    ]
    resources = ["*"]
  }

  statement {
    sid       = "ManageOwnDnsRecords"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [aws_route53_zone.main.arn]

    condition {
      test     = each.value
      variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
      values   = local.test_domain_names
    }
  }

  statement {
    sid = "ManageCertificates"
    actions = [
      "acm:AddTagsToCertificate",
      "acm:DeleteCertificate",
      "acm:DescribeCertificate",
      "acm:ListCertificates",
      "acm:ListTagsForCertificate",
      "acm:RemoveTagsFromCertificate",
      "acm:RequestCertificate"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ManageFrontendDomain"
    actions = [
      "amplify:CreateDomainAssociation",
      "amplify:DeleteDomainAssociation",
      "amplify:GetDomainAssociation",
      "amplify:ListDomainAssociations",
      "amplify:UpdateDomainAssociation"
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:amplify:${var.aws_region}:${data.aws_caller_identity.current.account_id}:apps/*"
    ]
  }
}

resource "aws_iam_policy" "github_domain" {
  for_each = data.aws_iam_policy_document.github_domain

  name        = "${var.project_name}-github-${each.key}-domain"
  description = "Permisos del pipeline ${upper(each.key)} para DNS, ACM y el dominio de Amplify."
  policy      = each.value.json
}

resource "aws_iam_role_policy_attachment" "github_domain" {
  for_each = {
    test = aws_iam_role.github_test.name
    prod = aws_iam_role.github_prod.name
  }

  role       = each.value
  policy_arn = aws_iam_policy.github_domain[each.key].arn
}

# Certificado del origen HTTPS del ALB (origin-<ambiente>.<dominio>, en la región del ALB).
# Se emite acá y no en cada ambiente: el módulo edge sólo lo busca (data "aws_acm_certificate"),
# así destruir y recrear un ambiente no vuelve a pedir ni validar certificados.
resource "aws_acm_certificate" "api_origin" {
  for_each = local.github_deploy_roles

  domain_name       = "origin-${each.key}.${var.domain_name}"
  validation_method = "DNS"

  tags = {
    Environment = each.key
    Purpose     = "api-origin-tls"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "certificate_validation" {
  for_each = aws_acm_certificate.api_origin

  zone_id = aws_route53_zone.main.zone_id
  name    = one(each.value.domain_validation_options).resource_record_name
  type    = one(each.value.domain_validation_options).resource_record_type
  records = [one(each.value.domain_validation_options).resource_record_value]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "api_origin" {
  for_each = aws_acm_certificate.api_origin

  certificate_arn         = each.value.arn
  validation_record_fqdns = [aws_route53_record.certificate_validation[each.key].fqdn]
}

# Lo que necesita el módulo edge para usar el certificado y publicar el registro A del origen.
# Cada ambiente sólo puede escribir su propio origin-<ambiente>; la denegación explícita cubre
# a PROD, cuya política github-prod-domain admite todos los nombres fuera de test.<dominio>.
data "aws_iam_policy_document" "api_origin_tls" {
  for_each = local.github_deploy_roles

  statement {
    sid = "FindOriginCertificateAndDnsZone"
    actions = [
      "acm:ListCertificates",
      "route53:ListHostedZones",
      "route53:ListHostedZonesByName"
    ]
    resources = ["*"]
  }

  statement {
    sid = "ReadOwnOriginCertificate"
    actions = [
      "acm:DescribeCertificate",
      "acm:GetCertificate",
      "acm:ListTagsForCertificate"
    ]
    resources = [aws_acm_certificate.api_origin[each.key].arn]
  }

  statement {
    sid = "ReadOriginDnsZone"
    actions = [
      "route53:GetHostedZone",
      "route53:ListResourceRecordSets",
      "route53:ListTagsForResource"
    ]
    resources = [aws_route53_zone.main.arn]
  }

  statement {
    sid       = "WaitForOriginDnsChange"
    actions   = ["route53:GetChange"]
    resources = ["arn:${data.aws_partition.current.partition}:route53:::change/*"]
  }

  statement {
    sid       = "ManageOwnOriginDnsRecord"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [aws_route53_zone.main.arn]

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "route53:ChangeResourceRecordSetsRecordTypes"
      values   = ["A"]
    }

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "route53:ChangeResourceRecordSetsActions"
      values   = ["CREATE", "UPSERT", "DELETE"]
    }

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
      values   = [aws_acm_certificate.api_origin[each.key].domain_name]
    }
  }

  statement {
    sid       = "DenyOtherOriginDnsRecords"
    effect    = "Deny"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [aws_route53_zone.main.arn]

    condition {
      test     = "ForAnyValue:StringEquals"
      variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
      values = [
        for environment, certificate in aws_acm_certificate.api_origin :
        certificate.domain_name if environment != each.key
      ]
    }
  }
}

resource "aws_iam_role_policy" "api_origin_tls" {
  for_each = local.github_deploy_roles

  name   = "api-origin-tls"
  role   = each.value.id
  policy = data.aws_iam_policy_document.api_origin_tls[each.key].json
}
