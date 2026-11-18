#!/usr/bin/env node
// tests/todo-card-click-left.spec.mjs
// ── 消息流里的**待办卡片**：① 落 agent 侧（左）② 点非交互区 ⇒ 当前对话框补 `#` 引用
//
// 出处（todo-card-click-left 批，2026-11-18；作者令逐字两条）：
//   「1 待办卡片 要支持可以直接点击 然后就到对话框有 # 的待办引用
//    2.待办卡片应该靠左 这是你发出的」
//
// ── 被测面（真链路，零引擎零 sbt；宿主实例 :6138 零触碰）────────────────
//   · 真应用页（真 index.html + 真 main.js 组合根 + 真 chat.renderTool + 真
//     <nf-sched-card> 组件 + 真 css/chat.css / reminder.css）
//   · 数据面 = **真 WS 帧桩**（page.routeWebSocket：网关帧形状逐字照 WsTasksWorkspace-
//     Handlers 的 listScheduledTasks / scheduledTaskList），todoStore 走真 mapRow
//   · 手机形态 = 375×812 全屏页（body.mobile-layer，与作者真机同构），
//     **不是**弹窗/内嵌面板
//
// ── 断言编号（对应批量验收条目）─────────────────────────────────────────
//   A 侧对齐：agent 卡 = 左（.row.ai / flex-start / 贴聊天列左缘）
//             user 卡 = 右（.row.user / flex-end / 贴聊天列右缘）—— 两侧都测
//   B 点卡片非交互区 ⇒ composer 出现 `#<名>·<id>`（与 `#` 补全同一条 insertText）
//   C 卡内既有入口未被吞：取消（二次确认）/ 折展钮 / 编辑表单输入控件 / 卡片自身
//     的写操作按钮 —— 各自行为原样 + 都不触达 composer
//   D 手机命中区（卡体高 ≥ 44px 量级）+ 点空白不误触发 + 触摸滚动不误触发
//   E 真引用：提交帧 content 逐字带 `#<名>·<id>`（模型侧收到的读数）+ 消息渲染出
//     .todo-tag chip 且 data-todo-id = 该待办 id
//   F 「没有可输入的对话框」的明确行为：输入框被别的面盖住 ⇒ 回 chat home 再插；
//     输入框被拆除 ⇒ 返回 false + 可见提示条（不静默）
//   Z 零 pageerror
//
// ── 判据诚实面 ─────────────────────────────────────────────────────────
//   本文件证明：**前端**把引用写进提交帧、并把它渲染成带身份的 chip。
//   本文件**不证明**：真引擎把该帧喂给真模型（那需要隔离实例 + 真 LLM；见报告
//   「待真机亲验」节）。引用形态本身是既有 `#` 补全的同一条（todoRefText 单点），
//   故「模型收到什么」= 提交帧 content 这一条读数。
//
// 跑法：node tests/todo-card-click-left.spec.mjs            （全绿 exit 0）
//       TCLC_OUT=<dir> node tests/todo-card-click-left.spec.mjs
//       TCLC_KEEP=1 保留浏览器（调试）

