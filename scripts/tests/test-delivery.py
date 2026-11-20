#!/usr/bin/env python3
import pathlib
import shutil
import subprocess
import tempfile
import unittest

CHECK = pathlib.Path(__file__).resolve().parents[1] / 'check-delivery.py'


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Fixture')
        self.write('README', 'base')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True)

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value if isinstance(value, bytes) else value.encode())

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')

    def check(self):
        return subprocess.run(['python3', str(CHECK), '--base', self.base], cwd=self.root,
                              capture_output=True, text=True)

    def test_frontend_missing_and_complete(self):
        self.write('src/main/resources/web/app.js', 'change')
        self.write('delivery/batch/before.png', b'\x89PNG\r\n\x1a\nfixture')
        self.write('delivery/batch/screenshots.md', 'Viewport: 800x600\nScenario: test\nFixture or command: test')
        self.commit()
        missing = self.check()
        self.assertEqual(missing.returncode, 1)
        self.assertIn('after.png', missing.stderr)
        self.write('delivery/batch/after.png', b'\x89PNG\r\n\x1a\nfixture')
        self.commit()
        self.assertEqual(self.check().returncode, 0)

    def test_backend_and_invalid_base(self):
        self.write('src/main/scala/App.scala', 'change')
        self.commit()
        self.assertEqual(self.check().returncode, 1)
        self.write('delivery/batch/changes.md', 'What: x\nWhy: y\nImpact: z')
        self.write('delivery/batch/state-machine.md', 'Before stateDiagram-v2\nAfter stateDiagram-v2')
        self.write('delivery/batch/behavior.md', 'Before after mermaid')
        self.commit()
        self.assertEqual(self.check().returncode, 0)
        self.base = 'not-a-ref'
        self.assertEqual(self.check().returncode, 2)


class ArtifactDirShape(unittest.TestCase):
    """The tmp-directory shape criterion of check-delivery.py (relaxed 2026-11-19).

    A batch may legitimately own only ONE face: a pure-audit batch ships nothing
    (evidence/ alone), a shipping batch may carry delivery/ alone. The criterion is
    therefore "at least one of delivery/ and evidence/ exists as a real directory".
    The negative control below (neither present) must still be red, and an entry
    that exists as a symlink or as a plain file must still be refused — the
    relaxation is scoped to presence, not to safety.
    """

    EMPTY_MANIFEST = '# Artifact manifest\n\n| Relative path | Bytes | mtime | Status |\n| --- | ---: | --- | --- |\n'

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = pathlib.Path(self.tmp.name)
        subprocess.check_output(['git', 'init', '-q'], cwd=self.repo)
        subprocess.check_output(['git', 'config', 'user.email', 'fixture@example.invalid'], cwd=self.repo)
        subprocess.check_output(['git', 'config', 'user.name', 'Fixture'], cwd=self.repo)
        (self.repo / 'README').write_text('base')
        subprocess.check_output(['git', 'add', '.'], cwd=self.repo)
        subprocess.check_output(['git', 'commit', '-qm', 'fixture'], cwd=self.repo)
        self.artifacts = self.repo / '.stand' / 'tmp' / 'demo'
        self.artifacts.mkdir(parents=True)

    def check(self):
        return subprocess.run(['python3', str(CHECK), '--manifest-only', '--tmp', str(self.artifacts)],
                              cwd=self.repo, capture_output=True, text=True)

    def test_neither_face_is_red(self):
        (self.artifacts / 'MANIFEST.md').write_text(self.EMPTY_MANIFEST)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('missing or unsafe artifact directory', result.stderr)

    def test_either_face_alone_is_green(self):
        for name in ('evidence', 'delivery'):
            with self.subTest(face=name):
                for stale in ('evidence', 'delivery'):
                    shutil.rmtree(self.artifacts / stale, ignore_errors=True)
                (self.artifacts / name).mkdir()
                (self.artifacts / 'MANIFEST.md').write_text(self.EMPTY_MANIFEST)
                result = self.check()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_both_faces_are_green(self):
        for name in ('evidence', 'delivery'):
            (self.artifacts / name).mkdir()
        (self.artifacts / 'MANIFEST.md').write_text(self.EMPTY_MANIFEST)
        self.assertEqual(self.check().returncode, 0)

    def test_symlinked_face_is_still_red(self):
        real = self.repo / 'elsewhere'
        real.mkdir()
        (self.artifacts / 'evidence').symlink_to(real)
        (self.artifacts / 'MANIFEST.md').write_text(self.EMPTY_MANIFEST)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('missing or unsafe artifact directory', result.stderr)

    def test_face_that_is_a_plain_file_is_still_red(self):
        (self.artifacts / 'delivery').write_text('not a directory')
        (self.artifacts / 'evidence').mkdir()
        (self.artifacts / 'MANIFEST.md').write_text(self.EMPTY_MANIFEST)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('missing or unsafe artifact directory', result.stderr)


if __name__ == '__main__':
    unittest.main()
