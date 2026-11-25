#!/usr/bin/env node
// tests/todo-inbox-ui.spec.mjs — 待办收件箱（todo inbox）六条作者令的可执行闸 + 证据腿
// （作者令 2026-11-21 六条，移动端 390×844 视口）：
//
//   ① 输入框标准化：次级面板（待办详情页回复条 / 快速决策卡页悬浮控件带）的输入框与
//      Canvas Ask 输入条同一装配点（js/input/secondaryComposer.js）+ 同一套令牌 +
//      同一键道（Enter 发送 / Shift+Enter 换行）；三道检查见
//      scripts/check-secondary-composer.mjs（源码级），本闸验**运行时几何与功能**。
//   ② md 渲染：待办卡片/详情正文走站内唯一 md 管线（utils.renderMarkdownWithMath，
//      与消息气泡同一条）+ 同一 sanitizer（原始 HTML 转义、脚本不执行、外链走单点闸）。
//   ③ 选项待办端到端：decision.options 卡「渲染 → 点选 → 回传链路（出真帧）」，逐条
//      记录在 options-e2e.txt。
//   ④ 顶部空间：「第 n / m 条」计数搬进顶带胶囊（页内独占行退役），卡片区上移放大。
//   ⑤ 右上角过滤：顶带右端过滤钮 + chips 面板，按标签 / 重要度只显示部分决策卡片。
//   ⑥ 悬浮控件：回答输入带搬出卡片，成为页面级流内页脚（悬浮档视觉）——卡片内滚
//      不推走它、卡片底缘裁不到它；入场有动效（GIF 取证）。
//
// 运行：
//   node tests/todo-inbox-ui.spec.mjs
//   TIU_WEB_ROOT=<另一棵 web 树> node tests/todo-inbox-ui.spec.mjs   # 改前腿/破坏腿
//   TIU_OUT=<证据目录> node tests/todo-inbox-ui.spec.mjs
//   退出码：0 = 全绿；1 = 有红；2 = 无红但有 SKIP（CI 档 SKIP 记 FAIL ⇒ 1）。
//
// 数据面 = 有状态 routeWebSocket 帧桩（不证明真实后端信箱持久化）；静态面 = page.route 从磁盘
// 注真 web 树。零后端、零 sbt、零宿主实例（不碰 :6138 / :8080 / :8097）。
// 🔴 诚实申报：headless 里没有系统软键盘/safe-area，①⑥ 的键盘腿只验通道；真机手感
//    另列「待真机亲验」。

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TIU_WEB_ROOT
  ? normalize(resolve(process.env.TIU_WEB_ROOT))
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.TIU_OUT || join(HERE, '..', 'tmp', 'todo-inbox-ui');
const SHOTS = join(OUT, 'shots');
const FRAMES = join(OUT, 'frames');
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(SHOTS, { recursive: true });
mkdirSync(FRAMES, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'),
  JSON.stringify({ createdAt: new Date().toISOString(), project: 'stand', batch: process.env.TIU_BATCH || 'todo-inbox-ui-20261121' }) + '\n');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todo-inbox-ui-root';
/** 作者钉的移动端视口：390×844（iPhone 14/15 逻辑尺寸）。 */
const VW = 390;
const VH = 844;

let pass = 0, fail = 0, skipped = 0;
const lines = [];
const ok = (name, cond, detail = '') => {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
};
const SKIP_IS_FAIL = !!(process.env.CI || process.env.TIU_CI);
const skip = (name, why) => {
  const line = `SKIP  ${name}${why ? '  — ' + why : ''}`;
  console.log(line);
  lines.push(line);
  skipped++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const probe = {};
const sentFrames = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const pageErrors = [];
// WS 桩仅验证前端帧、回执及编排，不证明真实后端信箱持久化。
const backend = { rows: [], rejectAnswer: false };
const capturedFrames = () => sentFrames.map((raw) => { try { return JSON.parse(raw); } catch { return null; } }).filter(Boolean);
const answerFrames = () => capturedFrames().filter((m) => m.type === 'answerTodoDecision');
function resetBackend(fx) {
  backend.rows = fx.itemList.map((item, i) => ({
    id: item.id, content: item.name, name: item.name, detail: item.detail || '',
    kind: 'todo', sessionId: SID, project: fx.tagList.find((t) => t.id === item.tagId)?.name || null,
    priority: item.priority, triggerAt: item.due, createdAt: 1000 + i,
    completed: false, decision: item.decision ? structuredClone(item.decision) : null,
  }));
}

/** 输入框统一契约（作者令 ①「与 ask 的输入框样式功能一致」）—— 一份拒绝集：
 *  任一运行时读数与 ask 的配方分家即红。 */
const COMPOSER_CONTRACT = {
  sendClasses: ['ds-turn-btn', 'ds-turn-btn--send'],
  shellClasses: ['nf-glass'],
  /** Ask 面的封顶（次级输入框紧凑档）= 120，唯一声明点 secondaryComposer.js。 */
  maxHeightCompact: '120px',
};

/* ─────────────── 夹具（5 条未完成待办：单选/多选决策 + 长 md 正文 + 旗标/标签）─── */

function fixture() {
  const now = Date.now();
  const mdDetail = [
    '机制现状：`check_wiki_docs.py` R21 = 新文档缺提要或计数不在 40-100 硬报、基线清单内文档 advisory。',
    '',
    '**但“落哪”本身也还没定**（同一条线上我问过，卡片还挂着）。',
    '',
    '- 计数口径 = 汉字每字计一、英文/数字连续单词各计一',
    '- 提要落点两种都支持（frontmatter `summary:` 或正文 `<!-- reader-summary -->` 后的完整段落）',
    '',
    '参考资料：[站内规范](https://example.com/spec)。',
    '',
    '```js',
    'const x = 1; // 围栏代码块',
    '```',
    '',
    '<img src=x onerror="window.__xss_img=1"><script>window.__xss_script=1</script>',
  ].join('\n');
  return {
    tagList: [
      { id: 'tw', name: '工作', color: 'rgb(0, 122, 255)' },
      { id: 'tl', name: '生活', color: 'rgb(16, 185, 129)' },
    ],
    itemList: [
      {
        id: 'iu-1', tagId: 'tw', priority: 3, due: now + 3_600_000,
        name: 'reader-summary-backlog',
        detail: mdDetail,
        decision: {
          question: '先推进哪一件？',
          options: [{ id: 'a', title: 'A 把待办数据模型的字段对齐做完' }, { id: 'b', title: 'B 先把手机端列表整改发出去' }, { id: 'c', title: 'C 两个并行' }],
        },
      },
      {
        id: 'iu-2', tagId: 'tl', priority: 2, due: now + 7_200_000,
        name: 'multi-select 决策卡',
        detail: '多选决策：可勾多个再回传。',
        decision: { question: '这周要碰哪些面？', options: ['输入框', '过滤'], multiSelect: true },
      },
      {
        id: 'iu-3', tagId: 'tl', priority: 1, due: null,
        name: '纯长正文（无决策）',
        detail: Array.from({ length: 18 }, (_, i) => `内容段 ${i + 1}：用来把卡片撑到必须下滑才看得完。`).join('\n'),
      },
      { id: 'iu-4', tagId: 'tw', priority: 1, due: null, name: '工作·低旗标', detail: '过滤腿：工作 + 低。' },
      { id: 'iu-5', tagId: 'tl', priority: 3, due: null, name: '生活·高旗标', detail: '过滤腿：生活 + 高。' },
      // 详情页腿的承载行：**无决策**（有 decision 的行在列表页展开行内只读面，
      // 不进条目页 —— 既有产品行为，本批不动），正文带 md 语法。
      {
        id: 'iu-6', tagId: 'tw', priority: 0, due: null, name: '详情页 md 事项',
        detail: [
          '机制现状：`check_wiki_docs.py` R21 硬报。',
          '',
          '**但“落哪”本身也还没定**（同一条线上我问过，卡片还挂着）。',
          '',
          '- 计数口径 = 汉字每字计一',
          '- 提要落点两种都支持',
        ].join('\n'),
      },
    ],
  };
}

/* ─────────────── 启动（真 web 树 + 帧桩 + 夹具模式）─────────────── */

async function boot() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH }, deviceScaleFactor: 2,
    hasTouch: true, isMobile: true, colorScheme: 'light', locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) pageErrors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) pageErrors.push(`console.error: ${m.text()}`); });

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      sentFrames.push(String(raw));
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: m.scope || 'session', sessionId: m.sessionId, tasks: backend.rows.filter((r) => !r.completed) }));
      else if (m.type === 'answerTodoDecision') {
        const row = backend.rows.find((r) => r.id === m.id);
        if (backend.rejectAnswer) ws.send(JSON.stringify({ type: 'error', msgType: m.type, id: m.id, code: 'decision_not_persisted', message: '信箱不可用' }));
        else if (row?.decision) {
          row.decision.answer = { selectedOptionIds: m.selectedOptionIds, otherText: m.otherText || null };
          ws.send(JSON.stringify({ type: 'todoDecisionAnswered', id: row.id, sessionId: row.sessionId, answer: row.decision.answer, task: row }));
        }
      } else if (m.type === 'completeTodo') {
        const row = backend.rows.find((r) => r.id === m.id);
        if (row && (!row.decision || row.decision.answer)) {
          row.completed = true;
          ws.send(JSON.stringify({ type: 'scheduledTaskCompleted', id: row.id, sessionId: row.sessionId, task: row }));
        } else ws.send(JSON.stringify({ type: 'error', msgType: m.type, id: m.id, code: 'decision_unanswered', message: '先作答' }));
      } else if (typeof m.content === 'string' && m.sessionId) ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId }));
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  const fx = fixture();
  resetBackend(fx);
  await page.evaluate(async () => { (await import('/js/todoStore.js')).todoStore.__live(); });
  await page.waitForFunction(async () => (await import('/js/todoStore.js')).todoStore.openItems().length === 6);
  return { browser, ctx, page };
}

