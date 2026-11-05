// rich-render.spec.mjs — 围栏渲染族验收（rich-render 批 2026-11-05）。
//
// 作者令三条（逐字）：
//   08:51「我记得有个结构化的语法叫什么？mairid？画框图。我希望你能用起来，同时
//         让我们能够支持渲染这样的内容。」
//   08:53「对，多用框图表格等可视化的输入，还有什么可以直接支持的可视化输出？」
//   08:55「可以，试试内联svg」
//
// 被测真实链路（前端零替身 —— 真模块图、真 CSS、真 vendor 库）：
//   WS historyPage → persistence.js → 聊天气泡（ai / user 两种面）
//     → utils.js renderMarkdownWithMath（共享字符串管线：```mermaid / ```svg
//       围栏 → 占位；$$/$ 数学 → .nf-math 占位）
//     → js/mermaidRenderer.js / js/svgFence.js / js/mathRender.js（fenceKit 水合）
//
// 判据（机械断言，每条独立 PASS/FAIL；失败 ⇒ EXIT 非 0）：
//   ① ```mermaid 围栏 ⇒ 产出 svg 且原 <code> 不再可见
//   ② 解析失败 / 不支持的图类型 ⇒ 回退原码可见 + 错误态在场（不空白）
//   ③ 流式中途零 svg（逐帧 stream:true）⇒ 完成后恰好渲染一次（库 render 计数）
//   ④ 暗色下 svg 内文本色 vs 有效背景对比度 ≥ 4.5（计算值，非目测）
//   ⑤ 图类型清单：flowchart / sequenceDiagram / stateDiagram-v2 / pie / gantt /
//      xychart-beta 各自独立渲染成功（逐类型断言）
//   ⑥ 用户气泡面：已闭合的 ```mermaid 围栏在 .bubble.user 内同样出图
//   ⑦ KaTeX：$$块级 + $行内渲染成功；非法命令 ⇒ 错误态 + 原文可见
//   ⑧ inline SVG 安全审查矩阵（作者 08:55 六项）：script / on* / foreignObject /
//      外链 image / <a href> / 解析失败 —— 逐项断言 + 全程零外网请求
//   ⑨ 亮暗 × 1440/375 四张实拍
//
// 运行：node tests/rich-render.spec.mjs
// 可选 env：SHOTS_DIR=<dir>（默认 reports-evidence/rich-render-support__<本次启动时间戳>/shots）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = resolve(ROOT, 'src/main/resources/web');
const STAMP = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 15);
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'reports-evidence', `rich-render-support__${STAMP}__shots`);
const SID = 'rich-render-session';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
};

/* ─────────────── 夹具：每个能力一条消息（内容标记便于定位） ─────────────── */

const fence = (lang, body) => '```' + lang + '\n' + body + '\n```';

const T_FLOW = 'flowchart TD\n  A[开始 START] --> B{判断?}\n  B -->|是| C[完成 DONE]\n  B -->|否| D[结束]';
const T_SEQ = 'sequenceDiagram\n  participant U as 用户\n  participant S as 服务端\n  U->>S: 请求 REQ\n  S-->>U: 应答 RES';
const T_STATE = 'stateDiagram-v2\n  [*] --> Idle\n  Idle --> Run: start\n  Run --> [*]';
const T_PIE = 'pie title 占比\n  "Dogs" : 40\n  "Cats" : 60';
const T_GANTT = 'gantt\n  dateFormat YYYY-MM-DD\n  section 阶段 A\n  任务一 :a1, 2026-01-01, 3d\n  任务二 :after a1, 2d';
const T_XY = 'xychart-beta\n  title "Revenue"\n  x-axis [Jan, Feb, Mar]\n  y-axis "k" 0 --> 100\n  bar [20, 60, 90]';
const T_BROKEN = 'flowchart TD\n  A[Unclosed ---> B[End]';
const T_UNSUPPORTED = 'nonsenseDiagramType\n  A --> B';

const SVG_SAFE = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 60" width="160" height="60">'
  + '<rect x="4" y="4" width="152" height="52" rx="8" fill="#ffffff" stroke="#334155"/>'
  + '<text x="14" y="36" font-size="14" fill="#0f172a">SAFE-SVG</text></svg>';
const SVG_SCRIPT = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 60" width="160" height="60">'
  + '<script>window.__xssScript = 1;</script>'
  + '<rect x="4" y="4" width="152" height="52" fill="#ffffff" stroke="#334155"/>'
  + '<text x="14" y="36" font-size="13" fill="#0f172a">SCRIPT-STRIPPED</text></svg>';
const SVG_EXTIMG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 60" width="160" height="60">'
  + '<image href="https://evil.example/x.png" width="40" height="40"/>'
  + '<rect x="4" y="4" width="152" height="52" fill="#ffffff" stroke="#334155"/>'
  + '<text x="14" y="36" font-size="13" fill="#0f172a">NO-EXTERNAL-IMAGE</text></svg>';
