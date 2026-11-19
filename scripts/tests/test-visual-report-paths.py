import ast
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SUITE = ROOT / 'src/main/resources/seed/plugins/visual-report'


class PortablePathsTest(unittest.TestCase):
    def test_path_initializers_without_rendering_or_optional_dependencies(self):
        files = [p for p in SUITE.rglob('*.py') if '\nDATA_ROOT = ' in p.read_text()]
        self.assertEqual(len(files), 14)
        for file in files:
            tree = ast.parse(file.read_text(), filename=str(file))
            # Execute only path setup, never rendering, third-party imports or docstrings.
            selected = []
            names = {'DATA_ROOT', 'OUT', 'OUTDIR', 'OUT_DIR', 'OUT_WHEEL', 'OUT_VALUE', 'TMP', '_SUITE_MPLCONFIG', 'MPL_RC'}
            for node in tree.body:
                if isinstance(node, ast.Import) and all(x.name in {'os', 'tempfile'} for x in node.names):
                    selected.append(node)
                elif isinstance(node, ast.ImportFrom) and node.module == 'pathlib':
                    selected.append(node)
                elif isinstance(node, ast.Assign) and any(isinstance(x, ast.Name) and x.id in names for x in node.targets):
                    selected.append(node)
                elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Call):
                    call = node.value
                    if isinstance(call.func, ast.Attribute) and call.func.attr in {'makedirs', 'setdefault', 'mkdir'}:
                        selected.append(node)
                elif isinstance(node, ast.If) and any(name in ast.unparse(node.test) for name in ['_SUITE_MPLCONFIG', 'MPL_RC']):
                    selected.append(node)
            program = compile(ast.Module(body=selected, type_ignores=[]), str(file), 'exec')
            for mode in ['stand', 'legacy', 'default', 'explicit-mpl']:
                with self.subTest(file=file.name, mode=mode), tempfile.TemporaryDirectory(prefix='visual-paths-') as temp:
                    home = Path(temp) / 'home with spaces'
                    home.mkdir()
                    primary = home / 'primary data'
                    legacy = home / 'legacy data'
                    env = {'HOME': str(home), 'TMPDIR': temp}
                    expected = home / '.stand'
                    if mode in {'stand', 'explicit-mpl'}:
                        env.update(STAND_HOME=str(primary), NEBFLOW_HOME=str(legacy))
                        expected = primary
                    elif mode == 'legacy':
                        env['NEBFLOW_HOME'] = str(legacy)
                        expected = legacy
                    if mode == 'explicit-mpl':
                        env['MPLCONFIGDIR'] = str(home / 'custom mpl')
                    loaded_rc = []
                    namespace = {'matplotlib': SimpleNamespace(rc_file=loaded_rc.append)}
                    if mode == 'explicit-mpl':
                        rc = Path(env['MPLCONFIGDIR']) / 'matplotlibrc'
                        rc.parent.mkdir(parents=True)
                        rc.write_text('axes.unicode_minus: False\n')
                    with patch.dict(os.environ, env, clear=True), patch.object(tempfile, 'tempdir', temp):
                        exec(program, namespace)
                        self.assertEqual(Path(namespace['DATA_ROOT']), expected)
                        for name in names & namespace.keys():
                            value = str(namespace[name])
                            self.assertNotIn('Users/Kai', value.replace('\\', '/'))
                            if name.startswith('OUT'):
                                self.assertTrue(Path(value).is_relative_to(expected / 'plots'))
                                target = Path(value).parent if name in {'OUT_WHEEL', 'OUT_VALUE'} or Path(value).suffix == '.png' else Path(value)
                                self.assertTrue(target.is_dir(), value)
                            if name == 'TMP':
                                self.assertTrue(Path(value).is_relative_to(temp))
                                self.assertTrue(Path(value).is_dir())
                        if mode == 'explicit-mpl':
                            self.assertEqual(os.environ['MPLCONFIGDIR'], env['MPLCONFIGDIR'])
                            if 'MPL_RC' in namespace:
                                self.assertEqual(loaded_rc, [str(rc)])
                        else:
                            self.assertEqual(loaded_rc, [])
            # No executable constant may retain a personal absolute path.
            for node in ast.walk(tree):
                if isinstance(node, ast.Call):
                    for arg in node.args:
                        if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                            self.assertNotIn('C:/Users/Kai', arg.value.replace('\\', '/'))


if __name__ == '__main__':
    unittest.main()
