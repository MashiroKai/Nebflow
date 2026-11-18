#!/usr/bin/env node
// tests/todo-quick-decision.spec.mjs — 待办「快速决策」视角（todo-quick-decision）
// 的可执行闸 + 证据腿（作者令 2026-11-18 17:49「首页右下角左侧加一枚叠卡图标钮，
// 点进去进入 dating app 视角左右滑切换待办，中间卡片显示内容和问题」+
// 2026-11-18 17:52 追加「卡片要能承载选项+媒体这类长内容，必须支持下滑查看，
// 滚动不能吞掉点击/选择」）。
//
// 判据（逐条对应任务书验收）：
//   E1 入口钮：左下 `.ta-quick-fab` 在场、aria-label = 快速决策、几何与右下「+」
//      同规格（同尺寸/同底缘/左右内距对称）、两者零交叠；band 指针透明
//      （两钮之间那一点命中的是列表内容，不是挡板）；「+」的可点区域未被影响
//      （中心点反查仍是加号钮 + 真点击仍开新建抽屉）。
//   E2 全屏形态：push 后 = 手机全屏页（375×812 铺满、.live、页壳返回钮在场、
//      不是弹窗/内嵌面板 —— 页内零 dialog/overlay 节点）。
//   E3 卡片读数：内容（name + detail）与问题（decision.question）+ 选项行
//      （原生 radio/checkbox，与列表页同一控制件）都在卡上；卡上 id 可机读。
//   E4 手势：跟手（拖动中姿势读数 ≈ 位移，差 ≤2px）→ 左滑 = 下一条 / 右滑 = 上一条
//      （切换后卡片 id 与手势方向一致）→ 收尾动效走 token（exit = --ds-dur-hover /
//      enter = --ds-dur-surface，模块常量与 token 逐值同源由本闸钉住）→ 首尾不环绕
//      （停在原地 + 回弹，索引不变）。
//   E5 滚动与选中并存：长内容下卡片内滚（scrollHeight > clientHeight）可下滑；
//      竖滑不切卡；下滑之后再点选项仍能选中（选中态走 :has(:checked)）；
//      点击不改变滚动位；选项槽可承载高块（媒体位留槽的版式与高度策略）。
//   D  问题可见 + 回答输入区（作者 2026-11-18 报障批「手机端待办 决策卡视角
//      问题和回答输入框呢 现在怎么好像只有内容？」）：长正文不得把问题顶到折叠线
//      下（未滚动时 `.ta-qd-q` 完整落在卡片可视区内 + 问题排在正文之前）；卡内
//      必须有真回答框（textarea + 发送键，卡底固定带、不在滚动面内、底缘不被
//      dock 截断）；真填字读回真值；真提交出帧逐字进既有回复链路
//      （`#<待办名>·<id> <回答>`）；提交后输入框清空；长正文滚到底输入框仍在原位；
//      回答之后左右滑仍切卡。
//      ⚠️ D 组整组对**未修源码**直跑必红（QD_WEB_ROOT=<未修树>）——检测力自证。
//
// 运行：
//   node tests/todo-quick-decision.spec.mjs
//   QD_WEB_ROOT=<另一棵 web 树> node tests/todo-quick-decision.spec.mjs   # 改前腿/破坏腿
//   QD_OUT=<证据目录>       node tests/todo-quick-decision.spec.mjs
//   退出码：0 = 全绿；1 = 有红。
//
// 数据面 = routeWebSocket 帧夹具 + todoStore.__seed 夹具模式；静态面 = page.route
// 从磁盘注真 web 树。零后端、零 sbt、零宿主实例（不碰 :6138 / :8080 / :8097）。
// 🔴 诚实申报：手势腿是 CDP 触摸仿真（headless 里的真指针事件），不是真机手感 ——
//   真机左右滑手感另列「待真机亲验」。

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.QD_WEB_ROOT
  ? normalize(resolve(process.env.QD_WEB_ROOT))
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.QD_OUT || '/Users/kaiyu/stand-evidence/todo-quick-decision-20261118';
const SHOTS = join(OUT, 'shots');
const FRAMES = join(OUT, 'frames');
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(SHOTS, { recursive: true });
mkdirSync(FRAMES, { recursive: true });
// EvidenceTemp batch contract (scripts/check-evidence-manifests.mjs): a batch
// that writes into the stand-evidence namespace MUST carry a manifest.json, or
// the fail-closed TTL sweep would skip it and retention becomes unbounded. The
// task pins this batch's dir (QD_OUT), so we write the manifest here rather than
// relocate via the shared helper.
writeFileSync(
  join(OUT, 'manifest.json'),
  JSON.stringify({ createdAt: new Date().toISOString(), project: 'stand', batch: 'todo-quick-decision-20261118' }) + '\n',
);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todo-quick-decision-root';
const VW = 375;
const VH = 812;

let pass = 0, fail = 0;
const lines = [];
const ok = (name, cond, detail = '') => {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const probe = {};
/** Page→server frames, recorded on the stub socket. The answer submit leg reads
 *  the reply chain's real output here (the frame content is the acceptance
 *  readout, not a UI guess). */
const sentFrames = [];

const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const pageErrors = [];

/* ─────────────── 夹具（3 条未完成待办：两条带决策 + 一条纯长内容）─────────── */

function fixture() {
  const now = Date.now();
  const longDetail = Array.from({ length: 24 }, (_, i) => `内容段 ${i + 1}：这是待办正文里较长的第 ${i + 1} 段，用来把卡片撑到必须下滑才看得完。`).join('\n');
  return {
    tagList: [{ id: 'tw', name: '工作', color: 'rgb(0, 122, 255)' }],
    itemList: [
      {
        id: 'qd-1', tagId: 'tw', priority: 3, due: now + 3_600_000,
        name: '周会先讲哪件事',
        detail: `${longDetail}\n末段：正文到底了。`,
        decision: {
          question: '本周先推进哪一件？',
          options: [
            'A 把待办数据模型的字段对齐做完（选项文本故意写长一点，检验换行与行高策略）',
            'B 先把手机端列表整改发出去',
            'C 两个并行，按人力缺口再收',
          ],
        },
      },
      {
        id: 'qd-2', tagId: null, priority: 0,
        name: '纯内容待办（无问题）',
        detail: longDetail,
      },
      {
        id: 'qd-3', tagId: 'tw', priority: 1,
        name: '多选决策：明天带哪些材料',
        detail: '短正文。',
        decision: {
          question: '明天带哪些材料？',
          options: ['合同', '体检报告', '身份证'],
          multiSelect: true,
          allowOther: true,
        },
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
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'session', tasks: [] }));
      // Answer-submit leg: turn terminal. Zero-backend fixture has no engine
      // assistant turn, so without this `done` the busy gate would stay set and
      // silently swallow a second send (same stub as tests/todo-row-reply.spec.mjs).
      else if (typeof m.content === 'string' && m.sessionId) ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId }));
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });

  await seed(page, fixture());
  await page.waitForTimeout(200);
  return { browser, ctx, page };
}

