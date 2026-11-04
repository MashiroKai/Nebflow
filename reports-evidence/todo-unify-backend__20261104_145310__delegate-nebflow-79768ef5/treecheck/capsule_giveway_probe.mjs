#!/usr/bin/env node
// capsule_giveway_probe.mjs — 验：全套机械验收批（2026-11-04）④ 胶囊让位链实测。
//
// 测什么（只读数、不改实现）：
//   ① #task-list 仍挂在 #top-overlays（DOM 祖先链，容错：再核 contains）。
//   ② 胶囊出现 ⇒ 顶带 #top-overlays 变高 ⇒ #chat 的 padding-top 让位跟随
//      （js/main.js bandBottom() + syncTopReserve 观察器）；
//      判据 = Δpadding ≈ Δ(#top-overlays.bottom)（±2px），且 padding 严格增大。
//   ③ 胶囊消失（空态）⇒ 让位回落约等于初始值。
//   ④ 手机 375×812：body.mobile-layer 下胶囊可见（panel 段新口径，
//      原「整条 display:none」已退役）且可点。
//
// 环境 = 零后端零 sbt 零 6138：page.route 拦截全部请求由 web/ 目录直接回填充，
//        不 bind 任何端口（与 tests/todo-capsule-redesign-shots.mjs 同一 harness 腿）。
// 用法：node reports-evidence/.../treecheck/capsule_giveway_probe.mjs

import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.PROBE_WEB || join(HERE, '..', '..', '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, 'shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'giveway-probe-root';
const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };
const T0 = Date.now();
const H = 3600_000;

let pass = 0; let fail = 0; const fails = [];
function ok(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  PASS ${name}`); }
  else { fail++; fails.push(`${name}${detail ? ` — ${detail}` : ''}`); console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`); }
}
const r1 = (v) => Math.round(v * 10) / 10;

function seedScript(count, overdue = true) {
  return `
  (async () => {
    const m = await import('/js/todoStore.js');
    const now = ${T0}; const H = ${H}; const due = [];
    const future = ${overdue} && ${count} > 0 ? ${count} - 1 : ${count};
    for (let i = 0; i < future; i++) {
      due.push({ id: 'g-' + i, tagId: i % 2 ? 'tag-life' : 'tag-work',
        title: '让位探针条目 ' + (i + 1), notes: '',
        due: now + (i + 1) * H, priority: i % 4, createdAt: now - i * H });
    }
    ${overdue && count > 0 ? `due.push({ id: 'g-overdue', tagId: 'tag-work', title: '已逾期的一条', notes: '', due: now - 3 * H, priority: 3, createdAt: now - 30 * H });` : ''}
    m.todoStore.__seed({
      tagList: [
        { id: 'tag-work', name: '工作', color: m.TAG_COLOR_PRESETS[5] },
        { id: 'tag-life', name: '生活', color: m.TAG_COLOR_PRESETS[3] },
      ],
      itemList: due,
    });
  })()`;
}

/** 让位链读数（只读）。 */
const probeFn = async () => {
  const S = await import('/js/todoStore.js');
  const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, h: r.height }; };
  const taskList = document.getElementById('task-list');
  const overlays = document.getElementById('top-overlays');
  const chat = document.getElementById('chat');
  const header = document.getElementById('header');
  const cap = document.querySelector('#task-list .tfl-capsule');
  const cs = chat ? getComputedStyle(chat) : null;
  const edgeFade = document.getElementById('g2-edge-fade');
  // bandBottom() 复算（与 js/main.js 同式）
  const bandBottom = Math.max(
    header ? header.getBoundingClientRect().bottom : 0,
    overlays ? overlays.getBoundingClientRect().bottom : 0,
    edgeFade ? edgeFade.getBoundingClientRect().bottom : 0,
  );
  return {
    mobileLayer: document.body.classList.contains('mobile-layer'),
    taskListParentId: taskList && taskList.parentElement ? taskList.parentElement.id : null,
    taskListAncestorIds: (() => {
      const ids = []; let n = taskList;
      while (n) { if (n.id) ids.push(n.id); n = n.parentElement; }
      return ids;
    })(),
    taskListInTopOverlays: !!(taskList && overlays && overlays.contains(taskList)),
    overlays: rect(overlays),
    header: rect(header),
    chatTop: chat ? chat.getBoundingClientRect().top : null,
    chatPaddingTop: cs ? parseFloat(cs.paddingTop) : null,
    chatPaddingTopInline: chat ? chat.style.getPropertyValue('padding-top') : null,
    bandBottom,
    capsule: cap ? rect(cap) : null,
    capsuleVisible: cap ? (cap.getClientRects().length > 0 && getComputedStyle(cap).visibility !== 'hidden') : false,
    taskListDisplay: taskList ? getComputedStyle(taskList).display : null,
    taskListHasTasksClass: taskList ? taskList.classList.contains('has-tasks') : null,
    capsuleText: cap ? cap.textContent.trim().replace(/\s+/g, ' ') : null,
    cardRect: (() => { const c = document.querySelector('#task-list .tfl-card'); return c ? rect(c) : null; })(),
    storeOpen: S.todoStore.openCount(),
    storeDataSource: S.todoStore.dataSource,
  };
};