import { chromium } from 'playwright';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';
import { evidenceOut } from '../scripts/lib/evidence-batch.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TCLC_WEB_ROOT || join(HERE, '..', 'src', 'main', 'resources', 'web');
// 证据批目录：显式 TCLC_OUT 优先（本批作者令指定 /Users/kaiyu/stand-evidence/...），
// 否则走共享 helper 建 <tmp>/stand-evidence/<project>/<batch>/<yyyyMMdd>/。
// 两条路都写 manifest.json：命名空间写入者必须留 manifest —— EvidenceTemp 的
// TTL 清扫对缺 manifest 的目录 fail-closed（永不被回收），由
// scripts/check-evidence-manifests.mjs 把关。本文件因此 import 了共享 helper，
// 不另造一套批目录逻辑。
const OUT = evidenceOut(process.env.TCLC_OUT, 'todo-card-click-left', 'capture');
mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({
  createdAt: new Date().toISOString(),
  project: 'todo-card-click-left',
  batch: process.env.TCLC_OUT ? 'capture-explicit-out' : 'capture-auto',
}) + '\n');
const SHOTS = join(OUT, 'shots');
const VIDEO_TMP = join(OUT, '.video-tmp');
mkdirSync(SHOTS, { recursive: true });
mkdirSync(VIDEO_TMP, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'todo-card-left-root';
const TODO_ID = 'td0042ab';
const TODO_NAME = '把周报模板整理进 memory';
const TODO_DETAIL = '模板落在 memory/notes/weekly-report-template.md；下周一站会前跑一遍。';
// 长正文（>120 字 ⇒ 卡自己长出「展开/收起」钮，C 腿用它证明折展钮未被吞）
const LONG_TAIL = '这段是故意写长的正文尾巴，用来让卡片自己长出折展钮：'.repeat(7);

let failures = 0;
const log = [];
const rec = (line) => { log.push(line); console.log(line); };
const check = (name, cond, extra = '') => {
  rec(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures += 1;
};

const freePort = () => new Promise((resolve, reject) => {
  const srv = net.createServer();
  srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  srv.on('error', reject);
});

const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed|net::ERR_/i;

/** 网关行形状（listTaskJson 键集合；工具/卡/待办库三面共用一份）。 */
function gatewayRow({ id = TODO_ID, kind = 'todo', name = TODO_NAME, detail = TODO_DETAIL, content = null } = {}) {
  return {
    id, kind, name, detail,
    content: content == null ? name : content,
    project: null, triggerAt: Date.now() + 3 * 3600_000, createdAt: Date.now() - 60_000,
    triggered: false, triggeredAt: null, referencePath: null, repeat: null,
    enabled: true, missCount: 0, sessionId: SID, priority: 0,
    completed: false, completedAt: null,
  };
}

async function boot({ width, height, touch, scheme = 'light', record = false, mobileTag = '' }) {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({
    viewport: { width, height },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: !!touch,
    locale: 'zh-CN',
    ...(record ? { recordVideo: { dir: VIDEO_TMP, size: { width, height } } } : {}),
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) errors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) errors.push(`console.error: ${m.text()}`); });

  const sentFrames = [];
  const rows = new Map([
    [TODO_ID, gatewayRow()],
    // 长正文行（卡自己长出折展钮；卡面字段一律取自这条回读行）
    ['td00long', gatewayRow({ id: 'td00long', name: TODO_NAME, content: TODO_NAME + LONG_TAIL })],
    // 作者侧对照卡（同一 builder + emitter='user'）也读得到，免得落入「已不在」态
    ['td00user', gatewayRow({ id: 'td00user', name: '作者自己建的待办', content: '作者自己建的待办' })],
  ]);

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });

  const origin = `http://127.0.0.1:${await freePort()}`;
  await page.route('**/*', (route) => {
    const u = new URL(route.request().url());
    if (u.origin !== origin) return route.continue();
    let p = decodeURIComponent(u.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = (o) => ws.send(JSON.stringify(o));
    const pushRows = (scope) => send({
      type: 'scheduledTaskList', sessionId: SID,
      ...(scope ? { scope, includeCompleted: true } : {}),
      tasks: [...rows.values()],
    });
    send({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      sentFrames.push(m);
      if (m.type === 'getConfig') send({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
      else if (m.type === 'getSessions' || m.type === 'listSessions') {
        send({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID });
      } else if (m.type === 'getHistory') {
        send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 });
      } else if (m.type === 'ping') send({ type: 'pong' });
      else if (m.type === 'listScheduledTasks') setTimeout(() => pushRows(m.scope), 15);
    });
  });

  await page.goto(`${origin}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 25000 });
  if (touch) await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(600);
  return { browser, ctx, page, errors, sentFrames, rows, origin, mobileTag };
}

// ── 驱动面（全部走真模块真函数）─────────────────────────────────────────

/** 真 chat.renderTool：Schedule(create) 成功 ⇒ 卡片行落消息流（agent 发出）。 */
const renderToolCard = (page, { content, name, id = TODO_ID, long = false }) => page.evaluate(async (a) => {
  const chat = await import('/js/chat.js');
  chat.renderTool(
    a.toolLabel,
    `Todo ${a.id} registered`,
    `Todo ${a.id} registered (surfaces daily at 09:00 until done/cancelled): ${a.content}`,
    false,
    JSON.stringify({ action: 'create', kind: 'todo', content: a.content, name: a.name, detail: a.detail }),
    a.sid,
  );
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  await new Promise((r) => setTimeout(r, 140));
}, {
  content: long ? TODO_NAME + LONG_TAIL : content,
  name, id, sid: SID, detail: TODO_DETAIL,
  toolLabel: long ? 'Schedule(create)#long' : 'Schedule(create)',
});

/** 同一 builder 的**作者侧**腿：emitter='user' ⇒ 落 .row.user（右）。
 *  🔴 生产路径今天没有这个形态（消息流待办卡只由 Schedule 工具调用产生）；
 *  这里显式构造它，只为证明「行侧由 emitter 数据决定，不是一刀切全左」。 */
const renderUserSideCard = (page, { content, name, id = 'td00user' }) => page.evaluate(async (a) => {
  const cardMod = await import('/js/scheduleCard.js');
  const model = cardMod.scheduleCardModelFromTool({
    label: 'Schedule(create)',
    content: `Todo ${a.id} registered (surfaces daily at 09:00 until done/cancelled): ${a.content}`,
    inputJson: JSON.stringify({ action: 'create', kind: 'todo', content: a.content, name: a.name }),
    isError: false, sessionId: a.sid,
  });
  model.emitter = 'user';
  const row = cardMod.buildScheduleCardRow(model);
  row.setAttribute('data-probe', 'user-side');
  document.querySelector('#chat').appendChild(row);
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  await new Promise((r) => setTimeout(r, 140));
  return !!row;
}, { content, name, id, sid: SID });

const sideReading = (page, rowSel) => page.evaluate((sel) => {
  const row = document.querySelector(sel);
  if (!row) return null;
  const card = row.querySelector('.schedule-card');
  const chat = document.querySelector('#chat');
  const cs = getComputedStyle(row);
  const rr = row.getBoundingClientRect();
  const cr = card.getBoundingClientRect();
  const chr = chat.getBoundingClientRect();
  const csChat = getComputedStyle(chat);
  const padL = parseFloat(csChat.paddingLeft) || 0;
  const padR = parseFloat(csChat.paddingRight) || 0;
  return {
    classes: row.className,
    emitterAttr: row.getAttribute('data-sched-emitter'),
    justifyContent: cs.justifyContent,
    alignItems: cs.alignItems,
    row: { l: Math.round(rr.left), r: Math.round(rr.right), w: Math.round(rr.width) },
    card: { l: Math.round(cr.left), r: Math.round(cr.right), w: Math.round(cr.width), h: Math.round(cr.height) },
    chat: { l: Math.round(chr.left), r: Math.round(chr.right), contentL: Math.round(chr.left + padL), contentR: Math.round(chr.right - padR) },
    // 行盒内的落位（对齐判据）与聊天列内的绝对留白（呈现读数）
    dyLeft: Math.round(cr.left - rr.left),
    dyRight: Math.round(rr.right - cr.right),
    gapLeft: Math.round(cr.left - (chr.left + padL)),
    gapRight: Math.round((chr.right - padR) - cr.right),
    cursor: getComputedStyle(card).cursor,
    title: card.getAttribute('title') || '',
  };
}, rowSel);

const composerText = (page) => page.evaluate(() => {
  const el = document.getElementById('input');
  return el ? el.value : null;
});
const setComposer = (page, v) => page.evaluate((val) => {
  const el = document.getElementById('input');
  el.value = val;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.setSelectionRange(val.length, val.length);
  el.blur();
}, v);

const clearComposer = (page) => setComposer(page, '');

/**
 * 等 composer 值定格（= 断言期望值）后取值。
 * 为什么必须等：手机层（body.mobile-layer）里插入引用走
 * `await import('./mobile/navController.js')` 先把详情页收起再插 —— 异步一步，
 * 点完立刻读会读到空串（曾把 D7 读成假红）。仍超时就把真值返回去，让断言报真读数。
 */
async function waitComposer(page, expected, ms = 4000) {
  try {
    await page.waitForFunction((exp) => {
      const el = document.getElementById('input');
      return !!el && el.value === exp;
    }, expected, { timeout: ms });
  } catch { /* 落到下面读真值 */ }
  return composerText(page);
}

/** 「必须仍为空」的方向也要等一小段：证明引用不是晚到。 */
async function settledComposer(page, ms = 500) {
  await page.waitForTimeout(ms);
  return composerText(page);
}

/** 点卡片上某个子节点的中心（真实鼠标点，不是 el.click()）。 */
async function clickInner(page, sel) {
  const loc = page.locator(sel).first();
  await loc.scrollIntoViewIfNeeded();
  await page.waitForTimeout(80);
  const box = await loc.boundingBox();
  if (!box) throw new Error('no box for ' + sel);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(160);
}

// ═════════════════════════════════════════════════════════════════════════
// 主流程
// ═════════════════════════════════════════════════════════════════════════

async function leg(spec) {
  const tag = spec.mobileTag || `${spec.width}x${spec.height}`;
  rec(`\n──── LEG ${tag} (${spec.width}×${spec.height}${spec.touch ? ' · mobile-layer' : ' · desktop'}) ────`);
  const env = await boot(spec);
  const { page, errors } = env;
  try {
    // 让待办库真的收到该行（真 WS 帧 → 真 mapRow）——「#」补全与卡片点击腿同一数据面
    await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      todoStore.init();
      todoStore.refresh();
    });
    await page.waitForFunction(async (id) => {
      const { todoStore } = await import('/js/todoStore.js');
      return todoStore.openItems().some((r) => String(r.id) === id);
    }, TODO_ID, { timeout: 8000 });

    const storeRow = await page.evaluate(async (id) => {
      const { todoStore } = await import('/js/todoStore.js');
      const r = todoStore.openItems().find((x) => String(x.id) === id);
      return r ? { id: r.id, name: r.name, detail: r.detail } : null;
    }, TODO_ID);
    check(`${tag} · 待办库收到该行（真帧 → mapRow：name/id 就位）`,
      !!storeRow && storeRow.id === TODO_ID && !!storeRow.name, JSON.stringify(storeRow));

    // ── A. 两侧对齐 ─────────────────────────────────────────────────────
    await renderToolCard(page, { content: TODO_NAME, name: TODO_NAME });
    const agentSel = `.sched-card-row[data-sched-row-id="${TODO_ID}"]`;
    check(`${tag} · A1 卡片行进了消息流（#chat 内，紧跟工具进程行）`,
      await page.locator(`${agentSel}`).count() === 1, await page.locator(agentSel).count() + ' 行');
    const agentSide = await sideReading(page, agentSel);
    check(`${tag} · A2 agent 发出的卡片落 agent 侧（row.ai + flex-start）`,
      !!agentSide && /\bai\b/.test(agentSide.classes) && !/\buser\b/.test(agentSide.classes)
      && agentSide.justifyContent === 'flex-start' && agentSide.alignItems === 'flex-start',
      JSON.stringify({ cls: agentSide && agentSide.classes, jc: agentSide && agentSide.justifyContent, ai: agentSide && agentSide.alignItems }));
    check(`${tag} · A3 agent 卡落行盒**左缘**（左缝 ≤1px，行内右侧留白 >20px）`,
      !!agentSide && Math.abs(agentSide.dyLeft) <= 1 && agentSide.dyRight > 20,
      JSON.stringify({ dyLeft: agentSide && agentSide.dyLeft, dyRight: agentSide && agentSide.dyRight, gapLeft: agentSide && agentSide.gapLeft }));

    await renderUserSideCard(page, { content: '作者自己建的待办（对照腿）', name: '作者自己建的待办' });
    const userSel = '[data-probe="user-side"]';
    const userSide = await sideReading(page, userSel);
    check(`${tag} · A4 作者自己发出的卡片落作者侧（row.user + flex-end）`,
      !!userSide && /\buser\b/.test(userSide.classes) && userSide.justifyContent === 'flex-end',
      JSON.stringify({ cls: userSide && userSide.classes, jc: userSide && userSide.justifyContent }));
    check(`${tag} · A5 作者卡落行盒**右缘**（右缝 ≤1px，行内左侧留白 >20px）`,
      !!userSide && Math.abs(userSide.dyRight) <= 1 && userSide.dyLeft > 20,
      JSON.stringify({ dyLeft: userSide && userSide.dyLeft, dyRight: userSide && userSide.dyRight, gapRight: userSide && userSide.gapRight }));
    check(`${tag} · A6 两侧真不同（不是一刀切全左/全右）`,
      !!agentSide && !!userSide && Math.abs(agentSide.card.l - userSide.card.l) > 100,
      JSON.stringify({ agentCardLeft: agentSide && agentSide.card.l, userCardLeft: userSide && userSide.card.l }));
    check(`${tag} · A7 可点性有信号（cursor:pointer + title 提示）`,
      !!agentSide && agentSide.cursor === 'pointer' && !!agentSide.title,
      JSON.stringify({ cursor: agentSide && agentSide.cursor, title: agentSide && agentSide.title }));
    // 两侧对齐对照图（此刻消息流里只有这两张卡，最干净）
    await page.screenshot({ path: join(SHOTS, `${tag}-align.png`), fullPage: false });

    // ── D. 手机命中区（触控档）───────────────────────────────────────────
    if (spec.touch) {
      check(`${tag} · D1 卡体触控命中区 ≥44px（实测高）`,
        !!agentSide && agentSide.card.h >= 44, `卡体 ${agentSide && agentSide.card.w}×${agentSide && agentSide.card.h}`);
      // 命中测试：卡体中心点反查 = 卡内节点（点得到，没被别的面盖住）
      const hit = await page.evaluate((sel) => {
        const card = document.querySelector(sel + ' .schedule-card');
        const r = card.getBoundingClientRect();
        const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return el ? { tag: el.tagName, inCard: card.contains(el) } : null;
      }, agentSel);
      check(`${tag} · D2 卡体中心可命中自身（未被盖住）`, !!hit && hit.inCard, JSON.stringify(hit));
    }

    // ── B. 点卡片非交互区 ⇒ composer 补 `#` 引用 ──────────────────────────
    await clearComposer(page);
    await clickInner(page, `${agentSel} .schedule-card-head`);
    const afterHead = await waitComposer(page, `#${TODO_NAME}·${TODO_ID} `);
    check(`${tag} · B1 点卡片头行（非交互区）⇒ 对话框出现 # 引用`,
      afterHead === `#${TODO_NAME}·${TODO_ID} `, JSON.stringify(afterHead));
    const caret = await page.evaluate(() => {
      const el = document.getElementById('input');
      return { start: el.selectionStart, end: el.selectionEnd, len: el.value.length, focused: document.activeElement === el };
    });
    check(`${tag} · B2 光标落在引用末尾且输入框已聚焦`,
      caret.start === caret.end && caret.start === caret.len && caret.focused, JSON.stringify(caret));

    await clearComposer(page);
    await clickInner(page, `${agentSel} .schedule-card-body`);
    check(`${tag} · B3 点卡片正文（另一块非交互区）同样补引用`,
      (await waitComposer(page, `#${TODO_NAME}·${TODO_ID} `)) === `#${TODO_NAME}·${TODO_ID} `, JSON.stringify(await composerText(page)));

    // 已有草稿时是**追加**，不吞掉用户写的字
    await setComposer(page, '帮我看看 ');
    await page.evaluate(() => {
      const el = document.getElementById('input');
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    });
    await clickInner(page, `${agentSel} .schedule-card-head`);
    check(`${tag} · B4 引用插在光标处、不吞已有草稿`,
      (await waitComposer(page, `帮我看看 #${TODO_NAME}·${TODO_ID} `)) === `帮我看看 #${TODO_NAME}·${TODO_ID} `, JSON.stringify(await composerText(page)));
    await clearComposer(page);

    // ── D3. 点空白不误触发 ───────────────────────────────────────────────
    // 找一块**真空白**：命中测试落在 #chat 自身或非卡片的子节点上（首选直接命中 #chat）
    const bgBox = await page.evaluate((sel) => {
      const chat = document.querySelector('#chat');
      const r = chat.getBoundingClientRect();
      const card = document.querySelector(sel + ' .schedule-card').getBoundingClientRect();
      const direct = [];
      const other = [];
      for (let dy = 16; dy <= 300; dy += 4) {
        for (const x of [r.left + r.width / 2, r.left + 8]) {
          const c = { x: Math.round(x), y: Math.round(card.bottom + dy) };
          if (c.x <= r.left + 1 || c.x >= r.right - 1 || c.y <= r.top + 1 || c.y >= r.bottom - 1) continue;
          const el = document.elementFromPoint(c.x, c.y);
          if (!el) continue;
          if (el.closest('.schedule-card') || el.closest('#input-area')) continue;
          if (!chat.contains(el)) continue;
          if (el === chat) direct.push(c); else other.push(c);
        }
      }
      const pick = direct[0] || other[0];
      if (!pick) return null;
      const hit = document.elementFromPoint(pick.x, pick.y);
      return { ...pick, hitTag: hit ? hit.tagName + (hit.id ? '#' + hit.id : '') : null, hitIsChat: hit === chat };
    }, agentSel);
    check(`${tag} · D3 前置：找到一块真空白（命中 #chat，不是卡片）`,
      !!bgBox && bgBox.hitIsChat, JSON.stringify(bgBox));
    if (bgBox) {
      await page.mouse.click(bgBox.x, bgBox.y);
      await page.waitForTimeout(160);
      check(`${tag} · D3b 点聊天列空白不误触发（composer 仍空）`,
        (await settledComposer(page)) === '', JSON.stringify(await composerText(page)));
    } else {
      rec(`FAIL  ${tag} · D3b 找不到空白点，判据面不成立`);
      failures += 1;
    }

    // ── D4. 触摸滚动不误触发（真 touch 手势，走 CDP）─────────────────────
    // 放在最后：先让聊天列真的长出滚动量（真工具卡行），再把手指按在卡体上真拖一次。
    if (spec.touch) {
      await page.evaluate(async (sid) => {
        const chat = await import('/js/chat.js');
        const { chatViews, setActiveView } = await import('/js/chatView.js');
        setActiveView(chatViews.primary);
        // 真用户气泡行（工具行在桌面/手机都可能被「收拢」成零高 nf-tucked，
        // 撑不出滚动量 —— 用真消息行才测得着「滚动」这件事）
        for (let i = 0; i < 14; i++) {
          chat.renderUserBubble('filler message ' + i + ' — 用来把消息列撑出真实滚动量的消息行。', []);
        }
        await new Promise((r) => setTimeout(r, 260));
      }, SID);
      const scrollerInfo = await page.evaluate((sel) => {
        const card = document.querySelector(sel + ' .schedule-card');
        let el = card.parentElement;
        while (el && el.scrollHeight <= el.clientHeight + 4) el = el.parentElement;
        if (el) el.scrollTop = 0;
        const r = card.getBoundingClientRect();
        const hit = document.elementFromPoint(r.left + r.width / 2, r.top + 12);
        const sc = el || document.querySelector('#chat');
        return {
          scroller: sc.id || sc.className || sc.tagName,
          canScroll: sc.scrollHeight - sc.clientHeight,
          before: sc.scrollTop,
          cardTop: Math.round(r.top), cardH: Math.round(r.height),
          hitInCard: !!(hit && card.contains(hit)),
        };
      }, agentSel);
      check(`${tag} · D4 聊天列此刻真可滚 + 手指落点命中卡体（判据面成立）`,
        scrollerInfo.canScroll > 100 && scrollerInfo.hitInCard, JSON.stringify(scrollerInfo));
      await page.evaluate((sel) => {
        window.__nfCardClicks = 0;
        const card = document.querySelector(sel + ' .schedule-card');
        card.addEventListener('click', () => { window.__nfCardClicks += 1; }, true);
      }, agentSel);
      const cdp = await page.context().newCDPSession(page);
      const p0 = await page.evaluate((sel) => {
        const r = document.querySelector(sel + ' .schedule-card').getBoundingClientRect();
        return { x: Math.round(r.left + r.width / 2), y: Math.round(Math.min(r.top + 14, r.bottom - 14)) };
      }, agentSel);
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: p0.x, y: p0.y }] });
      for (let i = 1; i <= 8; i++) {
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: p0.x, y: p0.y - i * 18 }] });
      }
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await page.waitForTimeout(400);
      const after = await page.evaluate((sel) => {
        const card = document.querySelector(sel + ' .schedule-card');
        let el = card.parentElement;
        while (el && el.scrollHeight <= el.clientHeight + 4) el = el.parentElement;
        const sc = el || document.querySelector('#chat');
        return { top: sc.scrollTop, clicks: window.__nfCardClicks || 0, composer: document.getElementById('input').value };
      }, agentSel);
      check(`${tag} · D5 触摸拖拽真的滚了聊天列（动作有效，非空手势）`,
        after.top > scrollerInfo.before, JSON.stringify({ before: scrollerInfo.before, after: after.top }));
      check(`${tag} · D6 滚动不误触发（卡上 click 计数 0 + composer 未改）`,
        after.clicks === 0 && after.composer === '',
        JSON.stringify({ clicks: after.clicks, composer: after.composer }));
      // 滚回卡片（后面的卡内交互腿要在视口里点）——顺带证明「滚动之后再点」仍然有效
      await page.evaluate((sel) => {
        document.querySelector(sel).scrollIntoView({ block: 'center' });
      }, agentSel);
      await page.waitForTimeout(260);
      await clearComposer(page);
      await clickInner(page, `${agentSel} .schedule-card-head`);
      check(`${tag} · D7 滚动之后点卡片仍然补引用（滚动没把卡片点废）`,
        (await waitComposer(page, `#${TODO_NAME}·${TODO_ID} `)) === `#${TODO_NAME}·${TODO_ID} `, JSON.stringify(await composerText(page)));
      await clearComposer(page);
    }

    // ── C. 卡内既有入口未被吞 ────────────────────────────────────────────
    // C0 枚举：先把**卡内全部交互件**列出来（逐项回归的对账表）。
    // 「快速决策入口」在消息流卡片里今天并不存在（那是移动待办 app 的面，属
    // feat/todo-quick-decision-20261118）；本表如实列出实际存在的入口 —— 不做
    // "探望不到的入口"的假回归，也不留"以后新增的入口被整卡点击吞掉"的口子：
    // 白名单已含 [role=button]/[tabindex]/label，下面的 C11 专门钉这条护栏。
    const entries = await page.evaluate((sel) => {
      const card = document.querySelector(sel + ' .schedule-card');
      const sel2 = 'button,a[href],input,textarea,select,label,[role="button"],[tabindex],[data-sched-act]';
      return [...card.querySelectorAll(sel2)].map((el) => ({
        tag: el.tagName,
        act: el.getAttribute('data-sched-act') || '',
        cls: String(el.className || '').split(/\s+/).filter(Boolean).slice(0, 2).join('.'),
        txt: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 14),
      }));
    }, agentSel);
    check(`${tag} · C0 卡内交互件枚举（对账表：每个入口都在白名单里、都必须让路）`,
      entries.length >= 2 && entries.every((e) => e.tag === 'BUTTON' || e.act), JSON.stringify(entries));

    // C1 取消钮：走原有二次确认（且不触达 composer）
    await clickInner(page, `${agentSel} [data-sched-act="cancel"]`);
    const confirmShown = await page.locator(`${agentSel} [data-sched-act="cancel-yes"]`).count();
    check(`${tag} · C1 卡内「取消」钮仍走原二次确认（未被整卡点击吞掉）`, confirmShown === 1, `${confirmShown} 个确认钮`);
    check(`${tag} · C2 点按钮**不**补引用（composer 仍空）`,
      (await settledComposer(page)) === '', JSON.stringify(await composerText(page)));
    await clickInner(page, `${agentSel} [data-sched-act="cancel-no"]`);
    check(`${tag} · C3 「保留」退回查看态（原行为）`,
      await page.locator(`${agentSel} [data-sched-act="cancel-yes"]`).count() === 0);

    // C4 长正文卡的折展钮
    await renderToolCard(page, { content: TODO_NAME, name: TODO_NAME, id: 'td00long', long: true });
    const longSel = '.sched-card-row[data-sched-row-id="td00long"]';
    const toggleSel = `${longSel} .schedule-card-toggle`;
    check(`${tag} · C4 长正文卡自己长出折展钮`, await page.locator(toggleSel).count() === 1);
    const clampBefore = await page.evaluate((s) => getComputedStyle(document.querySelector(s + ' .schedule-card-body')).webkitLineClamp, longSel);
    await clickInner(page, toggleSel);
    const opened = await page.evaluate((s) => ({
      open: document.querySelector(s + ' .schedule-card-body').classList.contains('is-open'),
      clamp: getComputedStyle(document.querySelector(s + ' .schedule-card-body')).webkitLineClamp,
    }), longSel);
    check(`${tag} · C5 折展钮仍能展开（原行为，未被吞）`,
      opened.open && clampBefore !== opened.clamp, JSON.stringify({ before: clampBefore, after: opened.clamp }));
    check(`${tag} · C6 点折展钮不补引用（composer 仍空）`,
      (await settledComposer(page)) === '', JSON.stringify(await composerText(page)));

    // C7 编辑表单：按钮打开表单 + 输入控件可输入 + 点输入控件不补引用
    await clickInner(page, `${agentSel} [data-sched-act="edit"]`);
    check(`${tag} · C7 「编辑」钮仍打开卡内表单`, await page.locator(`${agentSel} .schedule-card-form`).count() === 1);
    await page.locator(`${agentSel} [data-sched-field="content"]`).fill('改一版正文');
    const typed = await page.evaluate((s) => document.querySelector(s + ' [data-sched-field="content"]').value, agentSel);
    check(`${tag} · C8 表单输入控件可正常输入（原行为）`, typed === '改一版正文', typed);
    check(`${tag} · C9 点/填输入控件不补引用（composer 仍空）`,
      (await settledComposer(page)) === '', JSON.stringify(await composerText(page)));
    await clickInner(page, `${agentSel} [data-sched-act="discard"]`);
    check(`${tag} · C10 「放弃修改」退回查看态（原行为）`,
      await page.locator(`${agentSel} .schedule-card-form`).count() === 0);

    // C11 护栏：卡里**任何一个**「像交互件的东西」都必须能被白名单认出来让路 ——
    // 包括本批之后才可能加进来的入口（role=button / tabindex / label）。
    // 判据：逐个用卡内那条真实委托的同一判据（closest(CARD_INTERACTIVE_SEL)）判一遍，
    // 有一个认不出来 = 整卡点击会把它吞掉 ⇒ 红。
    const guard = await page.evaluate((sel) => {
      const CARD_INTERACTIVE_SEL = ['button', 'a[href]', 'input', 'textarea', 'select', 'label',
        '[role="button"]', '[tabindex]', '[data-sched-act]',
        '.schedule-card-form', '.schedule-card-actions'].join(',');
      const card = document.querySelector(sel + ' .schedule-card');
      const probe = document.createElement('span');
      probe.setAttribute('role', 'button');       // 未来入口形态 1
      probe.className = 'schedule-card-chip-item';
      const probe2 = document.createElement('span');
      probe2.setAttribute('tabindex', '0');       // 未来入口形态 2
      card.appendChild(probe); card.appendChild(probe2);
      const all = [...card.querySelectorAll('*')];
      const uncovered = all.filter((el) => {
        const looksInteractive = el.matches('button,a[href],input,textarea,select,label,[role="button"],[tabindex]');
        return looksInteractive && !el.closest(CARD_INTERACTIVE_SEL);
      }).length;
      const covered = all.filter((el) => el.closest(CARD_INTERACTIVE_SEL)).length;
      probe.remove(); probe2.remove();
      return { uncovered, covered };
    }, agentSel);
    check(`${tag} · C11 护栏：卡内所有"像交互件"的元素都被白名单认出让路（含未来入口形态）`,
      guard.uncovered === 0 && guard.covered >= 4, JSON.stringify(guard));

    // ── E. 真引用：提交 → 模型侧收到的读数 + 消息 chip 身份 ───────────────
    await clearComposer(page);
    await clickInner(page, `${agentSel} .schedule-card-head`);
    await waitComposer(page, `#${TODO_NAME}·${TODO_ID} `);   // 手机层插入是异步一步，等定格再提交
    env.sentFrames.length = 0;
    await page.evaluate(() => {
      const el = document.getElementById('input');
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    });
    await page.keyboard.press('Enter');
    await page.waitForTimeout(800);
    const sendFrame = env.sentFrames.find((f) => typeof f.content === 'string' && f.content.includes('#'));
    check(`${tag} · E1 提交帧真的带引用（模型侧收到的 content 逐字）`,
      !!sendFrame && sendFrame.content.trim() === `#${TODO_NAME}·${TODO_ID}`,
      JSON.stringify(sendFrame && sendFrame.content));
    check(`${tag} · E2 引用里带待办身份（id 段在帧上，不是只有名字）`,
      !!sendFrame && sendFrame.content.includes('·' + TODO_ID), JSON.stringify(sendFrame && sendFrame.content));
    const chip = await page.evaluate((id) => {
      const t = [...document.querySelectorAll(`.todo-tag[data-todo-id="${id}"]`)].pop();
      return t ? { text: t.textContent, id: t.getAttribute('data-todo-id'), name: t.getAttribute('data-todo') } : null;
    }, TODO_ID);
    check(`${tag} · E3 消息里渲染成带身份的 # 引用 chip（data-todo-id = 待办 id）`,
      !!chip && chip.id === TODO_ID && chip.name === TODO_NAME, JSON.stringify(chip));

    // ── F. 「没有可输入的对话框」的明确行为 ───────────────────────────────
    const refApi = await page.evaluate(async () => {
      const m = await import('/js/todoComplete.js');
      return {
        insert: typeof m.insertTodoReference === 'function',
        refText: typeof m.todoRefText === 'function' ? m.todoRefText('甲', 'id1') : null,
      };
    });
    check(`${tag} · F0 引用插入腿在场（todoComplete.insertTodoReference + todoRefText 单点）`,
      refApi.insert && refApi.refText === '#甲·id1', JSON.stringify(refApi));
    if (spec.touch && refApi.insert) {
      // F1 输入框被手机层详情页盖住 ⇒ 先把 chat 拉回前台，引用照样落进框里
      await page.evaluate(async () => {
        const nav = await import('/js/mobile/navController.js');
        nav.pushPage('tasks');
      });
      await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="tasks"]', { timeout: 10000 });
      await page.waitForTimeout(320);
      const covered = await page.evaluate(() => {
        const el = document.getElementById('input');
        const r = el.getBoundingClientRect();
        const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return { h: Math.round(r.height), hitTag: hit ? hit.tagName + '.' + (hit.className || '') : null, inside: !!(hit && (hit === el || el.contains(hit))) };
      });
      check(`${tag} · F1 前置：输入框此刻确被详情页盖住（判据面成立）`, covered.h > 0 && covered.inside === false, JSON.stringify(covered));
      await page.evaluate(async () => {
        const { insertTodoReference } = await import('/js/todoComplete.js');
        const el = document.getElementById('input');
        el.value = '';
        window.__f1 = insertTodoReference({ name: '盖住腿', id: 'td00cov' });
      });
      // 手机导航的收页有过渡动画：有界轮询等它落定（不 sleep 硬等）
      let pageLive = true;
      for (let i = 0; i < 24 && pageLive; i++) {
        await page.waitForTimeout(150);
        pageLive = await page.evaluate(() => !!document.querySelector('#mobile-pages .mobile-page.live[data-page="tasks"]'));
      }
      const f1 = await page.evaluate(() => ({
        composer: document.getElementById('input').value,
        ret: window.__f1,
        stackTop: (() => {
          const l = document.querySelector('.mobile-page.live');
          return l ? l.getAttribute('data-page') : 'none';
        })(),
      }));
      check(`${tag} · F2 被盖住时：先回 chat home（详情页退场）再插入`,
        f1.ret === true && f1.composer === '#盖住腿·td00cov ' && pageLive === false,
        JSON.stringify({ ...f1, pageLive }));
    }

    // F3 输入框被拆除 ⇒ 返回 false + 可见提示条（不静默）
    if (refApi.insert) {
      await page.evaluate(() => {
        const el = document.getElementById('input');
        window.__detachedInput = el;
        el.remove();
      });
      const f3 = await page.evaluate(async () => {
        const { insertTodoReference } = await import('/js/todoComplete.js');
        return insertTodoReference({ name: '无框腿', id: 'td00none' });
      });
      await page.waitForTimeout(300);
      const toastText = await page.evaluate(() => document.body.innerText.includes('找不到可输入的对话框'));
      check(`${tag} · F3 输入框被拆除 ⇒ 返回 false + 可见提示条（不静默）`,
        f3 === false && toastText, JSON.stringify({ ret: f3, toast: toastText }));
      await page.evaluate(() => {
        const el = window.__detachedInput;
        const bar = document.getElementById('input-bar');
        if (bar && el) bar.insertBefore(el, bar.firstChild);
        el.value = '';
      });
    }

    // ── 取证截图（腿收尾态：此时已跑完 D/E/F，含滚动与提示条现场）──────────
    await page.evaluate(() => { document.querySelector('#chat').scrollTop = 0; });
    await page.waitForTimeout(120);
    await page.screenshot({ path: join(SHOTS, `${tag}-final.png`), fullPage: false });

    check(`${tag} · Z 零 pageerror/console.error`, errors.length === 0, errors.join(' | '));
    return env;
  } catch (e) {
    rec(`FAIL  ${tag} · 环境/驱动错误：${e && e.stack ? e.stack : e}`);
    failures += 1;
    return env;
  }
}

