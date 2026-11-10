#!/usr/bin/env node
// todo-label-popover.spec.mjs — 「添加标签的窗口会一直卡住」（作者报障 2026-11-07）
// 回归门禁：开合通道 / 保存 / 取消 / 点外部 / Esc / 草稿保留 / 校验反馈 / 重复打开，
// 以及真源落库腿（WS 帧桩：新建标签 → 落一条带 project 的待办 → 刷新后状态）。
//
// 运行：node tests/todo-label-popover.spec.mjs
//   TODOFACE_WEB_ROOT=<改前 web 树>  跑「验红」腿（默认本仓真树）
//   EVID_DIR=<dir>                  落读数 json + 截图（默认 reports-evidence/todo-label-popover__20261107）
//
// 零后端法（与 tests/_todo-face-harness.mjs 同源）：静态面 = page.route 从磁盘注入
// web 树（零本地端口、零 sbt、不碰宿主 :6138）；数据面 = routeWebSocket 帧桩。
// 真源落库腿的桩会**真的**收帧、建行、回下行 —— 刷新后的状态由「后端」重放，
// 不是本地内存假象；全程只碰桩内数据，真实用户待办零读者零写者。
//
// 入口全是真链：桌面 = 胶囊 → 面板 → 筛选栏 → chips 行「+」；手机 = chatChrome
// tasks → 手机页面 → 顶部「⋯」→「新建标签」。没有一处直接调内部方法。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = process.env.TODOFACE_WEB_ROOT
  ? normalize(process.env.TODOFACE_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = process.env.EVID_DIR || join(ROOT, 'reports-evidence', 'todo-label-popover__20261107');
const TAG = process.env.EVID_TAG || 'after';
mkdirSync(EVID, { recursive: true });

const SID = 'todo-label-popover-spec';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const TAGS = [
  { id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' },
  { id: 'tag-life', name: '生活', color: 'rgb(16,185,129)' },
];
const ITEMS = () => {
  const now = Date.now();
  return [
    { id: 'f-1', tagId: 'tag-work', title: '修登录态过期', notes: '', due: now + 3600_000, priority: 2, createdAt: now - 9 * 3600_000 },
    { id: 'f-2', tagId: 'tag-life', title: '缴房租', notes: '', due: now + 2 * 3600_000, priority: 1, createdAt: now - 8 * 3600_000 },
  ];
};

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}
const readings = {};
const save = (key, payload) => { readings[key] = payload; };
const near = (a, b, tol = 1) => a != null && b != null && Math.abs(a - b) <= tol;

/* ───────────── 启动面（静态树 + WS 桩）───────────── */
function wire(page, { backend = null, fixture = true, mobile = false } = {}) {
  page.__frames = [];
  page.__backend = backend;
  return (async () => {
    await page.addInitScript((m) => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      if (m) localStorage.setItem('nebflow_force_mobile', '1');
      if (navigator.serviceWorker) {
        Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
      }
    }, mobile);
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
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
      } catch { return route.fulfill({ status: 404, body: 'not found' }); }
    });
    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({
        type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
      }));
      const sendSessions = () => ws.send(JSON.stringify({
        type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
      }));
      sendConfig(); sendSessions();
      ws.onMessage((raw) => {
        let m;
        try { m = JSON.parse(raw); } catch { return; }
        page.__frames.push(m);
        if (m.type === 'ping') return ws.send(JSON.stringify({ type: 'pong' }));
        if (m.type === 'getConfig') return sendConfig();
        if (m.type === 'getSessions' || m.type === 'listSessions') return sendSessions();
        if (m.type === 'getHistory') {
          return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
        }
        if (m.type === 'listScheduledTasks') {
          const tasks = page.__backend ? page.__backend.tasks : [];
          return ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'all', tasks }));
        }
        if (page.__backend && m.type === 'createScheduledTask') {
          const row = {
            id: `bt-${page.__backend.tasks.length + 1}`, kind: m.kind || 'todo', name: m.name || m.content || '',
            detail: m.detail || '', content: m.content || '', project: m.project || '', priority: m.priority || 0,
            completed: false, completedAt: null, createdAt: Date.now(), sessionId: m.sessionId, triggerAt: null,
          };
          page.__backend.tasks.push(row);
          ws.send(JSON.stringify({ type: 'scheduledTaskCreated', task: row }));
        }
        if (page.__backend && m.type === 'updateScheduledTask') {
          const row = page.__backend.tasks.find((x) => x.id === m.id);
          if (row) {
            if (typeof m.project === 'string') row.project = m.project;
            if (typeof m.name === 'string') row.name = m.name;
            ws.send(JSON.stringify({ type: 'scheduledTaskUpdated', task: row }));
          }
        }
        return null;
      });
    });
  })();
}

