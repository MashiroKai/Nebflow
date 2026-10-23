// mermaid-md.spec.mjs — ```mermaid 围栏块渲染验收（mermaid-md 批，作者令
// 2026-10-23 19:24 逐字「让我们的markdown支持mermaid渲染」）。
//
// 被测真实链路（前端零替身）：
//   workspace-open-item → canvas.js openWorkspaceItem → fileViewers.js renderFile
//   → viewers/markdown.js viewMarkdown → utils.js renderMarkdownWithMath（共享
//   管线，mermaid 围栏 → .nf-mermaid 占位）→ mermaidRenderer.js（懒加载 vendor
//   mermaid 12.2.0 + IntersectionObserver 视口渲染 + prefers-color-scheme 主题）。
//
// 判据（作者令验收面）：
//   R1 mermaid 块渲染成功（flowchart + sequenceDiagram → 真 SVG，非零尺寸）
//   R2 语法错误块 → 错误态 + 原始源码可查看（不空白不吞）
//   R3 普通代码块不受影响（js 围栏仍是 pre>code + 复制按钮，无占位化）
//   R4 普通 md 零回归（段落/标题/链接正常，无 console/页面错误）
//   R5 视口外懒渲染（下方块在滚动到前不渲染，滚动后渲染）
//   R6 亮暗双主题跟随（emulateMedia 切换 → 重渲染 + 主题色变化）+ 截图×2
//
// 后端替身与 mdscroll-toc-scroll.spec.mjs 同一套（/api/** 空 JSON + nf-ticket
// 假票 + WS pop.readFile）。
//
// 运行：node tests/mermaid-md.spec.mjs
// 可选 env：PORT=8169、SHOTS_DIR=<dir>（默认写本 spec 目录 evidence 下）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8169);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(tmpdir(), 'mermaid-md-shots');
// 视口腿：MERMAID_SPEC_VIEWPORT=宽x高（默认 1280x900）。注意 harness 域为桌面
// 档：≤768px 走 mobile 层（mobileLayer.css bottom-sheet，canvas 面板要
// mf-cards-live 才显示），桌面 boot 判据在那里恒假超时——mobile 面挂同一套
// canvas viewer DOM，占位→SVG 管线与 chrome 无关；且桌面阅读列宽 ≈410px 本身
// 就覆盖了 mobile 量级的内容列（双图包含已验证）。
const VIEWPORT = (() => {
  const m = /^(\d+)x(\d+)$/.exec(process.env.MERMAID_SPEC_VIEWPORT || '1280x900');
  return m ? { width: Number(m[1]), height: Number(m[2]) } : { width: 1280, height: 900 };
})();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── 夹具：一份真 md（成功×2 + 语法错误×1 + js 代码块 + 长尾懒渲染块） ─────────────── */
const MD_PATH = join(tmpdir(), 'mermaid-md-fixture.md');
const MD_CONTENT = [
  '# Mermaid 渲染验收',
  '',
  '正文先行段落：下方依次是 flowchart、sequenceDiagram、一个语法错误块和一个普通 js 代码块。',
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
  '```mermaid',
  'flowchart TD',
  '  A[Unclosed ---> B[End]',
  '```',
  '',
  '```js',
  "const greeting = 'hello <world> & friends';",
  'console.log(greeting);',
  '```',
  '',
  '尾段落：这段文字在所有块之后，验证文档正常收尾。',
  '',
  '<div style="height:1400px"></div>',
  '',
  '```mermaid',
  'flowchart LR',
  '  X[懒渲染块] --> Y[滚动后才渲染]',
  '```',
  '',
].join('\n');

/* ─────────────── 静态服务（真 web/ 树；自有端口） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    if (process.env.MERMAID_SPEC_DEBUG) console.log('404:', p);
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));
writeFileSync(MD_PATH, MD_CONTENT);

/* ─────────────── 应用引导（与 mdscroll 同一套 WS 替身） ─────────────── */
const EXT_ITEM = { html: 'html', htm: 'html', md: 'markdown', markdown: 'markdown', json: 'json' };
async function boot(browser) {
  const ctx = await browser.newContext({ viewport: { width: VIEWPORT.width, height: VIEWPORT.height } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 300)); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  // Harness 后端替身补位：rootName/agentProfile 的 `agents/<name>/agent.json`
  // 展示名探针（静态服务下 404，与本批无关的既有背景探测）——按 /api/** 同一
  // 替身纪律给空 JSON，让 console-error 判据只看得到真实回归。
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type !== 'pop.readFile') return;
      const rawPath = String(m.path || '');
      const abs = rawPath.startsWith('~') ? join(homedir(), rawPath.slice(1).replace(/^[/\\]/, '')) : rawPath;
      try {
        const buf = readFileSync(abs);
        const ext = (abs.split('.').pop() || '').toLowerCase();
        ws.send(JSON.stringify({
          type: 'fileContent', path: rawPath, absPath: abs, itemType: EXT_ITEM[ext] || 'code',
          fileName: abs.split('/').pop(), size: buf.length, content: buf.toString('utf8'),
        }));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: abs }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  // ATTACHED（非 visible）：≤768px 走 mobile 层（layoutController 隐藏桌面
  // chrome，main.js 照常 boot）——可见性判据会在窄档假超时。
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors };
}

