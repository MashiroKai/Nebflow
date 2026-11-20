// social-md-shot-mermaid.test.mjs — md 长图腿的 mermaid 围栏渲染闸
// (feishu-mermaid batch, 2026-11-19；作者判定：这条腿飞书/微信共用、属产品路径、
//  此前完全无闸 —— 一次性人工产物证据不够，必须有脚本级闸防回归)。
//
// 被测真实腿（前端/引擎零替身）：
//   RichFeishuAdapter.deliverLongShot / 微信桥 → FileLongShot.NodeShot (md 腿)
//   → WebPageShot.NodePlaywright → 子进程 `node scripts/social-md-shot.mjs <in.md> <out.png>`。
//   本闸**直跑该脚本生成真实产物 PNG**（正常档 + 负控档各一张），并读它的截图前
//   渲染报告（env `SOCIAL_MD_RENDER_REPORT=<path>` ⇒ 该脚本写一份 DOM 状态 JSON）。
//
// 🔴 CI 可用性纪律（作者令 2026-11-19）：本闸真跑 Chromium，而 CI 的 static 腿是
//    **全新检出、可能没装浏览器** ⇒ 缺件时必须**显式 `t.skip('<原因>')`**（跳过可见），
//    绝不静默 exit 0 掩盖。探测三件：① playwright 包在；② `chromium.executablePath()`
//    指向的文件在；③ 真拉一次 chromium 成功（覆盖「装了但起不来」）。任一缺 ⇒ SKIP。
//    本机（开发机）为何能真跑：仓根 `node_modules/playwright`（devDependency，`npm ci`
//    装上）且已 `npx playwright install chromium`，浏览器落在
//    `~/Library/Caches/ms-playwright/chromium-*/…`（macOS）。
//
// 判据（顺序即优先级：行为级在前，报告面在后）：
//   B1 正常档 exit 0、产物是有效 PNG；
//   B2 负控档（SOCIAL_MD_NO_MERMAID=1）exit 0（降级不崩）、产物是有效 PNG；
//   B3 **行为级核心**：两档产物**逐字节不同** —— 渲染腿被整条移除时（正常档≡负控档）
//      这条先红，不依赖任何报告面；
//   R1 夹具里 2 个 ```mermaid 围栏（flowchart + sequenceDiagram）在截图前已换成
//      **真 SVG**：report.mermaidRemaining === 0、report.mermaidRendered === 2、
//      report.mermaidRan === true；
//   R2 普通 ```js 代码块未被误伤：report.plainCodeBlocks === 1 且语言仍是 js；
//   R4 负控的报告面：mermaidRan === false、mermaidRemaining === 2、mermaidRendered === 0，
//      且 R1 谓词**必须为假**（证明报告面断言对修复敏感、不是恒真空断言）。
//
// 运行：node --test scripts/tests/social-md-shot-mermaid.test.mjs（由既有
//  script-tests 腿 `node --test scripts/tests/*.test.mjs` 自动纳入，不新增 profile 面）。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const SCRIPT = join(ROOT, 'scripts', 'social-md-shot.mjs');
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/**
 * 浏览器级前置探测。缺件 ⇒ 返回 `{ ok:false, reason }`，调用方 `t.skip(reason)`
 * （跳过在测试报告里可见，退出码 0；不是静默 exit 0，也不是假装通过）。
 * 探测独立于被测脚本（直接起 chromium），所以脚本级回归仍会在真跑档被抓。
 */
async function probeBrowser() {
  const pkgDir = join(ROOT, 'node_modules', 'playwright');
  if (!existsSync(pkgDir)) {
    return { ok: false, reason: `playwright 未安装（缺 ${pkgDir}；先 npm ci）` };
  }
  /** @type {any} */
  let chromium;
  try {
    chromium = createRequire(join(ROOT, 'package.json'))('playwright').chromium;
  } catch (e) {
    return { ok: false, reason: 'playwright 不可加载：' + (e && e.message) };
  }
  let exe = '';
  try {
    exe = chromium.executablePath();
  } catch (e) {
    return { ok: false, reason: 'chromium.executablePath() 失败：' + (e && e.message) };
  }
  if (!exe || !existsSync(exe)) {
    return { ok: false, reason: `chromium 未安装（期望 ${exe}；跑 npx playwright install chromium）` };
  }
  try {
    const browser = await chromium.launch({ headless: true });
    await browser.close();
  } catch (e) {
    return { ok: false, reason: 'chromium 无法启动：' + (e && e.message) };
  }
  return { ok: true, exe };
}

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

