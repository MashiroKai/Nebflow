#!/usr/bin/env node
// todo-capsule-redesign-shots.mjs — 待办胶囊重做批（作者令 2026-11-02 11:52）
// 机械断言 ①–⑨ + 实拍证据 + 零 console error。
//
// 作者原话：「收缩到只显示 8 待办 这么几个文字的大小程度的一个在对话框下方的
// 一个胶囊。然后出现逾期的时候就是翻转一下 然后只显示 1 逾期 胶囊的大小不改变。
// 然后点击的时候就是从这么个小胶囊拓展到一个大的界面。样式要设计好，现在上下
// 都没居中。」
//
// 断言（不接受口头验收）：
//   ① 静止胶囊宽度 < 输入区宽度 × 40% 且由内容决定；两种文案下宽度严格相等
//   ② 胶囊水平中心 ↔ 输入区中线 ≤ 1px
//   ③ 文字行盒垂直中心 ↔ 胶囊垂直中心 ≤ 1px；圆点 cy ↔ 文字 cy ≤ 1px
//   ④ 左右内距差 ≤ 1px
//   ⑤ 有逾期时「待办」不可见/不可读；无逾期时只显示待办；两态渲染宽高逐值相等
//   ⑥ 点击展开面板、面板可见居中、有退出方式（点外部 / Esc / 收起钮）、
//      收起后胶囊尺寸不变
//   ⑦ prefers-reduced-motion 下无动画但状态正确（翻面/展开都成立）
//   ⑧ 深浅主题各验一次
//   ⑨ 桌面 1280 / 1440 都成立
//
// 产出 = evidence/20261102_todo-capsule/shots/*.png（桌面 1280+1440 × 亮/暗：
//   静止 / 逾期翻转 / 两态尺寸对照 / 展开面板 / 收起后 / 悬停 / 焦点；手机 375
//   亮暗各一帧）
// 用法：node tests/todo-capsule-redesign-shots.mjs
import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// CAPSULE_WEB 覆盖 = 「改前对照」腿（对 HEAD 快照工作树跑同一脚本取 before 帧）
const WEB = process.env.CAPSULE_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.CAPSULE_OUT || join(HERE, '..', 'evidence', '20261102_todo-capsule', 'shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'capsule-root-session';
const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };
const T0 = Date.now();
const H = 3600_000;
const PORT = 8121;

let passCount = 0;
let failCount = 0;
const fails = [];
function ok(name, cond, detail = '') {
  if (cond) { passCount++; console.log(`  PASS ${name}`); }
  else { failCount++; fails.push(`${name}${detail ? ` — ${detail}` : ''}`); console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`); }
}
const r1 = (v) => Math.round(v * 10) / 10;

// ── 确定性种子（作者原文两态 = 「8 待办」且其中 1 条逾期 ⇒
//    openCount=8 / overdueCount=1；翻面后显示「1 逾期」，翻回显示「8 待办」）────
function seedScript(overdue = true, count = 8) {
  return `
  (async () => {
    const m = await import('/js/todoStore.js');
    const now = ${T0};
    const H = ${H};
    const due = [];
    // overdue 时未来条目 = count − 1（逾期那条计入总待办数）
    const future = ${overdue} ? ${count} - 1 : ${count};
    for (let i = 0; i < future; i++) {
      due.push({
        id: 'c-' + i, tagId: i % 2 ? 'tag-life' : 'tag-work',
        title: '待办条目 ' + (i + 1), notes: '',
        due: now + (i + 1) * H, priority: i % 4, createdAt: now - i * H,
      });
    }
    ${overdue ? `due.push({ id: 'c-overdue', tagId: 'tag-work', title: '已逾期的一条', notes: '', due: now - 3 * H, priority: 3, createdAt: now - 30 * H });` : ''}
    m.todoStore.__seed({
      tagList: [
        { id: 'tag-work', name: '工作', color: m.TAG_COLOR_PRESETS[5] },
        { id: 'tag-life', name: '生活', color: m.TAG_COLOR_PRESETS[3] },
      ],
      itemList: due,
    });
  })()`;
}

async function makePage(browser, { width, colorScheme, mobile = false, reducedMotion = 'no-preference' } = {}) {
  const ctx = await browser.newContext({
    colorScheme,
    viewport: mobile ? { width: 375, height: 812 } : { width, height: 900 },
    deviceScaleFactor: 2,
    hasTouch: mobile,
    reducedMotion,
    serviceWorkers: 'block',
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = url.pathname;
    if (p === '/') p = '/index.html';
    if (p === '/api/nf-asset-stamp') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ stamp: 'e2e-fixed' }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Spider' }) });
    }
    if (p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: SELF_DEVICE,
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto(`http://localhost:${PORT}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  if (!mobile) {
    await page.evaluate(async (sid) => {
      const f = await import('/js/todoFace.js');
      f.renderTodoTaskList(sid);
    }, ROOT_SID);
  }
  await page.waitForTimeout(400);
  return { page, errors, ctx };
}

