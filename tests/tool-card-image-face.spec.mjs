// tool-card-image-face.spec.mjs — 工具卡图片引用面回归批（作者 2026-11-04 12:46
// 令：「图片是还是会存在工具卡片泄露出来的问题」）。
//
// 语义裁定（本批执行单③定语义）：工具卡 = 摘要面 —— 命令/参数 + 行数 + 状态。
// 图片（及任何媒体）**不许**在卡内内嵌渲染；折叠态预览与展开态全文都一样。
// 卡内图片一律折叠为「🖼 图片 ×N」引用（.tool-img-ref），点击走灯箱单点。
//
// 泄露面 = 真实会话的 44 条 `![…](/Users|file:|data:image…)` 工具结果（Read md
// 返图 / Bash cat md / Write 落 md 再回读，全量扫描 2026-11-04）。改前红：这些
// 内容经 mountToolCardBody → toolFoldHtml → renderHighlightedContent 落空后走
// 遗留腿 renderMarkdownWithMath（utils.js:1392 fallback）—— marked 把图片语法
// 变成真 <img>（nf-zoom-img），直接内嵌在卡片里。改后绿：零 <img>、引用件在场。
//
// 判据面 = 渲染 DOM（真实 renderTool / renderToolPending 链路，toolline-detail
// harness 同款自举，零引擎零 stub）。
//
// 跑法：
//   npx playwright test tests/tool-card-image-face.spec.mjs
// 红腿（对修复前树；never checkout main；红绿双向全绿 = 修复面证据）：
//   mkdir -p /tmp/nb-toolimg-base
//   git -C <repo> archive <base-sha> src/main/resources/web | tar -x -C /tmp/nb-toolimg-base
//   NB_UX_ROOT=/tmp/nb-toolimg-base PIN_EXPECT=red npx playwright test tests/tool-card-image-face.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/tool-card-image-face/harness.html';
const HARNESS_FILE = path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, ''));
const SHOT_DIR = process.env.NB_TOOLIMG_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261104_tool-card-image-face');

// 被测树 = NB_UX_ROOT（红腿基线树）或本 worktree（绿腿）。
const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;
const RED = process.env.PIN_EXPECT === 'red';

/** 新面钉：绿腿要求新行为在场；红腿（PIN_EXPECT=red）要求缺陷在场。 */
function pinNewFace(holds, desc) {
  if (RED) expect(holds, `红腿要求缺陷在场：${desc}`).toBe(false);
  else expect(holds, `绿腿要求新行为在场：${desc}`).toBe(true);
}
/** 守卫钉：两棵树上都必须成立。 */
function pinGuard(holds, desc) {
  expect(holds, `守卫钉（${RED ? '红腿' : '绿腿'}）：${desc}`).toBe(true);
}

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  if (!fs.existsSync(HARNESS_FILE)) throw new Error(`harness missing: ${HARNESS_FILE}`);
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, SERVE_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    colorScheme: 'dark',
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { page, pageErrors };
}

/** 证据截图（改前/改后同文件名，分目录：before/ after/）——截图先于断言，
 *  红腿失败也要留下现场。 */
async function shot(page, name) {
  // 证据面把卡体展开（.body 默认收起）——截图要拍到「卡里到底有什么」。
  await page.evaluate(() => {
    document.querySelectorAll('#chat .tool-card').forEach(c => {
      const b = c.querySelector('.body');
      if (b && !b.classList.contains('open')) c.click();
    });
  });
  await page.waitForTimeout(150);
  await page.screenshot({
    path: path.join(process.env.NB_TOOLIMG_SHOT_DIR || SHOT_DIR, `${RED ? 'red' : 'green'}-${name}.png`),
    fullPage: true,
  });
}

/** 折叠态（预览面） census：卡内 <img> / 引用件 / 控件。 */
async function previewCensus(page) {
  return page.evaluate(() => window.__imgCensus());
}

    // 展开态 census：点卡（attachToolClick 状态机）后全文槽的 <img> / 引用件。
    async function expandCensus(page) {
      await page.evaluate(() => document.querySelector('.tool-card').click());
      await page.waitForTimeout(120);
      const c = await page.evaluate(() => window.__imgCensus());
      await page.evaluate(() => document.querySelector('.tool-card').click()); // 收起还原，后续用例零串扰
      return c;
    }

