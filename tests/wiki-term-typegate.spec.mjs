#!/usr/bin/env node
// wiki-term-typegate.spec.mjs — md/html 类型硬约束闸·词条同闸面验收
// （词条系统第三批 A 组 + 词条同闸；设计稿 §8 回写，调研稿 §9-1/§9-7）。
//
// 覆盖：
//   U1  白名单单点：mdHtmlGate.js 四扩展名枚举 + isMdHtmlFile 判据
//       （放行/大小写不敏感/伪扩展 .mdx/无扩展名/空/null 拒）
//   U2  单点纪律静态钉：askDock.js 与 viewers/markdown.js 经 import 消费
//       本模块，不内联第二份扩展名表
//   D1  md 页签（.md）：[[GCR]] 渲染为 a.nf-wikilink（解析面在场——阳性对照）
//   D2  非白名单路径（.txt absPath 强制走 markdown viewer 的再映射假设面）：
//       [[GCR]] 按普通文本渲染——零 a.nf-wikilink、字面文本保留、点击无深链
//   D3  .markdown 大写变体（.MARKDOWN）放行解析（大小写不敏感端到端）
//
// 运行：node tests/wiki-term-typegate.spec.mjs
// 可选 env：PORT（默认 8217）、TYPEGATE_SHOTS_DIR（截图目录）。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const GATE_SRC = join(WEB_DIR, 'js/mdHtmlGate.js');
// package.json 无 "type": "module"——node 直 import 会按 CJS 解。data-URL 导入
// 真实文件字节（零转译），判据单点的 node 面单测与浏览器消费同一份源码。
const { MD_HTML_EXTENSIONS, isMdHtmlFile, extOf } = await import(
  'data:text/javascript;base64,' + Buffer.from(readFileSync(GATE_SRC, 'utf8')).toString('base64')
);
const PORT = Number(process.env.PORT || 8217);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.TYPEGATE_SHOTS_DIR || resolve(HERE, `../evidence/wiki-term-typegate/shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);
  if (cond) pass++; else fail++;
}

// ── U1 判据单点（node 直 import，零 DOM 依赖）───────────────────────────────
ok('U1a 四扩展名封闭枚举逐字（与 AskSessions.AskableExtensions 跨语言对齐）',
  JSON.stringify(MD_HTML_EXTENSIONS) === JSON.stringify(['.md', '.markdown', '.html', '.htm']),
  JSON.stringify(MD_HTML_EXTENSIONS));

const u1Yes = ['/vault/笔记.md', '/a.MD', '/a.Markdown', '/a.MARKDOWN', '/p.html', '/p.HTML', '/p.htm', '/p.HTM', 'x.md'];
const u1No = ['/a.txt', '/a.pdf', '/a.scala', '/a.png', '/a', '/a.mdx', '/xmd', '/a.htm2', '', null, undefined, 42];
ok('U1b 白名单内（含大小写变体/相对路径）全部放行', u1Yes.every((p) => isMdHtmlFile(p) === true), u1Yes.join(','));
ok('U1c 白名单外（含伪扩展/无扩展名/空/null/非串）全部拒绝', u1No.every((p) => isMdHtmlFile(p) === false), JSON.stringify(u1No));

// U1d 路径归一（第三批显式要求）：?query / #fragment 先剥离，目录名里的点不算
// 扩展名。扩展名表本身也要显式导出，node 面单测才能复核（见 U1a）。
const u1dYes = [
  '/vault/笔记.md?tab=1', '/vault/笔记.md#^anchor', '/vault/笔记.md?x=1#frag',
  '/vault/page.html?project=alpha', '/workspace/docs/README.MD?v=2', '  /vault/笔记.markdown#frag  ',
];
const u1dNo = ['/vault/notes.txt?tab=1', '/vault/paper.pdf#page=3', '/vault/readme?tab=1', '/vault/noext#frag', '/vault/a.b/note'];
ok('U1d 带 ?query / #fragment 的路径先归一再判定（白名单仍放行、白名单外仍拒、目录点不误判）',
  u1dYes.every((p) => isMdHtmlFile(p) === true) && u1dNo.every((p) => isMdHtmlFile(p) === false),
  JSON.stringify({ yes: u1dYes, no: u1dNo }));
ok('U1e extOf 归一读数（与 scala MdHtmlGate.extOf 同语义）',
  extOf('/vault/笔记.MD?x=1') === '.md' && extOf('/vault/noext?tab=1') === '' && extOf('') === '',
  JSON.stringify([extOf('/vault/笔记.MD?x=1'), extOf('/vault/noext?tab=1'), extOf('')]));

// ── U2 单点纪律静态钉 ───────────────────────────────────────────────────────
const askDockSrc = readFileSync(join(WEB_DIR, 'js/askDock.js'), 'utf8');
const mdViewerSrc = readFileSync(join(WEB_DIR, 'js/viewers/markdown.js'), 'utf8');
ok('U2a askDock.js 消费 mdHtmlGate.js 单点（import + 赋值处/守卫调用点在场）',
  /from '\.\/mdHtmlGate\.js'/.test(askDockSrc) &&
  /isMdHtmlFile\(entry\.absPath\)/.test(askDockSrc) &&
  askDockSrc.includes('isMdHtmlFile(currentFile)'),
  '');
ok('U2b viewers/markdown.js 消费 mdHtmlGate.js 单点（import + 渲染闸调用点在场）',
  /from '\.\.\/mdHtmlGate\.js'/.test(mdViewerSrc) &&
  /isMdHtmlFile\(absPath\)/.test(mdViewerSrc),
  '');

// ── 浏览器 DOM 面（真实源码树 + mock WS/REST，canvas-ask-dock 同款骨架）────
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, r));

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} })); // graph 空载：链接 unresolved 但仍成锚（解析面在场即够）
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(600);

  const WIKI_DOC = '# 词条闸测试\n\n正文引用了 [[GCR]] 词条。\n';

  async function openDoc(name, content) {
    const absPath = `/tmp/wiki-typegate/${name}`;
    await page.evaluate(({ name, absPath, content }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content, absPath, pinned: true },
      }));
    }, { name, absPath, content });
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
    await sleep(300);
    return absPath;
  }

  /** 页签内 wikilink 解析读数：锚计数 + 字面文本残留 + 首个锚 href 形态。 */
  const wikiRead = (absPath) => page.evaluate((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    if (!pane) return null;
    const links = Array.from(pane.querySelectorAll('a.nf-wikilink'));
    return {
      anchors: links.length,
      unresolved: links.filter((a) => a.classList.contains('nf-wikilink-unresolved')).length,
      literalText: pane.textContent.includes('[[GCR]]'),
      hrefSample: links.length ? links[0].getAttribute('href') : null,
    };
  }, `file:${absPath}`);

  // D1 阳性对照：.md 页签——[[GCR]] 必须解析成锚（词条回退 F1 落地前的文档链
  // 解析面；空 graph ⇒ unresolved 锚 = 解析 pass 实跑的钉）。
  const absMd = await openDoc('词条闸测试.md', WIKI_DOC);
  await page.waitForFunction((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    return pane && pane.querySelectorAll('a.nf-wikilink').length > 0;
  }, `file:${absMd}`, { timeout: 8000 }).catch(() => {});
  const d1 = await wikiRead(absMd);
  ok('D1 .md 页签 [[GCR]] 解析为 wikilink 锚（解析面在场）', !!d1 && d1.anchors >= 1, JSON.stringify(d1));

  // D2 词条同闸：同内容、absPath 换 .txt——闸内零解析（renderWikilinks 不跑），
  // [[GCR]] 按普通文本渲染、零锚、点击无深链。
  const absTxt = await openDoc('词条闸测试.txt', WIKI_DOC);
  await sleep(800); // 给异步渲染腿足够时间——断言的是「不解析」，必须等过头
  const d2 = await wikiRead(absTxt);
  ok('D2 .txt 页签（强制 markdown viewer）零 wikilink 锚——[[词条]] 不解析', !!d2 && d2.anchors === 0, JSON.stringify(d2));
  ok('D2b .txt 页签字面文本保留（普通文本渲染）', !!d2 && d2.literalText === true, JSON.stringify(d2));
  await page.screenshot({ path: join(SHOTS_DIR, 'd2-txt-term-plaintext.png') });

  // D3 大小写不敏感端到端：.MARKDOWN 放行解析。
  const absMm = await openDoc('词条闸测试.MARKDOWN', WIKI_DOC);
  await page.waitForFunction((id) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${id}"]`);
    return pane && pane.querySelectorAll('a.nf-wikilink').length > 0;
  }, `file:${absMm}`, { timeout: 8000 }).catch(() => {});
  const d3 = await wikiRead(absMm);
  ok('D3 .MARKDOWN 页签（大写扩展名）解析放行（大小写不敏感端到端）', !!d3 && d3.anchors >= 1, JSON.stringify(d3));

  ok('T0 页面零 pageerror', errors.length === 0, errors.join(' | ').slice(0, 400));
  await ctx.close();
} finally {
  await browser.close();
  server.close();
}

console.log(`\n=== wiki-term-typegate: ${pass} pass, ${fail} fail ===`);
process.exit(fail === 0 ? 0 : 1);
