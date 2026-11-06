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

    def test_plan_protects_user_and_active_data_and_duplicate_hash_is_not_authority(self):
        entries = [
            {"path": "tmp/old.bin", "sizeBytes": 9, "ageSeconds": 40 * 86400, "sha256": "same"},
            {"path": "projects/p/.nebflow/worktrees/active/a", "sizeBytes": 9, "ageSeconds": 40 * 86400, "sha256": "same"},
            {"path": "reports-evidence/bgagent-mobile-chrome-20261106/x", "sizeBytes": 5, "ageSeconds": 90 * 86400, "sha256": "e"},
            {"path": "uploads/delivered.pdf", "sizeBytes": 5, "ageSeconds": 90 * 86400, "sha256": "u"},
            {"path": "docs/official.md", "sizeBytes": 5, "ageSeconds": 90 * 86400, "sha256": "d"},
        ]
        plan = capacity.prune_plan(entries)
        self.assertEqual(plan["mode"], "read-only-plan")
        self.assertEqual(plan["defaultAction"], "none")
        self.assertEqual(plan["entries"][0]["reason"], "review-required-reference-and-open-state-unknown")
        self.assertTrue(all(row["decision"] == "never-auto-delete" for row in plan["entries"]))
        self.assertEqual(plan["entries"][1]["reason"], "protected-by-policy")
        self.assertEqual(plan["entries"][2]["reason"], "protected-by-policy")
        self.assertEqual(plan["entries"][3]["reason"], "protected-by-policy")
        self.assertEqual(plan["entries"][4]["reason"], "protected-by-policy")
        self.assertEqual(plan["duplicateContentEvidenceOnly"][0]["copies"], 2)

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