const SVG_FOREIGNOBJECT = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 60" width="160" height="60">'
  + '<foreignObject x="0" y="0" width="160" height="60"><div xmlns="http://www.w3.org/1999/xhtml">FOREIGN-HTML</div></foreignObject>'
  + '<rect x="4" y="4" width="152" height="52" fill="#ffffff" stroke="#334155"/>'
  + '<text x="14" y="36" font-size="13" fill="#0f172a">NO-FOREIGN-OBJECT</text></svg>';
const SVG_ANCHOR = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 60" width="160" height="60">'
  + '<a href="https://evil.example/click" target="_blank"><text x="14" y="36" font-size="13" fill="#0f172a">LINKTEXT</text></a></svg>';
const SVG_ONATTR = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 60" width="160" height="60">'
  + '<rect x="4" y="4" width="152" height="52" fill="#ffffff" stroke="#334155" onclick="window.__xssOnclick = 1"/>'
  + '<text x="14" y="36" font-size="13" fill="#0f172a">NO-ONATTR</text></svg>';
const SVG_BROKEN = '<svg xmlns="http://www.w3.org/2000/svg"><rect x="1" y="1"';

const NESTED_MD = [
  '段落缩进验收：本段是纯正文段落，首行应缩进两字。第二句用来撑出换行后的齐头效果。',
  '',
  '- 一级项目 A',
  '',
  '  该项的第二段落在列表项之内，首行**不**缩进。',
  '',
  '  - 二级项目 A1',
  '    - 三级项目 A1a',
  '  - 二级项目 A2',
  '',
  '- 一级项目 B',
  '',
  '> 引用块内的段落同样不缩进。',
  '',
  '收尾正文段落：同样带首行缩进。',
].join('\n');

const HISTORY = [
  { type: 'ai', text: NESTED_MD, timestamp: Date.now() - 96000 },
  { type: 'ai', text: '① 流程图：\n\n' + fence('mermaid', T_FLOW), timestamp: Date.now() - 90000 },
  { type: 'ai', text: '② 时序图：\n\n' + fence('mermaid', T_SEQ), timestamp: Date.now() - 88000 },
  { type: 'ai', text: '③ 状态图：\n\n' + fence('mermaid', T_STATE), timestamp: Date.now() - 86000 },
  { type: 'ai', text: '④ 饼图：\n\n' + fence('mermaid', T_PIE), timestamp: Date.now() - 84000 },
  { type: 'ai', text: '⑤ 甘特图：\n\n' + fence('mermaid', T_GANTT), timestamp: Date.now() - 82000 },
  { type: 'ai', text: '⑥ 坐标图：\n\n' + fence('mermaid', T_XY), timestamp: Date.now() - 80000 },
  { type: 'ai', text: '⑦ 语法错误块：\n\n' + fence('mermaid', T_BROKEN), timestamp: Date.now() - 78000 },
  { type: 'ai', text: '⑧ 未知图类型：\n\n' + fence('mermaid', T_UNSUPPORTED), timestamp: Date.now() - 76000 },
  { type: 'user', text: '画给我看：\n' + fence('mermaid', 'flowchart LR\n  U[用户 USERFENCE] --> A[助手]'), timestamp: Date.now() - 74000 },
  { type: 'ai', text: '⑨ 数学：块级 $$\\int_0^1 x^2\\,dx = \\frac{1}{3}$$ 与行内 $E=mc^2$ 各一。', timestamp: Date.now() - 72000 },
  { type: 'ai', text: '⑩ 数学错误：$\\badmacro{x}$ 应回退原文。', timestamp: Date.now() - 70000 },
  { type: 'ai', text: '⑪ 内联 SVG：\n\n' + fence('svg', SVG_SAFE), timestamp: Date.now() - 68000 },
  { type: 'ai', text: '⑫ SVG script：\n\n' + fence('svg', SVG_SCRIPT), timestamp: Date.now() - 66000 },
  { type: 'ai', text: '⑬ SVG 外链图：\n\n' + fence('svg', SVG_EXTIMG), timestamp: Date.now() - 64000 },
  { type: 'ai', text: '⑭ SVG foreignObject：\n\n' + fence('svg', SVG_FOREIGNOBJECT), timestamp: Date.now() - 62000 },
  { type: 'ai', text: '⑮ SVG 链接：\n\n' + fence('svg', SVG_ANCHOR), timestamp: Date.now() - 60000 },
  { type: 'ai', text: '⑯ SVG on* 属性：\n\n' + fence('svg', SVG_ONATTR), timestamp: Date.now() - 58000 },
  { type: 'ai', text: '⑰ SVG 语法错误：\n\n' + fence('svg', SVG_BROKEN), timestamp: Date.now() - 56000 },
  { type: 'ai', text: '⑱ 表格现状：\n\n| 列 A | 列 B |\n| --- | --- |\n| a1 | b1 |\n| a2 | b2 |', timestamp: Date.now() - 54000 },
];