test.describe('工具卡图片引用面', () => {
  test('① 折叠态：md 图片语法的工具结果零 <img>，出现「图片 ×N」引用件', async ({ browser }) => {
    const { page, pageErrors } = await newPage(browser);
    // 真实会话族谱（全库 44 条同形扫描）：Read 工具读回一份带 md 图的 md。
    const content = [
      '# 报告',
      '',
      '![修前·正常](/tests/fixtures/tool-card-image-face/f1.png)',
      '',
      '![修后](/tests/fixtures/tool-card-image-face/f2.png)',
      '',
      '正文段落。',
    ].join('\n');
    await page.evaluate(async ([label, content]) => {
      await window.__tcDone(label, `${content.split('\n').length} lines`, content, false,
        JSON.stringify({ file_path: '/tmp/report.md' }));
    }, ['Read(/tmp/report.md)', content]);
    const c = await previewCensus(page);
    await shot(page, '1-fold');
    pinGuard(c.toolCards >= 1, '工具卡在场');
    pinGuard(c.imgs === 0 || RED, `折叠态零 <img>（读数 ${c.imgs}）`);
    pinNewFace(c.imgRefs === 2 && !c.refImgs, `「🖼 图片 ×2」引用件在场且不内嵌（refs=${c.imgRefs} refImgs=${c.refImgs}）`);
    expect(pageErrors, '零页面错误').toEqual([]);
    await page.context().close();
  });

  test('② 展开态：全文槽同样零 <img>，引用件在场（可点开灯箱）', async ({ browser }) => {
    const { page, pageErrors } = await newPage(browser);
    const long = ['# 长', ''].concat(
      Array.from({ length: 20 }, (_, i) => `行 ${i + 1}`),
      ['', '![长文尾图](/tmp/long.png)', ''].join('\n').split('\n'),
    ).join('\n');
    await page.evaluate(async ([label, content]) => {
      await window.__tcDone(label, `${content.split('\n').length} lines`, content, false,
        JSON.stringify({ command: 'cat /tmp/long.md' }));
    }, ['Bash(cat /tmp/long.md)', long]);
    const c = await expandCensus(page);
    pinGuard(c.toolCards >= 1, '工具卡在场');
    pinGuard(c.imgs === 0 || RED, `展开态零 <img>（读数 ${c.imgs}）`);
    pinNewFace(c.imgRefs >= 1 && !c.refImgs, `展开态引用件在场且不内嵌（refs=${c.imgRefs} refImgs=${c.refImgs}）`);
    expect(pageErrors, '零页面错误').toEqual([]);
    await page.context().close();
  });

  test('③ 引用点击 = 灯箱单点（不导航、不内嵌展开）', async ({ browser }) => {
    const { page } = await newPage(browser);
    const content = '![一张图](/tmp/one.png)\n\n正文。';
    await page.evaluate(async ([label, content]) => {
      await window.__tcDone(label, '3 lines', content, false, JSON.stringify({ file_path: '/tmp/one.md' }));
    }, ['Read(/tmp/one.md)', content]);
    const before = page.url();
    // 红腿基线树上没有引用件（缺陷形态 = 真 <img> 内嵌），点不开——先把缺陷
    // 读数落成红腿判据，再走绿腿的点击链。
    const pre = await page.evaluate(() => window.__imgCensus());
    if (RED) {
      pinNewFace(pre.imgRefs === 0 && pre.imgs === 0, `基线树无引用件、图片在 .body 里内嵌（refs=${pre.imgRefs} imgs=${pre.imgs}）`);
      await page.context().close();
      return;
    }
    // 遗留短卡面：.body 默认收起，先点卡展开（attachToolClick 状态机）再点引用件。
    await page.evaluate(() => { const c = document.querySelector('.tool-card'); if (c) c.click(); });
    const hasRef = await page.evaluate(() => !!document.querySelector('.tool-img-ref'));
    expect(hasRef, `引用件必须在场（读数 ${JSON.stringify(pre)}）`).toBe(true);
    await page.evaluate(() => document.querySelector('.tool-img-ref').click());
    await page.waitForTimeout(250);
    const c = await page.evaluate(() => window.__imgCensus());
    await shot(page, '3-lightbox');
    pinNewFace(c.lightbox === true, '灯箱开（图片唯一出口 = 灯箱单点）');
    pinGuard(page.url() === before, '零导航');
    // 灯箱是图片的唯一渲染面（作者可见面）：卡内 0 个 <img>，灯箱内恰 1 个。
    pinNewFace(c.imgs === 0 && c.overlayImgs === 1, `卡内零 <img>、灯箱内恰 1（imgs=${c.imgs} overlay=${c.overlayImgs}）`);
    await page.context().close();
  });

  test('④ 数据 URI 图（含 SVG badge）同样折叠为引用件', async ({ browser }) => {
    const { page, pageErrors } = await newPage(browser);
    const badge = 'data:image/svg+xml;base64,' + Buffer.from(
      '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="24"><rect width="120" height="24" fill="#36a"/></svg>').toString('base64');
    const content = `![badge](${badge})\n\n说明文。`;
    await page.evaluate(async ([label, content]) => {
      await window.__tcDone(label, '3 lines', content, false, JSON.stringify({ file_path: '/tmp/badge.md' }));
    }, ['Read(/tmp/badge.md)', content]);
    const c = await previewCensus(page);
    pinGuard(c.imgs === 0 || RED, `data URI 图零内嵌（读数 ${c.imgs}）`);
    pinNewFace(c.imgRefs === 1 && !c.refImgs, `data URI 图折叠为引用件（refs=${c.imgRefs}）`);
    expect(pageErrors, '零页面错误').toEqual([]);
    await page.context().close();
  });

  test('⑤ 短内容非图片工具卡不折叠（遗留点击开合面零回归）', async ({ browser }) => {
    const { page } = await newPage(browser);
    await page.evaluate(async ([label]) => {
      await window.__tcDone(label, 'done', '普通短输出\n第二行', false, JSON.stringify({ command: 'echo hi' }));
    }, ['Bash(echo hi)']);
    const c = await previewCensus(page);
    await shot(page, '5-noimage');
    pinGuard(c.toolCards >= 1, '工具卡在场');
    pinGuard(c.imgs === 0, '零图零引用（非图片工具卡零新面）');
    pinGuard(c.imgRefs === 0, '非图片卡零引用件');
    await page.context().close();
  });

  test('⑥ 非折叠卡（短内容 + 图片）同样零 <img>、引用件在场', async ({ browser }) => {
    const { page, pageErrors } = await newPage(browser);
    const content = '![shot](/tmp/short.png)\n\n短文。';
    await page.evaluate(async ([label, content]) => {
      await window.__tcDone(label, '3 lines', content, false, JSON.stringify({ file_path: '/tmp/short.md' }));
    }, ['Read(/tmp/short.md)', content]);
    const c = await previewCensus(page);
    pinGuard(c.imgs === 0 || RED, `短卡零 <img>（读数 ${c.imgs}）`);
    pinNewFace(c.imgRefs === 1 && !c.refImgs, `短卡引用件在场（refs=${c.imgRefs}）`);
    expect(pageErrors, '零页面错误').toEqual([]);
    await page.context().close();
  });

  test('⑦ Mail 卡 markdown 正文的图片同样折叠（真·注入路径：卡内 markdown 面）', async ({ browser }) => {
    const { page, pageErrors } = await newPage(browser);
    const md = [
      '收到，交付件如下：',
      '',
      '![修前·正常](/tests/fixtures/tool-card-image-face/f1.png)',
      '![修后·构成](/tests/fixtures/tool-card-image-face/f2.png)',
      '',
      '其余正文。',
    ].join('\n');
    await page.evaluate(async ([label, input]) => {
      await window.__tcDone(label, 'done', 'Message sent to agent', false, input);
    }, ['Mail(→delegate-x)', JSON.stringify({ to: 'delegate-x', message: md })]);
    const c = await previewCensus(page);
    await shot(page, '7-mail');
    pinGuard(c.toolCards >= 1, 'Mail 工具卡在场');
    pinGuard(c.imgs === 0 || RED, `Mail 卡零内嵌 <img>（读数 ${c.imgs}）`);
    pinNewFace(c.imgRefs === 1 && !c.refImgs, `Mail 卡相邻两图合成一件引用（refs=${c.imgRefs} refImgs=${c.refImgs}）`);
    expect(pageErrors, '零页面错误').toEqual([]);
    await page.context().close();
  });

  test('⑧ 越界闸：卡内子元素外接矩形/内容宽度都不越出卡壳（无断点长串）', async ({ browser }) => {
    const { page } = await newPage(browser);
    // 病理样本：折叠预览面 + 命令行长 token（base64 / 一行式脚本 / git patch 形态）。
    // 修前 `cat <400 字无断点>` 在 .detail-cmd 里没有断行机会 ⇒ 逐字横向画到卡外
    // （改前读数：.detail-cmd scrollWidth 2649 / clientWidth 540，越界 2109px）。
    const long = 'X'.repeat(400);
    const content = [long, 'Y'.repeat(300)].concat(Array.from({ length: 10 }, (_, i) => `行 ${i + 1}`)).join('\n');
    await page.evaluate(async ([label, content, input]) => {
      await window.__tcDone(label, content.split('\n').length + ' lines', content, false, input);
    }, ['Bash(cat blob.b64)', content, JSON.stringify({ command: 'cat ' + long })]);
    const geo = await page.evaluate(() => window.__containment());
    await shot(page, '8-contained');
    pinNewFace(geo.escaped.length === 0, `卡内子元素零越界（越界件 ${JSON.stringify(geo.escaped.slice(0, 3))}）`);
    pinGuard(geo.children > 0, '卡内有子元素（判据非空转）');
    pinGuard(geo.previewVisible, '折叠预览面在场（判据打在预览面上）');
    await page.context().close();
  });
});
