#!/usr/bin/env node
// indent-revert-shots.mjs — 作者 2026-11-24 令文档首行缩进「都去掉」的浏览器闸。
//
// 被测面（同一 harness 跑两次：改前 web 树 vs 改后 web 树）：
//   ① 文档面：`workspace-open-item` → canvas.js → fileViewers.renderFile →
//      viewers/markdown.js viewMarkdown → `.canvas-md-viewer`（真渲染链，真 CSS）
//   ② 消息面：WS 帧桩 historyPage → chat.js 历史腿 → renderMarkdownWithMath →
//      `.bubble.ai`（真渲染链，真 CSS）
//
// 🔴 诚实申报（照 tests/_chat-face-harness.mjs 同一纪律）：本脚本不起后端实例 ——
//   静态面 = 指定的真 web 树（page.route 从磁盘注入），数据面 = WS 帧桩。
//   断言与截图都来自**真实浏览器 + 真实模块图 + 真实 CSS + 真实几何读数**，
//   不是手绘示意图。改前/改后 = 同一脚本 + 两棵 web 树（WEB_ROOT）。
//
// 用法：
//   node tests/indent-revert-shots.mjs                       # 改后（本仓分支）
//   WEB_ROOT=/tmp/indent-before/src/main/resources/web \
//   SHOT_PREFIX=before node tests/indent-revert-shots.mjs    # 改前（main 导出树）
// 可选 env：PORT=8187、SHOTS_DIR=<dir>、VIEWPORT_W/VIEWPORT_H

import { chromium, webkit } from 'playwright';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, normalize, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = process.env.WEB_ROOT
  ? normalize(process.env.WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8187);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, '.nebflow', 'evidence', '20261105_indent-revert');
const PREFIX = process.env.SHOT_PREFIX || 'after';
const VW = Number(process.env.VIEWPORT_W || 1280);
const VH = Number(process.env.VIEWPORT_H || 900);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

/* ─────────────── 夹具：最小样例文档（判据 #2 要求的那份形态）───────────────
   标题 + 两段长文（一段带 `&emsp;&emsp;` 实体起行 = 现行文档写作纪律的形态，
   一段不带）+ 有序/无序列表 + 代码块 + 表格 + 引用块。长段刻意写满折行，
   才能看出「首行缩进 / 续行齐左」。 */
const P_ENTITY =
  '&emsp;&emsp;第一段（源文件以实体起行）：作者令文档段落首行缩进都去掉，'
  + '首行与其余各行齐左。这一段特意写长，用来观察折行后的第二行、第三行是否仍然贴着内容列左缘 —— '
  + '如果出现挂起缩进或整块左移，折行后的行会整体向右漂移，读数与截图都能立刻看出来。';

const P_PLAIN =
  '第二段（源文件不带实体）：这一段同样写长，用来对照「实体起行」与「纯正文」两种源形态是否'
  + '收敛到同一个视觉结果 —— 首行与续行齐左，不做首行缩进，也不做层级下沉。';

const DOC_MD = [
  '# 缩进验收样例（撤回批 2026-11-05）',
  '',
  P_ENTITY,
  '',
  P_PLAIN,
  '',
  '## 列表（不吃首行缩进）',
  '',
  '- 无序项一：列表项自己带项目符号缩进，段落不吃首行缩进。',
  '- 无序项二：这一条也写长一点，观察列表项折行后是否仍然对齐项目符号后的文本列。',
  '',
  '1. 有序项一：编号列与文本列，首行不缩进。',
  '2. 有序项二：同上，折行仍对齐文本列。',
  '',
  '## 代码块（不吃首行缩进）',
  '',
  '```js',
  'const indent = "code block must stay flush with the content column";',
  'console.log(indent);',
  '```',
  '',
  '## 表格（不吃首行缩进）',
  '',
  '| 面 | 改后读数 | 说明 |',
  '| --- | --- | --- |',
  '| 正文段落 | 零首行缩进 | 首行续行齐左 |',
  '| 列表 | 只缩列表符号 | 不吃首行缩进 |',
  '| 代码 / 表格 / 引用 | 零缩进 | 不吃首行缩进 |',
  '',
  '## 引用块（不吃首行缩进）',
  '',
  '> 引用块的第一行紧贴引用竖线后的文本列，没有首行缩进。',
  '> 第二行同样齐左。',
  '',
].join('\n');

/* agent 消息（判据 #3）：多级列表 + 续段，撤回前二级/三级会被额外下沉。 */
const MSG_MD = [
  '这条消息的正文从气泡左缘起排，不做首行缩进，也没有第二层下沉。',
  '',
  '- 一级条目：结论先行',
  '  - 二级条目：证据挂在父条目下',
  '- 一级条目二：门禁读数',
  '',
  '续段：这一段是上一条目的补充说明，撤回前会被当成从属内容再缩一层。',
].join('\n');