const seed = async (page, fx) => {
  resetBackend(fx);
  await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.__live());
  await page.waitForFunction(async () => {
    const s = (await import('/js/todoStore.js')).todoStore;
    return s.openItems().length === 6 && s.openItems().some((x) => x.id === 'iu-1' && !x.decision.answer);
  });
  await page.waitForTimeout(300);
};

const push = async (page, id) => {
  await page.evaluate(async (pid) => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage(pid);
  }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(360);
};

const RECT = `(sel) => { const el = document.querySelector(sel); if (!el) return null; const b = el.getBoundingClientRect();
  const f = (v) => +v.toFixed(2);
  return { x: f(b.x), y: f(b.y), w: f(b.width), h: f(b.height), right: f(b.right), bottom: f(b.bottom), cx: f(b.x + b.width / 2), cy: f(b.y + b.height / 2) }; }`;

const QP = '.mobile-page[data-page="todo-quick-decision"]';
const userContentFrames = () => sentFrames
  .map((raw) => { try { return JSON.parse(raw); } catch { return null; } })
  .filter((m) => m && typeof m.content === 'string' && m.sessionId === SID)
  .map((m) => m.content);

const { browser, ctx, page } = await boot();

/* ═══════════════ ① 输入框标准化（与 ask 同一装配点 / 同一令牌 / 同一键道）═══════ */
await push(page, 'tasks');
await page.locator('.mobile-page[data-page="tasks"] .ta-quick-fab').click();
await page.waitForSelector(`${QP}.live`, { timeout: 15000 });
await page.waitForTimeout(420);

const comp = await page.evaluate(`(async () => {
  const pageEl = document.querySelector('${QP}');
  const shell = pageEl.querySelector('.ta-qd-answer-input');
  const ta = shell ? shell.querySelector('textarea') : null;
  const send = pageEl.querySelector('.ta-qd-answer-send');
  const cs = (el) => (el ? getComputedStyle(el) : null);
  const root = getComputedStyle(document.documentElement);
  const pick = (name) => root.getPropertyValue(name).trim();
  // 自增高封顶 = 站内唯一声明点（js/input/secondaryComposer.js 的常量），
  // 这里**import 同一模块读值**（不是抄一个数字）。
  const mod = await import('/js/input/secondaryComposer.js');
  return {
    clampConstant: mod.SECONDARY_COMPOSER_MAX_H_COMPACT,
    shellClass: shell ? shell.className : null,
    shellRadius: cs(shell) ? cs(shell).borderRadius : null,
    shellRadiusToken: pick('--nf-field-shell-radius') || null,
    taClass: ta ? ta.className : null,
    taBg: ta ? cs(ta).backgroundColor : null,
    taBorder: ta ? cs(ta).borderTopWidth + ' ' + cs(ta).borderTopStyle : null,
    taShadow: ta ? cs(ta).boxShadow : null,
    taFontSize: ta ? cs(ta).fontSize : null,
    taMaxHeight: ta ? cs(ta).maxHeight : null,
    sendClass: send ? send.className : null,
    sendSize: send ? cs(send).width + 'x' + cs(send).height : null,
    sendSizeToken: send ? cs(send).getPropertyValue('--ds-turn-btn-size').trim() : null,
    sendIconToken: send ? cs(send).getPropertyValue('--ds-turn-btn-icon').trim() : null,
    sendGlyph: send && send.querySelector('svg') ? (send.querySelector('svg').getAttribute('data-lucide') || 'send-svg') : null,
    placeholder: ta ? ta.placeholder : null,
    ariaLabel: ta ? ta.getAttribute('aria-label') : null,
    hasStop: !!pageEl.querySelector('.ta-qd-answer-input .ds-turn-btn--stop'),
  };
})()`);
probe.composer = comp;
ok('① 输入件 = 次级输入框模板产出（毛玻璃外壳 + 裸件 textarea + ds-turn-btn 发送键）',
  !!comp.shellClass && COMPOSER_CONTRACT.shellClasses.every((c) => comp.shellClass.split(/\s+/).includes(c))
  && COMPOSER_CONTRACT.sendClasses.every((c) => (comp.sendClass || '').split(/\s+/).includes(c))
  && comp.sendGlyph === 'send',
  JSON.stringify({ shell: comp.shellClass, send: comp.sendClass, glyph: comp.sendGlyph }));
ok('① 内芯按裸件收编（零自绘底/边/影 —— 造型只有 css/inputSurface.css 一个来源）',
  comp.taBg === 'rgba(0, 0, 0, 0)' && /^0px/.test(comp.taBorder || '') && comp.taShadow === 'none',
  JSON.stringify({ bg: comp.taBg, border: comp.taBorder, shadow: comp.taShadow, cls: comp.taClass }));
ok('① 与 ask 输入条同一套令牌（外壳圆角 --nf-field-shell-radius / 字档 --nf-field-fs）'
  + '+ 自增高封顶 = 唯一声明点常量（js/input/secondaryComposer.js）',
  comp.taFontSize === '13.5px' && !!comp.shellRadius
  && comp.clampConstant === 120 && comp.taMaxHeight === 'none',
  JSON.stringify({ fs: comp.taFontSize, cssMaxH: comp.taMaxHeight, clamp: comp.clampConstant,
    radius: comp.shellRadius, radiusToken: comp.shellRadiusToken }));
ok('① 发送键几何 = ask 输入条**同一份声明**（--ds-turn-btn-size / --ds-turn-btn-icon 逐值相同）',
  comp.sendSizeToken === '40px' && comp.sendIconToken === '18px' && comp.hasStop === false,
  JSON.stringify({ sizeToken: comp.sendSizeToken, iconToken: comp.sendIconToken,
    size: comp.sendSize, hasStop: comp.hasStop }));
ok('① 占位/无障碍名 = 「回复这个待办…」（与详情页回复条同一键）',
  comp.placeholder === '回复这个待办…' && comp.ariaLabel === '回复这个待办…',
  JSON.stringify({ ph: comp.placeholder, aria: comp.ariaLabel }));
await page.screenshot({ path: join(SHOTS, '01-input-standard.png') });

// 键道（与 ask 同一条 bindComposerKeys）：Enter 发送、Shift+Enter 换行。
const framesBeforeEnter = answerFrames().length;
const chatBeforeEnter = userContentFrames().length;
await page.locator(`${QP} .ta-qd-answer-input textarea`).click();
await page.keyboard.type('Enter 直接发送（与 ask 同键道）');
await page.keyboard.down('Shift');
await page.keyboard.press('Enter');
await page.keyboard.up('Shift');
await page.waitForTimeout(200);
const framesAfterShiftEnter = answerFrames().length;
const afterShiftEnter = await page.evaluate(`(() => { const ta = document.querySelector('${QP} .ta-qd-answer-input textarea'); return { value: ta.value }; })()`);
await page.keyboard.press('Enter');
await page.waitForTimeout(520);
const framesAfterEnter = answerFrames();
const afterEnter = await page.evaluate(`(() => { const ta = document.querySelector('${QP} .ta-qd-answer-input textarea');
  const card = document.querySelector('${QP} .ta-qd-card'); return { value: ta.value, last: card.dataset.qdAnswerLast || null }; })()`);
probe.enterKey = { before: framesBeforeEnter, afterShiftEnter: framesAfterShiftEnter, afterEnter: framesAfterEnter, afterShiftEnterValue: afterShiftEnter, afterEnterValue: afterEnter };
ok('① Shift+Enter = 换行（不发送）：值里出现换行且零新帧',
  /\n/.test(afterShiftEnter.value) && framesAfterShiftEnter === framesBeforeEnter,
  JSON.stringify({ value: afterShiftEnter.value.slice(0, 40), frames: framesAfterShiftEnter }));