const seed = (page, fx) => page.evaluate(async (f) => {
  const store = await import('/js/todoStore.js');
  store.todoStore.__seed({ tagList: f.tagList, itemList: f.itemList });
}, fx);

const push = async (page, id) => {
  await page.evaluate(async (pid) => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage(pid);
  }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(320);
};

/* ─────────────── 触摸腿（CDP 真指针序列）─────────────── */

async function touchDrag(page, x, y, dx, dy, { steps = 8, midRead = null, stepMs = 16 } = {}) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: Math.round(x + (dx * i) / steps), y: Math.round(y + (dy * i) / steps), id: 1 }],
    });
    await sleep(stepMs);
  }
  let mid = null;
  if (midRead) mid = await midRead();
  // touchEnd 必须在同一个 CDP 会话里发（新会话没有 TouchStart 状态会被协议拒绝）。
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
  return mid;
}

/* ─────────────── 页内读数 ─────────────── */
const RECT = `(sel) => { const el = document.querySelector(sel); if (!el) return null; const b = el.getBoundingClientRect();
  const f = (v) => +v.toFixed(2);
  return { x: f(b.x), y: f(b.y), w: f(b.width), h: f(b.height), right: f(b.right), bottom: f(b.bottom), cx: f(b.x + b.width / 2), cy: f(b.y + b.height / 2) }; }`;

const CARD_SEL = '.mobile-page[data-page="todo-quick-decision"]';

const readCard = () => `(() => {
  const card = document.querySelector('${CARD_SEL} .ta-qd-card');
  if (!card) return null;
  const scroll = card.querySelector('.ta-qd-scroll');
  const rows = [...card.querySelectorAll('.ta-qd-opt')];
  const cs = getComputedStyle(card);
  const bg = (el) => el ? getComputedStyle(el).backgroundColor : '';
  return {
    itemId: card.dataset.itemId || null,
    hidden: card.hidden,
    count: document.querySelector('${CARD_SEL} .ta-qd-count')?.textContent,
    meta: card.querySelector('.ta-qd-meta')?.textContent || '',
    title: card.querySelector('.ta-qd-title')?.textContent || '',
    content: card.querySelector('.ta-qd-content')?.textContent || '',
    question: card.querySelector('.ta-qd-q')?.textContent || '',
    status: card.querySelector('.ta-qd-status')?.textContent || '',
    optCount: rows.length,
    optTexts: rows.map((r) => r.querySelector('.ta-qd-opt-label')?.textContent || ''),
    optTypes: rows.map((r) => r.querySelector('input')?.type || ''),
    optChecked: rows.map((r) => !!r.querySelector('input')?.checked),
    optBgs: rows.map((r) => bg(r)),
    slotCount: card.querySelectorAll('.ta-qd-opt-body').length,
    role: card.querySelector('.ta-qd-opts')?.getAttribute('role') || null,
    scrollTop: scroll ? +scroll.scrollTop.toFixed(1) : null,
    scrollH: scroll ? scroll.scrollHeight : null,
    clientH: scroll ? scroll.clientHeight : null,
    bodyScrollTop: +document.querySelector('${CARD_SEL} .mobile-page-body').scrollTop,
    pose: cs.transform,
    dqdx: card.style.getPropertyValue('--qd-dx') || '',
    settle: card.dataset.qdSettle || '',
    drag: card.dataset.qdDrag || '',
    transition: cs.transitionProperty + ' | ' + cs.transitionDuration + ' | ' + cs.transitionTimingFunction,
    touchAction: cs.touchAction,
    insideBody: !!card.closest('.mobile-page-body'),
    last: card.dataset.qdLast || null,
    text: (card.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 300),
  };
})()`;

const poseX = (pose) => {
  if (!pose || pose === 'none') return null;
  const m = pose.match(/matrix\(([^)]+)\)/);
  if (!m) return null;
  const parts = m[1].split(',').map((v) => Number(v.trim()));
  return Number.isFinite(parts[4]) ? parts[4] : null;
};

/* ── 问题可见 + 回答输入区读数（作者 2026-11-18 报障腿）─────────────────
   几何全部相对卡片可视盒量：问题「可见」= 其盒落在卡内且卡未滚动；
   输入框「不被 dock 截断」= 其底缘不越过 dock 上缘。 */
const readAnswer = () => `(() => {
  const card = document.querySelector('${CARD_SEL} .ta-qd-card');
  if (!card) return null;
  const cardBox = card.getBoundingClientRect();
  const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2), left: +b.left.toFixed(2), right: +b.right.toFixed(2), h: +b.height.toFixed(2) }; };
  const q = card.querySelector('.ta-qd-q');
  const content = card.querySelector('.ta-qd-content');
  const ta = card.querySelector('.ta-qd-answer-input');
  const send = card.querySelector('.ta-qd-answer-send');
  const dock = document.querySelector('#mobile-dock');
  const scroll = card.querySelector('.ta-qd-scroll');
  const qr = q ? q.getBoundingClientRect() : null;
  const tr = ta ? ta.getBoundingClientRect() : null;
  const dr = dock ? dock.getBoundingClientRect() : null;
  return {
    card: { top: +cardBox.top.toFixed(2), bottom: +cardBox.bottom.toFixed(2) },
    dock: dr ? { top: +dr.top.toFixed(2), bottom: +dr.bottom.toFixed(2) } : null,
    scrollTop: scroll ? +scroll.scrollTop.toFixed(1) : null,
    q: box(q), content: box(content),
    questionText: q ? q.textContent : '',
    contentText: content ? content.textContent.slice(0, 24) : '',
    qInCard: !!(qr && qr.top >= cardBox.top - 1 && qr.bottom <= cardBox.bottom + 1),
    order: { qBeforeContent: !!(qr && content && qr.top < content.getBoundingClientRect().top) },
    answer: ta ? {
      present: true, tag: ta.tagName.toLowerCase(), value: ta.value,
      type: ta.type, placeholder: ta.placeholder, ariaLabel: ta.getAttribute('aria-label'),
      sendPresent: !!send, sendText: send ? send.textContent : null,
      sendTag: send ? send.tagName.toLowerCase() : null,
      ...box(ta),
      inCard: tr.top >= cardBox.top - 1 && tr.bottom <= cardBox.bottom + 1,
      aboveDock: !dr || tr.bottom <= dr.top + 0.5,
      maxHeight: getComputedStyle(ta).maxHeight,
      cls: ta.className,
    } : { present: false },
    status: (card.querySelector('.ta-qd-status') || {}).textContent || '',
    error: (card.querySelector('.ta-qd-answer-error') || {}).textContent || '',
    lastAnswer: card.dataset.qdAnswerLast || null,
    answerInScroller: !!card.querySelector('.ta-qd-scroll .ta-qd-answer'),
  };
})()`;

/* 用户帧检索：stub socket 录制里带 content 的用户消息帧（回复链路的真实出帧）。 */
const userContentFrames = () => sentFrames
  .map((raw) => { try { return JSON.parse(raw); } catch { return null; } })
  .filter((m) => m && typeof m.content === 'string' && m.sessionId === SID)
  .map((m) => m.content);

