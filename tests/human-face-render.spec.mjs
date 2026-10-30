// human-face-render.spec.mjs — ```human 过滤层渲染验收（human-face 批，作者批
// 2026-10-30：人面子强制＋框外抽屉折叠；设计稿 docs/design/20261030_human-
// readable-presentation.zh-CN.md §6 二期）。
//
// 被测真实链路（前端零替身，照 mermaid-md.spec.mjs 同一套替身纪律）：
//   workspace-open-item → canvas.js → viewers/markdown.js → utils.js
//   renderMarkdownWithMath（共享管线 ```human pass：_humanFencesBalanced 流式闸
//   → _transformHumanFace 卡片/抽屉变换）+ termCite.js（body 级 MutationObserver
//   把卡内 [[词条]] 接成 hover 引用，termTip.js 气泡 API 兜底腿）。
//
// 判据：
//   R1 框内卡片化：```human 围栏 → .nf-human-card，卡内是渲染过的 markdown
//      （h2/strong），不是灰底代码块
//   R2 框外折叠：围栏外内容进 <details class="nf-human-details">，默认收起，
//      summary 带「细节 (N)」，点开可见 Findings 全文
//   R3 无围栏文档零回归（无卡片无抽屉，标题/代码块复制键照常）
//   R4 流式中间态：围栏未闭合 → 不变换、不炸，内容以普通代码块可见
//   R5 三面覆盖：canvas md viewer（真实引导链）＋聊天气泡宿主＋flow/report
//      卡片宿主（共享管线挂载，全局 observer 生效）——五面同管线，测三面
//      代表（wiki docs 与 viewer 同链路）
//   R6 术语 hover：卡内 [[词条]] → a.nf-wikilink-term（data-wiki-term-fallback）
//      → hover 出 .nf-term-tip（词条名+定义，API 兜底腿恰一次）
//   R7 亮暗双主题：卡片背景随 prefers-color-scheme 翻转 + 截图×2
//   R8 全进程零 console 错误 / 零 pageerror
//
// 运行：node tests/human-face-render.spec.mjs
// 可选 env：PORT=8173、SHOTS_DIR=<dir>（默认 evidence/20261030_human-face-render/shots）

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
const PORT = Number(process.env.PORT || 8173);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'evidence', '20261030_human-face-render', 'shots');
const VIEWPORT = { width: 1280, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── 夹具：人面子文档（围栏 + 框外细节 + 普通代码块） ─────────────── */
const MD_PATH = join(tmpdir(), 'human-face-fixture.md');
const MD_CONTENT = [
  '# 人面子渲染验收',
  '',
  '```human',
  '## 印象',
  '结论：过滤层上线，五面一次收。',
  '',
  '**关键数字**：改 3 文件 · 断言全绿 · 耗时 1h',
  '',
  '保留术语引用：[[反馈回路]] 在本卡内应可 hover。',
  '```',
  '',
  '## 发现',
  '',
  '机器面细节第一段：完整过程、代码锚、验收读数照常写全。',
  '',
  '```js',
  "const machine = 'face';",
  '```',
  '',
  '机器面细节第二段：收尾。',
  '',
].join('\n');

const NOFENCE_PATH = join(tmpdir(), 'human-face-nofence.md');
const NOFENCE_CONTENT = [
  '# 无围栏回归文档',
  '',
  '普通段落：没有人面子围栏的文档必须原样呈现。',
  '',
  '```js',
  "const plain = 'code';",
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
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));
writeFileSync(MD_PATH, MD_CONTENT);
writeFileSync(NOFENCE_PATH, NOFENCE_CONTENT);

/* ─────────────── 应用引导（与 mermaid-md 同一套 WS 替身） ─────────────── */
const EXT_ITEM = { html: 'html', htm: 'html', md: 'markdown', markdown: 'markdown', json: 'json' };
async function boot(browser) {
  const ctx = await browser.newContext({ viewport: VIEWPORT });
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
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  // 词条注册表兜底腿替身：[[反馈回路]] 命中（后注册者优先于 /api/** 通配）。
  let termApiHits = 0;
  await page.route('**/api/wiki/term*', (r) => {
    termApiHits++;
    r.fulfill({
      json: {
        found: true, resolved: true,
        term: { name: '反馈回路', definition: '失败触发重试、重试放大负载的恶性循环。', rel: 'docs/glossary.md', anchor: '#^retry-storm' },
      },
    });
  });
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
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors, termApi: () => termApiHits };
}