// ═════════════════════════════════════════════════════════════════════════
// 连续实录（GIF/MP4）：点卡片 → 引用出现在对话框 → 提交 → 消息里出现 chip
// ═════════════════════════════════════════════════════════════════════════

async function recordMobileClip() {
  rec('\n──── RECORD 手机全屏连续实录（375×812）────');
  const env = await boot({ width: 375, height: 812, touch: true, scheme: 'light', record: true, mobileTag: '375x812-rec' });
  const { page } = env;
  try {
    await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      todoStore.init(); todoStore.refresh();
    });
    await page.waitForFunction(async (id) => {
      const { todoStore } = await import('/js/todoStore.js');
      return todoStore.openItems().some((r) => String(r.id) === id);
    }, TODO_ID, { timeout: 8000 });
    await renderToolCard(page, { content: TODO_NAME, name: TODO_NAME });
    await page.evaluate(() => { document.querySelector('#chat').scrollTop = 0; });
    await page.waitForTimeout(900);
    const sel = `.sched-card-row[data-sched-row-id="${TODO_ID}"] .schedule-card-head`;
    await clickInner(page, sel);
    await page.waitForTimeout(1200);            // 引用落框（看得到）
    await page.evaluate(() => {
      const el = document.getElementById('input');
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    });
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1500);            // 消息 + chip 落屏
    await page.screenshot({ path: join(SHOTS, '375x812-record-final.png') });
  } catch (e) {
    rec(`FAIL  RECORD 错误：${e && e.stack ? e.stack : e}`);
    failures += 1;
  } finally {
    await env.ctx.close();
    await env.browser.close();
  }
}