const SNAP = `(() => {
  const pops = [...document.querySelectorAll('.tfl-tag-popover')];
  const pop = pops[pops.length - 1] || null;
  const r = pop ? pop.getBoundingClientRect() : null;
  const card = document.querySelector('.tfl-card');
  const ae = document.activeElement;
  const btns = pop ? [...pop.querySelectorAll('.tfl-tag-btn')].map((b) => b.textContent.trim()) : [];
  return {
    popCount: pops.length,
    popOpen: !!pop && pop.classList.contains('open'),
    rect: r ? { x: +r.left.toFixed(1), y: +r.top.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) } : null,
    inViewport: r ? (r.left >= 0 && r.top >= 0 && r.right <= innerWidth + 0.5 && r.bottom <= innerHeight + 0.5) : null,
    btns,
    nameVal: pop ? (pop.querySelector('.tfl-tag-name') || {}).value : null,
    invalid: !!(pop && pop.querySelector('.tfl-tag-name.is-invalid')),
    errText: pop ? ((pop.querySelector('.tfl-tag-err') || {}).textContent || '') : '',
    cardState: card ? card.dataset.state : null,
    activeEl: ae ? ae.tagName + '.' + (ae.className || '') : null,
    createRow: !!document.querySelector('.tfl-create-row, .tfl-row-title-input'),
    vw: innerWidth, vh: innerHeight,
  };
})()`;
const snap = (page) => page.evaluate(SNAP);
const tagsOf = (page) => page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.tags
  .map((t) => ({ id: t.id, name: t.name, color: t.color })));

/* ───────────── 桌面腿 ───────────── */
async function mountDesktop(page) {
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => !!document.querySelector('#task-list'), null, { timeout: 20000 });
  await page.evaluate(async ({ sid, tags, items }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: items });
    (await import('/js/todoFace.js')).renderTodoTaskList(sid);
  }, { sid: SID, tags: TAGS, items: ITEMS() });
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { state: 'visible', timeout: 15000 });
  await page.waitForTimeout(250);
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 10000 });
  await page.waitForTimeout(900);
  await page.locator('.tfl-filter-toggle').click();
  await page.waitForSelector('#task-list .tfl-chip-tag', { state: 'visible', timeout: 10000 });
  await page.waitForTimeout(250);
}
const openTag = async (page) => { await page.locator('#task-list .tfl-chip-add').click(); await page.waitForTimeout(260); };
/** 保证标签窗当前是开着的（改前树某些步会静默关窗，判据要继续量下去）。 */
async function ensureTagOpen(page) {
  if ((await page.locator('.tfl-tag-popover').count()) === 0) await openTag(page);
}
/** 动作按钮点击（改前树没有这两枚按钮 ⇒ 退回等价的隐式通道：保存=回车、取消=Esc）。
 *  这样同一份判据能在「改前/改后」两棵树上都跑出读数，偏差即验红实证。 */