/* ══════════════════════ 主腿 ══════════════════════ */

const { browser, ctx, page } = await boot();

// 契约：模块常量与设计 token 同源（防第二套时长/参数）
const motion = await page.evaluate(async () => {
  const m = await import('/js/mobile/todoAppleFace.js');
  const cs = getComputedStyle(document.body);
  const tok = (n) => cs.getPropertyValue(n).trim();
  return { exported: m.QUICK_DECISION_MOTION || null, durHover: tok('--ds-dur-hover'), durSurface: tok('--ds-dur-surface') };
});
probe.motion = motion;
{
  const ms = (s) => parseInt(String(s), 10);
  ok('E4 模块时长常量 = 动效 token（exit = --ds-dur-hover / enter = --ds-dur-surface）',
    !!motion.exported && motion.exported.exitMs === ms(motion.durHover) && motion.exported.enterMs === ms(motion.durSurface),
    JSON.stringify({ exported: motion.exported, durHover: motion.durHover, durSurface: motion.durSurface }));
}

/* ── E1 入口钮（首页）──────────────────────────────────────────────── */
await push(page, 'tasks');
const band = await page.evaluate(`(() => {
  const R = ${RECT};
  const wrap = document.querySelector('.mobile-page[data-page="tasks"] .ta-fab-wrap');
  const quick = document.querySelector('.mobile-page[data-page="tasks"] .ta-quick-fab');
  const plus = document.querySelector('.mobile-page[data-page="tasks"] .ta-fab');
  if (!quick || !plus) return { quick: null, plus: null };
  const q = R('.mobile-page[data-page="tasks"] .ta-quick-fab');
  const p = R('.mobile-page[data-page="tasks"] .ta-fab');
  const hit = (x, y) => { const el = document.elementFromPoint(x, y);
    if (!el) return 'null';
    const btn = el.closest('.ta-quick-fab, .ta-fab, button');
    const cls = typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\\s+/)[0] : '';
    return el.tagName.toLowerCase() + cls + ' > ' + (btn ? (btn.className || '').split(/\\s+/)[0] : 'none'); };
  const mid = { x: Math.round((q.right + p.x) / 2), y: Math.round(q.cy) };
  return {
    quick: q, plus: p,
    quickLabel: quick.getAttribute('aria-label'),
    quickIcon: quick.querySelector('[data-lucide]')?.getAttribute('data-lucide') || null,
    plusLabel: plus.getAttribute('aria-label'),
    bandPe: wrap ? getComputedStyle(wrap).pointerEvents : null,
    quickPe: getComputedStyle(quick).pointerEvents,
    gapPx: +(p.x - q.right).toFixed(2),
    leftInset: q.x, rightInset: +(innerWidth - p.right).toFixed(2),
    quickHit: hit(q.cx, q.cy), plusHit: hit(p.cx, p.cy), midHit: hit(mid.x, mid.y), mid,
    viewport: { w: innerWidth, h: innerHeight },
  };
})()`);
probe.band = band;
ok('E1 左下快速决策钮在场 + 可读标签（aria-label）',
  !!band.quick && band.quickLabel === '快速决策', JSON.stringify({ present: !!band.quick, label: band.quickLabel }));
ok('E1 图标 = 站内既有叠卡图（lucide layers）', band.quickIcon === 'layers', String(band.quickIcon));
ok('E1 两钮同规格（同直径 / 同底缘）',
  !!band.quick && !!band.plus
  && Math.abs(band.quick.w - band.plus.w) <= 0.5 && Math.abs(band.quick.h - band.plus.h) <= 0.5
  && Math.abs(band.quick.bottom - band.plus.bottom) <= 1,
  JSON.stringify({ quick: band.quick && `${band.quick.w}x${band.quick.h}@${band.quick.bottom}`, plus: band.plus && `${band.plus.w}x${band.plus.h}@${band.plus.bottom}` }));
ok('E1 左右内距对称（成组口径）', Math.abs(band.leftInset - band.rightInset) <= 0.5,
  JSON.stringify({ left: band.leftInset, right: band.rightInset }));
ok('E1 两钮零交叠（间隙 > 8px，且都在视口内）',
  band.gapPx > 8 && band.quick.x >= 0 && band.plus.right <= VW, `gap=${band.gapPx}px`);
ok('E1 band 指针透明：两钮之间那一点命中列表内容（不是挡板）',
  band.bandPe === 'none' && / > none$/.test(band.midHit),
  JSON.stringify({ bandPe: band.bandPe, midHit: band.midHit }));
ok('E1 已有「+」的可点区域未受影响（中心点反查仍是加号钮）',
  / > ta-fab$/.test(band.plusHit), band.plusHit);
ok('E1 两钮各自可点（反查命中自身）',
  / > ta-quick-fab$/.test(band.quickHit) && / > ta-fab$/.test(band.plusHit),
  JSON.stringify({ quick: band.quickHit, plus: band.plusHit }));
await page.screenshot({ path: join(SHOTS, '01-home-band.png') });

// 「+」真点击仍开新建抽屉（原入口行为未被侵占）→ 取消关闭
await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
await page.waitForTimeout(320);
const plusClick = await page.evaluate(() => ({
  sheet: !!document.querySelector('.mobile-page[data-page="tasks"] [data-todo-create-overlay]'),
  cancel: !!document.querySelector('.mobile-page[data-page="tasks"] .ta-create-cancel'),
}));
ok('E1 点「+」仍开新建抽屉（原入口行为未被侵占）', plusClick.sheet === true, JSON.stringify(plusClick));
await page.screenshot({ path: join(SHOTS, '02-plus-still-works.png') });
if (plusClick.cancel) {
  await page.locator('.mobile-page[data-page="tasks"] .ta-create-cancel').click();
  await page.waitForTimeout(260);
}
const sheetGone = await page.evaluate(() => !document.querySelector('.mobile-page[data-page="tasks"] [data-todo-create-overlay]'));
ok('E1 新建抽屉可正常关闭（入口测试不留残留节点）', sheetGone === true, String(sheetGone));

/* ── E2 点入口进入全屏快速决策页 ────────────────────────────────────── */
await page.locator('.mobile-page[data-page="tasks"] .ta-quick-fab').click();
await page.waitForSelector('.mobile-page[data-page="todo-quick-decision"].live', { timeout: 15000 });
await page.waitForTimeout(320);
const shell = await page.evaluate(`(() => {
  const R = ${RECT};
  const pageEl = document.querySelector('${CARD_SEL}');
  const cs = getComputedStyle(pageEl);
  const nav = window.__nfMobileNav;
  return {
    rect: R('${CARD_SEL}'),
    live: pageEl.classList.contains('live'),
    stackTop: nav && nav.stack ? nav.stack.slice() : null,
    backBtn: !!document.querySelector('${CARD_SEL} .mobile-back-btn'),
    dialogs: pageEl.querySelectorAll('[role="dialog"], .ta-create-overlay, .mfs, .ds-overlay').length,
    pageElRole: pageEl.getAttribute('role'),
    pageElLabel: pageEl.getAttribute('aria-label'),
    display: cs.display, position: cs.position,
  };
})()`);
probe.shell = shell;
ok('E2 快速决策 = 手机全屏页（375×812 铺满 + .live）',
  shell.live === true && shell.rect && Math.abs(shell.rect.w - VW) <= 0.5 && Math.abs(shell.rect.h - VH) <= 0.5
  && shell.rect.x === 0 && shell.rect.y === 0, JSON.stringify(shell.rect));
