#!/usr/bin/env node
// tests/mobile-todo-apple-shots.mjs — 手机端待办页 Apple 提醒事项信息架构
// （实现批 · 作者令 2026-11-02 11:57）自足冒烟 + 双主题截图。
//
// 独立 node 脚本（自起 chromium、自打 PASS/FAIL；不 import @playwright/test 的
// runner 面 ⇒ playwright.config.mjs 的 testIgnore 自动排除，不进 CI 收集面）。
//
// 覆盖面（作者令「自足冒烟」四项）：
//   ① 页面能 push：三页都可 pushPage 且 .live（返回 pop 回上一层）
//   ② 四卡有数：卡读数 == todoStore 计数投影（今天/逾期/全部/已完成）
//   ③ 列表行可点：我的列表行 → 列表详情页（标题 = tag 名、行数 = 未完成数）
//   ④ 详情页空态与有内容态都渲染（__seed 全部完成 → 居中「全部完成」）
//   ⑤ 新建态保存能落库（createItem 后 allItems() 增长）
//   ⑥ 工具条出现且恰好 3 个图标（日期/旗标/标签）
//   ⑦ 勾选 = todoStore.completeItem（计数变化、行退场）
//
// 用法：node tests/mobile-todo-apple-shots.mjs [--keep]

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.MTA_OUT_DIR || join(HERE, '..', 'evidence', `${TS}_mobile-todo-apple`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mobile-todo-apple-root';
const ORIGIN = 'http://localhost:8898'; // 8899 = panel-title spec，错开端口
const VW = 375;
const VH = 812;

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures += 1;
};

