import importlib.machinery
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'tools/stand-terms'
loader = importlib.machinery.SourceFileLoader('stand_terms', str(CLI))
spec = importlib.util.spec_from_loader(loader.name, loader)
terms = importlib.util.module_from_spec(spec)
loader.exec_module(terms)

class DocumentSummaryTests(unittest.TestCase):
    def test_extract_boundaries(self):
        for n in (39, 40, 100, 101):
            with self.subTest(n=n):
                text = '---\nsummary: ' + '文' * n + '\n---\n# 标题\n'
                self.assertEqual(terms.extract_document_summary(text), '文' * n if 40 <= n <= 100 else '')
        self.assertEqual(terms.extract_document_summary('# no frontmatter'), '')
        self.assertEqual(terms.extract_document_summary('---\nstatus: draft\n---\n'), '')
        self.assertEqual(terms.extract_document_summary('---\nsummary: ' + '文' * 40), '')
        self.assertEqual(terms.extract_document_summary('---\nsummary: "' + '文' * 40 + '"\n---\n'), '文' * 40)

    def test_real_cli(self):
        with tempfile.TemporaryDirectory() as tmp:
            vault = Path(tmp) / 'vault'; vault.mkdir()
            summary = '文' * 100
            item_path = vault / 'item.md'
            item_path.write_text('---\ntype: design\nstatus: draft\nsummary: ' + summary + '\n---\n# 测试文档\n', encoding='utf-8')
            os.utime(item_path, (1000000000, 1000000000))
            (vault / 'legacy.md').write_text('---\nstatus: draft\n---\n# 旧文档\n', encoding='utf-8')
            db = Path(tmp) / 'index.db'
            def cli(*args):
                p = subprocess.run(['python3', str(CLI), *args, '--db', str(db), '--json'], text=True, capture_output=True)
                self.assertEqual(p.returncode, 0, p.stderr + p.stdout)
                return json.loads(p.stdout)
            cli('rebuild', '--root', str(vault), '--project', 'test')
            for result in (cli('list', '--limit', '0')['results'], cli('show', '测试文档')['results'], cli('doc', 'item.md')['documents'][0]['terms']):
                item = next(x for x in result if x['name'] == '测试文档')
                self.assertEqual(item['definition'], summary)
                self.assertEqual(item['rel'], 'item.md')
                self.assertEqual(item['status'], 'draft')
                self.assertEqual(item['updated_at'], terms.datetime.fromtimestamp(1000000000).isoformat())
                self.assertEqual(item['abs_path'], str(item_path))
            self.assertFalse(any(x['name'] == '旧文档' for x in cli('list', '--limit', '0')['results']))
            self.assertEqual(cli('doc', 'legacy.md')['documents'][0]['title'], '旧文档')
            for args in [('list', '--limit', '0'), ('show', '测试文档'), ('doc', 'item.md')]:
                p = subprocess.run(['python3', str(CLI), *args, '--db', str(db)], text=True, capture_output=True)
                self.assertEqual(p.returncode, 0, p.stderr)
                for value in (summary, str(item_path), 'draft', terms.datetime.fromtimestamp(1000000000).isoformat()):
                    self.assertIn(value, p.stdout)

if __name__ == '__main__': unittest.main()
