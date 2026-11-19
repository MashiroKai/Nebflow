// wiki-docs-human-face.spec.mjs — wiki-docs skill 卡片面 / 图随文档走 / 以图为主
// 三条条款的契约面校验。
//
// 沿革：human-face 批 2026-10-30 立起（条款⑤ 报告头四段式 + 双副本纪律）；
// 2026-11-05 两批改写它：
//   · 「图不显示」批：条款⑤ 的关键图**不再写在 ```human 围栏里**（渲染器
//     `expandImageEmbeds()` 不展开围栏内嵌入 ⇒ 围栏里的图永远不出图），关键图
//     行移到围栏外、路径写成 vault 相对含批次子目录；条款⑥ 增两个绑定点
//     （R17 bare-name-embed / R18 image-in-human-fence）。
//   · 「以图为主」批（作者令 21:24）：条款⑬ 立规（文档必须含图、逐条细节必须
//     折叠），校验器加 R19 image-required / R20 stacked-detail。
//   本 spec 的旧断言（「五条mandates」「![[key-chart.png]] 在 fence 内」
//   「no validator rule yet」）正是把那个 bug 钉成契约的那几句，随批改写。
//
// 判据：
//   C1 条款⑤ 卡片面在位 + 关键图行在围栏外（模板里不再有围栏内嵌图）
//   C2 条款⑥ 两个渲染器绑定点在位（R17/R18）
//   C3 条款⑬ 以图为主在位（R19/R20 + 折叠形态 + 例外声明标记）
//   C4 校验器侧 R17-R20 真的注册了（规则表 + 阈值逐字）
//   C5 双副本纪律：运行时实例与 plugin-repos 副本与种子逐字节一致
//
// 运行：node tests/wiki-docs-human-face.spec.mjs

import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const SKILL_REL = 'skills/wiki-docs/SKILL.md';
const SCRIPT_REL = 'skills/wiki-docs/scripts/check_wiki_docs.py';
const SEED = resolve(ROOT, 'src/main/resources/seed/plugins/visual-report', SKILL_REL);
const SEED_SCRIPT = resolve(ROOT, 'src/main/resources/seed/plugins/visual-report', SCRIPT_REL);
const RUNTIME = resolve(homedir(), '.stand/plugins/visual-report', SKILL_REL);
const RUNTIME_SCRIPT = resolve(homedir(), '.stand/plugins/visual-report', SCRIPT_REL);
const PLUGIN_REPO = resolve(homedir(), '.stand/plugin-repos/visual-report', SKILL_REL);
const PLUGIN_REPO_SCRIPT = resolve(homedir(), '.stand/plugin-repos/visual-report', SCRIPT_REL);

let failed = 0;
const ok = (cond, label) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) failed++;
};
const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');

const seedText = readFileSync(SEED, 'utf8');
const scriptText = readFileSync(SEED_SCRIPT, 'utf8');

// ── C1 条款⑤ 卡片面：关键图行在卡片**里**（卡片出图批 2026-11-05 反转）──
ok(/Card face — a fenced/.test(seedText), 'C1 条款⑤ 卡片面在位（Mandatory clauses #5）');
ok(/结论 → 关键数字 → 图 →/.test(seedText), 'C1 卡片四段式（结论 → 关键数字 → 图 → 待你决定的事）');
ok(/the chart LINE itself goes \*\*inside the card\*\*/.test(seedText),
  'C1 条款⑤ 明写「关键图行写在卡片体里」（首屏可见）');
ok(/only the card body is on[\s\S]{0,60}first screen/.test(seedText),
  'C1 条款⑤ 给出理由：只有卡片体在首屏（围栏外折进抽屉）');
ok(/!\[\[assets\/<batch>\/key-chart\.png\]\]/.test(seedText),
  'C1 模板的关键图写成 vault 相对含批次子目录');
ok(!/^!\[\[key-chart\.png\]\]/m.test(seedText),
  'C1 模板里不许有裸名嵌入（裸名 = 位置依赖）');

