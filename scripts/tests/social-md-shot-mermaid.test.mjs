// social-md-shot-mermaid.test.mjs — md 长图腿的 mermaid 围栏渲染闸
// (feishu-mermaid batch, 2026-11-19；作者判定：这条腿飞书/微信共用、属产品路径、
//  此前完全无闸 —— 一次性人工产物证据不够，必须有脚本级闸防回归)。
//
// 被测真实腿（前端/引擎零替身）：
//   RichFeishuAdapter.deliverLongShot / 微信桥 → FileLongShot.NodeShot (md 腿)
//   → WebPageShot.NodePlaywright → 子进程 `node scripts/social-md-shot.mjs <in.md> <out.png>`。
//   本闸直跑该脚本生成真实产物 PNG，并读它的截图前渲染报告
//   （env `SOCIAL_MD_RENDER_REPORT=<path>` ⇒ 该脚本写一份 DOM 状态 JSON）。
//
// 判据：
//   R1 夹具里 2 个 ```mermaid 围栏（flowchart + sequenceDiagram）在截图前已换成
//      **真 SVG**：report.mermaidRemaining === 0、report.mermaidRendered === 2、
//      report.mermaidRan === true；
//   R2 普通 ```js 代码块未被误伤：report.plainCodeBlocks === 1 且语言仍是 js；
//   R3 产物体真：after.png 存在、非空、PNG magic；
//   R4 负控（SOCIAL_MD_NO_MERMAID=1）：R1 的谓词**必须红** —— mermaidRemaining
//      === 2、mermaidRendered === 0、mermaidRan === false，且产物与正常档
//      **逐字节不同**（证明闸对修复敏感、不是恒真的空断言）。
//      走闸判定行：R1 谓词在正常档 true、负控档 false ⇒ 关掉渲染腿的改动会被抓。
//
// 运行：node --test scripts/tests/social-md-shot-mermaid.test.mjs（由既有
//  script-tests 腿 `node --test scripts/tests/*.test.mjs` 自动纳入，不新增 profile 面）。
// 前置：仓根 node_modules/playwright + 已装 chromium（与 tests/*.spec.mjs 同款依赖）。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const SCRIPT = join(ROOT, 'scripts', 'social-md-shot.mjs');

// 夹具与验收夹具同形：flowchart + sequenceDiagram + 一个普通 js 代码块 + 正文。
const FIXTURE_MD = [
  '# Mermaid 出站闸夹具',
  '',
  '正文：下方依次是 flowchart、sequenceDiagram、普通 js 代码块。',
  '',
  '```mermaid',
  'flowchart TD',
  '  A[开始 Start] --> B{是否 OK?}',
  '  B -- 是 --> C[处理 Process]',
  '  B -- 否 --> D[结束 End]',
  '```',
  '',
  '```mermaid',
  'sequenceDiagram',
  '  participant U as 用户',
  '  participant S as 服务端',
  '  U->>S: 请求 Request',
  '  S-->>U: 应答 Response',
  '```',
  '',
  '```js',
  "const greeting = 'hello <world> & friends';",
  'console.log(greeting);',
  '```',
  '',
  '尾段落。',
  '',
].join('\n');

/** 直跑产品腿一枪：`node scripts/social-md-shot.mjs <in.md> <out.png>`，打开渲染报告。 */
function shoot(dir, { outName, env = {} }) {
  const mdPath = join(dir, 'fixture.md');
  writeFileSync(mdPath, FIXTURE_MD);
  const out = join(dir, outName);
  const report = join(dir, `${outName}.report.json`);
  const r = spawnSync(process.execPath, [SCRIPT, mdPath, out], {
    cwd: ROOT,
    encoding: 'utf8',
    timeout: 90_000,
    env: {
      ...process.env,
      // 正常档明确关掉负控开关（判定是严格 `=== '1'`，空串即关）。
      SOCIAL_MD_NO_MERMAID: '',
      SOCIAL_MD_RENDER_REPORT: report,
      ...env,
    },
  });
  return { r, out, report };
}

const readReport = (path) => JSON.parse(readFileSync(path, 'utf8'));
const pngOk = (path) =>
  statSync(path).size > 0 && readFileSync(path).subarray(0, 8)
    .equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));

/** R1 的判定谓词：mermaid 围栏已在截图前全部换成真 SVG。 */
const mermaidFullyRendered = (rep) =>
  rep.mermaidRan === true && rep.mermaidRemaining === 0 && rep.mermaidRendered === 2;

test('md 长图腿：mermaid 围栏换成 SVG、普通代码块未误伤（含负控）', () => {
  const dir = mkdtempSync(join(tmpdir(), 'social-md-shot-mermaid-'));
  try {
    const normal = shoot(dir, { outName: 'after.png' });
    assert.equal(normal.r.status, 0,
      `正常档脚本必须 exit 0（stderr: ${normal.r.stderr}）`);
    const okRep = readReport(normal.report);

    // R1 两个 mermaid 围栏 → 真 SVG（截图前）
    assert.equal(okRep.mermaidBlocksTotal, 2, '夹具应恰好 2 个 mermaid 围栏');
    assert.equal(okRep.mermaidRan, true, 'mermaid pass 应已运行');
    assert.equal(okRep.mermaidRemaining, 0, '截图前不应残留 pre>code.language-mermaid');
    assert.equal(okRep.mermaidRendered, 2, '两个 mermaid 块应各出一张 SVG');

    // R2 普通 js 代码块未被误伤
    assert.equal(okRep.plainCodeBlocks, 1, '普通代码块应原样保留 1 个');
    assert.deepEqual(okRep.plainCodeLanguages, ['language-js'], '保留的应是 js 代码块');

    // R3 真实产物
    assert.ok(pngOk(normal.out), `产物应是有效 PNG：${normal.out}`);

    // R4 负控：同一个谓词必须红 —— 证明闸对「渲染腿被关掉」敏感
    const neg = shoot(dir, { outName: 'negative.png', env: { SOCIAL_MD_NO_MERMAID: '1' } });
    assert.equal(neg.r.status, 0, '负控档脚本仍应 exit 0（降级不崩）');
    const negRep = readReport(neg.report);
    assert.equal(negRep.mermaidRan, false, '负控应跳过 mermaid pass');
    assert.equal(negRep.mermaidRemaining, 2, '负控应残留 2 个源码块');
    assert.equal(negRep.mermaidRendered, 0, '负控不应产出任何 SVG');
    assert.equal(mermaidFullyRendered(negRep), false,
      'R1 谓词在负控档必须为假（否则闸是恒真的空断言）');
    assert.equal(mermaidFullyRendered(okRep), true, 'R1 谓词在正常档必须为真');

    // 产物级对照：关掉渲染 ⇒ 出图确实不同（不是只改了报告）
    const after = readFileSync(normal.out);
    const negative = readFileSync(neg.out);
    assert.ok(!after.equals(negative), '正常档与负控档产物必须逐字节不同');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