const shot = (page, name) => page.screenshot({ path: join(OUT, `${name}.png`) });
const shotEl = (page, sel, name) => page.locator(sel).first().screenshot({ path: join(OUT, `${name}.png`) });

// ── 几何采集（只读）───────────────────────────────────────────────────────
const geoFn = () => {
  const q = (s) => document.querySelector(s);
  const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, cx: r.x + r.width / 2, cy: r.y + r.height / 2, r: r.right, b: r.bottom }; };
  const cap = q('#task-list .tfl-capsule');
  const bar = q('#input-bar');
  const area = q('#input-area');
  const mount = q('#task-list .tfl-mount');
  const dot = q('#task-list .tfl-capsule-dot');
  const faceTodo = q('#task-list .tfl-cap-face-todo');
  const faceOver = q('#task-list .tfl-cap-face-overdue');
  const visibleFace = faceTodo && getComputedStyle(faceTodo).visibility !== 'hidden' ? faceTodo
    : (faceOver && getComputedStyle(faceOver).visibility !== 'hidden' ? faceOver : null);
  const cs = cap ? getComputedStyle(cap) : null;
  const kids = cap ? [...cap.children].map((k) => k.getBoundingClientRect()) : [];
  const contentLeft = kids.length ? Math.min(...kids.map((r) => r.left)) : null;
  const contentRight = kids.length ? Math.max(...kids.map((r) => r.right)) : null;
  return {
    cap: rect(cap), bar: rect(bar), area: rect(area), mount: rect(mount), dot: rect(dot),
    face: rect(visibleFace),
    faceTodoVisible: faceTodo ? getComputedStyle(faceTodo).visibility !== 'hidden' : null,
    faceOverVisible: faceOver ? getComputedStyle(faceOver).visibility !== 'hidden' : null,
    faceTodoText: faceTodo ? faceTodo.textContent.trim() : null,
    faceOverText: faceOver ? faceOver.textContent.trim() : null,
    ariaLabel: cap ? cap.getAttribute('aria-label') : null,
    faceTodoAriaHidden: faceTodo ? faceTodo.getAttribute('aria-hidden') : null,
    faceOverAriaHidden: faceOver ? faceOver.getAttribute('aria-hidden') : null,
    radius: cs ? cs.borderTopLeftRadius : null,
    padL: cs ? parseFloat(cs.paddingLeft) : null,
    padR: cs ? parseFloat(cs.paddingRight) : null,
    bg: cs ? cs.backgroundColor : null,
    card: rect(q('#task-list .tfl-card')),
    cardState: q('#task-list .tfl-card') ? q('#task-list .tfl-card').dataset.state : null,
    cardDisplay: q('#task-list .tfl-card') ? getComputedStyle(q('#task-list .tfl-card')).display : null,
    contentLeft, contentRight,
    overflowX: cap ? cap.scrollWidth - cap.clientWidth : null,
    viewport: { w: innerWidth, h: innerHeight },
  };
};
const geo = (page) => page.evaluate(geoFn);