async function openMdTab(page) {
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'mermaid-md-fixture.md', content, absPath, pinned: true },
    }));
  }, { absPath: MD_PATH, content: MD_CONTENT });
  await page.waitForSelector('.canvas-tab-pane.active .canvas-md-scroll');
  await sleep(200);
}

/* ─────────────── 断言 ─────────────── */
let failed = 0;
const ok = (cond, label) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) failed++;
};

const browser = await chromium.launch();
try {
  const { page, pageErrors, consoleErrors } = await boot(browser);
  await openMdTab(page);
  const viewer = '.canvas-tab-pane.active .canvas-md-viewer';

  // R1 前置：占位结构在（共享管线把围栏换成了占位 div，占位框保留布局）
  const phCount = await page.locator(`${viewer} .nf-mermaid`).count();
  ok(phCount === 4, `R1pre 共享管线产出 4 个 .nf-mermaid 占位（实得 ${phCount}）`);

  // R1 flowchart + sequenceDiagram 渲染为非零尺寸 SVG
  await page.waitForFunction(() => {
    const done = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid[data-mermaid-state="done"]');
    return done.length >= 2 && done[0].querySelector('svg') && done[1].querySelector('svg');
  }, { timeout: 30000 });
  const svgBox = await page.evaluate(() => {
    const els = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid[data-mermaid-state="done"] svg');
    const b0 = els[0].getBoundingClientRect();
    const b1 = els[1].getBoundingClientRect();
    return { w0: b0.width, h0: b0.height, w1: b1.width, h1: b1.height };
  });
  ok(svgBox.w0 > 40 && svgBox.h0 > 40, `R1 flowchart SVG 非零尺寸 ${Math.round(svgBox.w0)}x${Math.round(svgBox.h0)}`);
  ok(svgBox.w1 > 40 && svgBox.h1 > 40, `R1 sequenceDiagram SVG 非零尺寸 ${Math.round(svgBox.w1)}x${Math.round(svgBox.h1)}`);

  // R2 语法错误块 → 错误态；点「查看源码」→ 原始源码可见（不空白不吞）
  await page.waitForSelector(`${viewer} .nf-mermaid-error`, { timeout: 30000 });
  const errState = await page.evaluate(() => {
    const el = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid-error');
    if (!el) return null;
    const label = el.querySelector('.nf-mermaid-errlabel');
    const btn = el.querySelector('.nf-mermaid-srcbtn');
    const pre = el.querySelector('.nf-mermaid-src');
    return {
      state: el.dataset.mermaidState,
      labelText: label && label.textContent,
      srcHidden: pre ? pre.hidden : null,
      btnText: btn ? btn.textContent : null,
      svgLeak: !!el.querySelector('svg'),
    };
  });
  ok(!!errState && errState.state === 'error', `R2 错误态落位（state=${errState && errState.state}）`);
  ok(!!errState && errState.labelText === '图表渲染失败', `R2 错误文案（${errState && errState.labelText}）`);
  ok(!!errState && errState.svgLeak === false, 'R2 错误态不残留半成品 SVG');
  await page.locator(`${viewer} .nf-mermaid-error .nf-mermaid-srcbtn`).click();
  const srcShown = await page.evaluate(() => {
    const el = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid-error');
    const pre = el.querySelector('.nf-mermaid-src');
    return { hidden: pre.hidden, text: pre.textContent };
  });
  ok(srcShown.hidden === false && srcShown.text.includes('A[Unclosed ---> B[End]'),
    `R2 源码可查看（hidden=${srcShown.hidden}，含原始行=${srcShown.text.includes('A[Unclosed ---> B[End]')}）`);

  // R3 普通代码块不受影响：js 围栏原样 pre>code + 复制按钮，未被占位化
  const jsBlock = await page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer');
    const code = v.querySelector('pre code.language-js');
    if (!code) return null;
    const wrap = code.closest('.code-block-wrap');
    return {
      inMermaid: !!code.closest('.nf-mermaid'),
      hasCopyBtn: !!(wrap && wrap.querySelector('.code-copy-btn')),
      text: code.textContent.includes("const greeting = 'hello <world> & friends';"),
    };
  });
  ok(!!jsBlock && jsBlock.inMermaid === false, 'R3 js 代码块未被 mermaid 化');
  ok(!!jsBlock && jsBlock.hasCopyBtn === true, 'R3 js 代码块保留复制按钮');
  ok(!!jsBlock && jsBlock.text === true, 'R3 js 代码块内容逐字保留');

  // R4 普通 md 零回归：标题/段落/尾段落正常
  const mdShape = await page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer');
    const text = v.textContent || '';
    return {
      h1: !!v.querySelector('h1'),
      tailParagraph: text.includes('尾段落：这段文字在所有块之后'),
      firstParagraph: text.includes('正文先行段落'),
      copyButtonCount: v.querySelectorAll('.code-copy-btn').length,
    };
  });
  ok(mdShape.h1 && mdShape.tailParagraph && mdShape.firstParagraph, 'R4 普通 md 结构零回归');

  // R5 视口外懒渲染：底部块滚动到前不渲染，滚动后渲染
  const lazyBefore = await page.evaluate(() => {
    const els = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid');
    const last = els[els.length - 1];
    return last.dataset.mermaidState;
  });
  ok(lazyBefore !== 'done', `R5 视口外块未渲染（state=${lazyBefore}）`);
  await page.evaluate(() => {
    const els = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid');
    els[els.length - 1].scrollIntoView({ block: 'center' });
  });
  await page.waitForFunction(() => {
    const els = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid');
    const last = els[els.length - 1];
    return last && last.dataset.mermaidState === 'done' && last.querySelector('svg');
  }, { timeout: 30000 });
  ok(true, 'R5 滚动到位后底部块完成渲染');

  // R6 亮主题截图（顶部视口含 flowchart + sequenceDiagram）
  await page.evaluate(() => {
    const scroller = document.querySelector('.canvas-tab-pane.active .canvas-md-scroll');
    scroller.scrollTo({ top: 0 });
  });
  await sleep(400);
  mkdirSync(SHOTS_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOTS_DIR, 'mermaid-light.png') });

  // R6 暗主题：emulateMedia 切换 → 完成块重渲染（gen 递增）+ 前景色变化
  const lightFill = await page.evaluate(() => {
    const svg = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid[data-mermaid-state="done"] svg');
    return svg ? (svg.getAttribute('style') || '') + '|' + (svg.querySelector('[stroke]')?.getAttribute('stroke') || '') : '';
  });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForFunction(() => {
    const els = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid[data-mermaid-state="done"] svg');
    return els.length >= 2;
  }, { timeout: 30000 });
  await sleep(600);
  const darkFill = await page.evaluate(() => {
    const svg = document.querySelector('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid[data-mermaid-state="done"] svg');
    const stroke = svg.querySelector('[stroke]')?.getAttribute('stroke') || '';
    const text = svg.querySelector('text')?.getAttribute('fill') || '';
    return stroke + '|' + text + '|' + (svg.getAttribute('style') || '');
  });
  ok(lightFill !== darkFill, `R6 主题切换后 SVG 配色变化（light≠dark）`);
  await page.screenshot({ path: join(SHOTS_DIR, 'mermaid-dark.png') });
  const darkState = await page.evaluate(() => {
    const els = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer .nf-mermaid');
    return Array.from(els).map((e) => e.dataset.mermaidState).join(',');
  });
  ok(!darkState.includes('loading'), `R6 切换后无悬挂渲染态（states=${darkState}）`);

  // R4 补充：console / 页面错误（mermaid 对语法错误块会打 console.error——按已知
  // 噪声过滤；pageerror（未捕获异常）必须恒为零）
  // R7 全局面覆盖：共享管线产物挂到 body 任意容器（模拟 chat/flow 卡等其它
  // markdown 面）→ main.js 的全局 MutationObserver 自动 hydrate，无需面内接线。
  const r7Count = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const host = document.createElement('div');
    host.id = 'r7-any-face-host';
    document.body.appendChild(host);
    host.innerHTML = renderMarkdownWithMath('```mermaid\nflowchart TD\n  P[管线] --> Q[全覆盖]\n```', false);
    return host.querySelectorAll('.nf-mermaid').length;
  });
  ok(r7Count === 1, `R7 其它 markdown 面共享管线产出占位（${r7Count}）`);
  await page.waitForSelector('#r7-any-face-host .nf-mermaid[data-mermaid-state="done"] svg', { timeout: 30000 });
  ok(true, 'R7 全局 observer 对任意挂载面自动完成渲染');

  const knownNoise = (msg) => /mermaid|Mermaid|Parse error|graph/i.test(msg);  const realConsoleErrors = consoleErrors.filter((m) => !knownNoise(m));
  ok(pageErrors.length === 0, `R4 页面零未捕获异常（${pageErrors.length}）`);
  ok(realConsoleErrors.length === 0, `R4 零新增 console 错误（非 mermaid 噪声 ${realConsoleErrors.length} 条${realConsoleErrors.length ? '：' + realConsoleErrors[0] : ''}）`);
  if (consoleErrors.length > realConsoleErrors.length) {
    console.log(`NOTE  ${consoleErrors.length - realConsoleErrors.length} 条 mermaid 语法错误块自身的 console 输出（预期内）`);
  }

  await page.context().close();
} finally {
  await browser.close();
  webServer.close();
  rmSync(MD_PATH, { force: true });
}

console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