ok('E2 形态是页面而非弹窗（页内零 dialog/overlay 节点）', shell.dialogs === 0 && shell.pageElRole !== 'dialog',
  JSON.stringify({ dialogs: shell.dialogs, role: shell.pageElRole }));
ok('E2 返回路径在场（页壳左上返回钮）', shell.backBtn === true);
ok('E2 导航栈顶 = todo-quick-decision（push 语义复用）',
  !shell.stackTop || shell.stackTop[shell.stackTop.length - 1] === 'todo-quick-decision',
  JSON.stringify(shell.stackTop));

const card0 = await page.evaluate(readCard());
probe.card0 = card0;
ok('E3 卡片在页体内（不是浮层覆盖）', !!card0 && card0.insideBody === true, JSON.stringify({ insideBody: card0 && card0.insideBody }));
ok('E3 卡位读数 = 第 1 / 3 条', card0.count === '第 1 / 3 条', String(card0.count));
ok('E3 卡片显示待办内容（name + detail 都在卡上）',
  card0.title === '周会先讲哪件事' && /内容段 1/.test(card0.content) && /末段：正文到底了/.test(card0.content),
  JSON.stringify({ title: card0.title, contentLen: card0.content.length }));
ok('E3 卡片显示问题（decision.question）', card0.question === '本周先推进哪一件？', String(card0.question));
ok('E3 选项行 = 原生 radio（与列表页同一控制件）+ 3 条',
  card0.optCount === 3 && card0.optTypes.every((t) => t === 'radio') && card0.role === 'radiogroup',
  JSON.stringify({ types: card0.optTypes, role: card0.role }));
ok('E3 每行都有媒体位槽（.ta-qd-opt-body，媒体组挂载点）',
  card0.slotCount === 3, JSON.stringify({ slotCount: card0.slotCount }));
ok('E3 卡上 id 可机读（data-item-id = 当前待办）', card0.itemId === 'qd-1', String(card0.itemId));
ok('E4 卡片 touch-action = pan-y（横轴不让给浏览器）', card0.touchAction === 'pan-y', String(card0.touchAction));
await page.screenshot({ path: join(SHOTS, '03-quick-card-1.png') });

/* ── D 问题可见 + 回答输入区（作者 2026-11-18：「问题和回答输入框呢？现在怎么
   好像只有内容？」）────────────────────────────────────────────────────────
   这一组是**对未修源码直跑必红**的检测腿：问题被长正文顶到折叠线下、或卡内
   没有回答框时，D1–D9 会整组报红 —— 判据全部取真几何/真出帧，不看声明。 */
const ANSWER_TEXT = '选 A：先把待办数据模型的字段对齐做完';
const qa0 = await page.evaluate(readAnswer());
probe.qa0 = qa0;
ok('D1 问题在卡首屏可见（未滚动时 .ta-qd-q 完整落在卡片可视区内，未被裁）',
  !!qa0.q && qa0.questionText === '本周先推进哪一件？' && qa0.qInCard === true && qa0.scrollTop === 0,
  JSON.stringify({ q: qa0.q, card: qa0.card, scrollTop: qa0.scrollTop, text: qa0.questionText, inCard: qa0.qInCard }));
ok('D2 问题/选项排在长正文之前（长正文不再把问题顶到折叠线下）',
  qa0.order.qBeforeContent === true, JSON.stringify(qa0.order));
ok('D3 回答输入框在场（卡内真 textarea + 真发送键）',
  qa0.answer.present === true && qa0.answer.tag === 'textarea'
  && qa0.answer.sendPresent === true && qa0.answer.sendTag === 'button',
  JSON.stringify({ present: qa0.answer.present, tag: qa0.answer.tag, send: qa0.answer.sendText, sendTag: qa0.answer.sendTag }));
ok('D4 输入区 = 卡底固定带：在卡内 + 底缘不被底部 dock 截断',
  qa0.answer.inCard === true && qa0.answer.aboveDock === true,
  JSON.stringify({ answer: { top: qa0.answer.top, bottom: qa0.answer.bottom, h: qa0.answer.h },
    card: qa0.card, dock: qa0.dock }));
ok('D4 输入区不在滚动面内（长内容滚动时输入框不跟着走）',
  qa0.answerInScroller === false, String(qa0.answerInScroller));

// 没修源码这一支根本没有回答框：后面每条输入腿直接判红（不挂起、不误报超时），
// 「对未修源码直跑应报红」因此是一组干净的 FAIL，而不是一次崩溃。
if (qa0.answer.present === true) {
  await page.locator(`${CARD_SEL} .ta-qd-answer-input`).fill(ANSWER_TEXT);
  await page.waitForTimeout(160);
  const typed = await page.evaluate(readAnswer());
  probe.typed = typed;
  ok('D5 输入框可输入（真填字 → 读回真值）', typed.answer.value === ANSWER_TEXT,
    JSON.stringify({ value: typed.answer.value, placeholder: typed.answer.placeholder, aria: typed.answer.ariaLabel }));
  await page.screenshot({ path: join(SHOTS, '10-answer-typed.png') });

  const framesBefore = userContentFrames().length;
  await page.locator(`${CARD_SEL} .ta-qd-answer-send`).click();
  await page.waitForTimeout(460);
  const framesAfter = userContentFrames();
  const afterSend = await page.evaluate(readAnswer());
  probe.answerSubmit = { before: framesBefore, frames: framesAfter, last: afterSend.lastAnswer };
  ok('D6 真提交：回答逐字进既有回复链路（用户帧 = #待办名·id + 回答）',
    framesAfter.length === framesBefore + 1
    && framesAfter[framesAfter.length - 1] === `#周会先讲哪件事·qd-1 ${ANSWER_TEXT}`,
    JSON.stringify({ frames: framesAfter, last: afterSend.lastAnswer }));
  ok('D7 提交后输入框清空 + 无错误行',
    afterSend.answer.value === '' && afterSend.error === '',
    JSON.stringify({ value: afterSend.answer.value, error: afterSend.error }));

  // 长正文滚到底：输入区仍钉在卡底、仍在卡内、仍不被 dock 截断
  await page.evaluate(`(() => { const s = document.querySelector('${CARD_SEL} .ta-qd-scroll'); s.scrollTop = s.scrollHeight; })()`);
  await page.waitForTimeout(220);
  const deep = await page.evaluate(readAnswer());
  probe.answerDeepScroll = deep;
  ok('D8 长正文滚到底后输入框仍在卡内 + 不被 dock 截断 + 位置不动（滚动面 ≠ 输入区）',
    deep.scrollTop > 0 && deep.answer.inCard === true && deep.answer.aboveDock === true
    && Math.abs(deep.answer.top - qa0.answer.top) <= 1,
    JSON.stringify({ scrollTop: deep.scrollTop, answerTop: deep.answer.top, beforeTop: qa0.answer.top,
      card: deep.card, dock: deep.dock }));
  await page.screenshot({ path: join(SHOTS, '11-content-bottom-answer-pinned.png') });
  await page.evaluate(`(() => { const s = document.querySelector('${CARD_SEL} .ta-qd-scroll'); s.scrollTop = 0; })()`);
  await page.waitForTimeout(180);
} else {
  probe.typed = null;
  ok('D5 输入框可输入（真填字 → 读回真值）', false, '卡内没有回答输入框（未修源码）');
  ok('D6 真提交：回答逐字进既有回复链路（用户帧 = #待办名·id + 回答）', false, '卡内没有回答输入框（未修源码）');
  ok('D7 提交后输入框清空 + 无错误行', false, '卡内没有回答输入框（未修源码）');
  ok('D8 长正文滚到底后输入框仍在卡内 + 不被 dock 截断 + 位置不动（滚动面 ≠ 输入区）',
    false, '卡内没有回答输入框（未修源码）');
}