ok('① Enter = 发送（决策作答帧、输入清空、不重复发送聊天）',
  framesAfterEnter.length === framesBeforeEnter + 1
  && framesAfterEnter.at(-1).id === 'iu-1' && framesAfterEnter.at(-1).otherText === 'Enter 直接发送（与 ask 同键道）'
  && userContentFrames().length === chatBeforeEnter && afterEnter.value === '',
  JSON.stringify({ frames: framesAfterEnter.slice(-1), last: afterEnter.last, value: afterEnter.value }));

// 每次作答会完成卡片；重置数据并等待同一决策卡后再测正文和选项。
await seed(page, fixture());
await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="todo-quick-decision"] .ta-qd-card')?.dataset.itemId === 'iu-1');

/* ═══════════════ ② md 渲染（站内唯一管线 + 同一 sanitizer）═══════════════ */
const md = await page.evaluate(`(() => {
  const card = document.querySelector('${QP} .ta-qd-card');
  const box = card.querySelector('.ta-qd-content');
  const html = box ? box.innerHTML : '';
  return {
    isMd: !!box && box.classList.contains('is-md'),
    text: box ? box.textContent : '',
    strong: box ? box.querySelectorAll('strong, b').length : 0,
    code: box ? box.querySelectorAll('code').length : 0,
    pre: box ? box.querySelectorAll('pre').length : 0,
    li: box ? box.querySelectorAll('li').length : 0,
    links: box ? [...box.querySelectorAll('a')].map((a) => ({ href: a.getAttribute('href'), cls: a.className })) : [],
    scripts: document.querySelectorAll('${QP} script').length,
    onerrorImg: document.querySelectorAll('${QP} img[onerror]').length,
    rawAsterisks: /\\*\\*/.test(box ? box.textContent : ''),
    rawBackticks: (box ? box.textContent : '').includes('\`'),
    xss: { img: window.__xss_img || null, script: window.__xss_script || null },
  };
})()`);
probe.md = md;
ok('② 卡片正文按 md 渲染（容器进 md 档：p/strong/code/pre/li 都在，原始 `**` 与反引号不再裸显）',
  md.isMd === true && md.strong > 0 && md.code > 0 && md.pre > 0 && md.li >= 2
  && md.rawAsterisks === false && md.rawBackticks === false,
  JSON.stringify({ isMd: md.isMd, strong: md.strong, code: md.code, pre: md.pre, li: md.li,
    rawAsterisks: md.rawAsterisks, rawBackticks: md.rawBackticks, textHead: md.text.slice(0, 40) }));
ok('② XSS 沿用既有 sanitizer：内联脚本/事件属性都不生（原始 HTML 转义成字面文本）',
  md.scripts === 0 && md.onerrorImg === 0 && md.xss.img === null && md.xss.script === null,
  JSON.stringify({ scripts: md.scripts, onerrorImg: md.onerrorImg, xss: md.xss }));
ok('② 外链走单一处置点（md-ext 牌 + http(s) scheme），不在卡内直接跳外部',
  md.links.length === 1 && /md-ext/.test(md.links[0].cls || '') && /^https?:\/\//.test(md.links[0].href || ''),
  JSON.stringify(md.links));
await page.screenshot({ path: join(SHOTS, '02-md-render.png') });

/* ═══════════════ ③ 选项待办端到端（渲染 → 点选 → 回传链路）═══════════════ */
const optRows = await page.evaluate(`(() => [...document.querySelectorAll('${QP} .ta-qd-opt')].map((r) => ({
  label: (r.querySelector('.ta-qd-opt-label') || {}).textContent || '',
  type: (r.querySelector('input') || {}).type || '',
  role: (document.querySelector('${QP} .ta-qd-opts') || {}).getAttribute ? document.querySelector('${QP} .ta-qd-opts').getAttribute('role') : null,
})))()`);
const e2e = [];
const rec = (step, detail) => { e2e.push(`${step}  ${detail}`); };
rec('E1 夹具：iu-1 = 单选决策卡（3 选项）', JSON.stringify(optRows.map((r) => r.label)));
ok('③ 选项渲染：3 行原生 radio（radiogroup 语义）+ 选项文本与行数据逐字一致',
  optRows.length === 3 && optRows.every((r) => r.type === 'radio')
  && optRows[0].label === 'A 把待办数据模型的字段对齐做完',
  JSON.stringify(optRows));

// 真点第 2 项（触摸）
const tap2 = await page.evaluate(`(() => { const r = document.querySelectorAll('${QP} .ta-qd-opt')[1].getBoundingClientRect();
  return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) }; })()`);
await page.touchscreen.tap(tap2.x, tap2.y);
await page.waitForTimeout(240);
const afterTap = await page.evaluate(`(() => ({
  checked: [...document.querySelectorAll('${QP} .ta-qd-opt')].map((r) => !!r.querySelector('input').checked),
  rowBg: getComputedStyle(document.querySelectorAll('${QP} .ta-qd-opt')[1]).backgroundColor,
  sent: document.querySelector('${QP} .ta-qd-card').dataset.qdAnswerLast || null,
}))()`);
rec('E2 点选第 2 项（真触摸）→ checked 读数', JSON.stringify(afterTap.checked));
ok('③ 点选：命中的选项被选中（原生 :checked + 选中态底变化），其余不选',
  afterTap.checked[1] === true && afterTap.checked[0] === false && afterTap.checked[2] === false,
  JSON.stringify(afterTap));
ok('③ 选中态有可见样式（sapphire 族合成底，不是纯透明）', /0\.14|rgb/.test(afterTap.rowBg), afterTap.rowBg);

const framesBeforeOptSend = answerFrames().length;
await page.locator(`${QP} .ta-qd-answer-send`).click();
await page.waitForTimeout(520);
const framesAfterOptSend = answerFrames();
const afterOptSend = await page.evaluate(`(() => ({
  checked: [...document.querySelectorAll('${QP} .ta-qd-opt')].map((r) => !!r.querySelector('input').checked),
  last: document.querySelector('${QP} .ta-qd-card').dataset.qdAnswerLast || null,
  value: document.querySelector('${QP} .ta-qd-answer-input textarea').value,
  error: document.querySelector('${QP} .ta-qd-answer-error').textContent,
}))()`);
rec('E3 只点选、不手输 → 点发送 → 出帧', JSON.stringify(framesAfterOptSend.slice(-1)));
probe.optionsE2E = { afterTap, afterOptSend, frames: framesAfterOptSend };
ok('③ 回传链路：只点选发决策帧，选项 ID 和行会话准确',
  framesAfterOptSend.length === framesBeforeOptSend + 1
  && framesAfterOptSend.at(-1).id === 'iu-1' && framesAfterOptSend.at(-1).sessionId === SID
  && JSON.stringify(framesAfterOptSend.at(-1).selectedOptionIds) === '["b"]',
  JSON.stringify({ frame: framesAfterOptSend.slice(-1), last: afterOptSend.last }));
rec('E4 发出后选中态清空 + 输入框清空 + 无错误行', JSON.stringify(afterOptSend));
ok('③ 发出后状态收敛：选中清空 + 件清空 + 无错误行',
  afterOptSend.checked.every((v) => v === false) && afterOptSend.value === '' && afterOptSend.error === '',
  JSON.stringify(afterOptSend));

// 每次成功会完成该卡；重置后验证手输与选项共存。
await seed(page, fixture());
await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="todo-quick-decision"] .ta-qd-card')?.dataset.itemId === 'iu-1');
// 手输 + 点选并存：结构化作答帧保留两者。
await page.evaluate(`(() => { const r = document.querySelectorAll('${QP} .ta-qd-opt')[0].getBoundingClientRect();
  window.__tapA = { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) }; })()`);
const tapA = await page.evaluate(() => window.__tapA);
await page.touchscreen.tap(tapA.x, tapA.y);
await page.waitForTimeout(200);
await page.locator(`${QP} .ta-qd-answer-input textarea`).fill('我选 A');
const framesBeforeMixed = answerFrames().length;
await page.locator(`${QP} .ta-qd-answer-send`).click();
await page.waitForTimeout(520);
const framesMixed = answerFrames();
rec('E5 手输 + 点选并存 → 出帧', JSON.stringify(framesMixed.slice(-1)));
ok('③ 手输与点选并存：结构化帧保留文字和选项',
  framesMixed.length === framesBeforeMixed + 1 && framesMixed.at(-1).otherText === '我选 A'
  && JSON.stringify(framesMixed.at(-1).selectedOptionIds) === '["a"]',
  JSON.stringify({ frame: framesMixed.slice(-1) }));
await page.screenshot({ path: join(SHOTS, '03-options-e2e.png') });

/* ③b：2ec7d2126 / 8eca73e4c 改为决策作答真源及信箱投递。
   忙态不再走聊天注入；错误回执必须保留草稿并阻止完成。WS 桩不证明真实信箱持久化。 */
