#!/usr/bin/env python3
"""Regression tests for shared CLI artwork payloads and source ownership."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


banner = load("banner", ROOT / "scripts/render-install-banner.py")
checker = load("checker", ROOT / "scripts/check-logo-matrix.py")


class BannerPayloadTests(unittest.TestCase):
    def sample(self):
        values = {key: "sample" for key in banner.PAYLOAD_KEYS}
        values.update(art="72.;72.;72.;72.;72.;72.;72.;72.;72.",
                      ink_dark="255;255;255", ink_light="0;0;0",
                      stats={"width": 72, "rows": 9})
        return values

    def test_properties_are_deterministic_ascii(self):
        values = self.sample()
        text = banner.properties_block(values)
        self.assertEqual(text, banner.properties_block(values))
        text.encode("ascii")
        self.assertTrue(text.endswith("\n"))
        decoded = banner.parse_properties(text)
        self.assertEqual(set(decoded), set(banner.PAYLOAD_KEYS) | {"columns", "rows"})
        for key in banner.PAYLOAD_KEYS:
            self.assertEqual(decoded[key], values[key])
        self.assertEqual((decoded["columns"], decoded["rows"]), ("72", "9"))

    def test_java_property_escaping(self):
        value = ' leading\\path\n\r\t\f=:#"!\u2588\U0001f600'
        escaped = banner.escape_property(value)
        escaped.encode("ascii")
        self.assertIn("\\\\path", escaped)
        self.assertEqual(banner.parse_properties("art=" + escaped)["art"], value)
        self.assertEqual(banner.parse_properties(r"art=a\=b\:c\#d\!e")["art"], "a=b:c#d!e")

    def test_invalid_properties_are_rejected(self):
        for text in ("art=a\nart=b", "art=a\\", "art=\\u12xz", "no delimiter"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                banner.parse_properties(text)

    def test_embedded_payload_parsers_agree(self):
        values = self.sample()
        expected = {key: values[key] for key in banner.PAYLOAD_KEYS}
        self.assertEqual(checker.parse_sh(banner.sh_block(values)), expected)
        self.assertEqual(checker.parse_ps1(banner.ps1_block(values)), expected)
        decoded = checker.parse_runtime(banner.properties_block(values))
        self.assertEqual({key: decoded[key] for key in banner.PAYLOAD_KEYS}, expected)

    def test_checked_in_resource_matches_installers(self):
        resource = Path(banner.BANNER_RESOURCE).read_bytes()
        values = banner.parse_properties(resource.decode("ascii"))
        for path, parser in ((banner.INSTALL_SH, checker.parse_sh),
                             (banner.INSTALL_PS1, checker.parse_ps1)):
            parsed = parser(Path(path).read_text(encoding="utf-8-sig"))
            self.assertEqual({key: values[key] for key in banner.PAYLOAD_KEYS}, parsed)
        rows = values["art"].split(";")
        self.assertEqual(len(rows), 9)
        import re
        for row in rows:
            tokens = re.findall(r"(\d+)([.un#])", row)
            self.assertEqual("".join(count + cell for count, cell in tokens), row)
            self.assertEqual(sum(int(count) for count, _ in tokens), 72)

    def run_main(self, args):
        with patch.object(sys, "argv", ["render-install-banner.py", *args]), \
                contextlib.redirect_stdout(io.StringIO()):
            banner.main()

    def test_resource_only_update_does_not_touch_installers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sh, ps, resource = root / "install.sh", root / "install.ps1", root / "brand/banner.properties"
            sh.write_bytes(b"shell sentinel\r\n")
            ps.write_bytes(b"\xef\xbb\xbfpowershell sentinel\r\n")
            with patch.object(banner, "build", return_value=self.sample()), \
                    patch.object(banner, "INSTALL_SH", str(sh)), \
                    patch.object(banner, "INSTALL_PS1", str(ps)), \
                    patch.object(banner, "BANNER_RESOURCE", str(resource)):
                self.run_main(["--update", "--resource-only"])
                self.assertEqual(resource.read_text(), banner.properties_block(self.sample()))
                self.assertEqual(sh.read_bytes(), b"shell sentinel\r\n")
                self.assertEqual(ps.read_bytes(), b"\xef\xbb\xbfpowershell sentinel\r\n")
                mtime = resource.stat().st_mtime_ns
                self.run_main(["--update", "--resource-only"])
                self.assertEqual(resource.stat().st_mtime_ns, mtime)

    def test_default_update_covers_all_faces_without_rewriting_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sh, ps, resource = root / "install.sh", root / "install.ps1", root / "banner.properties"
            values = self.sample()
            sh.write_text(banner.sh_block(values), encoding="utf-8")
            ps.write_text(banner.ps1_block(values), encoding="utf-8-sig")
            before = {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in (sh, ps)}
            with patch.object(banner, "build", return_value=values), \
                    patch.object(banner, "INSTALL_SH", str(sh)), \
                    patch.object(banner, "INSTALL_PS1", str(ps)), \
                    patch.object(banner, "BANNER_RESOURCE", str(resource)):
                self.run_main(["--update"])
                self.assertEqual(resource.read_text(), banner.properties_block(values))
                for path in (sh, ps):
                    self.assertEqual((path.read_bytes(), path.stat().st_mtime_ns), before[path])
                sh.write_text(banner.sh_block(values).replace(values["art"], "stale"))
                ps.write_text(banner.ps1_block(values).replace(values["art"], "stale"), encoding="utf-8-sig")
                resource.write_text("art=stale\n")
                self.run_main(["--update"])
                self.assertEqual(checker.parse_sh(sh.read_text())["art"], values["art"])
                self.assertEqual(checker.parse_ps1(ps.read_text(encoding="utf-8-sig"))["art"], values["art"])
                self.assertEqual(resource.read_text(), banner.properties_block(values))

    def test_check_detects_resource_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sh, ps, resource = root / "install.sh", root / "install.ps1", root / "banner.properties"
            values = self.sample()
            sh.write_text(banner.sh_block(values), encoding="utf-8")
            ps.write_text(banner.ps1_block(values), encoding="utf-8-sig")
            resource.write_text(banner.properties_block(values), encoding="ascii")
            with patch.object(banner, "build", return_value=values), \
                    patch.object(banner, "INSTALL_SH", str(sh)), \
                    patch.object(banner, "INSTALL_PS1", str(ps)), \
                    patch.object(banner, "BANNER_RESOURCE", str(resource)):
                with self.assertRaises(SystemExit) as result:
                    self.run_main(["--check"])
                self.assertEqual(result.exception.code, 0)
                resource.write_text(resource.read_text().replace("rows=9", "rows=8"))
                with self.assertRaises(SystemExit) as result:
                    self.run_main(["--check"])
                self.assertEqual(result.exception.code, 1)
                resource.unlink()
                with self.assertRaises(SystemExit) as result:
                    self.run_main(["--check"])
                self.assertEqual(result.exception.code, 1)

    def test_source_gate_rejects_independent_art(self):
        self.assertIn("shell-owned banner renderer",
                      checker.independent_artwork("stand_banner_print() { :; }", shell=True))
        self.assertIn("inline block artwork", checker.independent_artwork('"███████╗"'))
        self.assertIn("inline block artwork", checker.independent_artwork('"▐█▌"', shell=True))
        self.assertIn("independent shell artwork payload",
                      checker.independent_artwork('BANNER_ART="abc"', shell=True))
        self.assertEqual(checker.independent_artwork('case "u" => "▀"'), [])
        self.assertEqual(checker.independent_artwork("# stand_banner_print", shell=True), [])


if __name__ == "__main__":
    unittest.main()