async function makePage(browser, { mobile = false, mountDesktop = true } = {}) {
  const ctx = await browser.newContext({
    colorScheme: 'light',
    viewport: mobile ? { width: 375, height: 812 } : { width: 1280, height: 800 },
    deviceScaleFactor: 2,
    hasTouch: mobile,
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
  await page.goto(`http://localhost:8121/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  if (!mobile && mountDesktop) {
    await page.evaluate(async (sid) => {
      const f = await import('/js/todoFace.js');
      f.renderTodoTaskList(sid);
    }, ROOT_SID);
  }
  await page.waitForTimeout(400);
  return { page, errors, ctx };
}

const read = (page) => page.evaluate(probeFn);

async function desktopLeg(browser) {
  console.log('[desktop 1280x800]');
  // 基线 = 产品自举态（main.js 自举已自行挂载待办面 ⇒ 胶囊已在位；
  // 该读数只用于 ① 祖先链与后续对照）。确定性数据随后由 __seed 注入。
  const { page, errors, ctx } = await makePage(browser, { mobile: false, mountDesktop: false });
  try {
    await page.waitForTimeout(400);
    const empty = await read(page);
    console.log('  boot-baseline', JSON.stringify({
      taskListParentId: empty.taskListParentId, ancestorIds: empty.taskListAncestorIds,
      overlays: empty.overlays, bandBottom: r1(empty.bandBottom),
      chatPaddingTop: empty.chatPaddingTop, capsule: empty.capsule, hasTasks: empty.taskListHasTasksClass,
    }));

    // ① DOM 祖先链（挂载前已可判：槽位就在 #top-overlays 里）
    ok('① #task-list 直接挂在 #top-overlays', empty.taskListParentId === 'top-overlays', String(empty.taskListParentId));

    // ② 胶囊出现（挂载待办面 + 8 条待办 1 条逾期）⇒ 让位跟随
    await page.evaluate(seedScript(8, true));
    await page.evaluate(async (sid) => {
      const f = await import('/js/todoFace.js');
      f.renderTodoTaskList(sid);
    }, ROOT_SID);
    await page.waitForSelector('#task-list .tfl-capsule', { timeout: 8000 });
    await page.waitForTimeout(700);
    const withCap = await read(page);
    console.log('  capsule', JSON.stringify({
      taskListInTopOverlays: withCap.taskListInTopOverlays,
      overlays: withCap.overlays, header: withCap.header,
      bandBottom: r1(withCap.bandBottom), chatTop: r1(withCap.chatTop),
      chatPaddingTop: withCap.chatPaddingTop, chatPaddingTopInline: withCap.chatPaddingTopInline,
      capsule: withCap.capsule, capsuleVisible: withCap.capsuleVisible, capsuleText: withCap.capsuleText,
      storeOpen: withCap.storeOpen, dataSource: withCap.storeDataSource, hasTasks: withCap.taskListHasTasksClass,
      formulaPad: Math.max(8, Math.ceil(withCap.bandBottom - withCap.chatTop) + 8),
    }));
    await page.screenshot({ path: join(OUT, 'd-1280-capsule-giveway.png') });
    ok('② 胶囊出现后 #task-list 仍在 #top-overlays 内', withCap.taskListInTopOverlays === true);
    ok('② 胶囊在桌面可见', withCap.capsuleVisible === true);
    // 让位有效性判据（硬）：#chat 内容起点（= chatTop + padding-top）必须落在胶囊下缘之下
    ok('② #chat 内容起点落在胶囊下缘之下（不让位列叠）',
      withCap.chatPaddingTop >= (withCap.capsule.bottom - withCap.chatTop) + 8 - 1,
      `contentTop=${r1(withCap.chatTop + withCap.chatPaddingTop)} capsuleBottom=${r1(withCap.capsule.bottom)}`);
    ok('② 胶囊整体落在顶带 band 内（不越出 #top-overlays）',
      withCap.capsule.bottom <= withCap.overlays.bottom + 0.5,
      `capBottom=${r1(withCap.capsule.bottom)} bandBottom=${r1(withCap.overlays.bottom)}`);
    ok('② 让位值 = max(8, ceil(bandBottom − chatTop) + 8)', Math.abs(
      withCap.chatPaddingTop - Math.max(8, Math.ceil(withCap.bandBottom - withCap.chatTop) + 8)) <= 1,
      `pad=${withCap.chatPaddingTop} formula=${Math.max(8, Math.ceil(withCap.bandBottom - withCap.chatTop) + 8)}`);

    // ③ 观察器链实测：往 #top-overlays 里注入一条 ask-pending 行（该带真实存在的产品
    //    元素，见 index.html #ask-pending-bar）⇒ bandBottom 变高 ⇒ #chat padding 必须跟随。
    //    本步只注入刺激、只读数（产品代码零改动）。
    await page.evaluate(() => {
      const bar = document.getElementById('ask-pending-bar');
      bar.classList.remove('hidden');
      bar.textContent = '';
      const row = document.createElement('div');
      row.className = 'ask-pending-row';
      row.textContent = '让位探针：一条未回答的提问';
      bar.appendChild(row);
    });
    await page.waitForTimeout(600);
    const grown = await read(page);
    console.log('  band-grown', JSON.stringify({
      overlays: grown.overlays, bandBottom: r1(grown.bandBottom),
      chatPaddingTop: grown.chatPaddingTop,
      formulaPad: Math.max(8, Math.ceil(grown.bandBottom - grown.chatTop) + 8),
    }));
    await page.screenshot({ path: join(OUT, 'd-1280-band-grown-giveway.png') });
    const dOverlay = r1(grown.bandBottom - withCap.bandBottom);
    const dPad = r1(grown.chatPaddingTop - withCap.chatPaddingTop);
    ok('③ 顶带变高（注入 ask-pending 行后 bandBottom 增大）', dOverlay > 1, `dOverlay=${dOverlay}`);
    ok('③ #chat padding-top 跟随顶带增大（Δ≈Δ，±2px）', dPad > 1 && Math.abs(dPad - dOverlay) <= 2,
      `dPad=${dPad} dBand=${dOverlay}`);
    ok('③ 变高后仍满足让位公式', Math.abs(
      grown.chatPaddingTop - Math.max(8, Math.ceil(grown.bandBottom - grown.chatTop) + 8)) <= 1,
      `pad=${grown.chatPaddingTop} formula=${Math.max(8, Math.ceil(grown.bandBottom - grown.chatTop) + 8)}`);

    // ④ 复位刺激 ⇒ 让位回落
    await page.evaluate(() => {
      const bar = document.getElementById('ask-pending-bar');
      bar.textContent = '';
      bar.classList.add('hidden');
    });
    await page.waitForTimeout(600);
    const closed = await read(page);
    console.log('  band-restored', JSON.stringify({ bandBottom: r1(closed.bandBottom), chatPaddingTop: closed.chatPaddingTop }));
    ok('④ 复位后让位回落至胶囊态（±2px）', Math.abs(closed.chatPaddingTop - withCap.chatPaddingTop) <= 2,
      `${closed.chatPaddingTop} vs ${withCap.chatPaddingTop}`);
    // ⑤ 展开面板：面板是浮层（不撑顶带、不挤压消息区），只核不叠在胶囊位上
    await page.click('#task-list .tfl-capsule');
    await page.waitForTimeout(900);
    const open = await read(page);
    console.log('  panel-open', JSON.stringify({
      overlays: open.overlays, bandBottom: r1(open.bandBottom),
      chatPaddingTop: open.chatPaddingTop, cardRect: open.cardRect,
    }));
    await page.screenshot({ path: join(OUT, 'd-1280-capsule-panel-giveway.png') });
    ok('⑤ 展开面板后让位不变（面板是浮层，顶带高度不动）',
      Math.abs(open.chatPaddingTop - withCap.chatPaddingTop) <= 1,
      `${open.chatPaddingTop} vs ${withCap.chatPaddingTop}`);
    ok('⑤ 面板顶缘不高于顶带下缘（浮层紧贴带底）',
      !!open.cardRect && open.cardRect.top >= open.overlays.bottom - 1,
      `cardTop=${open.cardRect && r1(open.cardRect.top)} bandBottom=${r1(open.overlays.bottom)}`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    ok('⑥ 无 pageerror / console error', errors.length === 0, errors.slice(0, 3).join(' | '));
  } finally { await ctx.close(); }
}

async function mobileLeg(browser) {
  console.log('[mobile 375x812]');
  const { page, errors, ctx } = await makePage(browser, { mobile: true });
  try {
    await page.evaluate(seedScript(8, true));
    await page.waitForSelector('#task-list .tfl-capsule', { timeout: 8000 });
    await page.waitForTimeout(700);
    const m = await read(page);
    console.log('  mobile ', JSON.stringify({
      mobileLayer: m.mobileLayer, taskListDisplay: m.taskListDisplay,
      taskListParentId: m.taskListParentId, capsule: m.capsule, capsuleVisible: m.capsuleVisible,
      bandBottom: r1(m.bandBottom), chatPaddingTop: m.chatPaddingTop,
    }));
    await page.screenshot({ path: join(OUT, 'm-375-capsule-visible.png') });
    ok('④ 手机层 body.mobile-layer 生效', m.mobileLayer === true);
    ok('④ 手机层 #task-list 不是 display:none', m.taskListDisplay !== 'none', String(m.taskListDisplay));
    ok('④ 手机层胶囊可见', m.capsuleVisible === true);
    ok('④ 手机层胶囊可见计数 = 1', await page.locator('#task-list .tfl-capsule:visible').count() === 1);
    ok('④ 手机层零 pageerror', errors.length === 0, errors.slice(0, 3).join(' | '));
  } finally { await ctx.close(); }
}

const browser = await chromium.launch();
try {
  await desktopLeg(browser);
  await mobileLeg(browser);
} finally { await browser.close(); }
console.log(`\nPROBE: ${pass} passed, ${fail} failed`);
if (fail) { console.log('FAILS:\n  - ' + fails.join('\n  - ')); process.exitCode = 1; }