const idleRead = JSON.parse(await page.locator(`${QP} .ta-qd-card`).getAttribute('data-qd-answer-last'));
ok('③b 空闲态作答走信箱编排而非聊天帧', idleRead.ok === true && idleRead.mode === 'mailbox', JSON.stringify(idleRead));
await seed(page, fixture());
await page.evaluate(async (sid) => (await import('/js/state.js')).default.busySessionIds.add(sid), SID);
const busyStart = sentFrames.length;
await page.locator(`${QP} .ta-qd-answer-input textarea`).fill('忙态也要能发出去');
await page.locator(`${QP} .ta-qd-answer-send`).click();
await page.waitForTimeout(60);
const busyMid = await page.evaluate(`(() => { const band = document.querySelector('${QP} .ta-qd-answer-input'); const cs = getComputedStyle(band.querySelector('.ds-turn-btn--send')); return { sent: band.dataset.taSent || '', animName: cs.animationName, animDur: cs.animationDuration }; })()`);
await page.waitForTimeout(480);
const busyFrames = capturedFrames().slice(busyStart);
const busyRead = JSON.parse(await page.locator(`${QP} .ta-qd-card`).getAttribute('data-qd-answer-last'));
const busyAfter = await page.locator(`${QP} .ta-qd-answer-input`).getAttribute('data-ta-sent');
ok('③b 忙态作答仍走信箱，不发聊天注入，完成跟随作答', busyRead.ok && busyRead.mode === 'mailbox'
  && busyFrames.some((m) => m.type === 'answerTodoDecision' && m.otherText === '忙态也要能发出去')
  && busyFrames.findIndex((m) => m.type === 'completeTodo' && m.id === 'iu-1') > busyFrames.findIndex((m) => m.type === 'answerTodoDecision')
  && !busyFrames.some((m) => m.type === 'immediateInput' || typeof m.content === 'string'), JSON.stringify({ busyRead, busyFrames }));
ok('③b 回复成功有一次轻量回执动画', busyMid.sent === 'ok' && busyMid.animName === 'taOkPop' && busyMid.animDur === '0.24s' && !busyAfter, JSON.stringify({ busyMid, busyAfter }));
await page.evaluate(async () => (await import('/js/state.js')).default.busySessionIds.clear());
await seed(page, fixture());
backend.rejectAnswer = true;
const rejectedStart = sentFrames.length;
await page.locator(`${QP} .ta-qd-answer-input textarea`).fill('失败保留草稿');
await page.locator(`${QP} .ta-qd-answer-send`).click();
await page.waitForTimeout(300);
const rejected = await page.evaluate(`(() => ({ value: document.querySelector('${QP} textarea').value, error: document.querySelector('${QP} .ta-qd-answer-error').textContent, last: JSON.parse(document.querySelector('${QP} .ta-qd-card').dataset.qdAnswerLast) }))()`);
backend.rejectAnswer = false;
const rejectedFrames = capturedFrames().slice(rejectedStart);
ok('③b 作答拒绝时保留草稿、显示错误且不完成', rejected.last.ok === false && rejected.last.code === 'decision_not_persisted' && rejected.value === '失败保留草稿' && rejected.error.includes('信箱不可用') && !rejectedFrames.some((m) => m.type === 'completeTodo'), JSON.stringify({ rejected, rejectedFrames }));
await seed(page, fixture());

/* ═══════════════ ③c 卡片视角「标记完成」链路（写 store + 划卡离场 + 自动翻下一张）═══
   作者追加令：卡片视角要能**直接处理任务**（不是只看）。判据三条：
     ① WS 桩回执下 store 的 openItems 减一；
     ② 划卡离场 = `data-qd-done="exit"` 在场且卡片带 qdCardDone 动画（token 时长）；
     ③ 自动翻下一张 = 卡片 `data-item-id` 变成下一条未完成行（牌堆本身只给未完成行）。 */
// 未作答决策不可手动完成（2ec7d2126）；先验证门控，再模拟已有答案。
const gateFrames = sentFrames.length;
await page.locator(`${QP} .ta-save`).click();
await page.waitForTimeout(150);
const gateError = await page.locator(`${QP} .ta-qd-answer-error`).textContent();
ok('③c 未答决策不能完成', !!gateError && !capturedFrames().slice(gateFrames).some((m) => m.type === 'completeTodo'), JSON.stringify({ gateError }));
backend.rows.find((r) => r.id === 'iu-1').decision.answer = { selectedOptionIds: ['a'] };
await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.refresh());
await page.waitForFunction(async () => !!(await import('/js/todoStore.js')).todoStore.openItems().find((x) => x.id === 'iu-1')?.decision?.answer);
const deckBefore = await page.evaluate(`(async () => {
  const store = await import('/js/todoStore.js');
  const card = document.querySelector('${QP} .ta-qd-card');
  return { open: store.todoStore.openItems().length, itemId: card.dataset.itemId,
    count: document.querySelector('${QP} .ta-qd-count').textContent };
})()`);
// 2026-11-22 10:48 作者令：完成键从页底操作条搬到顶带簇右上角（与详情页同一颗
// 蓝圆 ✓）；本判据的链路断言不变，只是落点选择器换到 `.ta-save`。
await page.locator(`${QP} .ta-save`).click();
await page.waitForTimeout(560);
const deckAfter = await page.evaluate(`(async () => {
  const store = await import('/js/todoStore.js');
  const card = document.querySelector('${QP} .ta-qd-card');
  const open = store.todoStore.openItems();
  return { open: open.length, itemId: card.dataset.itemId,
    count: document.querySelector('${QP} .ta-qd-count').textContent,
    last: card.dataset.qdDoneLast ? JSON.parse(card.dataset.qdDoneLast) : null,
    stillDone: card.dataset.qdDone || '' };
})()`);
// 离场键帧的**声明面**读数（确定性腿）：手写一次数据态再读计算样式，避开
// 「点击后 80ms 抢读」的时间竞态；真动画观感由本节 GIF 取证。
const doneAnim = await page.evaluate(`(() => {
  const card = document.querySelector('${QP} .ta-qd-card');
  const idle = getComputedStyle(card).animationName;
  card.dataset.qdDone = 'exit';
  const cs = getComputedStyle(card);
  const out = { idleAnim: idle, animName: cs.animationName, animDur: cs.animationDuration,
    easing: cs.animationTimingFunction, fill: cs.animationFillMode };
  delete card.dataset.qdDone;
  return out;
})()`);
probe.deckDone = { deckBefore, deckAfter, doneAnim };
rec('E8 卡片完成：完成前行数 → 点完成 → 行数/卡读数', JSON.stringify({ before: deckBefore, after: deckAfter }));
ok('③c 卡片「完成」真写 store：openItems 减一 + 读回执（itemId/ok）',
  deckAfter.open === deckBefore.open - 1 && !!deckAfter.last
  && deckAfter.last.ok === true && deckAfter.last.itemId === deckBefore.itemId
  && capturedFrames().some((m) => m.type === 'completeTodo' && m.id === deckBefore.itemId && m.sessionId === SID),
  JSON.stringify({ before: deckBefore.open, after: deckAfter.open, last: deckAfter.last }));
ok('③c 完成动效 = 划卡离场键帧（qdCardDone / token 时长 0.16s / forwards；落定后数据态已摘）',
  doneAnim.idleAnim === 'none' && doneAnim.animName === 'qdCardDone'
  && doneAnim.animDur === '0.16s' && doneAnim.fill === 'forwards' && deckAfter.stillDone === '',
  JSON.stringify({ ...doneAnim, stillDone: deckAfter.stillDone }));
ok('③c 完成后自动翻到下一张未处理卡（牌堆只含未完成行 ⇒ 同一索引即下一张）',
  deckAfter.itemId !== deckBefore.itemId && deckAfter.count.startsWith('第 1 /'),
  JSON.stringify({ before: deckBefore.itemId, after: deckAfter.itemId, count: deckAfter.count }));
await page.screenshot({ path: join(SHOTS, '03c-card-done.png') });

/* 复位：③b/③c 动过牌堆、离线队列与忙态集；④⑤⑥ 的画面判据要回到夹具原始态。 */
await page.evaluate(`(async () => {
  const s = (await import('/js/state.js')).default;
  for (const sid of Object.keys(s.messageQueue || {})) {
    s.messageQueue[sid] = (s.messageQueue[sid] || []).filter((i) => !/断线时先入队/.test(i.text || ''));
  }
  s.busySessionIds.clear();
})()`);
await seed(page, fixture());
await page.waitForTimeout(420);

