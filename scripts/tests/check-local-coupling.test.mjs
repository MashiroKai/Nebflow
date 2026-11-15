import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '../..');
const pdf = 'src/main/resources/seed/plugins/document-production/skills/pdf/';

test('lexical spans preserve real identities, mixed lines, and source rules', () => {
  const dir = mkdtempSync(join(tmpdir(), 'coupling-test-'));
  try {
    const put = (path, text) => {
      mkdirSync(resolve(dir, path, '..'), { recursive: true });
      writeFileSync(join(dir, path), text);
    };
    put('scripts/user-knowledge-patterns.txt', readFileSync(join(root, 'scripts/user-knowledge-patterns.txt')));
    const cases = new Map([
      ['src/main/fixture.js', [
        ['adjustCap(key, cap)', false],
        ['adjustCap(key, cap); const url = "https://git.ustc.edu.cn/kaiyu/project";', true],
        ['rgba(46,107,79,.18)', false],
        ['rgba(46,107,79,.18); const device = "Kai";', true],
        ['--accent-rgb: 46,107,79;', false],
        ['accent: [46, 107, 79],', false],
        ['<path d="M 288.60,107.16 L 914.65,107.16 Z"/>', false],
        ['<path d="M 288.60,107.16 Z"/> Kai', true],
        ['<path d="M 107 Kai"/>', true],
        ['// timeWheel.css:107', false],
        ['// local implementation (107 行)', false],
        ['// local implementation （107 行）; model glm-5.2-107', true],
        ['// timeWheel.css:107; gateway 107', true],
        ['const model = "glm-5.2-107";', true],
        ['const gateway = "free 107 gateway";', true],
        ['const path = "C:/Users/Kai/data";', true],
        ['const path = "/Users/kaiyu/data";', true],
        ['const font = "kai";', true],
        ['const owner = "https://git.ustc.edu.cn/kaiyu/project";', true],
        ['const hidden = "mykaimashirodevice";', true],
        ['const hidden = "XkaiyuY";', true],
        ['const hidden = "adjustCaps";', true],
      ]],
      ['src/main/library.min.js', [
        ['107:[function(e,t,r){t.exports=1}]', false],
        ['107:[function(e,t,r){t.exports="Kai"}]', true],
        ['"../internals/perform":107,', false],
        ['return-107<=e&&e<=107?[e+139]:108;', false],
        ['e=e.length<1240?107:e.length<33900?1131:32768;', false],
        ['return-107<=e&&e<=107?"Kai":"normal";', true],
        ['const model="glm-5.2-107";', true],
        ['const owner="https://git.ustc.edu.cn/kaiyu/project";', true],
      ]],
      [`${pdf}scripts/cover_render.py`, [
        ['"kai": [', false],
        ['serif = F["kai"] if ct.get("style") == "kai" else F["serif"]', false],
        ['"kai": "C:/Users/Kai/font"', true],
        ['# kai optional', false],
        ['# serif family: use kai if requested', false],
        ['"""Return {sans, serif, kai, latin} of registered fonts.', false],
        ['const owner = "kai";', true],
        ['picks.get("kai"); const owner = "kai";', true],
      ]],
      [`${pdf}env_setup/env_check.sh`, [
        ['grep -qi "yahei\\|kai"', false],
        ['grep -qi "yahei\\|kai" # C:/Users/Kai', true],
      ]],
      ['src/main/resources/web/js/pinyinTable.js', [
        ['kai:开揩楷凯', false],
        ['kai:开揩楷凯 /Users/kaiyu', true],
      ]],
    ]);
    for (const [path, rows] of cases) put(path, rows.map(([line]) => line).join('\n'));
    const run = spawnSync(process.execPath, [join(root, 'scripts/check-local-coupling.mjs'), '--root', dir, '--json'], { encoding: 'utf8', timeout: 15000, maxBuffer: 4 * 1024 * 1024 });
    assert.equal(run.status, 1, run.stderr);
    const result = JSON.parse(run.stdout);
    const found = new Set(result.hits.map((hit) => `${hit.path}:${hit.line}`));
    for (const [path, rows] of cases) rows.forEach(([, expected], index) => {
      assert.equal(found.has(`${path}:${index + 1}`), expected, `${path}:${index + 1}`);
    });
    assert.equal(result.summary.probeFailures, 0);
    assert.equal(result.summary.controlMismatches, 0);
    assert.equal(result.summary.glueCoverageGaps, 0);
    assert.ok(result.summary.lexicalSpans > 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