// ── 桌面腿 ────────────────────────────────────────────────────────────────
async function desktopLeg(browser, width, colorScheme, opts = {}) {
  const reduced = opts.reducedMotion === 'reduce';
  const tag = `${width}-${colorScheme}${reduced ? '-reduced' : ''}`;
  const { page, errors, ctx } = await makePage(browser, { width, colorScheme, reducedMotion: opts.reducedMotion || 'no-preference' });
  try {
    // ── 逾期态（种子 = 8 待办 + 1 逾期 ⇒ 作者原文两态）──
    await page.evaluate(seedScript(true, 8));
    await page.waitForSelector('#task-list .tfl-capsule', { timeout: 6000 });
    await page.waitForTimeout(350);
    let g = await geo(page);
    console.log(`[${tag}] overdue-state`, JSON.stringify({
      cap: g.cap && { x: r1(g.cap.x), y: r1(g.cap.y), w: r1(g.cap.w), h: r1(g.cap.h) },
      bar: g.bar && { x: r1(g.bar.x), w: r1(g.bar.w), cx: r1(g.bar.cx) },
      text: g.faceOverText, aria: g.ariaLabel,
    }));
    // 读数明细（docs 尺寸表取数口径）：居中三重 + 内距 + 圆点
    console.log(`[${tag}] metrics`, JSON.stringify({
      capW: r1(g.cap.w), capH: r1(g.cap.h),
      widthRatio: Math.round((g.cap.w / g.bar.w) * 1000) / 1000,
      centerDx: r1(g.cap.cx - g.bar.cx),
      lineBoxDy: r1(g.face.cy - g.cap.cy),
      dotDy: r1(g.dot.cy - g.face.cy),
      padL: g.padL, padR: g.padR,
      contentGapL: r1(g.contentLeft - g.cap.x), contentGapR: r1(g.cap.r - g.contentRight),
      dotD: r1(g.dot.w),
    }));

    // ⑤（逾期态）：只显示「N 逾期」，「待办」面不可见/不可读
    ok(`⑤[${tag}] 逾期态显示「1 逾期」`, g.faceOverVisible === true && /1\s*逾期/.test(g.faceOverText || ''), g.faceOverText);
    ok(`⑤[${tag}] 逾期态「待办」面不可见`, g.faceTodoVisible === false);
    ok(`⑤[${tag}] 逾期态「待办」面 aria-hidden`, g.faceTodoAriaHidden === 'true');
    ok(`⑤[${tag}] 胶囊 aria-label = 逾期读数`, /逾期/.test(g.ariaLabel || ''), g.ariaLabel);
    // ① / ② / ③ / ④
    const capOver = g.cap, barW = g.bar.w;
    ok(`①[${tag}] 胶囊宽度 < 输入区 × 40%`, capOver.w < barW * 0.4, `${r1(capOver.w)} vs ${r1(barW * 0.4)}`);
    ok(`②[${tag}] 胶囊中心 ↔ 输入区中线 ≤1px`, Math.abs(capOver.cx - g.bar.cx) <= 1, `${r1(capOver.cx)} vs ${r1(g.bar.cx)}`);
    ok(`③[${tag}] 文字行盒中心 ↔ 胶囊中心 ≤1px`, Math.abs(g.face.cy - capOver.cy) <= 1, `${r1(g.face.cy)} vs ${r1(capOver.cy)}`);
    ok(`③[${tag}] 圆点 cy ↔ 文字 cy ≤1px`, Math.abs(g.dot.cy - g.face.cy) <= 1, `${r1(g.dot.cy)} vs ${r1(g.face.cy)}`);
    const gapL = r1(g.contentLeft - capOver.x), gapR = r1(capOver.r - g.contentRight);
    ok(`④[${tag}] 左右内距差 ≤1px`, Math.abs(gapL - gapR) <= 1, `left=${gapL} right=${gapR}`);
    ok(`④[${tag}] 计算内距左右相等`, g.padL === g.padR, `${g.padL}/${g.padR}`);
    ok(`样式[${tag}] 圆角 999（胶囊）`, parseFloat(g.radius) >= 999 - 0.5 || g.radius === '999px', g.radius);
    ok(`⑤[${tag}] 逾期态无横向溢出`, g.overflowX <= 0, String(g.overflowX));
    await shot(page, `d-${tag}-overdue`);
    await shotEl(page, '#task-list', `d-${tag}-overdue-el`);
    await shotEl(page, '#task-list .tfl-capsule', `d-${tag}-overdue-cap`);
    const sizeOver = { w: capOver.w, h: capOver.h };

    // ── 无逾期态（同一会话，清掉逾期项 ⇒ 翻回「8 待办」）──
    await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      todoStore.updateItem('c-overdue', { hasDue: false });
    });
    await page.waitForTimeout(reduced ? 120 : 400);
    g = await geo(page);
    console.log(`[${tag}] todo-state`, JSON.stringify({
      cap: g.cap && { x: r1(g.cap.x), w: r1(g.cap.w), h: r1(g.cap.h) },
      text: g.faceTodoText, aria: g.ariaLabel,
    }));
    ok(`⑤[${tag}] 无逾期时只显示待办`, g.faceTodoVisible === true && g.faceOverVisible === false, `${g.faceTodoText} / ${g.faceOverText}`);
    ok(`⑤[${tag}] 无逾期文案 = 8 待办`, /8\s*待办/.test(g.faceTodoText || ''), g.faceTodoText);
    ok(`⑤[${tag}] 无逾期时「逾期」面 aria-hidden`, g.faceOverAriaHidden === 'true');
    ok(`⑤[${tag}] 两态宽度逐值相等（0px 差）`, g.cap.w === sizeOver.w, `${r1(g.cap.w)} vs ${r1(sizeOver.w)}`);
    ok(`⑤[${tag}] 两态高度逐值相等（≤1px）`, Math.abs(g.cap.h - sizeOver.h) <= 1, `${r1(g.cap.h)} vs ${r1(sizeOver.h)}`);
    ok(`②[${tag}] 无逾期态中心仍居中`, Math.abs(g.cap.cx - g.bar.cx) <= 1, `${r1(g.cap.cx)} vs ${r1(g.bar.cx)}`);
    await shot(page, `d-${tag}-todo`);
    await shotEl(page, '#task-list .tfl-capsule', `d-${tag}-todo-cap`);
    const sizeTodo = { w: g.cap.w, h: g.cap.h };

    // ── 三位数溢出探针（报告口径：固定宽怎么算 / 三位数会不会溢出）──
    await page.evaluate(seedScript(false, 128));
    await page.waitForTimeout(400);
    const g3 = await geo(page);
    ok(`①[${tag}] 三位数（128 待办）仍在 40% 以内`, g3.cap.w < g3.bar.w * 0.4, `${r1(g3.cap.w)} / ${r1(g3.bar.w)}`);
    ok(`①[${tag}] 三位数无横向溢出`, g3.overflowX <= 0, String(g3.overflowX));
    ok(`①[${tag}] 三位数宽度由内容驱动（> 8 待办宽）`, g3.cap.w > sizeTodo.w, `${r1(g3.cap.w)} vs ${r1(sizeTodo.w)}`);
    await shotEl(page, '#task-list', `d-${tag}-3digit`);
    // 复位到两态对照的种子
    await page.evaluate(seedScript(true, 8));
    await page.waitForTimeout(400);

    // ── 悬停 / 焦点态 ──
    const capBox = await page.locator('#task-list .tfl-capsule').boundingBox();
    await page.mouse.move(capBox.x + capBox.width / 2, capBox.y + capBox.height / 2);
    await page.waitForTimeout(220);
    await shotEl(page, '#task-list', `d-${tag}-hover`);
    await page.keyboard.press('Tab');
    await page.mouse.move(10, 10);
    await page.evaluate(() => document.querySelector('#task-list .tfl-capsule').focus());
    await page.waitForTimeout(160);
    await shotEl(page, '#task-list', `d-${tag}-focus`);
    const focusRing = await page.evaluate(() => {
      const c = document.querySelector('#task-list .tfl-capsule');
      const s = getComputedStyle(c);
      return { focused: document.activeElement === c, outlineWidth: s.outlineWidth, outlineStyle: s.outlineStyle };
    });
    ok(`样式[${tag}] 焦点可见描边`, focusRing.focused && parseFloat(focusRing.outlineWidth) > 0, JSON.stringify(focusRing));

    // ── ⑥ 点击展开大面板 ──
    const before = (await geo(page)).cap;
    await page.click('#task-list .tfl-capsule');
    await page.waitForTimeout(reduced ? 150 : 800);
    g = await geo(page);
    console.log(`[${tag}] open`, JSON.stringify({
      card: g.card && { x: r1(g.card.x), y: r1(g.card.y), w: r1(g.card.w), h: r1(g.card.h), cx: r1(g.card.cx) },
      bar: { cx: r1(g.bar.cx) }, state: g.cardState,
    }));
    console.log(`[${tag}] panelMetrics`, JSON.stringify({
      panelW: r1(g.card.w), panelH: r1(g.card.h),
      panelCenterDx: r1(g.card.cx - g.bar.cx),
      panelBottomToArea: r1(g.area.y - g.card.b),
      areaH: r1(g.area.h),
    }));
    ok(`⑥[${tag}] 面板展开且可见`, g.cardState === 'open' && g.cardDisplay === 'flex' && g.card.w > 0 && g.card.h > 0, JSON.stringify({ state: g.cardState, d: g.cardDisplay }));
    ok(`⑥[${tag}] 面板水平居中（↔ 输入区中线 ≤1px）`, Math.abs(g.card.cx - g.bar.cx) <= 1, `${r1(g.card.cx)} vs ${r1(g.bar.cx)}`);
    ok(`⑥[${tag}] 面板在视口内`, g.card.x >= 0 && g.card.r <= g.viewport.w && g.card.y >= 0, JSON.stringify(g.card));
    ok(`⑥[${tag}] 面板不与输入区重叠（面板底 ≤ 输入区顶）`, g.card.b <= g.area.y + 0.5, `${r1(g.card.b)} vs ${r1(g.area.y)}`);
    ok(`⑥[${tag}] 面板复用既有列表（8 行）`, await page.locator('.tfl-row').count() === 8, String(await page.locator('.tfl-row').count()));
    ok(`⑥[${tag}] 展开后胶囊尺寸不变`, g.cap.w === before.w && Math.abs(g.cap.h - before.h) <= 1, `${r1(g.cap.w)}/${r1(g.cap.h)} vs ${r1(before.w)}/${r1(before.h)}`);
    await shot(page, `d-${tag}-open`);
    await shotEl(page, '#task-list .tfl-card', `d-${tag}-open-panel`);

    // 退出方式 1：Esc（焦点在面板外 —— 输入框）
    await page.evaluate(() => document.querySelector('#input').focus());
    await page.keyboard.press('Escape');
    await page.waitForTimeout(reduced ? 150 : 800);
    g = await geo(page);
    ok(`⑥[${tag}] Esc 收起（焦点在输入框）`, g.cardState === 'capsule', g.cardState);
    ok(`⑥[${tag}] 收起后胶囊尺寸不变`, g.cap.w === before.w && Math.abs(g.cap.h - before.h) <= 1, `${r1(g.cap.w)}/${r1(g.cap.h)}`);
    ok(`⑥[${tag}] 收起后胶囊仍居中`, Math.abs(g.cap.cx - g.bar.cx) <= 1);
    await shot(page, `d-${tag}-after-collapse`);

    // 退出方式 2：收起钮
    await page.click('#task-list .tfl-capsule');
    await page.waitForTimeout(reduced ? 150 : 700);
    await page.click('#task-list .tfl-collapse-btn');
    await page.waitForTimeout(reduced ? 150 : 800);
    ok(`⑥[${tag}] 收起钮收起`, (await geo(page)).cardState === 'capsule');

    // 退出方式 3：点外部（聊天体空白）。2026-11-03 10:11 ① 挪位批：面板改为
    // 锚顶带自胶囊**向下**生长（水平居中、宽 min(420, vw-32)），原坐标
    // (width/2, 300) 现落在面板**内部**（旧几何里面板长在输入框上方，300 必空）；
    // 「外部」语义不变，坐标改取聊天体左缘空白（x=24 恒在居中面板的水平范围外）。
    await page.click('#task-list .tfl-capsule');
    await page.waitForTimeout(reduced ? 150 : 700);
    await page.mouse.click(24, 300);
    await page.waitForTimeout(reduced ? 150 : 800);
    ok(`⑥[${tag}] 点外部收起`, (await geo(page)).cardState === 'capsule');

    // 退出方式 4：再点胶囊（toggle）
    await page.click('#task-list .tfl-capsule');
    await page.waitForTimeout(reduced ? 150 : 700);
    await page.click('#task-list .tfl-capsule');
    await page.waitForTimeout(reduced ? 150 : 700);
    ok(`⑥[${tag}] 再点胶囊收起（toggle）`, (await geo(page)).cardState === 'capsule');

    // ── ⑦ reduced-motion：状态正确、无动画 ──
    if (reduced) {
      const anim = await page.evaluate(() => ({
        cap: getComputedStyle(document.querySelector('#task-list .tfl-cap-face')).animationName,
        card: getComputedStyle(document.querySelector('#task-list .tfl-card')).animationName,
      }));
      ok(`⑦[${tag}] 翻面动画退役`, anim.cap === 'none', JSON.stringify(anim));
      ok(`⑦[${tag}] 面板动画退役`, anim.card === 'none', JSON.stringify(anim));
      // 翻面在 reduced-motion 下仍然是「一步到位的正确状态」
      await page.evaluate(async () => {
        const { todoStore } = await import('/js/todoStore.js');
        todoStore.updateItem('c-overdue', { due: Date.now() - 3600_000 });
      });
      await page.waitForTimeout(120);
      const gR = await geo(page);
      ok(`⑦[${tag}] reduced-motion 翻面状态正确`, gR.faceOverVisible === true && gR.faceTodoVisible === false);
      ok(`⑦[${tag}] reduced-motion 下两态宽度仍相等`, gR.cap.w === sizeTodo.w, `${r1(gR.cap.w)} vs ${r1(sizeTodo.w)}`);
    }

    ok(`零 console error [${tag}]`, errors.length === 0, errors.slice(0, 3).join(' | '));
  } catch (e) {
    failCount++; fails.push(`desktop ${tag} threw — ${e.message}`);
    console.log(`  FAIL desktop ${tag} threw — ${e.message}`);
    await shot(page, `d-${tag}-ERROR`).catch(() => {});
  } finally {
    await ctx.close();
  }
}