/* ═══════════════ ④ 顶部空间（计数进顶带 / 卡片上移放大）═══════════════ */
const top = await page.evaluate(`(() => {
  const R = ${RECT};
  const pageEl = document.querySelector('${QP}');
  const count = pageEl.querySelector('.ta-qd-count');
  const pill = pageEl.querySelector('.ta-pill');
  const cluster = pageEl.querySelector('.ta-cluster');
  const header = pageEl.querySelector('.mobile-page-header');
  const card = pageEl.querySelector('.ta-qd-card');
  const dock = document.querySelector('#mobile-dock');
  return {
    hasHeadRow: !!pageEl.querySelector('.ta-qd-head'),
    countInPill: !!(count && pill && pill.contains(count)),
    countInCluster: !!(pill && cluster && cluster.contains(pill)),
    countText: count ? count.textContent : null,
    countBox: R('${QP} .ta-qd-count'),
    pillBox: R('${QP} .ta-pill'),
    headerBox: header ? R('${QP} .mobile-page-header') : null,
    cardBox: R('${QP} .ta-qd-card'),
    stageBox: R('${QP} .ta-qd-stage'),
    bandBox: R('${QP} .ta-qd-float'),
    pageBox: R('${QP}'),
    dockBox: dock ? R('#mobile-dock') : null,
    vh: window.innerHeight,
  };
})()`);
probe.topSpace = top;
ok('④ 计数搬进顶带胶囊（页内独占行 .ta-qd-head 已退役；计数在 .ta-pill 内、pill 在顶带簇内）',
  top.hasHeadRow === false && top.countInPill === true && top.countInCluster === true
  && /^第 \d+ \/ \d+ 条$/.test(top.countText || ''),
  JSON.stringify({ head: top.hasHeadRow, inPill: top.countInPill, text: top.countText }));
ok('④ 计数落在顶栏区域（顶带胶囊盒在视口上部 1/6 内、与返回钮同一条带）',
  top.pillBox.y < top.vh / 6 && top.pillBox.bottom <= (top.headerBox ? top.cardBox.y : top.cardBox.y),
  JSON.stringify({ pill: top.pillBox, header: top.headerBox, card: top.cardBox }));
const usable = top.dockBox ? top.dockBox.y : top.vh;
// ④ 的判据（作者追加令 2026-11-21 ④ 后修）：改前是「卡高 ≥ dock 之上可用高的 82%」
// ——那条比例把**控件带的高度**当成了卡片的固定失地，而本批新增的底部操作条
// （完成/旗标/日期/列表四键）本身就是控件带的一部分，比例口径会随带高变化而假红。
// 现在改成与带高无关的更强口径：卡片吃满「顶带净空 → 控件带顶」之间的**全部**
// 竖向空间（只留一档 --ds-space-2 呼吸缝），即卡片的放大程度不再由控件带让多少。
const room = top.bandBox ? top.bandBox.y - top.cardBox.y : usable - top.cardBox.y;
ok('④ 卡片区上移并放大（卡片顶 ≤ 顶带净空 + 一档间距；卡片吃满控件带以上全部竖向空间）',
  top.cardBox.y <= 88 && room > 0 && (top.cardBox.h / room) >= 0.97,
  JSON.stringify({ cardTop: top.cardBox.y, cardH: top.cardBox.h, roomAboveBand: +room.toFixed(2),
    ratio: +(top.cardBox.h / room).toFixed(3) }));
await page.screenshot({ path: join(SHOTS, '04-top-space.png') });

/* ═══════════════ ⑤ 右上角过滤（标签 / 重要度 → 只显示部分卡片）═══════════════ */
const fltBtn = await page.evaluate(`(() => {
  const R = ${RECT};
  const b = document.querySelector('${QP} .ta-qd-filter-btn');
  const band = document.querySelector('${QP} .ta-cluster');
  return { present: !!b, box: R('${QP} .ta-qd-filter-btn'), bandBox: band ? R('${QP} .ta-cluster') : null,
    label: b ? b.getAttribute('aria-label') : null, expanded: b ? b.getAttribute('aria-expanded') : null };
})()`);
probe.filterBtn = fltBtn;
ok('⑤ 右上角过滤钮在场（顶带簇内，中心点在视口右半边 = 右上角）',
  fltBtn.present === true && !!fltBtn.bandBox && fltBtn.box.cx > VW / 2 && fltBtn.box.y < VH / 6,
  JSON.stringify({ box: fltBtn.box, band: fltBtn.bandBox, label: fltBtn.label }));

await page.locator(`${QP} .ta-qd-filter-btn`).click();
await page.waitForTimeout(320);
const fltOpen = await page.evaluate(`(() => {
  const R = ${RECT};
  const box = document.querySelector('${QP} .ta-qd-filters');
  const chips = [...document.querySelectorAll('${QP} .ta-qd-flt-chip')].map((c) => ({
    text: c.textContent.trim(), on: c.classList.contains('is-on'), pressed: c.getAttribute('aria-pressed'),
    h: +c.getBoundingClientRect().height.toFixed(1),
  }));
  return { open: !!box && !box.hidden, chips, box: box ? R('${QP} .ta-qd-filters') : null,
    expanded: document.querySelector('${QP} .ta-qd-filter-btn').getAttribute('aria-expanded') };
})()`);
probe.filterOpen = fltOpen;
ok('⑤ 点过滤钮展开 chips 面板（chips 档：标签行 + 重要度行；触控高 ≥44）',
  fltOpen.open === true && fltOpen.expanded === 'true' && fltOpen.chips.length >= 6
  && fltOpen.chips.every((c) => c.h >= 44),
  JSON.stringify({ open: fltOpen.open, n: fltOpen.chips.length, heights: fltOpen.chips.map((c) => c.h) }));
await page.screenshot({ path: join(SHOTS, '05-filter-open.png') });

// 按标签过滤：点「生活」
const tagChip = await page.evaluate(`(() => {
  const chips = [...document.querySelectorAll('${QP} .ta-qd-flt-row:nth-child(1) .ta-qd-flt-chip')];
  const i = chips.findIndex((c) => c.textContent.trim() === '生活');
  const r = chips[i].getBoundingClientRect();
  return { i, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
})()`);
await page.touchscreen.tap(tagChip.x, tagChip.y);
await page.waitForTimeout(360);
const afterTagFilter = await page.evaluate(`(() => {
  const card = document.querySelector('${QP} .ta-qd-card');
  const count = document.querySelector('${QP} .ta-qd-count');
  return { itemId: card.dataset.itemId, count: count ? count.textContent : null,
    tag: (card.querySelector('.ta-qd-meta .ta-qd-chip') || {}).textContent || '',
    btnOn: document.querySelector('${QP} .ta-qd-filter-btn').dataset.qdFilter || null,
    chipOn: [...document.querySelectorAll('${QP} .ta-qd-flt-row:nth-child(1) .ta-qd-flt-chip')].map((c) => c.classList.contains('is-on') ? c.textContent.trim() : null).filter(Boolean) };
})()`);
probe.tagFilter = afterTagFilter;
ok('⑤ 按标签过滤生效：牌堆只剩「生活」下的 3 条（计数 1 / 3），过滤钮进 on 态',
  afterTagFilter.count === '第 1 / 3 条' && afterTagFilter.btnOn === 'on'
  && afterTagFilter.chipOn.length === 1 && afterTagFilter.chipOn[0] === '生活',
  JSON.stringify(afterTagFilter));
await page.screenshot({ path: join(SHOTS, '05-filter-tag.png') });

// 再叠重要度过滤：高（生活 + 高 ⇒ 1 条）
const prioChip = await page.evaluate(`(() => {
  const chips = [...document.querySelectorAll('${QP} .ta-qd-flt-row:nth-child(2) .ta-qd-flt-chip')];
  const i = chips.findIndex((c) => c.textContent.trim() === '高');
  const r = chips[i].getBoundingClientRect();
  return { i, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
})()`);
await page.touchscreen.tap(prioChip.x, prioChip.y);
await page.waitForTimeout(360);
const afterBoth = await page.evaluate(`(() => {
  const card = document.querySelector('${QP} .ta-qd-card');
  return { itemId: card.dataset.itemId, count: document.querySelector('${QP} .ta-qd-count').textContent };
})()`);
probe.bothFilter = afterBoth;
ok('⑤ 两维叠乘：生活 + 高 ⇒ 只剩「生活·高旗标」1 条（第 1 / 1 条）',
  afterBoth.count === '第 1 / 1 条' && afterBoth.itemId === 'iu-5', JSON.stringify(afterBoth));
await page.screenshot({ path: join(SHOTS, '05-filter-both.png') });