const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`); });
};

async function boot(scheme) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => { /* 注册面静默空转 */ }),
      configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'session', tasks: [] }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(500);
  return { browser, page };
}

const push = async (page, id) => {
  await page.evaluate(async (pid) => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage(pid);
  }, id);
  await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 15000 });
  await page.waitForTimeout(260);
};
const popAll = async (page) => {
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    try { nav.popToRoot(); } catch { /* 已在根 */ }
  });
  await page.waitForTimeout(120);
};
const store = (page, fn, arg) => page.evaluate(async ({ src, a }) => {
  const m = await import('/js/todoStore.js');
  // eslint-disable-next-line no-new-func
  return new Function('todoStore', 'a', src)(m.todoStore, a);
}, { src: fn, a: arg });

// ── 主腿（light）─────────────────────────────────────────────────────────
async function mainLeg() {
  const { browser, page } = await boot('light');
  // 确定性种子（夹具 = 两家标签 + 三条未完成 + 一条已完成；今天/逾期/全部可分别断言）
  await store(page, `
    const DAY = 86400000;
    const d = new Date(); d.setHours(23, 0, 0, 0);   // 今天 23:00（尚未过期 ⇒ 今天卡与逾期卡不重叠）
    const today = d.getTime();
    todoStore.__seed({
      tagList: [
        { id: 'tw', name: '工作', color: 'rgb(0, 122, 255)' },
        { id: 'tl', name: '生活', color: 'rgb(77, 217, 102)' },
      ],
      itemList: [
        { id: 'i1', tagId: 'tw', title: '写周报', due: today, priority: 3, completed: false },
        { id: 'i2', tagId: 'tw', title: '过期的事', due: today - 3 * DAY, priority: 0, completed: false },
        { id: 'i3', tagId: 'tl', title: '买菜', due: null, priority: 0, completed: false },
        { id: 'i4', tagId: 'tl', title: '已完成的旧事', due: null, priority: 1, completed: true },
      ],
    });
  `);

  // ① 首页 push
  await push(page, 'tasks');
  ok('① tasks 页面 live（pushPage 生效）', await page.locator('.mobile-page[data-page="tasks"].live').count() === 1);
  ok('① 页壳返回钮在（48 g2-fab）', await page.locator('.mobile-page[data-page="tasks"] .mobile-back-btn').count() === 1);
  // 本页两段式大标题静态隐藏（作用域精确限定）
  ok('① 流内大标题本页静态隐藏（display:none）',
    await page.locator('.mobile-page[data-page="tasks"] .mobile-page-title').evaluate((el) => getComputedStyle(el).display) === 'none');
  // Re-pointed for main's single-node page title: the separate sticky-capsule
  // node (-mini) was retired, so the sticky state is covered by hiding the one
  // node — assert it no longer exists instead of its opacity.
  ok('① 吸附态无独立标题节点（单节点标题：隐藏即两态皆隐）',
    await page.locator('.mobile-page[data-page="tasks"] .mobile-page-title-mini').count() === 0);

  // ② 四卡有数
  const cards = await page.evaluate(() => {
    const out = {};
    for (const k of ['today', 'overdue', 'all', 'completed']) {
      const c = document.querySelector(`.mobile-page[data-page="tasks"] .ta-card-${k}`);
      out[k] = {
        n: c?.querySelector('.ta-card-n')?.textContent,
        l: c?.querySelector('.ta-card-l')?.textContent,
        bg: c ? getComputedStyle(c).backgroundColor : '',
      };
    }
    return out;
  });
  ok('② 四卡读数（今天 1 / 逾期 1 / 全部 3 / 已完成 1）',
    cards.today.n === '1' && cards.overdue.n === '1' && cards.all.n === '3' && cards.completed.n === '1',
    JSON.stringify(cards));
  ok('② 四卡标签为中文词条', cards.today.l === '今天' && cards.overdue.l === '逾期' && cards.all.l === '全部' && cards.completed.l === '已完成');
  ok('② 四卡底色四族互不相同', new Set([cards.today.bg, cards.overdue.bg, cards.all.bg, cards.completed.bg]).size === 4,
    [cards.today.bg, cards.overdue.bg, cards.all.bg, cards.completed.bg].join(' | '));

  // 「我的列表」= todoStore.tags + 末行「全部待办」
  const rows = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="tasks"] .ta-rows .ta-row')]
    .map((r) => ({
      label: r.querySelector('.ta-row-label')?.textContent,
      count: r.querySelector('.ta-row-count')?.textContent,
      cls: r.className,
    })));
  ok('② 列表卡行 = 2 标签 + 末行「全部待办」（列表从 todoStore.tags 来）',
    rows.length === 3 && rows[0].label === '工作' && rows[0].count === '2'
    && rows[1].label === '生活' && rows[1].count === '1' && rows[2].label === '全部待办' && rows[2].count === '3',
    JSON.stringify(rows));

  // 顶部胶囊三图标（右上；左端留空）
  const pill = await page.evaluate(() => {
    const p = document.querySelector('.mobile-page[data-page="tasks"] .ta-pill');
    if (!p) return null;
    const r = p.getBoundingClientRect();
    return { btns: p.querySelectorAll('.ta-pill-btn').length, right: innerWidth - r.right, left: r.left, h: r.height };
  });
  ok('② 右上白色胶囊三图标（左端留空不压返回钮）', !!pill && pill.btns === 3 && pill.left > 64 && pill.right < 20, JSON.stringify(pill));

  // FAB
  const fab = await page.evaluate(() => {
    const f = document.querySelector('.mobile-page[data-page="tasks"] .ta-fab');
    if (!f) return null;
    const r = f.getBoundingClientRect();
    return { w: r.width, h: r.height, right: innerWidth - r.right, bg: getComputedStyle(f).backgroundColor };
  });
  ok('② 右下蓝色 FAB（48×48，--mtap-lg）', !!fab && Math.abs(fab.w - 48) < 1 && Math.abs(fab.h - 48) < 1, JSON.stringify(fab));

  await page.screenshot({ path: join(SHOTS, 'light-01-tasks.png') });

  // ③ 列表行可点 → 列表详情页（标题 = tag 名，行数 = 未完成数）
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(200);
  const detail = await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="todo-apple-list"].live');
    const t = sec.querySelector('.ta-dtitle');
    return {
      title: t?.textContent,
      titleColor: t ? getComputedStyle(t).color : '',
      titleAlign: t ? getComputedStyle(t).textAlign : '',
      items: [...sec.querySelectorAll('.ta-item')].map((r) => ({
        title: r.querySelector('.ta-item-title')?.textContent,
        done: r.classList.contains('is-done'),
        prio: !!r.querySelector('.ta-prio'),
        checkW: r.querySelector('.ta-check')?.getBoundingClientRect().width,
        circleW: r.querySelector('.ta-check-circle')?.getBoundingClientRect().width,
      })),
      emptyHidden: sec.querySelector('.ta-empty')?.hidden,
    };
  });
  ok('③ 列表详情页标题 = 标签名且左对齐', detail.title === '工作' && detail.titleAlign === 'left', JSON.stringify({ t: detail.title, a: detail.titleAlign }));
  ok('③ 列表详情标题颜色 = tag.color（数据色）', /rgb\(0,\s*122,\s*255\)/.test(detail.titleColor), detail.titleColor);
  ok('③ 列表行渲染（2 条未完成、priority>0 才有优先标记）',
    detail.items.length === 2 && detail.items[0].prio && !detail.items[1].prio, JSON.stringify(detail.items));
  ok('③ 勾选命中 48 / 视觉 24（≥44 / ≥24 契约）',
    Math.abs(detail.items[0].checkW - 48) < 1 && Math.abs(detail.items[0].circleW - 24) < 1,
    `hit=${detail.items[0].checkW} vis=${detail.items[0].circleW}`);
  ok('③ 有内容态：空态隐藏', detail.emptyHidden === true);
  await page.screenshot({ path: join(SHOTS, 'light-02-todo-apple-list.png') });

  // ⑦ 勾选 = completeItem → 行退场 + 计数上移
  await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item .ta-check').first().click();
  await page.waitForTimeout(260);
  const afterComplete = await store(page, 'return { open: todoStore.openCount(), done: todoStore.completedCount() };');
  ok('⑦ 勾选行 = todoStore.completeItem（未完成 3→2、已完成 1→2）',
    afterComplete.open === 2 && afterComplete.done === 2, JSON.stringify(afterComplete));
  ok('⑦ 完成后行从列表退场',
    await page.locator('.mobile-page[data-page="todo-apple-list"] .ta-item').count() === 1);

  // 返回（pop 一层回首页）
  await page.locator('.mobile-page[data-page="todo-apple-list"] .mobile-back-btn').click();
  await page.waitForTimeout(300);
  ok('① 返回 pop 回首页（栈语义复用）',
    await page.locator('.mobile-page[data-page="tasks"].live').count() === 1);

  // ④ 空态：把剩余未完成全部完成 → 打开标签列表 = 居中「全部完成」
  await store(page, `for (const it of todoStore.allItems()) { if (!it.completed) todoStore.completeItem(it.id); }`);
  await page.waitForTimeout(220);
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(200);
  const empty = await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="todo-apple-list"].live');
    const e = sec.querySelector('.ta-empty');
    const r = e.getBoundingClientRect();
    return {
      hidden: e.hidden, text: e.textContent,
      cx: Math.round(r.left + r.width / 2), cy: Math.round(r.top + r.height / 2),
      itemCount: sec.querySelectorAll('.ta-item').length,
      display: getComputedStyle(e).display,
    };
  });
  ok('④ 空态渲染（文案「全部完成」、无行）', empty.hidden === false && empty.text === '全部完成' && empty.itemCount === 0, JSON.stringify(empty));
  ok('④ 空态垂直+水平居中（页体内居中）', Math.abs(empty.cx - VW / 2) < 24 && empty.cy > 200, JSON.stringify(empty));
  await page.screenshot({ path: join(SHOTS, 'light-03-todo-apple-empty.png') });
  await page.locator('.mobile-page[data-page="todo-apple-list"] .mobile-back-btn').click();
  await page.waitForTimeout(260);

  // ⑤⑥ 新建态：FAB → item 页；工具条 3 键；输入 + 保存落库
  await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
  await page.waitForTimeout(300);
  const itemPage = await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="todo-apple-item"].live');
    const tool = sec.querySelector('.ta-tool');
    const tr = tool ? tool.getBoundingClientRect() : null;
    const save = sec.querySelector('.ta-save');
    const sr = save ? save.getBoundingClientRect() : null;
    return {
      placeholder: sec.querySelector('.ta-title-input')?.placeholder,
      infoBtn: !!sec.querySelector('.ta-info'),
      toolBtns: tool ? tool.querySelectorAll('.ta-tool-btn').length : 0,
      toolIcons: tool ? [...tool.querySelectorAll('.ta-tool-btn [data-lucide]')].map((i) => i.getAttribute('data-lucide')) : [],
      toolBottom: tr ? Math.round(innerHeight - tr.bottom) : null,
      saveW: sr ? Math.round(sr.width) : null,
      saveBg: save ? getComputedStyle(save).backgroundColor : '',
      pillVisible: sec.querySelector('.ta-pill')?.hidden === false,
      pillBtns: [...(sec.querySelectorAll('.ta-pill .ta-pill-btn') || [])]
        .filter((b) => getComputedStyle(b).display !== 'none')
        .map((b) => b.getAttribute('aria-label')),
      focused: document.activeElement?.className || '',
    };
  });
  ok('⑤ 新建行占位 = 中文「新待办」+ (i) 信息钮', itemPage.placeholder === '新待办' && itemPage.infoBtn, JSON.stringify(itemPage));
  ok('⑥ 工具条恰好 3 个图标（日期/旗标/标签）',
    itemPage.toolBtns === 3 && JSON.stringify(itemPage.toolIcons) === JSON.stringify(['calendar', 'flag', 'hash']),
    JSON.stringify(itemPage.toolIcons));
  ok('⑥ 工具条贴底（无键盘时 = dock 上缘静止位）', itemPage.toolBottom != null && itemPage.toolBottom < 120, `bottom=${itemPage.toolBottom}`);
  ok('⑤ 保存 = 蓝色圆形对勾（48 / sapphire 实底）', itemPage.saveW === 48 && /91,\s*127,\s*191/.test(itemPage.saveBg.replace(/\s/g, ' ')), `${itemPage.saveW} ${itemPage.saveBg}`);
  ok('⑤ 新建态光标就位（输入框自动聚焦）', /ta-title-input/.test(itemPage.focused), itemPage.focused);
  ok('⑤ 新建态胶囊只放有能力的键（旗标；完成键无对象 ⇒ 不出）',
    itemPage.pillVisible === true && itemPage.pillBtns.length === 1 && itemPage.pillBtns[0] === '旗标',
    JSON.stringify(itemPage.pillBtns));
  await page.screenshot({ path: join(SHOTS, 'light-04-todo-apple-new.png') });

  // 工具条交互：日期循环 + 旗标循环（有实体写口）
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-tool-btn').nth(0).click();
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-tool-btn').nth(1).click();
  await page.waitForTimeout(160);
  const chips = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="todo-apple-item"] .ta-chip')]
    .map((c) => c.textContent));
  ok('⑥ 工具条写口生效（日期/旗标/标签三条 chips 出现）', chips.length === 3, JSON.stringify(chips));

  // 输入标题 + 保存 → createItem 落库 + pop
  const before = await store(page, 'return todoStore.allItems().length;');
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-title-input').fill('冒烟新建的待办');
  await page.locator('.mobile-page[data-page="todo-apple-item"] .ta-save').click();
  await page.waitForTimeout(320);
  const after = await store(page, `const all = todoStore.allItems(); return { n: all.length, last: all[all.length - 1] && all[all.length - 1].title };`);
  ok('⑤ 新建保存落库（allItems 增长 + 标题正确）', after.n === before + 1 && after.last === '冒烟新建的待办', JSON.stringify({ before, after }));
  ok('⑤ 保存后 pop 回上一层（首页 live）',
    await page.locator('.mobile-page[data-page="tasks"].live').count() === 1);

  // 更多菜单（两项真实入口）
  await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(2).click();
  await page.waitForTimeout(160);
  const menu = await page.evaluate(() => {
    const m = document.querySelector('.mobile-page[data-page="tasks"] .ta-menu');
    return { hidden: m?.hidden, rows: [...(m?.querySelectorAll('.ta-menu-row') || [])].map((r) => r.querySelector('.ta-menu-l')?.textContent) };
  });
  ok('② 更多菜单 = 两项真实入口（已完成 / 全部待办）', menu.hidden === false && menu.rows.length === 2, JSON.stringify(menu));
  await page.screenshot({ path: join(SHOTS, 'light-05-tasks-menu.png') });
  await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(2).click();

  // 搜索（本地过滤 = 真实能力）
  await page.locator('.mobile-page[data-page="tasks"] .ta-pill-btn').nth(0).click();
  await page.waitForTimeout(160);
  const searchRow = await page.evaluate(() => {
    const c = document.querySelector('.mobile-page[data-page="tasks"] .ta-search-input');
    if (!c) return null;
    c.value = '冒烟';
    c.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  });
  await page.waitForTimeout(200);
  const hits = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="tasks"] .ta-row-hit')]
    .map((r) => r.querySelector('.ta-row-label')?.textContent));
  ok('② 搜索本地过滤（真实能力，非假按钮）', searchRow === true && hits.length === 1 && hits[0] === '冒烟新建的待办', JSON.stringify(hits));

  // 双主题截图（暗色不得糊）
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    try { nav.popToRoot(); } catch { /* 根 */ }
  });
  await page.waitForTimeout(150);
  await page.emulateMedia({ colorScheme: 'dark' });
  await push(page, 'tasks');
  const darkCards = await page.evaluate(() => [...document.querySelectorAll('.mobile-page[data-page="tasks"] .ta-card')]
    .map((c) => getComputedStyle(c).backgroundColor));
  ok('⑦ 暗主题四卡仍为四族色（不糊）', new Set(darkCards).size === 4, darkCards.join(' | '));
  await page.screenshot({ path: join(SHOTS, 'dark-01-tasks.png') });
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await page.waitForTimeout(220);
  await page.screenshot({ path: join(SHOTS, 'dark-02-todo-apple-list.png') });
  await page.locator('.mobile-page[data-page="todo-apple-list"] .mobile-back-btn').click();
  await page.waitForTimeout(200);
  await page.locator('.mobile-page[data-page="tasks"] .ta-fab').click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
  await page.waitForTimeout(220);
  await page.screenshot({ path: join(SHOTS, 'dark-03-todo-apple-new.png') });

  // 键盘避让几何（--ta-kb 写口存在；真键盘在 headless 不可模拟，断言 token 通道 + 静止位）
  const kb = await page.evaluate(() => ({
    track: !!window.visualViewport,
    prop: getComputedStyle(document.documentElement).getPropertyValue('--ta-kb').trim(),
    cssBottom: getComputedStyle(document.querySelector('.mobile-page[data-page="todo-apple-item"] .ta-tool')).bottom,
  }));
  ok('⑥ 键盘避让通道在场（--ta-kb 由 visualViewport 写入，CSS bottom 引用）',
    kb.prop !== '' && /--ta-kb|px/.test(kb.cssBottom), JSON.stringify(kb));

  ok('⑧ 零 console/page 错误', consoleErrors.length === 0, consoleErrors.slice(0, 4).join(' || '));
  await browser.close();
}

await mainLeg();
console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}  —  shots: ${SHOTS}`);
process.exit(failures === 0 ? 0 : 1);