const HISTORY = [
  { type: 'user', text: '把缩进优化撤回吧，效果并不好。', timestamp: Date.now() - 60_000 },
  { type: 'ai', text: MSG_MD, timestamp: Date.now() - 30_000 },
];

const DOC_PATH = join(tmpdir(), `indent-revert-${process.pid}`, 'indent-sample.md');
mkdirSync(dirname(DOC_PATH), { recursive: true });
writeFileSync(DOC_PATH, DOC_MD);

/* ─────────────── 静态文件服务（真 web 树）─────────────── */
const webServer = createServer((req, res) => {
  const url = new URL(req.url || '/', BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) { res.writeHead(403).end('forbidden'); return; }
  try {
    const buf = readFileSync(file);
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' }).end(buf);
  } catch {
    res.writeHead(404).end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

async function boot(browser) {
  const ctx = await browser.newContext({ viewport: { width: VW, height: VH }, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);
  await page.route('**/api/nf-file**', async (route) => {
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({
          type: 'historyPage', sessionId: m.sessionId, messages: HISTORY,
          hasMore: false, offset: 0, total: HISTORY.length,
        }));
      }
      if (m.type !== 'pop.readFile') return;
      try {
        const buf = readFileSync(DOC_PATH);
        ws.send(JSON.stringify({
          type: 'fileContent', path: DOC_PATH, absPath: DOC_PATH, itemType: 'markdown',
          fileName: 'indent-sample.md', size: buf.length, content: buf.toString('utf8'),
        }));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: DOC_PATH }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 'indent-session', agentName: 'Nebula', title: '缩进撤回批', updatedAt: Date.now() }],
      activeId: 'indent-session', folders: [],
    }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(600);
  return { ctx, page, pageErrors };
}

/* ─────────────── 几何读数（判据读数都在这里）─────────────── */
const MEASURE_DOC = (sel) => {
  const root = document.querySelector(sel);
  if (!root) return null;
  const rootLeft = root.getBoundingClientRect().left;
  /** 首个可见字形相对元素内容左缘的偏移（= 首行实际缩进，单位 px）。 */
  const firstGlyphOffset = (el) => {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let t = walker.nextNode();
    while (t && !(t.nodeValue || '').replace(/[\s\u2003\u3000]/g, '')) t = walker.nextNode();
    if (!t) return null;
    const raw = t.nodeValue || '';
    const i = raw.search(/[^\s\u2003\u3000]/);
    const range = document.createRange();
    range.setStart(t, Math.max(0, i));
    range.setEnd(t, Math.max(0, i) + 1);
    const r = range.getBoundingClientRect();
    return +(r.left - el.getBoundingClientRect().left).toFixed(2);
  };
  const ps = [...root.querySelectorAll(':scope > p')];
  return {
    fontPx: parseFloat(getComputedStyle(root).fontSize),
    paragraphs: ps.map((p) => {
      const lead = ((p.firstChild && p.firstChild.nodeValue) || '').match(/^[\u2003\u3000]+/);
      return {
        chars: (p.textContent || '').replace(/\s+/g, '').length,
        leadingEntitySpaces: lead ? lead[0].length : 0,
        computedTextIndent: getComputedStyle(p).textIndent,
        firstGlyphOffset: firstGlyphOffset(p),
        lineStarts: (() => {
          const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
          const lines = new Map();
          for (let t = walker.nextNode(); t; t = walker.nextNode()) {
            for (let i = 0; i < t.length; i++) {
              if (/\s/.test(t.data[i])) continue;
              const range = document.createRange();
              range.setStart(t, i); range.setEnd(t, i + 1);
              const r = range.getBoundingClientRect();
              const y = Math.round(r.top);
              if (!lines.has(y)) lines.set(y, r.left);
            }
          }
          return [...lines.values()];
        })(),
      };
    }),
    blocks: {
      ul: [...root.querySelectorAll(':scope > ul')].map((el) => +(el.getBoundingClientRect().left - rootLeft).toFixed(2)),
      ol: [...root.querySelectorAll(':scope > ol')].map((el) => +(el.getBoundingClientRect().left - rootLeft).toFixed(2)),
      pre: [...root.querySelectorAll(':scope > pre, :scope > .code-block-wrap')].map((el) => +(el.getBoundingClientRect().left - rootLeft).toFixed(2)),
      table: [...root.querySelectorAll(':scope > table')].map((el) => +(el.getBoundingClientRect().left - rootLeft).toFixed(2)),
      blockquote: [...root.querySelectorAll(':scope > blockquote')].map((el) => +(el.getBoundingClientRect().left - rootLeft).toFixed(2)),
      headings: [...root.querySelectorAll(':scope > h1, :scope > h2')].map((el) => +(el.getBoundingClientRect().left - rootLeft).toFixed(2)),
    },
    blockquoteInnerP: [...root.querySelectorAll(':scope > blockquote p')].map((p) => ({
      computedTextIndent: getComputedStyle(p).textIndent,
      firstGlyphOffset: firstGlyphOffset(p),
    })),
    liTextLeft: [...root.querySelectorAll(':scope > ul > li, :scope > ol > li')].slice(0, 2).map((li) => {
      const textLeft = [...li.childNodes].filter((n) => n.nodeType === 3 && (n.nodeValue || '').trim())[0];
      if (!textLeft) return null;
      const range = document.createRange();
      range.selectNodeContents(textLeft);
      return +(range.getBoundingClientRect().left - rootLeft).toFixed(2);
    }),
  };
};

const MEASURE_MSG = (sel) => {
  const bubble = document.querySelector(sel);
  if (!bubble) return null;
  const cs = getComputedStyle(bubble);
  const ps = [...bubble.querySelectorAll(':scope > p')];
  const bubbleLeft = bubble.getBoundingClientRect().left
    + parseFloat(cs.paddingLeft) + parseFloat(cs.borderLeftWidth || '0');
  const depth = (el) => +(el.getBoundingClientRect().left - bubbleLeft).toFixed(2);
  /** 逐层列表的列表盒 / 文本列左缘（撤回前二级/三级会被额外下沉）。 */
  const levels = [];
  const walk = (ul, lv) => {
    const li = ul.querySelector(':scope > li');
    if (!li) return;
    levels.push({ level: lv, listLeft: depth(ul), liLeft: depth(li) });
    const nested = li.querySelector(':scope > ul');
    if (nested && lv < 4) walk(nested, lv + 1);
  };
  bubble.querySelectorAll(':scope > ul').forEach((ul) => walk(ul, 1));
  const indentOwners = [bubble, ...bubble.querySelectorAll('*')]
    .filter((el) => parseFloat(getComputedStyle(el).textIndent) !== 0)
    .map((el) => ({ tag: el.tagName, cls: String(el.className), textIndent: getComputedStyle(el).textIndent }));
  return {
    fontPx: parseFloat(cs.fontSize),
    paragraphTextIndents: ps.map((p) => getComputedStyle(p).textIndent),
    paragraphFirstGlyph: ps.map((p) => +(p.getBoundingClientRect().left - bubbleLeft).toFixed(2)),
    listLevels: levels,
    anyTextIndentInBubble: indentOwners,
  };
};

/* ─────────────── 执行 ─────────────── */
mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await (process.env.BROWSER === 'webkit' ? webkit : chromium).launch();
const report = { webRoot: WEB, prefix: PREFIX, viewport: { w: VW, h: VH } };
try {
  const { page, pageErrors } = await boot(browser);

  // ① 文档面：走真渲染链开一个 md 页签
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'indent-sample.md', content, absPath, pinned: true },
    }));
  }, { absPath: DOC_PATH, content: DOC_MD });
  if (VW <= 600) await page.evaluate(async () => (await import('/js/mobile/navController.js')).dockTo('canvas'));
  await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll', { timeout: 20000 });
  await sleep(800);
  const DOC_SEL = '.canvas-tab-pane.active .canvas-md-viewer';
  report.doc = await page.evaluate(MEASURE_DOC, DOC_SEL);
  const docPane = page.locator('.canvas-tab-pane.active .canvas-md-scroll').first();
  await docPane.screenshot({ path: join(SHOTS_DIR, `${PREFIX}_doc.png`) });

  // ② 消息面：历史里那条 ai 消息（多级列表）
  const MSG_SEL = '.bubble.ai';
  await page.waitForSelector(MSG_SEL, { timeout: 20000 });
  await sleep(400);
  report.msg = await page.evaluate(MEASURE_MSG, MSG_SEL);
  const bubble = page.locator(MSG_SEL).first();
  await bubble.scrollIntoViewIfNeeded();
  await sleep(150);
  await bubble.screenshot({ path: join(SHOTS_DIR, `${PREFIX}_msg.png`) });
  const row = page.locator('.row:has(.bubble.ai)').last();
  await row.screenshot({ path: join(SHOTS_DIR, `${PREFIX}_msg_row.png`) });

  report.pageErrors = pageErrors;
  writeFileSync(join(SHOTS_DIR, `${PREFIX}_readings.json`), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
  assert.ok(report.doc.paragraphs.length >= 2, 'expected rendered document paragraphs');
  for (const p of report.doc.paragraphs) {
    assert.equal(parseFloat(p.computedTextIndent), 0, 'document paragraph must have zero text-indent');
    assert.equal(p.leadingEntitySpaces, 0, 'leading source spaces must be normalized');
    assert.ok(p.lineStarts.length >= 2, 'test paragraph must wrap');
    assert.ok(Math.abs(p.lineStarts[0] - p.lineStarts[1]) < 1, 'first and continuation line must align');
  }
} finally {
  await browser.close();
  await new Promise((r) => webServer.close(r));
}