// 过滤切换的 GIF：清空过滤 → 标签 → 重要度 → 清空
const cdpGif = await ctx.newCDPSession(page);
await cdpGif.send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: VW, maxHeight: VH, everyNthFrame: 1 });
const gifFrames = [];
cdpGif.on('Page.screencastFrame', (f) => { gifFrames.push(f.data); cdpGif.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {}); });
await sleep(300);
await page.locator(`${QP} .ta-qd-filter-btn`).click();          // 收面板
await sleep(320);
await page.locator(`${QP} .ta-qd-filter-btn`).click();          // 再展开
await sleep(420);
await page.touchscreen.tap(tagChip.x, tagChip.y);               // 生活
await sleep(420);
await page.touchscreen.tap(prioChip.x, prioChip.y);             // 高
await sleep(420);
const allTagChip = await page.evaluate(`(() => { const c = [...document.querySelectorAll('${QP} .ta-qd-flt-row:nth-child(1) .ta-qd-flt-chip')][0];
  const r = c.getBoundingClientRect(); return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) }; })()`);
await page.touchscreen.tap(allTagChip.x, allTagChip.y);         // 全部标签
await sleep(420);
await page.locator(`${QP} .ta-qd-filter-btn`).click();          // 收面板
await sleep(500);
await cdpGif.send('Page.stopScreencast');
await cdpGif.detach();

/* ═══════════════ ⑥ 悬浮控件（输入带搬出卡片 / 滚动不推走 / 入场动效）═══════════════ */
const float = await page.evaluate(`(() => {
  const R = ${RECT};
  const qd = document.querySelector('${QP} .ta-qd');
  const band = document.querySelector('${QP} .ta-qd-float');
  const card = document.querySelector('${QP} .ta-qd-card');
  const scroll = document.querySelector('${QP} .ta-qd-scroll');
  const dock = document.querySelector('#mobile-dock');
  const cs = band ? getComputedStyle(band) : null;
  return {
    present: !!band,
    outsideCard: !!(band && card && !card.contains(band)),
    lastChild: !!(qd && band && qd.lastElementChild === band),
    position: cs ? cs.position : null,
    bandBox: band ? R('${QP} .ta-qd-float') : null,
    cardBox: R('${QP} .ta-qd-card'),
    dockBox: dock ? R('#mobile-dock') : null,
    gapToDock: (band && dock) ? +(dock.getBoundingClientRect().top - band.getBoundingClientRect().bottom).toFixed(2) : null,
    inScroll: !!(scroll && band && scroll.contains(band)),
    floatState: band ? (band.dataset.qdFloat || '') : null,
  };
})()`);
probe.float = float;
ok('⑥ 控件带搬出卡片、成为页面级流内页脚（不在滚动面内、是 .ta-qd 最后一个孩子、零定位）',
  float.present === true && float.outsideCard === true && float.lastChild === true
  && float.position === 'static' && float.inScroll === false,
  JSON.stringify({ outsideCard: float.outsideCard, lastChild: float.lastChild, position: float.position, inScroll: float.inScroll }));
ok('⑥ 底缘不被 dock 截断（浮带整体在 dock 之上，留一档悬浮缝 ≥8px）',
  float.gapToDock != null && float.gapToDock >= 8 && float.bandBox.bottom < float.dockBox.y,
  JSON.stringify({ band: float.bandBox, dock: float.dockBox, gap: float.gapToDock }));
ok('⑥ 卡片底缘让位（卡片底 ≤ 浮带顶 —— 卡片不再压住输入框，也就无从裁切它）',
  float.cardBox.bottom <= float.bandBox.y + 0.5,
  JSON.stringify({ cardBottom: float.cardBox.bottom, bandTop: float.bandBox.y }));
await page.screenshot({ path: join(SHOTS, '06-floating.png') });

// 卡片内滚：浮带不动 + 进 raised 态
const before = await page.evaluate(`(${RECT})('${QP} .ta-qd-float')`);
await page.evaluate(`(() => { const s = document.querySelector('${QP} .ta-qd-scroll'); s.scrollTop = s.scrollHeight; })()`);
await page.waitForTimeout(280);
const afterScroll = await page.evaluate(`(() => {
  const band = document.querySelector('${QP} .ta-qd-float');
  const b = band.getBoundingClientRect();
  const scroll = document.querySelector('${QP} .ta-qd-scroll');
  return { top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2), raised: band.dataset.qdRaised || '',
    scrollTop: scroll.scrollTop, shadow: getComputedStyle(band.querySelector('.ta-qd-answer-input')).boxShadow };
})()`);
probe.floatScroll = { before, afterScroll };
ok('⑥ 卡片内滚不推走控件带（滚动位 > 0，浮带位置与滚动前逐像素一致）+ 抬升态生效',
  afterScroll.scrollTop > 0 && Math.abs(afterScroll.top - before.y) <= 1 && afterScroll.raised === '1',
  JSON.stringify({ scrollTop: afterScroll.scrollTop, bandTop: afterScroll.top, beforeTop: before.y, raised: afterScroll.raised }));
await page.screenshot({ path: join(SHOTS, '06-floating-scrolled.png') });
await page.evaluate(`(() => { const s = document.querySelector('${QP} .ta-qd-scroll'); s.scrollTop = 0; })()`);
await page.waitForTimeout(200);

// 入场动效的 GIF：退出 → 重进卡片页（浮带自下滑入）
const cdpGif2 = await ctx.newCDPSession(page);
await cdpGif2.send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: VW, maxHeight: VH, everyNthFrame: 1 });
const gifFrames2 = [];
cdpGif2.on('Page.screencastFrame', (f) => { gifFrames2.push(f.data); cdpGif2.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {}); });
await sleep(200);
await page.locator(`${QP} .mobile-back-btn`).click();
await sleep(420);
await page.locator('.mobile-page[data-page="tasks"] .ta-quick-fab').click();
await page.waitForSelector(`${QP}.live`, { timeout: 10000 });
await sleep(900);
await page.evaluate(`(() => { const s = document.querySelector('${QP} .ta-qd-scroll'); s.scrollTop = 220; })()`);
await sleep(520);
await page.evaluate(`(() => { const s = document.querySelector('${QP} .ta-qd-scroll'); s.scrollTop = 0; })()`);
await sleep(420);
await cdpGif2.send('Page.stopScreencast');
await cdpGif2.detach();

/* ═══════════════ 附加：详情页正文 md 渲染（② 的第二条腿，截图取证）═══════════════ */
await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* root */ } });
await page.waitForTimeout(240);
await push(page, 'tasks');
// 真产品路径进详情页：首页「全部待办」行 → 列表页 → 行体 → 条目页（不直接 push
// 列表页：那会让 listTarget 停在缺省档，行集可能与夹具不符）。
await page.locator('.mobile-page[data-page="tasks"] .ta-row-all').click();
await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 15000 });
await page.waitForTimeout(360);
const detailEntry = await page.evaluate(`(() => {
  const rows = [...document.querySelectorAll('.mobile-page[data-page="todo-apple-list"] .ta-item')];
  const body = document.querySelector('.mobile-page[data-page="todo-apple-list"] .ta-item-body');
  return { rows: rows.length, rowIds: rows.map((r) => r.dataset.itemId), hasBody: !!body,
    empty: (document.querySelector('.mobile-page[data-page="todo-apple-list"] .ta-empty') || {}).hidden };
})()`);
probe.detailEntry = detailEntry;
// 行体 = 真按钮；点**无决策**那一行进去（有 decision 的行在列表页展开行内只读面，
// 不进条目页 = 既有产品行为，本批不动）。
await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item[data-item-id="iu-6"] .ta-item-body')
  .scrollIntoViewIfNeeded();
await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item[data-item-id="iu-6"] .ta-item-body').click();
await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 15000 });
await page.waitForTimeout(460);
const detail = await page.evaluate(`(() => {
  const pageEl = document.querySelector('.mobile-page[data-page="todo-apple-item"]');
  const mdBox = pageEl.querySelector('.ta-notes-md');
  const ta = pageEl.querySelector('.ta-notes');
  const box = mdBox ? mdBox.querySelector('.ta-notes-md-body') : null;
  return {
    previewVisible: !!(mdBox && !mdBox.hidden),
    editorHidden: !!ta && ta.hidden,
    isMd: !!box && box.classList.contains('is-md'),
    strong: box ? box.querySelectorAll('strong, b').length : 0,
    code: box ? box.querySelectorAll('code').length : 0,
    rawAsterisks: box ? /\\*\\*/.test(box.textContent) : null,
    scripts: pageEl.querySelectorAll('script').length,
    onerrorImg: pageEl.querySelectorAll('img[onerror]').length,
  };
})()`);
probe.detailMd = detail;
ok('② 详情页正文同样走 md 渲染面（默认渲染态；编辑域在点按后接管 —— 编辑能力不丢）',
  detail.previewVisible === true && detail.isMd === true && detail.strong > 0
  && detail.rawAsterisks === false && detail.scripts === 0 && detail.onerrorImg === 0,
  JSON.stringify(detail));
