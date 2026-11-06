import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "artifact-capacity.py"
spec = importlib.util.spec_from_file_location("artifact_capacity", SCRIPT)
capacity = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(capacity)


class ArtifactCapacityTest(unittest.TestCase):
    def test_categories_and_hash_manifest(self):
        self.assertEqual(capacity.classify("reports-evidence/run/a.png"), "evidence")
        self.assertEqual(capacity.classify("tmp/render.bin"), "temporary-output")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "tmp").mkdir()
            target = root / "tmp" / "payload.bin"
            target.write_bytes(b"known payload")
            before = target.read_bytes()
            rows = capacity.inventory(root, now=target.stat().st_mtime + 10)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["category"], "temporary-output")
            self.assertEqual(rows[0]["ageSeconds"], 10)
            self.assertEqual(rows[0]["sha256"], "5682cf8bc4b549d2f5f79101d03dbe613127ce4161a9bce7829dd1630a8f66d8")
            self.assertEqual(rows[0]["status"], "inventory-only")
            self.assertEqual(target.read_bytes(), before)

    def test_symlink_targets_and_nonregular_files_are_not_followed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            outside = root.parent / (root.name + "-outside")
            outside.write_text("protected")
            try:
                (root / "link").symlink_to(outside)
                rows = capacity.inventory(root)
                self.assertEqual(rows, [])
                self.assertEqual(outside.read_text(), "protected")
            finally:
                outside.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