// 左右滑仍在：回答区不吃横滑手势（手势从卡面中部起，与 E4 同一条腿）
const dStage = await page.evaluate(`(${RECT})('${CARD_SEL} .ta-qd-stage')`);
const dX = Math.round(dStage.cx);
const dY = Math.round(dStage.y + 120);
await touchDrag(page, dX, dY, -140, 0, { steps: 10, stepMs: 14 });
await page.waitForTimeout(620);
const afterAnswerSwipe = await page.evaluate(readCard());
probe.afterAnswerSwipe = afterAnswerSwipe;
ok('D9 回答之后左滑仍切卡（选项/回答区不吞横滑）',
  afterAnswerSwipe.itemId === 'qd-2' && afterAnswerSwipe.count === '第 2 / 3 条',
  JSON.stringify({ id: afterAnswerSwipe.itemId, count: afterAnswerSwipe.count }));
await touchDrag(page, dX, dY, 140, 0, { steps: 10, stepMs: 14 });
await page.waitForTimeout(620);
const restored = await page.evaluate(readCard());
probe.answerSwipeRestored = restored;
ok('D9 滑回第一条（1 / 3，为后续 E5 腿复位）', restored.itemId === 'qd-1', JSON.stringify({ id: restored.itemId }));
await page.screenshot({ path: join(SHOTS, '12-answer-band-after-swipe.png') });

/* ── E5 长内容可下滑（卡片内滚，不是页面滚）+ 滚动与选中并存 ───────────── */
const scroll0 = await page.evaluate(`(() => {
  const s = document.querySelector('${CARD_SEL} .ta-qd-scroll');
  const b = document.querySelector('${CARD_SEL} .mobile-page-body');
  return { scrollH: s.scrollHeight, clientH: s.clientHeight, scrollTop: s.scrollTop,
    pageScrollH: b.scrollHeight, pageClientH: b.clientHeight };
})()`);
probe.scroll0 = scroll0;
ok('E5 长内容溢出卡片（scrollHeight > clientHeight ⇒ 必须下滑才看得完）',
  scroll0.scrollH > scroll0.clientH + 40, JSON.stringify(scroll0));

const cardRect = await page.evaluate(`(${RECT})('${CARD_SEL} .ta-qd-card')`);
// 真触摸竖滑（向上 150px）——竖滑是卡片内滚的腿，不是切卡腿
await touchDrag(page, Math.round(cardRect.cx), Math.round(cardRect.y + 240), 0, -150, { steps: 10, stepMs: 12 });
await page.waitForTimeout(360);
const afterScrollDrag = await page.evaluate(readCard());
probe.afterScrollDrag = afterScrollDrag;
ok('E5 竖滑不切卡（id 不变）+ 未进入拖拽/收尾态',
  afterScrollDrag.itemId === 'qd-1' && afterScrollDrag.drag === '' && afterScrollDrag.settle === '',
  JSON.stringify({ id: afterScrollDrag.itemId, drag: afterScrollDrag.drag, settle: afterScrollDrag.settle, scrollTop: afterScrollDrag.scrollTop }));
let scrollMech = 'native-touch';
if (!(afterScrollDrag.scrollTop > 0)) {
  // headless 的合成触摸未必驱动原生惯性滚动：退化为程序化滚动读数（诚实标注）
  await page.evaluate(() => { const s = document.querySelector('.ta-qd-scroll'); s.scrollTop = 90; });
  await page.waitForTimeout(140);
  scrollMech = 'programmatic';
}
const scrolled = await page.evaluate(readCard());
probe.scrolled = { ...scrolled, mechanism: scrollMech };
ok('E5 卡片内滚真的动了（滚动面 = 卡片内，不是页面）',
  scrolled.scrollTop > 0 && scrolled.bodyScrollTop === 0,
  JSON.stringify({ mechanism: scrollMech, scrollTop: scrolled.scrollTop, pageBodyScrollTop: scrolled.bodyScrollTop }));
await page.screenshot({ path: join(SHOTS, '04-card-scrolled.png') });

// 滚动之后再点选项：把目标行滚进可见区 → 真点 → 仍能选中（滚动不吞点击）
const tapTarget = await page.evaluate(`(() => {
  const row = document.querySelectorAll('${CARD_SEL} .ta-qd-opt')[1];
  row.scrollIntoView({ block: 'center' });
  const b = row.getBoundingClientRect();
  const card = document.querySelector('${CARD_SEL} .ta-qd-card').getBoundingClientRect();
  const x = Math.round(b.x + b.width / 2), y = Math.round(b.y + b.height / 2);
  const el = document.elementFromPoint(x, y);
  return { x, y, hit: el ? String(el.className || el.tagName) : null,
    inRow: !!(el && el.closest('.ta-qd-opt')), inCard: b.top >= card.top - 1 && b.bottom <= card.bottom + 1,
    scrollTop: document.querySelector('${CARD_SEL} .ta-qd-scroll').scrollTop };
})()`);
probe.tapTarget = tapTarget;
await page.touchscreen.tap(tapTarget.x, tapTarget.y);
await page.waitForTimeout(220);
const afterTap = await page.evaluate(readCard());
probe.afterTap = afterTap;
ok('E5 滚动之后再点选项：命中项选中（滚动不吞点击）',
  tapTarget.inRow === true && tapTarget.inCard === true && afterTap.optChecked[1] === true,
  JSON.stringify({ hit: tapTarget.hit, inRow: tapTarget.inRow, inCard: tapTarget.inCard, checked: afterTap.optChecked }));
ok('E5 选中态有可见样式（:has(:checked) 底/边变化）',
  afterTap.optBgs[1] !== afterTap.optBgs[0],
  JSON.stringify({ selected: afterTap.optBgs[1], unselected: afterTap.optBgs[0] }));