// ── 手机腿（共用组件不得被带崩：375 亮/暗各一帧 + 展开面可用）──────────────
async function mobileLeg(browser, colorScheme) {
  const tag = `375-${colorScheme}`;
  const { page, errors, ctx } = await makePage(browser, { colorScheme, mobile: true });
  try {
    await page.waitForSelector('[data-chrome="tasks"]', { timeout: 8000 });
    /* 口径翻新（胶囊面交互批 2026-11-04 ⑤ · 作者令「手机层胶囊必须可见」）：
       原断言 = 「手机层桌面胶囊槽位整条 display:none」（css/mobileLayer.css 的
       旧规则，已在本批退役）。新口径 = 槽位可见且胶囊在场（手机端待办入口），
       任务页（taskPanel 圆钮）那条腿仍不动 —— 入口退役归下游 entries 节点。 */
    ok(`手机[${tag}] 桌面胶囊槽位在手机层显示`, await page.evaluate(() => {
      const el = document.getElementById('task-list');
      if (!el || getComputedStyle(el).display === 'none') return false;
      return !!el.querySelector('.tfl-capsule');
    }));
    ok(`手机[${tag}] 手机层胶囊可见`, await page.locator('#task-list .tfl-capsule:visible').count() === 1);
    await page.click('[data-chrome="tasks"]');
    await page.waitForSelector('#mobile-task-panel:not([hidden]) .tfl-mobile', { timeout: 6000 });
    await page.waitForTimeout(600);
    ok(`手机[${tag}] 手机展开面正常（行数 = 8）`, await page.locator('#mobile-task-panel .tfl-row').count() === 8,
      String(await page.locator('#mobile-task-panel .tfl-row').count()));
    ok(`手机[${tag}] 手机面无胶囊`, await page.locator('#mobile-task-panel .tfl-capsule:visible').count() === 0);
    await shot(page, `m-${tag}-todo-page`);
    ok(`手机零 console error [${tag}]`, errors.length === 0, errors.slice(0, 3).join(' | '));
  } catch (e) {
    failCount++; fails.push(`mobile ${tag} threw — ${e.message}`);
    console.log(`  FAIL mobile ${tag} threw — ${e.message}`);
  } finally {
    await ctx.close();
  }
}

// ── 主流程 ───────────────────────────────────────────────────────────────
const browser = await chromium.launch({ headless: true });
for (const width of [1280, 1440]) {
  for (const scheme of ['light', 'dark']) {
    console.log(`\n══ desktop ${width} ${scheme} ══`);
    await desktopLeg(browser, width, scheme);
  }
}
console.log('\n══ desktop 1440 reduced-motion ══');
await desktopLeg(browser, 1440, 'dark', { reducedMotion: 'reduce' });
for (const scheme of ['light', 'dark']) {
  console.log(`\n══ mobile 375 ${scheme} ══`);
  await mobileLeg(browser, scheme);
}
await browser.close();

console.log(`\n──── todo-capsule-redesign: ${passCount} pass / ${failCount} fail ────`);
if (fails.length) { console.log('FAILED:\n' + fails.map((f) => `  - ${f}`).join('\n')); process.exit(1); }