function convertVideo() {
  const vids = readdirSync(VIDEO_TMP).filter((f) => f.endsWith('.webm'));
  if (vids.length === 0) { rec('FAIL  连续实录：没有录到视频'); failures += 1; return; }
  const src = join(VIDEO_TMP, vids.sort().pop());
  const gif = join(OUT, 'todo-card-click-ref.gif');
  const mp4 = join(OUT, 'todo-card-click-ref.mp4');
  const run = (args) => spawnSync('ffmpeg', args, { encoding: 'utf8' });
  // 原生像素 375×812 CSS（录出 375×814）：右侧 1px / 底部 2px 裁到偶数尺寸，
  // 既满足 H.264/yuv420p 的偶数约束，又不动用放大（GIF 与 MP4 逐像素同源）。
  const VF = 'fps=15,crop=374:812';
  let r = run(['-y', '-i', src, '-vf', `${VF},split[a][b];[a]palettegen[p];[b][p]paletteuse`, gif]);
  if (r.status !== 0) { rec('FAIL  GIF 转码失败：' + (r.stderr || '').slice(-400)); failures += 1; }
  r = run(['-y', '-i', src, '-vf', VF, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '26', '-movflags', '+faststart', mp4]);
  if (r.status !== 0) { rec('FAIL  MP4 转码失败：' + (r.stderr || '').slice(-400)); failures += 1; }
  const probe = (f) => {
    if (!existsSync(f)) return null;
    const p = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
      'stream=width,height,avg_frame_rate,nb_frames,duration', '-show_entries', 'format=duration,size',
      '-of', 'json', f], { encoding: 'utf8' });
    const j = (() => { try { return JSON.parse(p.stdout); } catch { return null; } })();
    if (!j || !j.streams || !j.streams[0]) return null;
    return { ...j.streams[0], size: j.format ? j.format.size : null, file_duration: j.format ? j.format.duration : null };
  };
  const gi = probe(gif);
  const mi = probe(mp4);
  rec('   GIF ffprobe: ' + JSON.stringify(gi));
  rec('   MP4 ffprobe: ' + JSON.stringify(mi));
  const gifBytes = existsSync(gif) ? statSync(gif).size : 0;
  const mp4Bytes = existsSync(mp4) ? statSync(mp4).size : 0;
  check('REC1 连续实录真的录出来了（GIF + MP4 同源同帧数，MP4 15fps，尺寸可读）',
    !!gi && !!mi && Number(gi.width) === 374 && Number(gi.height) === 812
    && mi.avg_frame_rate === '15/1'
    // GIF 的 avg_frame_rate 由 1/100s 帧延时量化而来（ffprobe 读数不恒等于 15/1），
    // 所以对 GIF 只钉「帧数与时长跟 MP4 逐值相等」——同一源、同一 fps 滤镜。
    && gi.nb_frames === mi.nb_frames
    && Math.abs(Number(gi.duration) - Number(mi.duration)) < 0.15,
    JSON.stringify({ gif: gi && `${gi.width}x${gi.height} n=${gi.nb_frames} d=${gi.duration} rate=${gi.avg_frame_rate}`, mp4: mi && `${mi.width}x${mi.height} n=${mi.nb_frames} d=${mi.duration} rate=${mi.avg_frame_rate}` }));
  check('REC2 交付媒体 ≤10MB', gifBytes > 0 && gifBytes <= 10 * 1024 * 1024 && mp4Bytes > 0,
    `gif=${gifBytes}B mp4=${mp4Bytes}B`);
  rmSync(VIDEO_TMP, { recursive: true, force: true });
}

// ═════════════════════════════════════════════════════════════════════════

async function main() {
  const only = process.env.TCLC_ONLY || '';
  if (!only || only === 'mobile') await leg({ width: 375, height: 812, touch: true, scheme: 'light', mobileTag: '375x812' });
  if (!only || only === 'dark') await leg({ width: 375, height: 812, touch: true, scheme: 'dark', mobileTag: '375x812-dark' });
  if (!only || only === 'desktop') await leg({ width: 1280, height: 800, touch: false, scheme: 'dark', mobileTag: '1280x800' });
  if (!only || only === 'record') await recordMobileClip();
  if (!only || only === 'record') convertVideo();
  rec(`\n── 汇总：${log.filter((l) => l.startsWith('PASS')).length} PASS · ${failures} FAIL ──`);
  writeFileSync(join(OUT, 'result.txt'), `# todo-card-click-left gate\nWEB=${WEB}\nOUT=${OUT}\n\n` + log.join('\n') + `\n\nFAIL=${failures}\n`);
  return failures === 0 ? 0 : 1;
}

let code = 2;
try { code = await main(); } catch (e) { console.error('environment error:', e && e.stack ? e.stack : e); code = 2; }
process.exit(code);