async function openMdTab(page, absPath, content) {
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: absPath.split('/').pop(), content, absPath, pinned: true },
    }));
  }, { absPath, content });
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
  const { page, pageErrors, consoleErrors, termApi } = await boot(browser);
  await openMdTab(page, MD_PATH, MD_CONTENT);
  const viewer = '.canvas-tab-pane.active .canvas-md-viewer';

  // ── R1 框内卡片化（viewer 面 = canvas md viewer / wiki docs 共用链路） ──
  const card = await page.evaluate((sel) => {
    const v = document.querySelector(sel);
    const c = v && v.querySelector('.nf-human-card');
    if (!c) return null;
    return {
      h2: !!c.querySelector('h2'),
      h2Text: c.querySelector('h2') && c.querySelector('h2').textContent,
      strong: !!c.querySelector('strong'),
      isCode: c.tagName === 'PRE' || !!c.querySelector(':scope > pre'),
    };
  }, viewer);
  ok(!!card, 'R1 viewer 面产出 .nf-human-card');
  ok(!!card && card.h2 && /印象/.test(card.h2Text || ''), `R1 卡内 markdown 真渲染（h2=印象，实得 ${card && card.h2Text}）`);
  ok(!!card && card.strong === true, 'R1 卡内关键数字行（strong）渲染');
  ok(!!card && card.isCode === false, 'R1 卡片不是代码块');

  // ── R2 框外折叠抽屉（标题段与 Findings 段各一个抽屉，照设计「框外全折叠」） ──
  const drawer = await page.evaluate((sel) => {
    const v = document.querySelector(sel);
    const ds = v ? Array.from(v.querySelectorAll('.nf-human-details')) : [];
    if (!ds.length) return null;
    const d = ds[ds.length - 1];
    return {
      count: ds.length,
      allClosed: ds.every((x) => !x.open),
      summaryText: ds.map((x) => x.querySelector('summary') && x.querySelector('summary').textContent).join(' / '),
      // 收起态渲染判据用 innerText（textContent 无视 <details> 折叠，恒含全文）
      findingsHidden: !d.innerText.includes('机器面细节第一段'),
    };
  }, viewer);
  ok(!!drawer, 'R2 viewer 面产出 .nf-human-details');
  ok(!!drawer && drawer.allClosed === true, `R2 全部抽屉默认收起（${drawer && drawer.count} 个：标题段 + Findings 段）`);
  ok(!!drawer && drawer.count >= 2 && drawer.summaryText.split(' / ').every((s) => /细节 \(\d+\)/.test(s || '')),
    `R2 summary 带行数（${drawer && drawer.summaryText}）`);
  ok(!!drawer && drawer.findingsHidden === true, 'R2 收起态看不到机器面正文');
  await page.evaluate((sel) => {
    const v = document.querySelector(sel);
    const ds = Array.from(v.querySelectorAll('.nf-human-details'));
    ds[ds.length - 1].querySelector('summary').click();
  }, viewer);
  const drawerOpen = await page.evaluate((sel) => {
    const v = document.querySelector(sel);
    const ds = Array.from(v.querySelectorAll('.nf-human-details'));
    const d = ds[ds.length - 1];
    return d && d.open && d.innerText.includes('机器面细节第一段') && d.innerText.includes('机器面细节第二段');
  }, viewer);
  ok(drawerOpen === true, 'R2 点开后 Findings 全文可见');
  const jsInDrawer = await page.evaluate((sel) => {
    const v = document.querySelector(sel);
    const ds = Array.from(v.querySelectorAll('.nf-human-details'));
    const d = ds[ds.length - 1];
    const code = d && d.querySelector('pre code.language-js');
    return !!(code && code.closest('.code-block-wrap') && code.closest('.code-block-wrap').querySelector('.code-copy-btn'));
  }, viewer);
  ok(jsInDrawer === true, 'R2 抽屉内普通代码块保留复制键（无回归）');

  // ── R3 无围栏文档零回归 ──
  await openMdTab(page, NOFENCE_PATH, NOFENCE_CONTENT);
  const noFence = await page.evaluate(() => {
    const panes = document.querySelectorAll('.canvas-tab-pane.active .canvas-md-viewer');
    const v = panes[panes.length - 1];
    return {
      noCard: !v.querySelector('.nf-human-card'),
      noDetails: !v.querySelector('.nf-human-details'),
      h1: !!v.querySelector('h1'),
      para: v.textContent.includes('普通段落：没有人面子围栏'),
      copyBtn: !!v.querySelector('pre code.language-js'),
    };
  });
  ok(noFence.noCard && noFence.noDetails, 'R3 无围栏文档无卡片无抽屉');
  ok(noFence.h1 && noFence.para && noFence.copyBtn, 'R3 标题/段落/代码块原样呈现');

  // ── R4 流式中间态（围栏未闭合：整段等闭合口径，不炸不变换） ──
  const streamMid = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const partial = '开头段落可见。\n\n```human\n## 印象\n流式中的半句结论';
    let html = '';
    let threw = null;
    try { html = renderMarkdownWithMath(partial, false, { cache: false }); } catch (e) { threw = String(e); }
    const host = document.createElement('div');
    host.id = 'r4-stream-host';
    host.innerHTML = html;
    document.body.appendChild(host);
    return {
      threw,
      hasCard: !!host.querySelector('.nf-human-card'),
      hasDetails: !!host.querySelector('.nf-human-details'),
      codeVisible: host.textContent.includes('流式中的半句结论'),
      openerVisible: host.textContent.includes('开头段落可见'),
    };
  });
  ok(streamMid.threw === null, 'R4 流式中间态渲染不抛异常');
  ok(streamMid.hasCard === false && streamMid.hasDetails === false, 'R4 未闭合围栏不变换（无卡片无抽屉）');
  ok(streamMid.codeVisible && streamMid.openerVisible, 'R4 中间态内容全部可见（框外可见兜底）');
  // 闭合后续帧：变换一步生效
  const streamDone = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const full = '开头段落可见。\n\n```human\n## 印象\n流式中的半句结论\n```\n\n收尾细节段。';
    const host = document.getElementById('r4-stream-host');
    host.innerHTML = renderMarkdownWithMath(full, false, { cache: false });
    return {
      hasCard: !!host.querySelector('.nf-human-card'),
      hasDetails: !!host.querySelector('.nf-human-details'),
    };
  });
  ok(streamDone.hasCard && streamDone.hasDetails, 'R4 围栏闭合后卡片+抽屉一步生效');

  // ── R5 三面覆盖：聊天气泡宿主 + flow/report 卡片宿主（共享管线挂载） ──
  const faceHosts = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const md = '前置说明句。\n\n```human\n## 印象\n气泡面结论一句。保留术语：[[反馈回路]]。\n```\n\n气泡面机器细节段。';
    // 类名只作面标注（管线产物与类无关；.bubble 的 max-width 会压窄宿主，
    // 故不挂真实气泡类，宽度自控保证证据截图可读）。
    const mk = (id, cls) => {
      const host = document.createElement('div');
      host.id = id;
      host.dataset.face = cls;
      host.style.flex = '0 0 380px';
      host.style.width = '380px';
      host.style.margin = '10px';
      host.innerHTML = renderMarkdownWithMath(md, false);
      document.body.appendChild(host);
      return host;
    };
    const chat = mk('r5-chat-host', 'chat-bubble');
    const flow = mk('r5-flow-host', 'flow-card');
    const report = mk('r5-report-host', 'report-card');
    return ['r5-chat-host', 'r5-flow-host', 'r5-report-host'].map((id) => {
      const h = document.getElementById(id);
      return {
        id,
        card: !!h.querySelector('.nf-human-card'),
        details: !!h.querySelector('.nf-human-details'),
        detailsClosed: !!(h.querySelector('.nf-human-details') && !h.querySelector('.nf-human-details').open),
      };
    });
  });
  for (const f of faceHosts) {
    ok(f.card && f.details && f.detailsClosed, `R5 ${f.id}（${f.id === 'r5-chat-host' ? '聊天气泡面' : f.id === 'r5-flow-host' ? 'flow 卡面' : 'report 卡面'}）卡片+收起抽屉齐`);
  }

  // ── R6 术语 hover（卡内 [[反馈回路]] → 气泡，API 兜底腿） ──
  await page.waitForSelector('#r5-chat-host a.nf-wikilink-term[data-wiki-term-fallback="反馈回路"]', { timeout: 10000 });
  const citeArm = await page.evaluate(() => {
    const a = document.querySelector('#r5-chat-host a.nf-wikilink-term');
    return { text: a.textContent, kind: a.dataset.wikiTermKind };
  });
  ok(citeArm.text === '反馈回路' && citeArm.kind === 'fallback', `R6 词条引用 linkify（text=${citeArm.text} kind=${citeArm.kind}）`);
  const hitsBefore = termApi();
  await page.hover('#r5-chat-host a.nf-wikilink-term');
  await page.waitForSelector('.nf-term-tip', { state: 'visible', timeout: 10000 });
  const tip = await page.evaluate(() => {
    const el = document.querySelector('.nf-term-tip');
    return {
      name: el.querySelector('.tt').textContent,
      def: el.querySelector('.ts').textContent,
    };
  });
  ok(tip.name === '反馈回路', `R6 hover 气泡出泡（词条名=${tip.name}）`);
  ok(/重试放大负载/.test(tip.def), `R6 气泡带定义（${tip.def.slice(0, 20)}…）`);
  ok(termApi() - hitsBefore === 1, `R6 API 兜底腿恰一次（Δ=${termApi() - hitsBefore}）`);
  await page.mouse.move(5, 5);
  await sleep(300);
  const tipHidden = await page.evaluate(() => {
    const el = document.querySelector('.nf-term-tip');
    return !el || el.style.display === 'none';
  });
  ok(tipHidden, 'R6 移开后气泡收起');

  // ── R7 亮暗双主题 + 截图（聊天气泡宿主元素级取景，抽屉点开态） ──
  await page.evaluate(() => {
    const d = document.getElementById('r5-chat-host').querySelector('.nf-human-details > summary');
    if (d) d.click();
  });
  await sleep(300);
  mkdirSync(SHOTS_DIR, { recursive: true });
  const bgLight = await page.evaluate(() => {
    const c = document.querySelector('#r5-chat-host .nf-human-card');
    return c ? getComputedStyle(c).backgroundColor : null;
  });
  const shotClip = async (name) => {
    await page.evaluate(() => {
      document.getElementById('r4-stream-host').style.display = 'none';
      document.getElementById('r5-chat-host').scrollIntoView({ block: 'start' });
    });
    await sleep(200);
    // 视口级截图（宿主在 app 外壳之外的 body 流里，clip 对未被 app CSS 约束的
    // 自由宿主不可靠——整视口取景稳）。
    await page.screenshot({ path: join(SHOTS_DIR, name) });
  };
  await shotClip('human-face-light.png');
  await page.emulateMedia({ colorScheme: 'dark' });
  await sleep(600);
  const bgDark = await page.evaluate(() => {
    const c = document.querySelector('#r5-chat-host .nf-human-card');
    return c ? getComputedStyle(c).backgroundColor : null;
  });
  ok(!!bgLight && !!bgDark && bgLight !== bgDark, `R7 卡片背景随主题翻转（light=${bgLight} dark=${bgDark}）`);
  await shotClip('human-face-dark.png');

  // ── R8 零错误铁律 ──
  ok(pageErrors.length === 0, `R8 页面零未捕获异常（${pageErrors.length}）`);
  ok(consoleErrors.length === 0, `R8 零 console 错误（${consoleErrors.length}${consoleErrors.length ? '：' + consoleErrors[0] : ''}）`);

  await page.context().close();
} finally {
  await browser.close();
  webServer.close();
  rmSync(MD_PATH, { force: true });
  rmSync(NOFENCE_PATH, { force: true });
}

console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