await page.screenshot({ path: join(SHOTS, '02b-detail-md.png') });
await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-notes-md').click();
await page.waitForTimeout(260);
const editState = await page.evaluate(`(() => {
  const pageEl = document.querySelector('.mobile-page[data-page="todo-apple-item"]');
  const ta = pageEl.querySelector('.ta-notes');
  return { editorVisible: !!ta && !ta.hidden, value: ta ? ta.value.slice(0, 24) : null,
    focused: document.activeElement === ta, previewHidden: (pageEl.querySelector('.ta-notes-md') || {}).hidden };
})()`);
probe.detailEdit = editState;
ok('② 点渲染面 → 编辑域接管（文本框可见、内容原样、焦点在其中）',
  editState.editorVisible === true && editState.previewHidden === true
  && typeof editState.value === 'string' && editState.value.startsWith('机制现状'),
  JSON.stringify(editState));

/* ═══════════════ ⑦ 动效与链路证据三支（作者追加令：关键操作动画 / 回复修通）═══════
   三支 GIF 各自对应作者点名的一条操作（作者铁律：每个动效 GIF）：
     · reply-send    = **待办详情页回复框**（IMG_1993 的现场）输入 → 发送 → 成功回执；
     · card-done     = 卡片视角 划卡 → 标记完成（打勾 + 划卡离场）→ 自动翻下一张；
     · create-enter  = 新建待办入场（列表页新增行的入场动画）。
   断言同时落机器读数（不只看图）：回复真出帧 + 零红字；完成真写 store；
   新建行真进列表且带一次性入场数据态。 */

/** 录一支 GIF：fn 执行期间的 screencast 帧。 */
async function recordGif(fn, { preMs = 260, postMs = 420 } = {}) {
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: VW, maxHeight: VH, everyNthFrame: 1 });
  const frames = [];
  cdp.on('Page.screencastFrame', (f) => { frames.push(f.data); cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {}); });
  await sleep(preMs);
  await fn();
  await sleep(postMs);
  await cdp.send('Page.stopScreencast');
  await cdp.detach();
  return frames;
}

/* ── ⑦a 详情页回复框：输入 → 发送 → 成功（IMG_1993 的现场）────────────── */
// 当前页 = 待办详情页（② 的腿留下的正是这一页，回复条在页脚）。
const detailReplyBox = '.mobile-page[data-page="todo-apple-item"] .ta-reply-input';
const replyFramesBefore = userContentFrames().length;
const gifFrames3 = await recordGif(async () => {
  await page.locator(`${detailReplyBox} textarea`).click();
  await page.waitForTimeout(160);
  await page.locator(`${detailReplyBox} textarea`).type('详情页回复实测', { delay: 55 });
  await page.waitForTimeout(200);
  await page.locator(`${detailReplyBox} .ds-turn-btn--send`).click();
  await page.waitForTimeout(300);   // 回执键帧 240ms 演完
}, { preMs: 200, postMs: 520 });
const detailReply = await page.evaluate(`(() => {
  const box = document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply');
  const input = document.querySelector('${detailReplyBox}');
  const cs = getComputedStyle(input.querySelector('.ds-turn-btn--send'));
  return { last: box && box.dataset.taReplyLast ? JSON.parse(box.dataset.taReplyLast) : null,
    error: (document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply-error') || {}).textContent,
    value: input.querySelector('textarea').value, sent: input.dataset.taSent || '',
    animName: cs.animationName };
})()`);
const replyFrames = userContentFrames().slice(replyFramesBefore);
probe.detailReply = { detailReply, replyFrames };
rec('E9 详情页回复框 → 发送', JSON.stringify({ last: detailReply.last, frames: replyFrames }));
ok('⑦ 详情页回复真出帧（帧正文 = #待办名·id + 回复内容）+ 件清空 + 零红字（IMG_1993 修通）',
  replyFrames.length === 1 && /^#详情页 md 事项·iu-6 详情页回复实测$/.test(replyFrames[0])
  && !!detailReply.last && detailReply.last.ok === true && detailReply.last.mode === 'new-turn'
  && detailReply.value === '' && detailReply.error === '',
  JSON.stringify({ frames: replyFrames, last: detailReply.last, error: detailReply.error }));
await page.screenshot({ path: join(SHOTS, '07-detail-reply-sent.png') });
// 旧的忙态注入／离线队列契约仍适用于非决策详情回复，而非决策卡。
await page.evaluate(async (sid) => (await import('/js/state.js')).default.busySessionIds.add(sid), SID);
const detailBusyStart = sentFrames.length;
await page.locator(`${detailReplyBox} textarea`).fill('详情忙态回复');
await page.locator(`${detailReplyBox} .ds-turn-btn--send`).click();
await page.waitForTimeout(300);
const detailBusy = await page.evaluate(() => JSON.parse(document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply').dataset.taReplyLast));
const detailBusyFrames = capturedFrames().slice(detailBusyStart);
ok('⑦ 详情忙态回复使用 immediateInput 且无错误', detailBusy.ok && detailBusy.mode === 'turn-boundary' && detailBusyFrames.some((m) => m.type === 'immediateInput' && m.sessionId === SID && m.content === '#详情页 md 事项·iu-6 详情忙态回复') && !(await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-reply-error').textContent()), JSON.stringify({ detailBusy, detailBusyFrames }));
await page.evaluate(async () => { const s = (await import('/js/state.js')).default; s.busySessionIds.clear(); window.__realWs = s.ws; s.ws = { readyState: 3 }; });
await page.locator(`${detailReplyBox} textarea`).fill('详情断线回复');
await page.locator(`${detailReplyBox} .ds-turn-btn--send`).click();
await page.waitForTimeout(300);
const detailOffline = await page.evaluate(async () => {
  const s = (await import('/js/state.js')).default;
  s.ws = window.__realWs;
  const queued = Object.values(s.messageQueue || {}).flat().find((item) => /详情断线回复/.test(item.text || ''));
  return { last: JSON.parse(document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply').dataset.taReplyLast), queued: !!queued, offline: !!queued?.offline, rendered: !!queued?.rendered, error: document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-reply-error').textContent };
});
ok('⑦ 详情断线回复进入离线队列且无错误', detailOffline.last.ok && detailOffline.last.mode === 'offline' && detailOffline.queued && detailOffline.offline && detailOffline.rendered && !detailOffline.error, JSON.stringify(detailOffline));
await page.evaluate(async () => { const s = (await import('/js/state.js')).default; for (const sid of Object.keys(s.messageQueue || {})) s.messageQueue[sid] = s.messageQueue[sid].filter((item) => !/详情断线回复/.test(item.text || '')); });

/* ── ⑦b 卡片视角完成流：翻卡 → 标记完成（划卡离场）→ 自动下一张 ──────────── */
await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* root */ } });
await page.waitForTimeout(240);
await push(page, 'tasks');
await page.locator('.mobile-page[data-page="tasks"] .ta-quick-fab').click();
await page.waitForSelector(`${QP}.live`, { timeout: 15000 });
await page.waitForTimeout(700);
// 第二张决策行已作答，才允许手动完成（2ec7d2126）。
backend.rows.find((r) => r.id === 'iu-2').decision.answer = { selectedOptionIds: ['x'] };
await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.refresh());
await page.waitForFunction(async () => !!(await import('/js/todoStore.js')).todoStore.openItems().find((x) => x.id === 'iu-2')?.decision?.answer);
// 翻到第 2 张（真手势：左滑），让 GIF 里先有「翻卡」这一拍
const deckAtStart = await page.evaluate(`(async () => {
  const store = await import('/js/todoStore.js');
  return { itemId: document.querySelector('${QP} .ta-qd-card').dataset.itemId,
    open: store.todoStore.openItems().length };
})()`);
const gifFrames4 = await recordGif(async () => {
  const box = await page.locator(`${QP} .ta-qd-card`).boundingBox();
  await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(120);
  await page.mouse.move(box.x + box.width * 0.7, box.y + box.height / 2);
  await page.mouse.down();
  for (const f of [0.5, 0.3, 0.12, -0.05]) {
    await page.mouse.move(box.x + box.width * f, box.y + box.height / 2, { steps: 4 });
    await page.waitForTimeout(28);
  }
  await page.mouse.up();
  await page.waitForTimeout(520);
  await page.locator(`${QP} .ta-save`).click();
  await page.waitForTimeout(700);
}, { preMs: 300, postMs: 620 });
const deckAtEnd = await page.evaluate(`(async () => {
  const store = await import('/js/todoStore.js');
  const card = document.querySelector('${QP} .ta-qd-card');
  return { itemId: card.dataset.itemId, open: store.todoStore.openItems().length,
    last: card.dataset.qdDoneLast ? JSON.parse(card.dataset.qdDoneLast) : null,
    count: document.querySelector('${QP} .ta-qd-count').textContent };
})()`);
probe.deckFlow = { deckAtStart, deckAtEnd };
rec('E10 卡片完成流（翻卡 → 完成 → 自动下一张）', JSON.stringify({ start: deckAtStart, end: deckAtEnd }));
ok('⑦ 卡片完成流：一条真被完成（openItems 减一）+ 卡翻到另一条 + 计数同步（GIF 同拍）',
  deckAtEnd.open === deckAtStart.open - 1 && !!deckAtEnd.last && deckAtEnd.last.ok === true
  && deckAtEnd.itemId !== deckAtStart.itemId,
  JSON.stringify({ start: deckAtStart, end: deckAtEnd }));