/* ─────────────── 引导（零后端、零端口：route 从磁盘注入） ─────────────── */

async function boot(browser, { width, height, dark }) {
  const ctx = await browser.newContext({
    viewport: { width, height }, colorScheme: dark ? 'dark' : 'light',
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
    // ③ 的计数探针：mermaid 全局是 vendor 脚本赋值进来的，用 setter 抓一次再包
    // render —— 这样「渲染了几次」是库自己的调用次数，不是我们的估计。
    let mm = null;
    Object.defineProperty(window, 'mermaid', {
      configurable: true,
      get() { return mm; },
      set(v) {
        mm = v;
        if (v && typeof v.render === 'function' && !v.__nfWrapped) {
          v.__nfWrapped = true;
          const orig = v.render.bind(v);
          v.render = (...args) => {
            window.__mmRenderCalls = (window.__mmRenderCalls || 0) + 1;
            return orig(...args);
          };
        }
      },
    });
    window.__mmRenderCalls = 0;
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(45000);
  const reqs = [];
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String((e && e.message) || e).slice(0, 300)));
  page.on('request', (r) => reqs.push(r.url()));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = join(WEB, p);
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') return ws.send(JSON.stringify({ type: 'pong' }));
      if (m.type === 'getConfig') return sendConfig();
      if (m.type === 'getSessions' || m.type === 'listSessions') return sendSessions();
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: m.sessionId, messages: HISTORY,
          hasMore: false, offset: 0, total: HISTORY.length,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForSelector('#chat .row', { timeout: 30000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await page.waitForTimeout(600);
  return { ctx, page, reqs, pageErrors };
}

/* ─────────────── 断言基座 ─────────────── */
let failed = 0;
const ok = (cond, label) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) failed++;
};

/** 逐屏扫过消息列：围栏是**视口懒渲染**（IntersectionObserver），不扫过去
 *  永远停在 pending —— 断言前先像人一样把整列读一遍。 */
async function sweep(page) {
  await page.evaluate(async () => {
    const chat = document.getElementById('chat');
    if (!chat) return;
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    chat.scrollTop = 0;
    await sleep(250);
    const step = Math.max(120, Math.round(chat.clientHeight * 0.55));
    for (let y = 0; y <= chat.scrollHeight + step; y += step) {
      chat.scrollTop = y;
      await sleep(150);
    }
    chat.scrollTop = 0;
    await sleep(250);
  });
  await page.waitForTimeout(300);
}

/** 等所有围栏离开 loading（pending 是「还没进过视口」，由 sweep 负责推进）。 */
async function settle(page) {
  await page.waitForFunction(() => {
    const els = [...document.querySelectorAll('.nf-mermaid, .nf-svg, .nf-math')];
    const live = els.filter((e) => {
      const st = e.getAttribute('data-mermaid-state') || e.getAttribute('data-svg-state') || e.getAttribute('data-math-state');
      return st === 'loading';
    });
    return live.length === 0;
  }, { timeout: 60000 });
  await page.waitForTimeout(400);
}

/** 把「段落缩进」夹具滚进视口（四张实拍都要求带嵌套列表）。 */
async function focusNested(page) {
  await page.evaluate(() => {
    const bubble = [...document.querySelectorAll('.bubble.ai')]
      .find((b) => (b.textContent || '').includes('段落缩进验收'));
    if (bubble) bubble.scrollIntoView({ block: 'start' });
  });
  await page.waitForTimeout(400);
}