async function tapBtn(page, label) {
  const loc = page.locator('.tfl-tag-popover .tfl-tag-btn', { hasText: label });
  if (await loc.count()) {
    await loc.first().click();
    await page.waitForTimeout(200);
    return 'button';
  }
  if (label === '保存') await page.locator('.tfl-tag-popover .tfl-tag-name').press('Enter');
  else await page.keyboard.press('Escape');
  await page.waitForTimeout(250);
  return 'fallback';
}
/** 面板收起后重开（外部点击会让面板收起 —— 这是面板既有语义）。 */
async function reopenPanel(page) {
  const st = await page.locator('#task-list .tfl-card').getAttribute('data-state');
  if (st !== 'open') {
    await page.locator('.tfl-capsule').click({ force: true });
    await page.waitForTimeout(1000);
  }
  if (!(await page.locator('#task-list .tfl-chip-add').isVisible().catch(() => false))) {
    await page.locator('#task-list .tfl-filter-toggle').click({ force: true });
    await page.waitForTimeout(350);
  }
}

async function desktopLeg(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e.message)));
  await wire(page);
  await mountDesktop(page);

  // D1 打开：在场 + 聚焦 + 两枚显式动作按钮
  await openTag(page);
  let s = await snap(page);
  save('D1', s);
  ok('D1 标签窗口可打开（可见 / 输入框聚焦）', s.popCount === 1 && s.popOpen && /INPUT/.test(s.activeEl || ''), JSON.stringify({ popOpen: s.popOpen, activeEl: s.activeEl }));
  ok('D1 窗口内有「取消」「保存」两枚显式按钮（改前 = 0 枚）',
    s.btns.length === 2 && s.btns.includes('保存') && s.btns.includes('取消'), JSON.stringify(s.btns));
  await page.screenshot({ path: join(EVID, `d1-open.${TAG}.png`) });

  // D2 点面板内部其它位置 ⇒ 关（报障核心：改前不关）
  await page.locator('#task-list .tfl-header').first().click({ force: true });
  await page.waitForTimeout(300);
  s = await snap(page);
  save('D2', s);
  ok('D2 点面板内其它位置即关窗（改前：窗口原地不动）', s.popCount === 0, JSON.stringify({ popCount: s.popCount }));
  ok('D2 关窗不连带收起面板', s.cardState === 'open', String(s.cardState));

  // D3 Esc 关窗、面板不动
  await openTag(page);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(320);
  s = await snap(page);
  save('D3', s);
  ok('D3 Esc 关窗', s.popCount === 0, JSON.stringify({ popCount: s.popCount }));
  ok('D3 Esc 关窗不连带收起面板', s.cardState === 'open', String(s.cardState));

  // D4 取消 + 草稿保留
  await openTag(page);
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('半途标签');
  await tapBtn(page, '取消');
  await page.waitForTimeout(320);
  s = await snap(page);
  save('D4a', s);
  ok('D4 「取消」按钮关窗', s.popCount === 0, JSON.stringify({ popCount: s.popCount }));
  await openTag(page);
  s = await snap(page);
  save('D4b', s);
  ok('D4 取消后重开：用户输入仍在（改前：静默丢弃）', s.nameVal === '半途标签', JSON.stringify({ nameVal: s.nameVal }));

  // D5 空名保存 ⇒ 不关 + 反馈
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('');
  await tapBtn(page, '保存');
  await page.waitForTimeout(300);
  s = await snap(page);
  save('D5', s);
  ok('D5 空名保存：窗口不关 + 有校验反馈（改前：静默关闭）',
    s.popCount === 1 && s.invalid && s.errText.length > 0, JSON.stringify({ popCount: s.popCount, err: s.errText }));

  // D6 重名保存 ⇒ 不关 + 反馈 + 不产生重复标签
  await ensureTagOpen(page);
  const beforeTags = await tagsOf(page);
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('工作');
  await tapBtn(page, '保存');
  await page.waitForTimeout(300);
  s = await snap(page);
  const dupTags = await tagsOf(page);
  save('D6', { ...s, beforeTags, dupTags });
  ok('D6 重名保存：窗口不关 + 有校验反馈', s.popCount === 1 && s.invalid && s.errText.length > 0, JSON.stringify({ err: s.errText }));
  ok('D6 重名不新增第二枚同名标签', dupTags.filter((t) => t.name === '工作').length === beforeTags.filter((t) => t.name === '工作').length,
    JSON.stringify(dupTags.map((t) => t.name)));

  // D7 选色 + 保存 ⇒ store 真值 + chips 出现 + 窗口关
  await ensureTagOpen(page);
  const PICK = 7; // 索引 7 = 紫
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('紫色标签');
  await page.locator('.tfl-tag-popover .tfl-tag-color').nth(PICK).click();
  await page.waitForTimeout(120);
  const pickedColor = await page.evaluate((i) => getComputedStyle(document.querySelectorAll('.tfl-tag-popover .tfl-tag-color')[i])
    .getPropertyValue('--tag-color').trim(), PICK);
  await tapBtn(page, '保存');
  await page.waitForTimeout(420);
  s = await snap(page);
  const tagsAfterSave = await tagsOf(page);
  const created = tagsAfterSave.find((t) => t.name === '紫色标签');
  const chipIds = await page.evaluate(() => [...document.querySelectorAll('#task-list .tfl-chip-tag')].map((c) => c.dataset.tagId));
  save('D7', { ...s, tagsAfterSave, pickedColor, chipIds });
  ok('D7 保存后 store 真值出现该标签且颜色 = 所选', !!created && created.color === pickedColor,
    JSON.stringify({ pickedColor, created }));
  ok('D7 保存后 chips 行出现该标签', !!created && chipIds.includes(created.id), JSON.stringify(chipIds));
  ok('D7 保存后窗口关闭', s.popCount === 0, JSON.stringify({ popCount: s.popCount }));
  ok('D7 窗口全程在视口内', s.inViewport !== false, JSON.stringify(s.rect));
  await page.screenshot({ path: join(EVID, `d7-saved.${TAG}.png`) });

  // D8 重复打开 ⇒ 同时最多一个窗口
  await openTag(page);
  await page.waitForTimeout(120);
  await page.locator('#task-list .tfl-chip-add').click({ force: true });
  await page.waitForTimeout(300);
  s = await snap(page);
  save('D8', s);
  ok('D8 重复打开：DOM 中始终只有一扇标签窗', s.popCount <= 1, JSON.stringify({ popCount: s.popCount }));

  // D9 关窗的那一次点按不连带开新建行
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
  await openTag(page);
  const box = await page.locator('#task-list .tfl-list').boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height - 10);
  await page.waitForTimeout(350);
  s = await snap(page);
  save('D9', s);
  ok('D9 点列表空白关窗：不连带开出新建行', s.popCount === 0 && !s.createRow, JSON.stringify({ popCount: s.popCount, createRow: s.createRow }));

  // D10 键盘可达：Tab 能走到动作按钮，Enter 触发保存
  await reopenPanel(page);
  await openTag(page);
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('键盘标签');
  const reached = await page.evaluate(() => {
    const pop = document.querySelector('.tfl-tag-popover');
    const btns = pop ? [...pop.querySelectorAll('.tfl-tag-btn')] : [];
    return btns.length > 0 && btns.map((b) => b.tabIndex).every((t) => t >= 0);
  });
  const saveLoc = page.locator('.tfl-tag-popover .tfl-tag-btn', { hasText: '保存' });
  if (await saveLoc.count()) await saveLoc.first().focus();
  const focusedText = await page.evaluate(() => (document.activeElement ? document.activeElement.textContent.trim() : ''));
  await page.keyboard.press('Enter');
  await page.waitForTimeout(400);
  s = await snap(page);
  const kbdTags = await tagsOf(page);
  save('D10', { ...s, reached, focusedText, kbdTags });
  ok('D10 动作按钮键盘可达（tabIndex ≥ 0 且可聚焦）', reached && focusedText === '保存', JSON.stringify({ reached, focusedText }));
  ok('D10 键盘 Enter 触发保存并落地', s.popCount === 0 && kbdTags.some((t) => t.name === '键盘标签'), JSON.stringify(kbdTags.map((t) => t.name)));

  ok('D 全程零页面异常', errs.length === 0, errs.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ───────────── 暗档（键盘可达与对比度不依赖主题）───────────── */
async function darkLeg(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark' });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e.message)));
  await wire(page);
  await mountDesktop(page);
  await openTag(page);
  const s = await snap(page);
  const look = await page.evaluate(() => {
    const pop = document.querySelector('.tfl-tag-popover');
    const save = pop.querySelector('.tfl-tag-btn.is-primary');
    const cancel = pop.querySelector('.tfl-tag-btn:not(.is-primary)');
    const cs = (el) => getComputedStyle(el);
    return {
      popBg: cs(pop).backgroundColor,
      saveBg: cs(save).backgroundColor, saveFg: cs(save).color,
      cancelFg: cs(cancel).color, cancelBorder: cs(cancel).borderColor,
      saveH: +save.getBoundingClientRect().height.toFixed(1),
      cancelH: +cancel.getBoundingClientRect().height.toFixed(1),
    };
  });
  save_key('D11', { ...s, look });
  ok('D11 暗档：窗口可开 + 两枚按钮在场且可点（高 ≥ 26）',
    s.popCount === 1 && s.btns.length === 2 && look.saveH >= 26 && look.cancelH >= 26, JSON.stringify(look));
  ok('D11 暗档：保存钮有实色底（非透明）', !/rgba\(0, 0, 0, 0\)|transparent/.test(look.saveBg), look.saveBg);
  await page.screenshot({ path: join(EVID, `d11-dark.${TAG}.png`) });
  ok('D11 暗档零页面异常', errs.length === 0, errs.slice(0, 2).join(' | '));
  await ctx.close();
}
function save_key(k, v) { readings[k] = v; }

/* ───────────── 手机腿 ───────────── */
async function mobileLeg(browser) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e.message)));
  await wire(page, { mobile: true });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(300);
  await page.evaluate(async ({ sid, tags, items }) => {
    const store = await import('/js/todoStore.js');
    store.todoStore.__seed({ tagList: tags, itemList: items });
    (await import('/js/todoFace.js')).renderTodoTaskList(sid);
  }, { sid: SID, tags: TAGS, items: ITEMS() });
  await page.waitForTimeout(200);
  await page.locator("[data-chrome='tasks']").first().click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="tasks"]');
    return el && el.classList.contains('live');
  }, null, { timeout: 10000 });
  await page.waitForTimeout(800);

  const openMobileTag = async () => {
    await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
    await page.waitForTimeout(380);
    await page.locator('.tfl-menu-row', { hasText: '新建标签' }).first().click();
    await page.waitForTimeout(380);
  };

  // M1 打开：可见 + 视口内 + 动作按钮
  await openMobileTag(page);
  let s = await snap(page);
  save('M1', s);
  ok('M1 手机腿可打开标签窗', s.popCount === 1 && s.popOpen, JSON.stringify({ popOpen: s.popOpen }));
  ok('M1 手机腿窗口完整在视口内', s.inViewport === true, JSON.stringify(s.rect));
  ok('M1 手机腿可见「取消/保存」', s.btns.length === 2, JSON.stringify(s.btns));
  await page.screenshot({ path: join(EVID, `m1-open.${TAG}.png`) });

  // M2 点页面空白 ⇒ 关（报障根因：改前完全不关）
  await page.mouse.click(20, 700);
  await page.waitForTimeout(400);
  s = await snap(page);
  save('M2', s);
  ok('M2 手机腿点空白即关窗（改前：一直不关 = 报障现场）', s.popCount === 0, JSON.stringify({ popCount: s.popCount }));

  // M3 取消 ⇒ 关；草稿保留
  await openMobileTag(page);
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('手机标签');
  await page.mouse.click(20, 700);
  await page.waitForTimeout(380);
  await openMobileTag(page);
  s = await snap(page);
  save('M3', s);
  ok('M3 手机腿草稿保留（重开输入框仍是上次内容）', s.nameVal === '手机标签', JSON.stringify({ nameVal: s.nameVal }));

  // M4 保存 ⇒ 真值落地
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('手机标签');
  await tapBtn(page, '保存');
  await page.waitForTimeout(420);
  s = await snap(page);
  const mTags = await tagsOf(page);
  save('M4', { ...s, mTags });
  ok('M4 手机腿保存后 store 落地该标签', s.popCount === 0 && mTags.some((t) => t.name === '手机标签'), JSON.stringify(mTags.map((t) => t.name)));

  // M5 窗口开着再开 ⋯ 菜单 ⇒ 只剩菜单（窗口先关），不叠两层
  await openMobileTag(page);
  await page.locator(".mobile-page[data-page='tasks'] .tfl-topmenu-btn").click();
  await page.waitForTimeout(420);
  s = await snap(page);
  const menuVisible = await page.locator('.tfl-menu-panel, .tfl-menu-root').first().isVisible().catch(() => null);
  save('M5', { ...s, menuVisible });
  ok('M5 开菜单时标签窗先关（不叠两层浮层）', s.popCount === 0 && menuVisible !== false,
    JSON.stringify({ popCount: s.popCount, menuVisible }));
  await page.screenshot({ path: join(EVID, `m5-menu.${TAG}.png`) });

  ok('M 全程零页面异常', errs.length === 0, errs.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ───────────── 数据面：真源落库 + 刷新后状态 ───────────── */
async function liveLeg(browser) {
  // 桩后端 = 真源替身：起手一条「工作」组下的行（真源面才有分组一个都还没有时，
  // chips 行是空的 —— 本腿要验的正是「新建的空标签 + 颜色」刷新后还在不在）。
  const backend = { tasks: [{
    id: 'bt-0', kind: 'todo', name: '缴房租', detail: '', content: '缴房租', project: '工作',
    priority: 1, completed: false, completedAt: null, createdAt: Date.now() - 3600_000,
    sessionId: SID, triggerAt: null,
  }] };
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e.message)));
  await wire(page, { backend, fixture: false });

  const bootLive = async () => {
    await page.goto('http://localhost:1/');
    await page.waitForFunction(() => !!document.querySelector('#task-list'), null, { timeout: 20000 });
    await page.waitForFunction(async (sid) => {
      const st = (await import('/js/state.js')).default;
      return st.activeSessionId === sid && st.ws && st.ws.readyState === 1;
    }, SID, { timeout: 20000 });
    // 真源首拉（store 惰性自动拉；显式 init 一次把桩数据灌进来）
    await page.evaluate(async () => { (await import('/js/todoStore.js')).todoStore.init(); });
    await page.waitForTimeout(600);
    await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { state: 'visible', timeout: 15000 });
    await page.waitForTimeout(200);
    await page.locator('.tfl-capsule').click();
    await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 10000 });
    await page.waitForTimeout(900);
    await page.locator('.tfl-filter-toggle').click();
    await page.waitForSelector('#task-list .tfl-chip-add', { state: 'visible', timeout: 10000 });
    await page.waitForTimeout(250);
  };
  await bootLive();

  // P1 新建标签（选色）→ 刷新 → 仍在且颜色不变
  await openTag(page);
  await page.locator('.tfl-tag-popover .tfl-tag-name').fill('运动');
  await page.locator('.tfl-tag-popover .tfl-tag-color').nth(3).click();
  const picked = await page.evaluate(() => getComputedStyle(document.querySelectorAll('.tfl-tag-popover .tfl-tag-color')[3])
    .getPropertyValue('--tag-color').trim());
  await tapBtn(page, '保存');
  await page.waitForTimeout(400);
  let created = (await tagsOf(page)).find((t) => t.name === '运动');
  save('P1a', { created, picked });

  // P2 刷新（桩后端重放真源）：空标签与所选颜色是否还在
  await bootLive();
  let afterTags = await tagsOf(page);
  let afterTag = afterTags.find((t) => t.name === '运动');
  save('P2', { afterTags });
  ok('P2 刷新后新建的空标签仍在（改前：真源无行 ⇒ 标签整条消失）', !!afterTag,
    JSON.stringify(afterTags.map((t) => t.name)));
  ok('P2 刷新后标签颜色 = 用户所选（改前：回落 colorForName 哈希色）',
    !!afterTag && afterTag.color === picked, JSON.stringify({ picked, after: afterTag && afterTag.color }));

  // P3 该标签下建一条待办（真帧必须带 project = 标签名）；标签若已被改前树丢弃则先补建
  if (!afterTag) {
    await openTag(page);
    await page.locator('.tfl-tag-popover .tfl-tag-name').fill('运动');
    await page.locator('.tfl-tag-popover .tfl-tag-color').nth(3).click();
    await tapBtn(page, '保存');
    await page.waitForTimeout(400);
  }
  created = (await tagsOf(page)).find((t) => t.name === '运动');
  await page.locator(`#task-list .tfl-chip-tag[data-tag-id="${created.id}"]`).click();
  await page.waitForTimeout(500);
  const listBox = await page.locator('#task-list .tfl-list').boundingBox();
  await page.mouse.click(listBox.x + listBox.width / 2, listBox.y + listBox.height - 12);
  await page.waitForTimeout(400);
  await page.locator('#task-list .tfl-row-title-input').first().fill('晨跑三公里');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(600);
  const createFrame = page.__frames.filter((f) => f.type === 'createScheduledTask').pop();
  save('P3', { createFrame, backend: JSON.parse(JSON.stringify(backend.tasks)) });
  ok('P3 标签下新建待办：写帧真带 project（真源落库面）', !!createFrame && createFrame.project === '运动',
    JSON.stringify(createFrame));

  // P4 再刷新：标签与行都由真源重放回来
  await bootLive();
  afterTags = await tagsOf(page);
  const afterRows = await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.visibleItems()
    .map((r) => ({ name: r.name, tagId: r.tagId })));
  save('P4', { afterTags, afterRows });
  ok('P4 刷新后该标签下的待办仍在（真源行回来）', afterRows.some((r) => r.name === '晨跑三公里'),
    JSON.stringify(afterRows));
  ok('P4 刷新后标签仍在', !!afterTags.find((t) => t.name === '运动'), JSON.stringify(afterTags.map((t) => t.name)));
  await page.screenshot({ path: join(EVID, `p4-after-reload.${TAG}.png`) });

  ok('P 全程零页面异常', errs.length === 0, errs.slice(0, 2).join(' | '));
  await ctx.close();
}

const browser = await chromium.launch();
try {
  await desktopLeg(browser);
  await darkLeg(browser);
  await mobileLeg(browser);
  await liveLeg(browser);
} catch (e) {
  ok('EXCEPTION', false, String(e && e.message).slice(0, 400));
} finally {
  await browser.close();
}

mkdirSync(EVID, { recursive: true });
writeFileSync(join(EVID, `readings.${TAG}.json`), JSON.stringify(readings, null, 2));
writeFileSync(join(EVID, `verdict.${TAG}.txt`), lines.join('\n') + '\n');
console.log(`\n${fail === 0 ? 'ALL PASS' : 'FAILURES'}  pass=${pass} fail=${fail}\nEVID=${EVID}`);
process.exit(fail === 0 ? 0 : 1);
