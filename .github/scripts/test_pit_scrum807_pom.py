"""Self-contained contract tests; never launch Maven or PIT."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from pit_scrum807_pom import EXPECTED_TARGETS, NAMESPACE, PROBE_TARGET, Q


SCRIPT = Path(__file__).with_name("pit_scrum807_pom.py")


def synthetic_gate(path: Path) -> None:
    root = ET.Element(Q("project"))
    profiles = ET.SubElement(root, Q("profiles"))
    profile = ET.SubElement(profiles, Q("profile"))
    ET.SubElement(profile, Q("id")).text = "mutation-testing"
    plugins = ET.SubElement(ET.SubElement(profile, Q("build")), Q("plugins"))
    plugin = ET.SubElement(plugins, Q("plugin"))
    ET.SubElement(plugin, Q("groupId")).text = "org.pitest"
    ET.SubElement(plugin, Q("artifactId")).text = "pitest-maven"
    ET.SubElement(plugin, Q("version")).text = "1.18.2"
    config = ET.SubElement(plugin, Q("configuration"))
    target_classes = ET.SubElement(config, Q("targetClasses"))
    for target in EXPECTED_TARGETS:
        ET.SubElement(target_classes, Q("param")).text = target
    ET.SubElement(config, Q("mutationThreshold")).text = "60"
    ET.SubElement(config, Q("timeoutFactor")).text = "1.25"
    ET.SubElement(config, Q("jvmArgs"))
    path.write_bytes(ET.tostring(root, encoding="UTF-8", xml_declaration=True))


class PomContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="scrum807-contract-")
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.pom = self.path / "pom.xml"
        self.output = self.path / "probe.xml"
        synthetic_gate(self.pom)

    def command(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--pom", str(self.pom),
             "--write", str(self.output), *args],
            text=True, capture_output=True, check=False,
        )

    def test_probe_only_mutates_one_class_and_preserves_gate_options(self) -> None:
        run = self.command("--scope", "probe")
        self.assertEqual(run.returncode, 0, run.stderr)
        tree = ET.parse(self.output)
        config = tree.find(f".//{Q('configuration')}")
        assert config is not None
        targets = [x.text for x in config.findall(f"{Q('targetClasses')}/{Q('param')}")]
        self.assertEqual(targets, [PROBE_TARGET])
        self.assertIsNone(config.find(Q("targetTests")))
        self.assertIsNone(config.find(Q("mutators")))
        for tag, expected in (("mutationThreshold", "60"), ("threads", "1"),
                              ("timeoutConstant", "60000"), ("timeoutFactor", "1.25")):
            self.assertEqual(config.findtext(Q(tag)), expected)
        formats = [x.text for x in config.findall(f"{Q('outputFormats')}/{Q('param')}")]
        self.assertEqual(formats, ["HTML", "XML", "CSV"])

    def test_global_needs_separate_acknowledgement(self) -> None:
        run = self.command("--scope", "global")
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse(self.output.exists())
        run = self.command("--scope", "global", "--allow-global")
        self.assertEqual(run.returncode, 0, run.stderr)
        tree = ET.parse(self.output)
        self.assertEqual(
            [x.text for x in tree.findall(f".//{Q('targetClasses')}/{Q('param')}")],
            EXPECTED_TARGETS,
        )

    def test_refuses_modified_gate(self) -> None:
        gate = ET.parse(self.pom)
        config = gate.find(f".//{Q('configuration')}")
        assert config is not None
        ET.SubElement(config, Q("targetTests"))
        gate.write(self.pom)
        run = self.command("--scope", "probe")
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse(self.output.exists())

    def test_refuses_overwrite_of_existing_destination(self) -> None:
        self.output.write_text("PROTECTED", encoding="utf-8")
        run = self.command("--scope", "probe")
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(self.output.read_text(encoding="utf-8"), "PROTECTED")


if __name__ == "__main__":
    unittest.main()