ok('E5 点击不改变滚动位（选中不重排/不乱跳）',
  Math.abs(afterTap.scrollTop - tapTarget.scrollTop) <= 1,
  JSON.stringify({ before: tapTarget.scrollTop, after: afterTap.scrollTop }));
await page.screenshot({ path: join(SHOTS, '05-option-selected.png') });

// 选项槽承载高块（媒体位留槽的版式与高度策略：槽增高 ⇒ 行增高、卡仍可滚、仍可选）
const slotProof = await page.evaluate(`(() => {
  const row = document.querySelectorAll('${CARD_SEL} .ta-qd-opt')[0];
  const body = row.querySelector('.ta-qd-opt-body');
  const before = +row.getBoundingClientRect().height.toFixed(1);
  const block = document.createElement('div');
  block.className = 'qd-slot-probe';
  block.style.width = '100%';
  block.style.height = '220px';
  block.style.borderRadius = '12px';
  block.style.background = 'rgba(0, 0, 0, 0.12)';
  body.insertBefore(block, body.firstChild);
  const after = +row.getBoundingClientRect().height.toFixed(1);
  const s = document.querySelector('${CARD_SEL} .ta-qd-scroll');
  return { before, after, slotH: +block.getBoundingClientRect().height.toFixed(1),
    scrollH: s.scrollHeight, clientH: s.clientHeight, label: row.querySelector('.ta-qd-opt-label').textContent.slice(0, 12) };
})()`);
probe.slotProof = slotProof;
ok('E5 选项槽可承载高块（媒体位留槽：槽增高 ⇒ 行增高，标签仍在行内）',
  slotProof.after >= slotProof.before + 200 && slotProof.label.length > 0, JSON.stringify(slotProof));
const slotScrollable = await page.evaluate(`(() => {
  const s = document.querySelector('${CARD_SEL} .ta-qd-scroll');
  s.scrollTop = s.scrollHeight;
  return { scrollTop: +s.scrollTop.toFixed(1), scrollH: s.scrollHeight, clientH: s.clientHeight };
})()`);
probe.slotScrollable = slotScrollable;
ok('E5 媒体块加高后卡片仍可滚到底（高度策略 = 卡片内滚，不裁切）',
  slotScrollable.scrollTop > 0, JSON.stringify(slotScrollable));
// 清掉探针块（后续手势腿读干净版面）
await page.evaluate(() => {
  document.querySelector('.ta-qd-scroll .qd-slot-probe')?.remove();
  const s = document.querySelector('.ta-qd-scroll');
  if (s) s.scrollTop = 0;
});
await page.waitForTimeout(160);

/* ── E4 手势：左滑 = 下一条（跟手 + 收尾动效 + 方向一致）────────────── */
// 手势事件流记录（证明横拖的指针流未被 pointercancel 掐断 —— 就是
// `.ta-qd-scroll` touch-action:pan-y 那条口径的实测面）。
await page.evaluate(() => {
  window.__ev = [];
  const st = document.querySelector('.ta-qd-stage');
  ['pointerdown', 'pointermove', 'pointerup', 'pointercancel'].forEach((t) => {
    st.addEventListener(t, (e) => { window.__ev.push(`${t}${e.pointerType ? ':' + e.pointerType : ''}${e.isTrusted === false ? ':untrusted' : ''}`); }, true);
  });
});
const stageRect = await page.evaluate(`(${RECT})('${CARD_SEL} .ta-qd-stage')`);
const startX = Math.round(stageRect.cx);
const startY = Math.round(stageRect.y + 120);

const mid = await touchDrag(page, startX, startY, -120, 0, {
  steps: 6, stepMs: 18,
  midRead: async () => {
    const r = await page.evaluate(readCard());
    const cards = await page.evaluate(() => document.querySelectorAll('.ta-qd-card').length);
    return { pose: r.pose, dqdx: r.dqdx, settle: r.settle, drag: r.drag, itemId: r.itemId, cards };
  },
});
probe.dragFollow = mid;
probe.gestureEvents = await page.evaluate(() => window.__ev.slice());
ok('E4 横拖的指针流未被浏览器掐断（全程无 pointercancel ⇒ 跟手连续）',
  probe.gestureEvents.length > 0 && !probe.gestureEvents.some((x) => x.startsWith('pointercancel')),
  JSON.stringify(probe.gestureEvents));
const midPose = poseX(mid && mid.pose);
ok('E4 跟手：手指在 -120px 处，卡片实际位移 ≈ -120px（差 ≤2px）',
  midPose !== null && Math.abs(midPose - (-120)) <= 2 && mid.drag === '1',
  JSON.stringify({ poseX: midPose, read: mid }));
ok('E4 拖动中不切卡（松手前卡片 id 不变）+ 单实例卡（页内只有一张）',
  mid.itemId === 'qd-1' && mid.cards === 1, JSON.stringify({ itemId: mid.itemId, cards: mid.cards }));

// 松手：touchEnd 已在 touchDrag 内同会话发出 → 紧接着读 exit 腿的 token
// （computed transition-duration 是秒制，token 是毫秒制：逐值换算后比较）
const sec = (tok) => `${parseFloat(tok) / 1000}s`;
await page.waitForTimeout(40);
const exitLeg = await page.evaluate(readCard());
probe.exitLeg = exitLeg;
ok('E4 收尾（exit 腿）走 --ds-dur-hover / --ds-ease-std',
  exitLeg.settle === 'exit' && exitLeg.transition.includes(sec(motion.durHover)) && exitLeg.transition.includes('0.4, 0, 0.2, 1'),
  JSON.stringify({ settle: exitLeg.settle, transition: exitLeg.transition }));
await page.waitForTimeout(130);
const enterLeg = await page.evaluate(readCard());
probe.enterLeg = enterLeg;
ok('E4 收尾（enter 腿）走 --ds-dur-surface / --ds-ease-enter',
  enterLeg.settle === 'enter' && enterLeg.transition.includes(sec(motion.durSurface)) && enterLeg.transition.includes('0.16, 1, 0.3, 1'),
  JSON.stringify({ settle: enterLeg.settle, transition: enterLeg.transition }));
await page.waitForTimeout(340);
const afterNext = await page.evaluate(readCard());
probe.afterNext = afterNext;
ok('E4 左滑 = 下一条（切换后卡片 id 与手势方向一致：qd-1 → qd-2，读数 2 / 3）',
  afterNext.itemId === 'qd-2' && afterNext.count === '第 2 / 3 条'
  && JSON.parse(afterNext.last || '{}').dir === 1 && JSON.parse(afterNext.last || '{}').switched === true,
  JSON.stringify({ id: afterNext.itemId, count: afterNext.count, last: afterNext.last }));
ok('E4 落地归位（--qd-dx 归 0，无残留位移）',
  afterNext.pose === 'matrix(1, 0, 0, 1, 0, 0)' || afterNext.dqdx === '0px' || afterNext.dqdx === '0.0px',
  JSON.stringify({ pose: afterNext.pose, dqdx: afterNext.dqdx }));
