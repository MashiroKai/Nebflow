// wiki-docs-human-face.spec.mjs — wiki-docs skill 人面子条款（条款⑤）校验。
// human-face 批 2026-10-30：SKILL.md 增「Human face」条款（报告头四段式
// Impression→关键数字→Findings→遗留 + 附录 A 模板入 SKILL），双副本纪律
// ——引擎种子与运行时实例（<dataRoot>/plugins/…）sha256 一致。
//
// 判据：
//   C1 种子 SKILL.md 含条款⑤全部要件（human-face 条款、四段式、模板围栏、
//      术语禁入、no validator rule yet 明示）
//   C2 运行时实例副本（~/.stand/plugins/…）与种子逐字节一致
//   C3 plugin-repos 副本与种子逐字节一致（防同步源回灌漂移）
//
// 运行：node tests/wiki-docs-human-face.spec.mjs

import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const SEED = resolve(ROOT, 'src/main/resources/seed/plugins/visual-report/skills/wiki-docs/SKILL.md');
const RUNTIME = resolve(homedir(), '.stand/plugins/visual-report/skills/wiki-docs/SKILL.md');
const PLUGIN_REPO = resolve(homedir(), '.stand/plugin-repos/visual-report/skills/wiki-docs/SKILL.md');

let failed = 0;
const ok = (cond, label) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) failed++;
};
const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');

const seedText = readFileSync(SEED, 'utf8');

// C1 条款⑤要件
ok(/Human face \(人面子, human-face batch 2026-10-30\)/.test(seedText),
  'C1 条款⑤人面子条款存在（Mandatory clauses #5）');
ok(/The five clauses below are author mandates/.test(seedText),
  'C1 条款计数更新为五条');
ok(/Impression → key numbers → Findings pointer → open items/.test(seedText),
  'C1 报告头四段式（Impression→关键数字→Findings→遗留）');
ok(/```human/.test(seedText) && /## Impression/.test(seedText) && /\*\*Key numbers\*\*/.test(seedText),
  'C1 附录 A 模板入 SKILL（```human 围栏 + Impression + Key numbers）');
ok(/!\[\[key-chart\.png\]\]/.test(seedText),
  'C1 关键图表义务（≥1 图入 fence）');
ok(/no validator rule yet/.test(seedText),
  'C1 校验器存在性规则「另议」明示（不静默宣称机检）');
ok(/jargon ban/i.test(seedText) && /\[\[Name\]\]/.test(seedText),
  'C1 术语禁入清单 + 保留术语 [[Name]] hover 兜底');
ok(/merged into main\s*\(queued\)/.test(seedText),
  'C1 禁入清单人话写法（merge queue → merged into main (queued)）');

// C2/C3 双副本（+同步源）逐字节一致
ok(sha(RUNTIME) === sha(SEED), `C2 运行时实例副本与种子一致（${sha(SEED).slice(0, 12)}…）`);
ok(sha(PLUGIN_REPO) === sha(SEED), `C3 plugin-repos 副本与种子一致（${sha(SEED).slice(0, 12)}…）`);

console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