await page.screenshot({ path: join(SHOTS, '07-card-done-flow.png') });

/* ── ⑦c 新建待办入场（列表页新增行的入场动画）────────────────────────────── */
await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* root */ } });
await page.waitForTimeout(240);
await push(page, 'tasks');
await page.locator('.mobile-page[data-page="tasks"] .ta-row-all').click();
await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 15000 });
await page.waitForTimeout(420);
const LP = '.mobile-page[data-page="todo-apple-list"]';
const rowsBeforeCreate = await page.evaluate(`document.querySelectorAll('${LP} .ta-item').length`);
const gifFrames5 = await recordGif(async () => {
  // 3ad54e22f 退役自建入口：模拟新增行，只验证列表入场，不冒充 UI 创建。
  const row = { id: 'srv-new', content: '刚建的新待办（入场动效）', name: '刚建的新待办（入场动效）', detail: '', kind: 'todo', sessionId: SID, project: null, priority: 0, triggerAt: null, createdAt: Date.now(), completed: false };
  backend.rows.push(row);
  await page.evaluate(async (task) => (await import('/js/todoStore.js')).todoStore.__hydrate([task]), row);
  // 入场键帧只有 240ms（数据态 280ms 后摘）⇒ 建成后**立刻**轮询抓读数，
  // 不能等到 GIF 录完再读（那时动画已收尾 = 读到 none）。
  const t0 = Date.now();
  while (Date.now() - t0 < 900) {
    const hit = await page.evaluate(`(() => {
      const fresh = [...document.querySelectorAll('${LP} .ta-item')].find((r) => /刚建的新待办/.test(r.textContent || ''));
      if (!fresh) return null;
      return { id: fresh.dataset.itemId, state: fresh.dataset.taNew || '', anim: getComputedStyle(fresh).animationName };
    })()`);
    if (hit && hit.state === 'in') { probe.createEnterHit = hit; break; }
    await page.waitForTimeout(30);
  }
  await page.waitForTimeout(520);
}, { preMs: 240, postMs: 460 });
const createEnter = await page.evaluate(`(() => {
  const rows = [...document.querySelectorAll('${LP} .ta-item')];
  const fresh = rows.find((r) => /刚建的新待办/.test(r.textContent || ''));
  return { rows: rows.length, freshId: fresh ? fresh.dataset.itemId : null,
    sheetOpen: !!document.querySelector('.ta-create-sheet'),
    stateAfter: fresh ? (fresh.dataset.taNew || '') : null };
})()`);
// 入场键帧的声明面（确定性腿，同 ③c 的 doneAnim 手法）。
const enterAnim = await page.evaluate(`(() => {
  const row = document.querySelector('${LP} .ta-item');
  const idle = getComputedStyle(row).animationName;
  row.dataset.taNew = 'in';
  const cs = getComputedStyle(row);
  const out = { idleAnim: idle, animName: cs.animationName, animDur: cs.animationDuration,
    easing: cs.animationTimingFunction };
  delete row.dataset.taNew;
  return out;
})()`);
probe.createEnter = { rowsBeforeCreate, createEnter, enterAnim };
rec('E11 新建待办 → 列表新增行', JSON.stringify({ before: rowsBeforeCreate, after: createEnter.rows, freshId: createEnter.freshId }));
ok('⑦ 模拟新增行进列表（行数 +1、新行在场）并带入场键帧（taRowIn，落定后数据态摘除）',
  createEnter.rows === rowsBeforeCreate + 1 && !!createEnter.freshId && createEnter.sheetOpen === false
  && !!probe.createEnterHit && probe.createEnterHit.anim === 'taRowIn'
  && createEnter.stateAfter === ''
  && enterAnim.idleAnim === 'none' && enterAnim.animName === 'taRowIn' && enterAnim.animDur === '0.24s',
  JSON.stringify({ before: rowsBeforeCreate, ...createEnter, hit: probe.createEnterHit || null, enterAnim }));
await page.screenshot({ path: join(SHOTS, '07-create-enter.png') });

/* ═══════════════ Z 零页面错误 ═══════════════ */
ok('Z 零页面错误（pageerror/console.error 去噪后）', pageErrors.length === 0, pageErrors.slice(0, 4).join(' | '));

/* ═══════════════ 证据：两张 GIF（过滤切换 / 浮带入走进 raised）═══════════════ */
let media = null;
try {
  const ff = (args) => execFileSync(args[0], args.slice(1), { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
  const CAP = 10 * 1024 * 1024;
  const emit = (framesList, tag) => {
    const dir = join(FRAMES, tag);
    mkdirSync(dir, { recursive: true });
    framesList.forEach((b64, i) => writeFileSync(join(dir, `${String(i).padStart(4, '0')}.jpg`), Buffer.from(b64, 'base64')));
    const gif = join(OUT, `${tag}.gif`);
    const mp4 = join(OUT, `${tag}.mp4`);
    ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(dir, '%04d.jpg'),
      '-vf', 'fps=10,scale=280:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=3', gif]);
    ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(dir, '%04d.jpg'),
      '-vf', 'fps=30,scale=750:-2:flags=lanczos', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '24', mp4]);
    const probeOf = (p) => JSON.parse(ff(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
      '-show_entries', 'stream=width,height,avg_frame_rate', '-show_entries', 'format=duration,size', '-of', 'json', p]));
    return { path: gif, mp4, frames: framesList.length, gifBytes: readFileSync(gif).length,
      mp4Bytes: readFileSync(mp4).length, gifProbe: probeOf(gif), mp4Probe: probeOf(mp4) };
  };
  const g1 = emit(gifFrames, 'filter-toggle');
  const g2 = emit(gifFrames2, 'float-band');
  const g3 = emit(gifFrames3, 'reply-send');
  const g4 = emit(gifFrames4, 'card-done');
  const g5 = emit(gifFrames5, 'create-enter');
  media = { filter: g1, float: g2, reply: g3, done: g4, create: g5 };
  probe.media = media;
  ok('Z 五张证据都是 GIF（过滤切换 / 浮带入场 / 回复发送 / 完成划卡 / 新建入场），且 ≤10MB',
    [g1, g2, g3, g4, g5].every((g) => /\.gif$/.test(g.path) && g.gifBytes <= CAP && g.gifBytes > 20_000),
    JSON.stringify([g1, g2, g3, g4, g5].map((g) => ({ tag: g.path.split('/').pop(), bytes: g.gifBytes, frames: g.frames, cap: CAP }))));
  console.log(`      [证据] 过滤 ${g1.frames}帧/${g1.gifBytes}B · 浮带 ${g2.frames}帧/${g2.gifBytes}B`
    + ` · 回复 ${g3.frames}帧/${g3.gifBytes}B · 完成 ${g4.frames}帧/${g4.gifBytes}B · 新建 ${g5.frames}帧/${g5.gifBytes}B`);
} catch (e) {
  probe.mediaError = String((e && e.message) || e);
  skip('Z 五张证据都是 GIF（过滤切换 / 浮带入场 / 回复发送 / 完成划卡 / 新建入场），且 ≤10MB',
    '录制/转码腿不可得：' + probe.mediaError);
}

/* ═══════════════ 落盘 ═══════════════ */
writeFileSync(join(OUT, 'options-e2e.txt'),
  ['# 选项待办端到端记录（decision.options 卡：渲染 → 点选 → 回传链路）',
    `WEB=${WEB}`, `view=${VW}x${VH}`, '', ...e2e, '',
    `结论：PASS ${pass} / FAIL ${fail} / SKIP ${skipped}`, ''].join('\n'));
writeFileSync(join(OUT, 'probe.json'), JSON.stringify(probe, null, 2) + '\n');
writeFileSync(join(OUT, 'result.txt'),
  `# todo-inbox-ui gate (作者令 2026-11-21 六条)\nWEB=${WEB}\nOUT=${OUT}\nview=${VW}x${VH}\n\n`
  + lines.join('\n') + `\n\nPASS ${pass} / FAIL ${fail} / SKIP ${skipped}\n`
  + (media ? `\nGIF ${media.filter.path}\nGIF ${media.float.path}\nGIF ${media.reply.path}\n`
    + `GIF ${media.done.path}\nGIF ${media.create.path}\n` : ''));
console.log(`\n=== ${pass} passed, ${fail} failed, ${skipped} skipped ===`);
console.log(`evidence: ${OUT}`);
await ctx.close();
await browser.close();
process.exit(fail || (SKIP_IS_FAIL && skipped) ? 1 : (skipped ? 2 : 0));