ok('E3 第二条卡（无决策）只显示内容，不渲染空的问题/选项壳',
  afterNext.question === '' && afterNext.optCount === 0, JSON.stringify({ q: afterNext.question, opts: afterNext.optCount }));
await page.screenshot({ path: join(SHOTS, '06-card-2-after-left-swipe.png') });

/* ── 右滑 = 上一条 ─────────────────────────────────────────────────── */
await touchDrag(page, startX, startY, 120, 0, { steps: 8, stepMs: 16 });
await page.waitForTimeout(560);
const afterPrev = await page.evaluate(readCard());
probe.afterPrev = afterPrev;
ok('E4 右滑 = 上一条（qd-2 → qd-1，读数 1 / 3）',
  afterPrev.itemId === 'qd-1' && afterPrev.count === '第 1 / 3 条'
  && JSON.parse(afterPrev.last || '{}').dir === -1 && JSON.parse(afterPrev.last || '{}').switched === true,
  JSON.stringify({ id: afterPrev.itemId, last: afterPrev.last }));
ok('E4 回到第一条后选中态仍在（选择状态按待办存，不随换卡丢）',
  afterPrev.optChecked[1] === true, JSON.stringify(afterPrev.optChecked));

/* ── 首端：第一条再右滑 → 停住 + 回弹（索引不变）────────────────────── */
await touchDrag(page, startX, startY, 140, 0, { steps: 8, stepMs: 16 });
await page.waitForTimeout(560);
const atHead = await page.evaluate(readCard());
probe.atHead = atHead;
ok('E4 首端行为 = 停住（不环绕，索引仍 1 / 3）',
  atHead.itemId === 'qd-1' && atHead.count === '第 1 / 3 条'
  && JSON.parse(atHead.last || '{}').switched === false,
  JSON.stringify({ id: atHead.itemId, last: atHead.last }));
ok('E4 首端过滑后回弹归位（无残留位移）',
  atHead.pose === 'matrix(1, 0, 0, 1, 0, 0)' || atHead.dqdx === '0px' || atHead.dqdx === '0.0px',
  JSON.stringify({ pose: atHead.pose, dqdx: atHead.dqdx }));

/* ── 阈值以下（慢速小幅）→ 不切卡 + 回弹；快弹小幅 → 按快弹腿切卡 ────── */
await touchDrag(page, startX, startY, -40, 0, { steps: 5, stepMs: 90 });
await page.waitForTimeout(560);
const underThreshold = await page.evaluate(readCard());
probe.underThreshold = underThreshold;
ok('E4 阈值以下（慢速 -40px，非快弹）不切卡 + 回弹归位',
  underThreshold.itemId === 'qd-1' && JSON.parse(underThreshold.last || '{}').moved === false
  && (underThreshold.pose === 'matrix(1, 0, 0, 1, 0, 0)' || underThreshold.dqdx === '0px' || underThreshold.dqdx === '0.0px'),
  JSON.stringify({ id: underThreshold.itemId, last: underThreshold.last, pose: underThreshold.pose }));

const flick = await touchDrag(page, startX, startY, -40, 0, { steps: 5, stepMs: 12 });
void flick;
await page.waitForTimeout(560);
const afterFlick = await page.evaluate(readCard());
probe.afterFlick = afterFlick;
ok('E4 短距快弹（-40px / <200ms）按快弹腿切卡（与站内既有手势同一条判据）',
  afterFlick.itemId === 'qd-2' && JSON.parse(afterFlick.last || '{}').moved === true
  && JSON.parse(afterFlick.last || '{}').dt < 200,
  JSON.stringify({ id: afterFlick.itemId, last: afterFlick.last }));
// 回到第一条，再连滑到第三条进尾端
await touchDrag(page, startX, startY, 130, 0, { steps: 8, stepMs: 16 });
await page.waitForTimeout(560);

/* ── 尾端：连滑到第三条再左滑 → 停住 ───────────────────────────────── */
await touchDrag(page, startX, startY, -130, 0, { steps: 8, stepMs: 14 });
await page.waitForTimeout(560);
await touchDrag(page, startX, startY, -130, 0, { steps: 8, stepMs: 14 });
await page.waitForTimeout(560);
const atThird = await page.evaluate(readCard());
probe.atThird = atThird;
ok('E4 连滑到第 3 条（多选决策：checkbox + 三条选项）',
  atThird.itemId === 'qd-3' && atThird.count === '第 3 / 3 条'
  && atThird.optTypes.every((t) => t === 'checkbox') && atThird.role === 'group',
  JSON.stringify({ id: atThird.itemId, types: atThird.optTypes, role: atThird.role }));
await page.screenshot({ path: join(SHOTS, '07-card-3-multiselect.png') });
await touchDrag(page, startX, startY, -130, 0, { steps: 8, stepMs: 14 });
await page.waitForTimeout(560);
const atTail = await page.evaluate(readCard());
probe.atTail = atTail;
ok('E4 尾端行为 = 停住（不再前进，索引仍 3 / 3）',
  atTail.itemId === 'qd-3' && atTail.count === '第 3 / 3 条'
  && JSON.parse(atTail.last || '{}').switched === false,
  JSON.stringify({ id: atTail.itemId, last: atTail.last }));

/* ── 键盘腿（ArrowLeft/Right 同一条切换腿）─────────────────────────── */
const kbFocus = await page.evaluate(() => {
  const card = document.querySelector('.ta-qd-card');
  if (card) card.focus();
  return document.activeElement ? String(document.activeElement.className).split(' ')[0] : null;
});
probe.kbFocus = kbFocus;
await page.keyboard.press('ArrowLeft');
await page.waitForTimeout(560);
const afterKey = await page.evaluate(readCard());
probe.afterKey = afterKey;
ok('E4 键盘腿：ArrowLeft = 上一条（第 3 → 第 2 条）',
  afterKey.itemId === 'qd-2' && afterKey.count === '第 2 / 3 条', JSON.stringify({ id: afterKey.itemId, focus: kbFocus }));
await page.keyboard.press('ArrowRight');
await page.waitForTimeout(560);
const afterKey2 = await page.evaluate(readCard());
probe.afterKey2 = afterKey2;
ok('E4 键盘腿：ArrowRight = 下一条（第 2 → 第 3 条）',
  afterKey2.itemId === 'qd-3' && afterKey2.count === '第 3 / 3 条', JSON.stringify({ id: afterKey2.itemId }));

/* ── 返回路径：页壳返回钮回到列表页 ────────────────────────────────── */
await page.locator(`${CARD_SEL} .mobile-back-btn`).click();
await page.waitForTimeout(460);
const backHome = await page.evaluate(() => ({
  tasksLive: !!document.querySelector('.mobile-page[data-page="tasks"].live'),
  quickLive: !!document.querySelector('.mobile-page[data-page="todo-quick-decision"].live'),
}));
probe.backHome = backHome;
ok('E2 返回路径：返回钮 → 回到待办列表页（quick 页退出）',
  backHome.tasksLive === true && backHome.quickLive === false, JSON.stringify(backHome));