const browser = await chromium.launch();
try {
  const { ctx, page, reqs, pageErrors } = await boot(browser, { width: 1440, height: 900, dark: false });

  // 冷启动读数（⑨ 首屏关键路径证据）：此时不该有任何 vendor 重型库请求。
  const bootReqs = [...reqs];
  ok(!bootReqs.some((u) => u.includes('/vendor/katex.min.js')), '首屏未请求 katex.min.js（按需装载）');
  ok(!bootReqs.some((u) => u.includes('/vendor/mermaid/mermaid.min.js')), '首屏未请求 mermaid（按需装载）');

  await sweep(page);
  await settle(page);

  /* ── ① mermaid 围栏 ⇒ svg + 原 code 不可见 ─────────────────────────── */
  const flow = await page.evaluate((src) => {
    const el = [...document.querySelectorAll('.nf-mermaid')].find((e) => (e.getAttribute('data-mermaid-src') || '').includes('开始 START'));
    if (!el) return null;
    const svg = el.querySelector('svg');
    const b = svg ? svg.getBoundingClientRect() : null;
    return {
      state: el.getAttribute('data-mermaid-state'),
      hasSvg: !!svg,
      w: b ? Math.round(b.width) : 0,
      h: b ? Math.round(b.height) : 0,
      codeVisible: !!el.querySelector('pre code, code.language-mermaid'),
      foreign: !!el.querySelector('foreignObject'),
      inBubble: !!el.closest('.bubble'),
    };
  }, T_FLOW);
  ok(!!flow && flow.state === 'done' && flow.hasSvg, `① mermaid 围栏产出 svg（state=${flow && flow.state}）`);
  ok(!!flow && flow.w > 40 && flow.h > 40, `① svg 非零尺寸（${flow && flow.w}x${flow && flow.h}）`);
  ok(!!flow && flow.codeVisible === false, '① 原 <code> 不再可见（代码块已换图）');
  ok(!!flow && flow.foreign === false, '① htmlLabels=false：产物里零 foreignObject');
  ok(!!flow && flow.inBubble === true, '① 图落在真实聊天气泡内（非夹具容器）');

  /* ── ⑤ 图类型清单：逐类型断言 ──────────────────────────────────────── */
  const TYPES = [
    ['flowchart', '开始 START'],
    ['sequenceDiagram', '请求 REQ'],
    ['stateDiagram-v2', 'Idle'],
    ['pie', 'Dogs'],
    ['gantt', '任务一'],
    ['xychart-beta', 'Revenue'],
  ];
  for (const [name, needle] of TYPES) {
    const st = await page.evaluate((n) => {
      const el = [...document.querySelectorAll('.nf-mermaid')].find((e) => (e.getAttribute('data-mermaid-src') || '').includes(n));
      if (!el) return null;
      const svg = el.querySelector('svg');
      const b = svg ? svg.getBoundingClientRect() : null;
      return {
        state: el.getAttribute('data-mermaid-state'),
        hasSvg: !!svg, w: b ? Math.round(b.width) : 0, h: b ? Math.round(b.height) : 0,
      };
    }, needle);
    ok(!!st && st.state === 'done' && st.hasSvg && st.w > 30 && st.h > 20,
      `⑤ 图类型 ${name} 渲染成功（${st && st.w}x${st && st.h}, state=${st && st.state}）`);
  }

  /* ── ② 解析失败 / 未知类型 ⇒ 原码可见 + 错误态 ─────────────────────── */
  for (const [name, needle] of [['语法错误', 'Unclosed --->'], ['未知类型', 'nonsenseDiagramType']]) {
    const st = await page.evaluate((n) => {
      const el = [...document.querySelectorAll('.nf-mermaid')].find((e) => (e.getAttribute('data-mermaid-src') || '').includes(n));
      if (!el) return null;
      const pre = el.querySelector('.nf-fence-src');
      return {
        state: el.getAttribute('data-mermaid-state'),
        cls: el.classList.contains('nf-fence-error'),
        label: (el.querySelector('.nf-fence-errlabel') || {}).textContent || '',
        srcVisible: pre ? !pre.hidden : false,
        srcText: pre ? pre.textContent : '',
        svgLeak: !!el.querySelector('svg'),
      };
    }, needle);
    ok(!!st && st.state === 'error' && st.cls === true, `② ${name}：错误态在场（state=${st && st.state}）`);
    ok(!!st && st.srcVisible === true && st.srcText.includes(needle), `② ${name}：回退原码可见（不空白）`);
    ok(!!st && st.label.length > 0, `② ${name}：可读错误文案（"${st && st.label}"）`);
    ok(!!st && st.svgLeak === false, `② ${name}：错误态不残留半成品 svg`);
  }

  /* ── ⑥ 用户气泡面 ─────────────────────────────────────────────────── */
  const userFace = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.bubble.user .nf-mermaid')]
      .find((e) => (e.getAttribute('data-mermaid-src') || '').includes('USERFENCE'));
    if (!el) return null;
    const svg = el.querySelector('svg');
    const b = svg ? svg.getBoundingClientRect() : null;
    const bubble = el.closest('.bubble.user');
    return {
      state: el.getAttribute('data-mermaid-state'),
      hasSvg: !!svg, w: b ? Math.round(b.width) : 0,
      textKept: (bubble ? bubble.textContent : '').includes('画给我看'),
      rawFenceLeak: (bubble ? bubble.textContent : '').includes('```mermaid'),
    };
  });
  ok(!!userFace && userFace.state === 'done' && userFace.hasSvg && userFace.w > 30,
    `⑥ 用户气泡内 mermaid 围栏出图（state=${userFace && userFace.state}）`);
  ok(!!userFace && userFace.textKept === true && userFace.rawFenceLeak === false,
    '⑥ 用户气泡围栏外仍是纯文本、围栏标记不外泄');

  /* ── ⑦ KaTeX ──────────────────────────────────────────────────────── */
  const mathOk = await page.evaluate(() => {
    const els = [...document.querySelectorAll('.bubble.ai .nf-math')];
    const done = els.filter((e) => e.getAttribute('data-math-state') === 'done');
    const displayDone = done.filter((e) => e.getAttribute('data-math-display') === '1');
    const inlineDone = done.filter((e) => e.getAttribute('data-math-display') === '0');
    return {
      total: els.length,
      done: done.length,
      displayDone: displayDone.length,
      inlineDone: inlineDone.length,
      katexNodes: document.querySelectorAll('.bubble.ai .nf-math .katex').length,
      katexLoaded: typeof window.katex !== 'undefined',
    };
  });
  ok(mathOk.displayDone >= 1, `⑦ $$块级公式渲染成功（${mathOk.displayDone}）`);
  ok(mathOk.inlineDone >= 1, `⑦ $行内公式渲染成功（${mathOk.inlineDone}）`);
  ok(mathOk.katexNodes >= 2, `⑦ KaTeX 产物节点在场（${mathOk.katexNodes}）`);
  ok(mathOk.katexLoaded === true, '⑦ katex 由水合腿按需装载（延迟装上了）');
  const mathErr = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.nf-math')].find((e) => (e.getAttribute('data-math-src') || '').includes('badmacro'));
    if (!el) return null;
    return {
      state: el.getAttribute('data-math-state'),
      cls: el.classList.contains('nf-math-error'),
      text: el.textContent || '',
    };
  });
  ok(!!mathErr && mathErr.state === 'error' && mathErr.cls === true,
    `⑦ 非法命令：错误态在场（state=${mathErr && mathErr.state}）`);
  ok(!!mathErr && mathErr.text.includes('badmacro'), '⑦ 非法命令：原文可见（不空白）');

  /* ── ⑧ inline SVG 安全审查矩阵 ─────────────────────────────────────── */
  const svgMatrix = await page.evaluate(() => {
    const pick = (needle) => [...document.querySelectorAll('.nf-svg')]
      .find((e) => (e.getAttribute('data-svg-src') || '').includes(needle)) || null;
    const info = (el) => {
      if (!el) return null;
      const svg = el.querySelector('svg');
      return {
        state: el.getAttribute('data-svg-state'),
        hasSvg: !!svg,
        scripts: el.querySelectorAll('script').length,
        foreign: el.querySelectorAll('foreignObject').length,
        images: el.querySelectorAll('image').length,
        anchors: el.querySelectorAll('a').length,
        onAttrs: [...el.querySelectorAll('*')].reduce((n, e) => n + [...e.attributes].filter((a) => a.name.toLowerCase().startsWith('on')).length, 0),
        text: (el.textContent || '').replace(/\s+/g, ' ').trim(),
        errorState: el.classList.contains('nf-fence-error'),
        notice: (el.querySelector('.nf-fence-notice') || {}).textContent || '',
      };
    };
    return {
      safe: info(pick('SAFE-SVG')),
      script: info(pick('SCRIPT-STRIPPED')),
      extimg: info(pick('NO-EXTERNAL-IMAGE')),
      foreign: info(pick('NO-FOREIGN-OBJECT')),
      anchor: info(pick('LINKTEXT')),
      onattr: info(pick('NO-ONATTR')),
      broken: info(pick('rect x="1"')),
      xssScript: typeof window.__xssScript,
      xssOnclick: typeof window.__xssOnclick,
    };
  });
  ok(!!svgMatrix.safe && svgMatrix.safe.state === 'done' && svgMatrix.safe.hasSvg && svgMatrix.safe.text.includes('SAFE-SVG'),
    `⑧⑨ 正例：正常 svg 围栏出图且文字在场（state=${svgMatrix.safe && svgMatrix.safe.state}）`);
  ok(!!svgMatrix.script && svgMatrix.script.scripts === 0, '⑧① <script> 已被剥离（0 个 script 节点）');
  ok(svgMatrix.xssScript === 'undefined', '⑧① script 未执行（window.__xssScript 缺席）');
  ok(!!svgMatrix.script && svgMatrix.script.hasSvg === true && svgMatrix.script.text.includes('SCRIPT-STRIPPED'),
    '⑧① 净化后图形仍可见（不是空白）');
  ok(!!svgMatrix.onattr && svgMatrix.onattr.onAttrs === 0, '⑧② 所有 on* 事件属性已剥离（0 处）');
  ok(svgMatrix.xssOnclick === 'undefined', '⑧② on* 未执行（window.__xssOnclick 缺席）');
  ok(!!svgMatrix.foreign && svgMatrix.foreign.foreign === 0, '⑧③ <foreignObject> 整棵子树已剥离');
  ok(!!svgMatrix.extimg && svgMatrix.extimg.images === 0, '⑧④ 外链 <image> 节点已剥离');
  ok(!!svgMatrix.anchor && svgMatrix.anchor.anchors === 0 && svgMatrix.anchor.text.includes('LINKTEXT'),
    '⑧⑤ <a href> 去链接、保留文字');
  ok(!!svgMatrix.broken && svgMatrix.broken.state === 'error' && svgMatrix.broken.errorState === true,
    `⑧⑥ 解析失败 ⇒ 错误态（state=${svgMatrix.broken && svgMatrix.broken.state}）`);
  ok(!!svgMatrix.broken && svgMatrix.broken.text.includes('rect x="1"'),
    '⑧⑥ 解析失败 ⇒ 回退原码可见（不空白）');

  /* ── 零外网请求（无 CDN / 无 evil.example） ─────────────────────────── */
  const external = reqs.filter((u) => !u.startsWith('http://localhost:1/') && !u.startsWith('data:'));
  ok(external.length === 0, `零外网请求（本次全程 ${reqs.length} 个请求，外部 ${external.length} 个${external[0] ? '：' + external[0] : ''}）`);

  /* ── ④ 暗色对比度（计算值） ────────────────────────────────────────── */
  const contrastOf = async () => page.evaluate(() => {
    const svg = document.querySelector('.nf-mermaid[data-mermaid-state="done"] svg');
    if (!svg) return null;
    const parse = (s) => {
      const m = /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?/.exec(s || '');
      return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] } : null;
    };
    const lum = (c) => {
      const ch = (v) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
    };
    // 有效底 = 文档底色（body/html 上真正不透明的那层）作为基数，再自外向内合成
    // 半透明祖先背景。基数取文档底色而不是写死白 —— 否则暗档会把深底算成白底。
    const baseOf = (() => {
      for (const el of [document.body, document.documentElement]) {
        const c = parse(getComputedStyle(el).backgroundColor);
        if (c && c.a >= 0.99) return c;
      }
      return { r: 255, g: 255, b: 255, a: 1 };
    })();
    // 前景观测面：文字其实坐在**就近实绘形状**上（mermaid 的 node rect / 边标签
    // rect），不是坐在页面底上。所以底 = 形状 fill，找不到形状才退回「文档底色 +
    // 祖先半透明背景」合成 —— 与 js/svgInk.js 的 effectiveBackground 同一口径。
    const shapeBg = (el) => {
      let n = el.parentElement;
      let hops = 0;
      while (n && hops < 6) {
        const shape = n.querySelector(':scope > rect, :scope > circle, :scope > ellipse, :scope > polygon, :scope > path');
        if (shape) {
          const c = parse(getComputedStyle(shape).fill);
          if (c && c.a > 0.05) return c;
        }
        n = n.parentElement;
        hops++;
      }
      return null;
    };
    const texts = [...svg.querySelectorAll('text, tspan')];
    if (!texts.length) return null;
    let worst = { ratio: 99, ink: '', bg: '' };
    for (const t of texts) {
      const ink = parse(getComputedStyle(t).fill);
      if (!ink || ink.a === 0) continue;
      let acc = shapeBg(t);
      if (!acc) {
        const stack = [];
        let n = t.parentElement;
        while (n && stack.length < 14) {
          const bg = parse(getComputedStyle(n).backgroundColor);
          if (bg && bg.a > 0) stack.push(bg);
          n = n.parentElement;
        }
        acc = { ...baseOf };
        for (let i = stack.length - 1; i >= 0; i--) {
          const top = stack[i];
          acc = { r: top.r * top.a + acc.r * (1 - top.a), g: top.g * top.a + acc.g * (1 - top.a), b: top.b * top.a + acc.b * (1 - top.a), a: 1 };
        }
      }
      const li = lum(ink);
      const lb = lum(acc);
      const ratio = (Math.max(li, lb) + 0.05) / (Math.min(li, lb) + 0.05);
      if (ratio < worst.ratio) {
        worst = {
          ratio: Math.round(ratio * 100) / 100,
          ink: `rgb(${Math.round(ink.r)}, ${Math.round(ink.g)}, ${Math.round(ink.b)})`,
          bg: `rgb(${Math.round(acc.r)}, ${Math.round(acc.g)}, ${Math.round(acc.b)})`,
          text: (t.textContent || '').slice(0, 20),
          count: texts.length,
        };
      }
    }
    worst.dark = matchMedia('(prefers-color-scheme: dark)').matches;
    worst.bodyBg = getComputedStyle(document.body).backgroundColor;
    return worst;
  });

  const lightContrast = await contrastOf();

  // 亮档实拍（1440）
  mkdirSync(SHOTS_DIR, { recursive: true });
  await focusNested(page);
  await page.screenshot({ path: join(SHOTS_DIR, 'rich-render-1440-light.png'), fullPage: false });

  // 切暗档：主题翻转后围栏重排队
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(500);
  await sweep(page);
  await settle(page);
  const darkContrast = await contrastOf();
  await focusNested(page);
  await page.screenshot({ path: join(SHOTS_DIR, 'rich-render-1440-dark.png'), fullPage: false });

  ok(!!lightContrast && lightContrast.ratio >= 4.5,
    `④ 亮档 svg 全文本最小对比度 ${lightContrast && lightContrast.ratio}:1（墨 ${lightContrast && lightContrast.ink} / 底 ${lightContrast && lightContrast.bg} / 文本 "${lightContrast && lightContrast.text}" / ${lightContrast && lightContrast.count} 个文本节点）`);
  ok(!!darkContrast && darkContrast.dark === true, '④ 暗档生效（prefers-color-scheme: dark）');
  ok(!!darkContrast && darkContrast.ratio >= 4.5,
    `④ 暗档 svg 全文本最小对比度 ${darkContrast && darkContrast.ratio}:1（墨 ${darkContrast && darkContrast.ink} / 底 ${darkContrast && darkContrast.bg} / 文本 "${darkContrast && darkContrast.text}" / body 底 ${darkContrast && darkContrast.bodyBg}）`);

  /* ── ③ 流式：中途零 svg，完成后恰好一次 ────────────────────────────── */
  const streamProbe = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const host = document.createElement('div');
    host.id = 'stream-probe';
    document.body.appendChild(host);
    const frames = [
      '生成中：\n```mermaid\nflowchart TD\n  S[STREAM]',
      '生成中：\n```mermaid\nflowchart TD\n  S[STREAM] --> T[P2]',
      '生成中：\n```mermaid\nflowchart TD\n  S[STREAM] --> T[P2]',
    ];
    const mid = [];
    for (const f of frames) {
      host.innerHTML = renderMarkdownWithMath(f, true, { cache: false, stream: true });
      mid.push({
        placeholders: host.querySelectorAll('.nf-mermaid, .nf-svg').length,
        // ⚠ 只数**图围栏内**的 svg：代码块复制按钮自带一枚图标 svg，数全量会误判。
        svg: host.querySelectorAll('.nf-mermaid svg, .nf-svg svg').length,
        code: host.querySelectorAll('code.language-mermaid').length,
      });
    }
    return { mid };
  });
  const midOk = streamProbe.mid.every((f) => f.placeholders === 0 && f.svg === 0 && f.code === 1);
  ok(midOk, `③ 流式中途零占位零 svg、围栏按代码块显示（${JSON.stringify(streamProbe.mid)}）`);

  const callsBefore = await page.evaluate(() => window.__mmRenderCalls || 0);
  const finalRender = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const host = document.getElementById('stream-probe');
    // 终态：同一条消息完成 ⇒ 非流式档，出现占位（水合由全局 observer 驱动）
    host.innerHTML = renderMarkdownWithMath(
      '完成：\n```mermaid\nflowchart TD\n  S[STREAM] --> T[P2]\n```', true, { cache: false },
    );
    return { placeholders: host.querySelectorAll('.nf-mermaid').length };
  });
  ok(finalRender.placeholders === 1, '③ 消息完成后出现 1 个占位（随后水合成图）');
  await page.waitForFunction(() => {
    const host = document.getElementById('stream-probe');
    return host && host.querySelector('.nf-mermaid[data-mermaid-state="done"] svg');
  }, { timeout: 30000 });
  await page.waitForTimeout(800);
  const streamFinal = await page.evaluate(() => ({
    calls: window.__mmRenderCalls || 0,
    svg: document.querySelectorAll('#stream-probe .nf-mermaid svg').length,
    states: [...document.querySelectorAll('#stream-probe .nf-mermaid')].map((e) => e.getAttribute('data-mermaid-state')).join(','),
  }));
  ok(streamFinal.svg === 1, `③ 完成后 svg 恰好 1 个（${streamFinal.svg}，state=${streamFinal.states}）`);
  ok(streamFinal.calls - callsBefore === 1, `③ 库 render 调用恰好 1 次（before=${callsBefore} after=${streamFinal.calls}）`);

  /* ── ⑩ 段落缩进（作者令 09:13「对段落内容，要有缩进」） ──────────────── */
  const indent = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    const token = root.getPropertyValue('--ds-text-indent').trim();     // 期望 = 2em
    const listTok = root.getPropertyValue('--ds-list-indent').trim();   // 期望 = 1.6em
    const bubble = [...document.querySelectorAll('.bubble.ai')]
      .find((b) => (b.textContent || '').includes('段落缩进验收'));
    if (!bubble) return null;
    const p = bubble.querySelector(':scope > p');
    const liP = bubble.querySelector('li p');
    const li = bubble.querySelector('li p') ? bubble.querySelector('li p').closest('li') : null;
    const lvl2 = bubble.querySelector('ul ul');
    const lvl3 = bubble.querySelector('ul ul ul');
    const leftOf = (el) => (el ? Math.round(el.getBoundingClientRect().left * 10) / 10 : null);
    const quoteP = bubble.querySelector('blockquote p');
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const pCs = cs(p);
    const liCs = cs(liP);
    const l2 = cs(lvl2);
    const l3 = cs(lvl3);
    return {
      token, listTok,
      pIndent: pCs ? parseFloat(pCs.textIndent) : null,
      pFont: pCs ? parseFloat(pCs.fontSize) : null,
      liIndent: liCs ? parseFloat(liCs.textIndent) : null,
      liExists: !!liP,
      quoteIndent: cs(quoteP) ? parseFloat(cs(quoteP).textIndent) : null,
      l2: l2 ? parseFloat(l2.marginLeft) : null,
      l3: l3 ? parseFloat(l3.marginLeft) : null,
      l2Left: leftOf(lvl2),
      l3Left: leftOf(lvl3),
      liLeft: leftOf(bubble.querySelector('ul')),
      l2Font: l2 ? parseFloat(l2.fontSize) : null,
      nested: !!lvl2 && !!lvl3,
      mobile: document.body.classList.contains('mobile-layer'),
      narrow: getComputedStyle(document.documentElement).getPropertyValue('--ds-list-indent-narrow').trim(),
    };
  });
  ok(!!indent && indent.token === '2em' && Math.abs(indent.pIndent - 2 * indent.pFont) < 0.6,
    `⑩① 正文段落首行缩进 = token 值（computed ${indent && indent.pIndent}px / 2em@${indent && indent.pFont}px / token=${indent && indent.token}）`);
  ok(!!indent && indent.liExists === true && indent.liIndent === 0,
    `⑩② 列表项内段落零首行缩进（li>p text-indent=${indent && indent.liIndent}px）`);
  ok(!!indent && indent.quoteIndent === 0, `⑩② 引用块内段落零首行缩进（${indent && indent.quoteIndent}px）`);
  ok(!!indent && indent.nested === true && indent.l2 >= indent.l2Font * 0.99,
    `⑩ 列表每级缩进 ≥ 一档字号（每级 ${indent && indent.l2}px / 字档 ${indent && indent.l2Font}px；token=${indent && indent.listTok}）`);
  ok(!!indent && indent.l3Left - indent.l2Left >= indent.l2Font * 0.99 && indent.l2Left - indent.liLeft >= indent.l2Font * 0.99,
    `⑩ 二三级**累计**缩进各自 ≥ 一档字号（一级 ${indent && indent.liLeft}px → 二级 ${indent && indent.l2Left}px → 三级 ${indent && indent.l3Left}px）`);

  /* ── ⑧ 表格现状（只记录状态，不顺手改） ────────────────────────────── */
  const tableState = await page.evaluate(() => {
    const bubbles = [...document.querySelectorAll('.bubble.ai')];
    const b = bubbles.find((x) => (x.textContent || '').includes('列 A'));
    if (!b) return null;
    const table = b.querySelector('table');
    const cells = table ? table.querySelectorAll('td').length : 0;
    const tb = table ? table.getBoundingClientRect() : null;
    return {
      hasTable: !!table,
      cells,
      width: tb ? Math.round(tb.width) : 0,
      overflow: table ? table.scrollWidth > table.clientWidth + 1 : null,
      bubbleWidth: Math.round(b.getBoundingClientRect().width),
    };
  });
  ok(!!tableState && tableState.hasTable === true && tableState.cells === 4,
    `表格现状：GFM pipe table 在气泡内正常渲染（${tableState && tableState.cells} 个单元格，表宽 ${tableState && tableState.width}px / 气泡 ${tableState && tableState.bubbleWidth}px，内部溢出=${tableState && tableState.overflow}）`);

  ok(pageErrors.length === 0, `零未捕获异常（${pageErrors.length}${pageErrors[0] ? '：' + pageErrors[0] : ''}）`);

  await ctx.close();

  /* ── ⑨ 375 档（手机层）双主题实拍 ──────────────────────────────────── */
  for (const dark of [false, true]) {
    const m = await boot(browser, { width: 375, height: 812, dark });
    await sweep(m.page);
    await settle(m.page);
    const narrow = await m.page.evaluate(() => {
      const el = [...document.querySelectorAll('.nf-mermaid')].find((e) => (e.getAttribute('data-mermaid-src') || '').includes('开始 START'));
      const svg = el ? el.querySelector('svg') : null;
      const b = svg ? svg.getBoundingClientRect() : null;
      const body = document.body.getBoundingClientRect();
      return {
        state: el ? el.getAttribute('data-mermaid-state') : null,
        w: b ? Math.round(b.width) : 0,
        fits: b ? b.width <= body.width + 1 : false,
        mobileLayer: document.body.classList.contains('mobile-layer'),
      };
    });
    ok(narrow.state === 'done' && narrow.w > 20 && narrow.fits,
      `⑨ 375 档${dark ? '暗' : '亮'}：图渲染且不溢出（${narrow.w}px / mobile-layer=${narrow.mobileLayer}）`);
    await focusNested(m.page);
    await m.page.screenshot({ path: join(SHOTS_DIR, `rich-render-375-${dark ? 'dark' : 'light'}.png`), fullPage: false });
    await m.ctx.close();
  }
} finally {
  await browser.close();
}

console.log(`\nshots: ${SHOTS_DIR}`);
console.log(failed === 0 ? 'ALL PASS' : `FAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