// ── C2 条款⑥ 两个渲染器绑定点 ───────────────────────────────────────
ok(/The renderer's\s+`imageCandidates\(\)`/.test(seedText) || /imageCandidates\(\)/.test(seedText),
  'C2 条款⑥ 绑定点⑤：渲染器候选表（不递归子目录）在位');
ok(/Figure lines go INSIDE the ```human card/.test(seedText),
  'C2 条款⑥ 绑定点⑥：图行写进卡片体（vault 相对路径）在位');
ok(!/Never put an image embed inside a ```human fence/.test(seedText)
   && !/No image embed inside a human fence/.test(seedText),
  'C2 旧禁令（围栏内禁图）已撤下——两条规则不再打架');
ok(/bare-name-embed/.test(seedText) && /fence-embed-vault-relative/.test(seedText),
  'C2 两个规则名（bare-name-embed / fence-embed-vault-relative）写进纪律');

// ── C3 条款⑬ 以图为主 ────────────────────────────────────────────────
ok(/Figures are the document; words annotate them/.test(seedText),
  'C3 条款⑬ 以图为主在位（author ruling 2026-11-05 21:24）');
ok(/<details><summary>过程<\/summary>/.test(seedText),
  'C3 折叠规范形态（`<details><summary>过程</summary>`）逐字写进纪律');
ok(/wiki-figure-exempt/.test(seedText) && /wiki-fold-exempt/.test(seedText),
  'C3 例外必须显式声明（figure-exempt / fold-exempt 标记）');
ok(/image-required/.test(seedText) && /stacked-detail/.test(seedText),
  'C3 两条机器面（image-required / stacked-detail）写进纪律');

// ── C4 校验器侧真的注册了（条款不是纸面） ────────────────────────────
ok(/IMAGE_EMBED_STAMP\s*=\s*"20261105_130000"/.test(scriptText),
  'C4 R17/R18 生效界常量在位');
ok(/FIGURE_STAMP\s*=\s*"20261105_212400"/.test(scriptText),
  'C4 R19/R20 生效界常量在位');
ok(/STACKED_RUN_MIN\s*=\s*12/.test(scriptText), 'C4 R20 阈值逐字 = 12 连续行');
ok(/"R17", "bare-name-embed"/.test(scriptText), 'C4 RULES 表登记 R17 bare-name-embed');
ok(/"R18", "fence-embed-vault-relative"/.test(scriptText),
  'C4 RULES 表登记 R18 fence-embed-vault-relative');
ok(/def fence_embed_path_ok\(/.test(scriptText),
  'C4 R18 的路径形态判据函数在位（vault 相对 = 含目录）');
ok(/"R19", "image-required"/.test(scriptText), 'C4 RULES 表登记 R19 image-required');
ok(/"R20", "stacked-detail"/.test(scriptText), 'C4 RULES 表登记 R20 stacked-detail');
ok(/def renderer_candidates\(/.test(scriptText) && /def renderer_hit\(/.test(scriptText),
  'C4 渲染器候选表镜像函数在位（R17 不是正则瞎猜）');
ok(/def stacked_detail_runs\(/.test(scriptText), 'C4 逐条细节扫描函数在位（R20）');

// ── C5 双副本纪律（种子 = 运行时实例 = plugin-repos）─────────────────
ok(sha(RUNTIME) === sha(SEED), `C5 运行时实例 SKILL.md 与种子一致（${sha(SEED).slice(0, 12)}…）`);
ok(sha(PLUGIN_REPO) === sha(SEED), `C5 plugin-repos SKILL.md 与种子一致（${sha(SEED).slice(0, 12)}…）`);
ok(sha(RUNTIME_SCRIPT) === sha(SEED_SCRIPT),
  `C5 运行时实例 check_wiki_docs.py 与种子一致（${sha(SEED_SCRIPT).slice(0, 12)}…）`);
ok(sha(PLUGIN_REPO_SCRIPT) === sha(SEED_SCRIPT),
  `C5 plugin-repos check_wiki_docs.py 与种子一致（${sha(SEED_SCRIPT).slice(0, 12)}…）`);

console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