await page.screenshot({ path: join(SHOTS, '08-back-to-list.png') });

/* ── 空态（零未完成待办）────────────────────────────────────────────── */
await seed(page, { tagList: fixture().tagList, itemList: [] });
await page.waitForTimeout(240);
await push(page, 'todo-quick-decision');
const emptyState = await page.evaluate(`(() => {
  const card = document.querySelector('${CARD_SEL} .ta-qd-card');
  const empty = document.querySelector('${CARD_SEL} .ta-qd-empty');
  const head = document.querySelector('${CARD_SEL} .ta-qd-head');
  return { cardHidden: card.hidden, emptyHidden: empty.hidden, emptyText: empty.textContent, headHidden: head.hidden };
})()`);
probe.emptyState = emptyState;
ok('E3 零待办：卡不落地，只留空态事实行（不渲染空壳卡）',
  emptyState.cardHidden === true && emptyState.headHidden === true
  && emptyState.emptyHidden === false && emptyState.emptyText === '全部完成',
  JSON.stringify(emptyState));
await page.screenshot({ path: join(SHOTS, '09-quick-empty.png') });

ok('Z 零页面错误（pageerror/console.error 去噪后）', pageErrors.length === 0, pageErrors.slice(0, 4).join(' | '));

/* ── 证据腿：CDP screencast 录「点入口 → 滑卡 → 下滑 → 选中 → 返回」────── */
let media = null;
try {
  await seed(page, fixture());
  await page.waitForTimeout(200);
  await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* root */ } });
  await page.waitForTimeout(200);
  await push(page, 'tasks');
  await page.locator('.mobile-page[data-page="tasks"] .ta-quick-fab').click();
  await page.waitForSelector(`${CARD_SEL}.live`, { timeout: 10000 });
  await page.waitForTimeout(600);

  const cdp2 = await ctx.newCDPSession(page);
  const frames = [];
  cdp2.on('Page.screencastFrame', (f) => {
    frames.push(f.data);
    cdp2.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
  });
  await cdp2.send('Page.startScreencast', { format: 'jpeg', quality: 82, maxWidth: VW, maxHeight: VH, everyNthFrame: 1 });
  await sleep(400);
  const st = await page.evaluate(`(${RECT})('${CARD_SEL} .ta-qd-stage')`);
  // 拍①：左滑切到下一条（含跟手与收尾）
  await touchDrag(page, Math.round(st.cx), Math.round(st.y + 130), -140, 0, { steps: 10, stepMs: 26 });
  await sleep(650);
  // 拍②：卡片内下滑看长内容
  await touchDrag(page, Math.round(st.cx), Math.round(st.y + 200), 0, -170, { steps: 10, stepMs: 22 });
  await sleep(550);
  // 拍③：点一个选项（当前卡若有选项）
  const hasOpts = await page.evaluate(() => document.querySelectorAll('.ta-qd-opt').length);
  if (hasOpts > 0) {
    const t = await page.evaluate(`(() => { const r = document.querySelector('.ta-qd-opt').getBoundingClientRect();
      return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) }; })()`);
    await page.touchscreen.tap(t.x, t.y);
    await sleep(500);
  } else {
    await sleep(400);
  }
  // 拍③2：回答区（本批新增）—— 有输入框就真填字 + 真提交（未修源码这一支跳过）
  if (await page.evaluate(() => !!document.querySelector('.ta-qd-answer-input'))) {
    await page.locator('.ta-qd-answer-input').fill('选 A：先把待办数据模型的字段对齐做完');
    await sleep(700);
    await page.locator('.ta-qd-answer-send').click();
    await sleep(700);
  }
  // 拍④：返回列表页
  await page.locator(`${CARD_SEL} .mobile-back-btn`).click();
  await sleep(750);
  await cdp2.send('Page.stopScreencast');
  await cdp2.detach();

  frames.forEach((b64, i) => writeFileSync(join(FRAMES, `${String(i).padStart(4, '0')}.jpg`), Buffer.from(b64, 'base64')));
  const ff = (args) => execFileSync(args[0], args.slice(1), { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
  const gif = join(OUT, 'quick-decision-gesture.gif');
  const mp4 = join(OUT, 'quick-decision-gesture.mp4');
  // Compact GIF (<=10 MiB deliverable cap): downscale + palette size cap keep
  // the animation well under the author's 10 MB limit (measured ~4.3 MB).
  ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(FRAMES, '%04d.jpg'),
    '-vf', 'fps=10,scale=280:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=3', gif]);
  ff(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '10', '-i', join(FRAMES, '%04d.jpg'),
    '-vf', 'fps=30,scale=750:-2:flags=lanczos', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '24', mp4]);
  const fprobe = (p) => ff(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,avg_frame_rate,nb_read_packets', '-show_entries', 'format=duration,size,format_name', '-of', 'json', p]);
  media = { frames: frames.length, gif: { path: gif, probe: JSON.parse(fprobe(gif)) }, mp4: { path: mp4, probe: JSON.parse(fprobe(mp4)) } };
  probe.media = media;
  // Deliverable cap: interaction evidence must be <=10 MB (probed on the real
  // file here, not a declared number).
  const CAP = 10 * 1024 * 1024;
  const gifBytes = readFileSync(gif).length;
  const mp4Bytes = readFileSync(mp4).length;
  probe.mediaSizes = { gifBytes, mp4Bytes, capBytes: CAP };
  ok('Z 交付媒体 ≤10MB（对真值文件量尺，GIF 与 MP4）', gifBytes <= CAP && mp4Bytes <= CAP,
    JSON.stringify({ gifBytes, mp4Bytes, capBytes: CAP }));
  console.log(`      [证据] ${frames.length} 帧 → GIF ${JSON.stringify(media.gif.probe.streams && media.gif.probe.streams[0])} / MP4 ${JSON.stringify(media.mp4.probe.streams && media.mp4.probe.streams[0])}`);
} catch (e) {
  probe.mediaError = String((e && e.message) || e);
  console.log('      [证据] 录制腿失败：' + probe.mediaError);
}

/* ── 落盘 ───────────────────────────────────────────────────────────── */
writeFileSync(join(OUT, 'probe.json'), JSON.stringify(probe, null, 2) + '\n');
writeFileSync(join(OUT, 'result.txt'),
  `# todo-quick-decision gate (author 2026-11-18)\nWEB=${WEB}\nOUT=${OUT}\n\n`
  + lines.join('\n') + `\n\nPASS ${pass} / FAIL ${fail}\n`
  + (media ? `\nGIF ${media.gif.path}\n  ${JSON.stringify(media.gif.probe)}\nMP4 ${media.mp4.path}\n  ${JSON.stringify(media.mp4.probe)}\n` : ''));
console.log(`\n=== ${pass} passed, ${fail} failed ===`);
console.log(`evidence: ${OUT}`);
await ctx.close();
await browser.close();
process.exit(fail ? 1 : 0);
