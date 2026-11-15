#!/usr/bin/env python3
"""Create a disposable PIT POM without changing the SCRUM-807 gate POM."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


NAMESPACE = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", NAMESPACE)
ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
Q = lambda name: f"{{{NAMESPACE}}}{name}"

EXPECTED_TARGETS = [
    "ar.gob.municipalidad.rentas.BillingService*",
    "ar.gob.municipalidad.rentas.CreditBalanceService*",
    "ar.gob.municipalidad.rentas.AdjustmentService*",
    "ar.gob.municipalidad.rentas.ElectronicPaymentService*",
    "ar.gob.municipalidad.rentas.ExemptionService*",
    "ar.gob.municipalidad.rentas.LateChargeService*",
    "ar.gob.municipalidad.rentas.LiquidationRunService*",
    "ar.gob.municipalidad.rentas.LiquidationService*",
    "ar.gob.municipalidad.rentas.PaymentPlanPrincipalLedger*",
    "ar.gob.municipalidad.rentas.PaymentPlanService*",
    "ar.gob.municipalidad.rentas.PaymentService*",
    "ar.gob.municipalidad.rentas.PlanWorkflowService*",
    "ar.gob.municipalidad.rentas.ReversalService*",
    "ar.gob.municipalidad.rentas.DueDateProcessor*",
]
PROBE_TARGET = "ar.gob.municipalidad.rentas.PaymentPlanService*"


def child(parent: ET.Element, name: str) -> ET.Element:
    found = parent.find(Q(name))
    if found is None:
        found = ET.SubElement(parent, Q(name))
    return found


def required_child(parent: ET.Element, name: str) -> ET.Element:
    found = parent.find(Q(name))
    if found is None:
        raise ValueError(f"El POM no contiene {name} en la ubicación esperada")
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scope", choices=("probe", "global"), required=True)
    parser.add_argument(
        "--allow-global",
        action="store_true",
        help="Requiere aprobación separada; no se usa desde el workflow experimental",
    )
    parser.add_argument("--pom", type=Path, default=Path("backend/pom.xml"))
    parser.add_argument(
        "--write",
        type=Path,
        default=Path("backend/pom.pit-scrum807.xml"),
    )
    args = parser.parse_args()

    if args.scope == "global" and not args.allow_global:
        parser.error(
            "PIT global prohibido sin --allow-global y procedimiento aprobado"
        )
    if args.scope == "probe" and args.allow_global:
        parser.error("--allow-global no está permitido durante una sonda")

    source = args.pom.resolve()
    destination = args.write.resolve()
    if source == destination:
        parser.error("El POM generado no puede sobrescribir el POM original")
    if destination.exists():
        parser.error(f"El destino ya existe; se rechaza sobrescribirlo: {destination}")

    tree = ET.parse(source)
    root = tree.getroot()
    profiles = required_child(root, "profiles")
    profile = next(
        (item for item in profiles.findall(Q("profile"))
         if item.findtext(Q("id")) == "mutation-testing"),
        None,
    )
    if profile is None:
        raise ValueError("No se encontró el perfil mutation-testing")

    build = required_child(profile, "build")
    plugins = required_child(build, "plugins")
    pit = next(
        (item for item in plugins.findall(Q("plugin"))
         if item.findtext(Q("groupId")) == "org.pitest"
         and item.findtext(Q("artifactId")) == "pitest-maven"),
        None,
    )
    if pit is None:
        raise ValueError("No se encontró pitest-maven en el perfil")

    configuration = required_child(pit, "configuration")
    targets = required_child(configuration, "targetClasses")
    configured_targets = [item.text or "" for item in targets.findall(Q("param"))]
    if configured_targets != EXPECTED_TARGETS:
        raise ValueError(
            "El perímetro PIT del POM cambió: se esperaba exactamente la lista "
            f"de 14 clases del gate; se encontró {configured_targets!r}"
        )
    if configuration.find(Q("targetTests")) is not None:
        raise ValueError(
            "El gate ahora filtra targetTests; revisar con responsables antes "
            "de generar este POM"
        )
    if configuration.find(Q("mutators")) is not None:
        raise ValueError(
            "El gate ya no usa mutadores predeterminados; revisar el cambio "
            "antes de generar este POM"
        )
    if required_child(configuration, "mutationThreshold").text != "60":
        raise ValueError("mutationThreshold del gate no es 60")
    version = pit.findtext(Q("version"))
    if version is not None and version.strip() != "1.18.2":
        raise ValueError(
            f"La versión declarada de PIT cambió: {version!r}; se esperaba 1.18.2"
        )

    if args.scope == "probe":
        for item in list(targets):
            targets.remove(item)
        ET.SubElement(targets, Q("param")).text = PROBE_TARGET
        print(f"PIT probe: clase objetivo acotada = {PROBE_TARGET}")
        print("Selección de pruebas: sin targetTests; sin filtro adicional")
    else:
        print("PIT global: se conservan las 14 clases objetivo originales")
        print("Selección de pruebas: sin targetTests; sin filtro adicional")

    child(configuration, "threads").text = "1"
    child(configuration, "timeoutConstant").text = "60000"
    child(configuration, "mutationThreshold").text = "60"
    child(configuration, "timestampedReports").text = "false"

    output_formats = child(configuration, "outputFormats")
    for item in list(output_formats):
        output_formats.remove(item)
    for name in ("HTML", "XML", "CSV"):
        ET.SubElement(output_formats, Q("param")).text = name

    child(configuration, "reportsDirectory").text = (
        "${project.build.directory}/pit-scrum807-reports"
    )

    destination.parent.mkdir(parents=True, exist_ok=True)
    tree.write(destination, encoding="UTF-8", xml_declaration=True)
    print(f"POM descartable creado: {destination}")
    print("Mutadores: sin configuración <mutators>; se conserva el default PIT")
    print("Umbral: mutationThreshold=60")
    print("Concurrencia: threads=1; timeoutConstant=60000")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ET.ParseError, OSError, ValueError) as exc:
        print(f"ERROR preparando POM PIT descartable: {exc}", file=sys.stderr)
        raise SystemExit(2)