const readBytes = (path) => readFileSync(path);
const pngOk = (path) =>
  statSync(path).size > 0 && readBytes(path).subarray(0, 8).equals(PNG_MAGIC);
const readReport = (path) => JSON.parse(readFileSync(path, 'utf8'));

/** R1 的判定谓词：mermaid 围栏已在截图前全部换成真 SVG。 */
const mermaidFullyRendered = (rep) =>
  rep.mermaidRan === true && rep.mermaidRemaining === 0 && rep.mermaidRendered === 2;

test('md 长图腿：mermaid 围栏换成 SVG、普通代码块未误伤（含负控）', async (t) => {
  const probe = await probeBrowser();
  if (!probe.ok) {
    // 显式 SKIP：跳过可见、退出码 0，但不假装通过（绝不静默 exit 0 掩盖）。
    t.skip(`本机缺浏览器级前置，未跑真实产物闸：${probe.reason}`);
    return;
  }
  console.log(`NOTE  浏览器前置就绪（${probe.exe}），本闸真跑产品腿`);

  const dir = mkdtempSync(join(tmpdir(), 'social-md-shot-mermaid-'));
  try {
    // ── 正常档 ──
    const normal = shoot(dir, { outName: 'after.png' });
    assert.equal(normal.r.status, 0, `正常档必须 exit 0（stderr: ${normal.r.stderr}）`);
    assert.ok(pngOk(normal.out), `正常档产物应是有效 PNG：${normal.out}`);
    const afterBytes = readBytes(normal.out);

    // ── 负控档：关掉 mermaid pass ──
    const neg = shoot(dir, { outName: 'negative.png', env: { SOCIAL_MD_NO_MERMAID: '1' } });
    assert.equal(neg.r.status, 0, `负控档仍应 exit 0（降级不崩；stderr: ${neg.r.stderr}）`);
    assert.ok(pngOk(neg.out), `负控档产物应是有效 PNG：${neg.out}`);
    const negativeBytes = readBytes(neg.out);

    // ── B3 行为级核心（不依赖报告面）──
    assert.ok(!afterBytes.equals(negativeBytes),
      '关掉 mermaid pass 后产物必须逐字节不同 —— 相同即说明渲染腿根本没生效');

    // ── 报告面 ──
    assert.ok(existsSync(normal.report),
      `脚本应写出渲染报告（${normal.report}）：未写出说明本腿没有渲染 pass 的机器可读面`);
    assert.ok(existsSync(neg.report), `负控档也应写出渲染报告（${neg.report}）`);
    const okRep = readReport(normal.report);
    const negRep = readReport(neg.report);

    // R1 两个 mermaid 围栏 → 真 SVG（截图前）
    assert.equal(okRep.mermaidBlocksTotal, 2, '夹具应恰好 2 个 mermaid 围栏');
    assert.equal(okRep.mermaidRan, true, 'mermaid pass 应已运行');
    assert.equal(okRep.mermaidRemaining, 0, '截图前不应残留 pre>code.language-mermaid');
    assert.equal(okRep.mermaidRendered, 2, '两个 mermaid 块应各出一张 SVG');

    // R2 普通 js 代码块未被误伤
    assert.equal(okRep.plainCodeBlocks, 1, '普通代码块应原样保留 1 个');
    assert.deepEqual(okRep.plainCodeLanguages, ['language-js'], '保留的应是 js 代码块');

    // R4 负控的报告面：同一谓词必须为假（闸对「渲染腿被关掉」敏感）
    assert.equal(negRep.mermaidRan, false, '负控应跳过 mermaid pass');
    assert.equal(negRep.mermaidRemaining, 2, '负控应残留 2 个源码块');
    assert.equal(negRep.mermaidRendered, 0, '负控不应产出任何 SVG');
    assert.equal(mermaidFullyRendered(negRep), false,
      'R1 谓词在负控档必须为假（否则报告面是恒真的空断言）');
    assert.equal(mermaidFullyRendered(okRep), true, 'R1 谓词在正常档必须为真');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
