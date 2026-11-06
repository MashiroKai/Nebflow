// todo-entry-collapse.spec.mjs — 待办入口收敛批（2026-11-04 作者令）前端验收。
//
// 口径（2026-11-05 09:28 作者令**按端反转**后的现行口径）：
//   · 桌面 = 顶带待办胶囊 #task-list .tfl-capsule 唯一入口（原口径原样保留）；
//   · 手机 = 聊天根**左上悬浮「任务」圆钮**（#mobile-fab-layer 的
//     [data-chrome="tasks"]）→ pushPage('tasks') → #mobile-task-panel；
//     胶囊在手机层 display:none（端区分，不是同一组件两套皮）。
// 沿革：本 spec 出生时（入口收敛批 2026-11-04）两条腿都是「双端胶囊」；
// 作者 2026-11-05 09:28 要求手机端回到旧悬浮圆钮 ⇒ 手机腿的选择器断言整体反转。
// 本 spec = 选择器级断言（不比对像素）：
//   ① 消息列表顶部「待办事项」入口行退役：`.fm-todo-entry` 双端零命中；
//   ② 手机左上角任务圆钮 / 任务页面板退役：`[data-chrome="tasks"]`、
//      `#mobile-task-panel` 零命中（面板从未被建出）；
//   ③ 顶带待办胶囊 `#task-list .tfl-capsule` 在桌面 1280×800 与手机 375×812
//      都存在且可展开（点开 = `.tfl-card[data-state="open"]`）；
//   ④ 展开面板承载全部承载信息（逐项断言，信息不许丢）：
//      未完成计数（含**计数翻牌** tfl-count-flip）/ 逾期红面（红点 + 翻面 +
//      逾期读数）/ 重要度筛选四签（无·低·中·高）/ 分组可读性（标签 chips +
//      行内组徽标 + 点选分组即滤行）/ 列表行；
//   ⑤ `#todo-page` 不再自消息列表可达（无入口行；pushPage('todo') 无此页；
//      DOM 保留但无 .open 路径）。
//
// 数据面 = WS 夹具（零后端、零 sbt；静态服务器由 page.route 从磁盘注入 ⇒
// 全程零监听端口，结构上不可能碰到作者生产实例 6138）。
// Run: node node_modules/@playwright/test/cli.js test tests/todo-entry-collapse.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
// 截图落点：证据目录按 2026-11-04 文档命名新制 `<topic>__<time>`（收口批改写；
// 旧制时间打头目录已被 main 的命名迁移批改名，本行随即跟上，断言零改动）。
const SHOT_DIR = join(
  ROOT, 'reports-evidence', 'todo-unify-backend__20261104_145310__delegate-nebflow-79768ef5', 'entries2', 'shots',
);
mkdirSync(SHOT_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'todo-collapse-root';
const OTHER_SID = 'todo-collapse-other';
const T0 = Date.now();

test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });

/** 会话内任务表（提醒面渲染源；本 spec 只关心它与待办面不串味）。 */
function taskFixture() {
  return [
    { id: 's1', content: '即将触发的定时任务', triggerAt: T0 + 3_600_000, enabled: true, triggered: false, kind: null },
  ];
}

/** 跨会话收件箱夹具（scope:'all' 回执）—— 胶囊读数的**唯一**来源：
 *  已到期人建待办 1（o1 ⇒ 逾期 1）+ 计划人建 2（d1/d2，project=stand）+
 *  其它会话 1（x1，project=DOSTEL）+ ask/perm 镜像各 1（a1/p1，镜像行按
 *  todoStore 契约不进人待办面 ⇒ 不计入胶囊读数）。
 *  ⇒ 未完成计数 = 4、逾期 = 1。 */
function inboxFixture() {
  return [
    { id: 'o1', content: '已到期的人建待办', triggerAt: T0 - 3_600_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID },
    { id: 'a1', content: '[待答] 选哪个方案？ — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'ask-asknb-aaa111', missCount: 0, sessionId: SID },
    { id: 'p1', content: '[待批准] Bash · 删除临时目录 — worker-a', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: 'perm-permnb-bbb222', missCount: 0, sessionId: SID },
    { id: 'd1', content: '人建待办甲', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID, project: 'stand' },
    { id: 'd2', content: '人建待办乙', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID, project: 'stand' },
    { id: 'x1', content: '另一会话的待办', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: OTHER_SID, project: 'DOSTEL' },
  ];
}

async function bootApp(page, { viewport, inbox = inboxFixture() } = {}) {
  if (viewport) await page.setViewportSize(viewport);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  let live = null;
  const captured = [];
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    live = ws;
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      captured.push(parsed);
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'listScheduledTasks') {
        const tasks = parsed.scope === 'all' ? inbox : taskFixture();
        ws.send(JSON.stringify({ type: 'scheduledTaskList', tasks, scope: parsed.scope }));
      }
      else if (parsed.type === 'getPendingAsks') ws.send(JSON.stringify({ type: 'pendingAsksSnapshot', asks: [] }));
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  // 胶囊由 sidebar 会话切换驱动（renderTodoTaskList）——等读数落盘（非定时 sleep）：
  await page.waitForFunction(() => {
    const cap = document.querySelector('#task-list .tfl-capsule-count');
    return !!cap && /4/.test(cap.textContent || '');
  }, null, { timeout: 15000 });
  return { captured, sendInbox: (list) => live?.send(JSON.stringify({ type: 'scheduledTaskList', tasks: list, scope: 'all' })) };
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const text = m.text();
    if (/Failed to load resource|fetching the script/i.test(text)) return;
    errors.push(`console: ${text}`);
  });
  return errors;
}

test('①③④ 桌面 1280×800：入口行退役、胶囊唯一入口且可展开、面板承载计数/逾期/四签/分组', async ({ page }) => {
  const errors = watchErrors(page);
  const harness = await bootApp(page);

  // ── ① 入口行退役：消息列表 = 纯会话名册 ──
  await page.locator('#messages-btn').click();
  await expect(page.locator('#panel-messages')).toHaveClass(/active/);
  await expect(page.locator('.fm-todo-entry')).toHaveCount(0);
  await expect(page.locator('#fm-conversations [data-todo-entry]')).toHaveCount(0);
  // 列表容器在（不是把整个消息面板一起搞没了）。
  await expect(page.locator('#panel-messages')).toHaveCount(1);

  // ── ③ 胶囊在、且有任务态（.has-tasks 驱动显示）──
  const slot = page.locator('#task-list');
  await expect(slot).toHaveClass(/has-tasks/);
  const capsule = page.locator('#task-list .tfl-capsule');
  await expect(capsule).toHaveCount(1);
  await expect(capsule).toBeVisible();
  // 胶囊仍挂 #top-overlays（顶带单层，不是自建的第二层）。
  expect(await page.evaluate(() => !!document.querySelector('#top-overlays > #task-list'))).toBe(true);
  // 让位链（分发器补充 2026-11-04 18:08）：带底缘 ≤ #chat 的 padding-top ——
  // 胶囊出现即把消息列让出（main.js bandBottom 的 top-reserve 观察器）。
  expect(await page.evaluate(() => {
    const band = document.getElementById('top-overlays').getBoundingClientRect();
    const pad = parseFloat(getComputedStyle(document.getElementById('chat')).paddingTop) || 0;
    return { ok: pad + 1 >= band.bottom, bandBottom: Math.round(band.bottom), chatPad: Math.round(pad) };
  })).toMatchObject({ ok: true });
  await page.screenshot({ path: join(SHOT_DIR, 'desktop-1280-capsule.png') });

  // ④-a 未完成计数：静默态 = "4 待办"
  await expect(page.locator('#task-list .tfl-capsule-count')).toHaveText('4 待办');
  // ④-b 逾期红面：逾期 1 ⇒ 红点 + is-overdue + 逾期读数，且「待办」面不可读
  //     （翻面口径：同一时刻只有一面在流内）。
  await expect(capsule).toHaveClass(/is-overdue/);
  await expect(page.locator('#task-list .tfl-capsule-overdue')).toHaveText('1 逾期');
  expect(await page.evaluate(() => {
    const dot = document.querySelector('#task-list .tfl-capsule-dot');
    const cs = dot ? getComputedStyle(dot) : null;
    return cs ? cs.backgroundColor : '';
  })).toContain('rgb(255, 59, 48)'); // --tfl-overdue 红（todo-face.css 逾期色单源）
  expect(await page.evaluate(() => document.querySelector('#task-list .tfl-cap-face-todo')?.getAttribute('aria-hidden'))).toBe('true');
  expect(await page.evaluate(() => document.querySelector('#task-list .tfl-cap-face-overdue')?.getAttribute('aria-hidden'))).toBe('false');

  // ── 展开：点胶囊 ⇒ 面板开出（.tfl-card[data-state=open]）──
  await capsule.click();
  const card = page.locator('#task-list .tfl-card');
  await expect(card).toHaveAttribute('data-state', 'open');
  await expect(card).toBeVisible();
  // 面板头计数 = 胶囊那颗计数节点**搬家**（同一节点，非第二份文案）。
  await expect(page.locator('#task-list .tfl-header-count .tfl-cap-faces')).toHaveCount(1);
  await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('4 待办');
  await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-overdue')).toHaveText('1 逾期');

  // ④-c 计数翻牌：读数变了那一拍，计数宿主上播一次 tfl-count-flip。
  await page.evaluate(() => {
    window.__countFlipSeen = false;
    const host = document.querySelector('#task-list .tfl-header-count');
    const mo = new MutationObserver(() => {
      if (host.classList.contains('tfl-count-flip')) window.__countFlipSeen = true;
    });
    mo.observe(host, { attributes: true, attributeFilter: ['class'] });
    window.__countFlipMo = mo;
  });
  const grown = [...inboxFixture(), { id: 'n1', content: '新增的一条人建待办', triggerAt: T0 + 86_400_000, enabled: true, triggered: false, kind: 'todo', name: null, missCount: 0, sessionId: SID }];
  harness.sendInbox(grown);
  await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('5 待办');
  await page.waitForFunction(() => window.__countFlipSeen === true, null, { timeout: 5000 });

  // ④-d 列表行（展开面板 = 待办列表本体）：4 行 + 新增 1 行 = 5 行（镜像行不入人待办面）。
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(5);
  await expect(page.locator('#task-list .tfl-row[data-id="o1"]')).toContainText('已到期的人建待办');
  await expect(page.locator('#task-list .tfl-row[data-id="a1"]')).toHaveCount(0);
  await expect(page.locator('#task-list .tfl-row[data-id="p1"]')).toHaveCount(0);

  // ④-e 分组可读性：分组 = project 投影的 chips（stand / DOSTEL）+ 行内组徽标
  //     + 点选分组即滤行（分组是可读、可用的，不是死标签）。
  await page.locator('#task-list .tfl-filter-toggle').click();
  const tagChips = page.locator('#task-list .tfl-chip-tag');
  await expect(tagChips).toHaveCount(2);
  const chipNames = (await tagChips.allTextContents()).map((s) => s.trim()).sort();
  expect(chipNames).toEqual(['DOSTEL', 'stand']);
  // 行内组徽标（总条目视图口径：todoFace.js buildRow「标签徽标（总条目视图）」）。
  await expect(page.locator('#task-list .tfl-row[data-id="d1"] .tfl-row-badge')).toHaveText('stand');
  await expect(page.locator('#task-list .tfl-row[data-id="x1"] .tfl-row-badge')).toHaveText('DOSTEL');
  const standChip = page.locator('#task-list .tfl-chip-tag', { hasText: 'stand' });
  await standChip.click();
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(2);
  await expect(page.locator('#task-list .tfl-row[data-id="x1"]')).toHaveCount(0);
  await expect(page.locator('#task-list .tfl-row[data-id="d1"]')).toContainText('人建待办甲');
  // 回到 All（总条目）：分组筛选可回退。
  await page.locator('#task-list .tfl-chip-all').click();
  await expect(page.locator('#task-list .tfl-row')).toHaveCount(5);

  // ④-f 重要度筛选四签：无 / 低 / 中 / 高（四签齐、可点、按下态可读）
  const prioChips = page.locator('#task-list .tfl-prio-row .tfl-chip-prio');
  await expect(prioChips).toHaveCount(4);
  expect((await prioChips.allTextContents()).map((s) => s.trim())).toEqual(['无', '低', '中', '高']);
  const highChip = page.locator('#task-list .tfl-prio-row .tfl-chip-prio', { hasText: '高' });
  await highChip.click();
  await expect(highChip).toHaveAttribute('aria-pressed', 'true');
  await highChip.click(); // 复位（四签可再点取消）
  await expect(highChip).toHaveAttribute('aria-pressed', 'false');
  await page.screenshot({ path: join(SHOT_DIR, 'desktop-1280-capsule-expanded.png') });

  // 收起：面板头「收起」钮 ⇒ 面板收回、胶囊回到可见（展开态胶囊按设计让位
  // 给面板本体：JS 置 visibility:hidden，收起入口 = 面板头 chevron + 点外部）。
  await page.locator('#task-list .tfl-collapse-btn').click();
  await expect(card).toHaveAttribute('data-state', 'capsule');
  await expect(capsule).toBeVisible();

  expect(errors).toEqual([]);
});

test('②③④⑤ 手机 375×812：待办入口 = 左上悬浮圆钮（端区分），胶囊退场，信息不丢', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { viewport: { width: 375, height: 812 } });

  // 手机层激活。
  await expect(page.locator('body.mobile-layer')).toHaveCount(1);

  // ── ② 左上角任务圆钮**恢复**（作者令 2026-11-05 09:28，逐字：「手机端的待办…
  //    跟电脑端的区分开，电脑端是一个胶囊，手机端还是需要是之前那个左上角的一个
  //    悬浮的待办按钮」）。本腿在原「收敛到胶囊一处」批上**反转**，断言面：
  //    圆钮在、在左上、48 档、徽标读数跟上、右上搜索钮原样。
  const taskBtn = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="tasks"]');
  await expect(taskBtn).toHaveCount(1);
  await expect(taskBtn).toBeVisible();
  await expect(page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="search"]')).toHaveCount(1);
  const bb = await taskBtn.boundingBox();
  expect(bb).not.toBeNull();
  expect(bb.x).toBeLessThan(24);                       // 左缘（--mchrome-top 16）
  expect(bb.y).toBeLessThan(48);                       // 顶缘（16 + safe）
  expect(bb.width).toBeGreaterThanOrEqual(44);         // 触控档（48 g2-fab）
  expect(bb.height).toBeGreaterThanOrEqual(44);
  await expect(taskBtn.locator('.mh-chrome-badge')).toHaveText('4');   // = 未完成 4

  // ── ③ 胶囊在手机层**退场**（端区分的另一半：桌面才有胶囊）──
  expect(await page.evaluate(() => getComputedStyle(document.getElementById('task-list')).display)).toBe('none');
  await expect(page.locator('#task-list .tfl-capsule')).not.toBeVisible();
  // 顶带不再为胶囊预留一行：胶囊槽自身零盒，顶带高度回落到其余子件（8px 呼吸位），
  // 远小于胶囊带的 38px（26 胶囊 + 12 呼吸位）。
  expect(await page.evaluate(() => {
    const slot = document.getElementById('task-list').getBoundingClientRect();
    const band = document.getElementById('top-overlays').getBoundingClientRect();
    return { slot: Math.round(slot.height), band: Math.round(band.height) };
  })).toMatchObject({ slot: 0 });
  expect(await page.evaluate(() => Math.round(document.getElementById('top-overlays').getBoundingClientRect().height))).toBeLessThan(20);

  // ── ⑤ #todo-page 仍不可达（DOM 保留但无开合路径）；'tasks' 页与面板恢复 ──
  await expect(page.locator('#todo-page')).toHaveCount(1);
  await expect(page.locator('#todo-page')).not.toHaveClass(/open/);
  await expect(page.locator('.fm-todo-entry')).toHaveCount(0);

  // ── ④ 点圆钮 = push 任务页，面板承载计数/逾期/四签/分组（信息不丢）──
  await taskBtn.click();
  await expect(page.locator('.mobile-page[data-page="tasks"].live')).toHaveCount(1);
  await expect(page.locator('#mobile-task-panel')).toHaveCount(1);
  await expect(page.locator('#mobile-task-panel')).toBeVisible();
  await expect(page.locator('#mobile-task-panel .tp-stats')).toHaveText('4 待办');
  await expect(page.locator('#mobile-task-panel .tfl-row')).toHaveCount(4);
  await expect(page.locator('#mobile-task-panel .tfl-capsule-overdue').first()).toHaveText('1 逾期');
  // 顶部控制口径换代（todo-mobile-topmenu 批 2026-11-06）：筛选钮 / chips 行 /
  // 四签在手机页**退役**（DOM 不建），视图与重要度搬进悬浮「⋯」的两级面板 ——
  // 本腿断言改为新入口，信息面（四签 / 分组）在同一条链上仍可达、不丢。
  await expect(page.locator('#mobile-task-panel .tfl-filter-toggle')).toHaveCount(0);
  await expect(page.locator('#mobile-task-panel .tfl-chips-row')).toHaveCount(0);
  await expect(page.locator('#mobile-task-panel .tfl-prio-row')).toHaveCount(0);
  const menuBtn = page.locator('#mobile-task-panel .tfl-topmenu-btn');
  await expect(menuBtn).toHaveCount(1);
  await menuBtn.click();
  await expect(page.locator('.tfl-menu-panel.is-open')).toHaveCount(1);
  await page.locator('.tfl-menu-panel .tfl-menu-row', { hasText: '重要度' }).first().click();
  await expect(page.locator('.tfl-menu-sub.is-open .tfl-menu-row')).toHaveCount(4);   // 无/低/中/高
  await page.locator('.tfl-menu-panel .tfl-menu-row', { hasText: '标签' }).first().click();
  await expect(page.locator('.tfl-menu-sub.is-open .tfl-menu-row')).toHaveCount(3);   // 全部 + 2 枚标签
  await page.locator('.tfl-menu-scrim').click({ position: { x: 8, y: 600 } });
  await expect(page.locator('.tfl-menu')).toBeHidden();
  // 返回钮（页面壳左上）在安全区内且可点：整页不压刘海
  const back = page.locator('.mobile-page[data-page="tasks"] .mobile-back-btn');
  await expect(back).toHaveCount(1);
  const bkb = await back.boundingBox();
  expect(bkb.y).toBeGreaterThanOrEqual(0);
  await page.screenshot({ path: join(SHOT_DIR, 'mobile-375-tasks-fab.png') });

  // 出页：面板随出栈收口（closeTaskPanel 腿恢复）
  await back.click();
  await expect(page.locator('.mobile-page[data-page="tasks"].live')).toHaveCount(0);
  expect(await page.evaluate(() => !!document.getElementById('mobile-task-panel')?.hidden)).toBe(true);

  // ── ① 收尾：手机消息页仍无入口行（列表 = 纯会话名册）──
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await expect(page.locator('.mobile-page[data-page="messages"].live')).toHaveCount(1);
  await expect(page.locator('.mobile-page[data-page="messages"] .fm-todo-entry')).toHaveCount(0);
  await expect(page.locator('.fm-todo-entry')).toHaveCount(0);
  await expect(page.locator('#todo-page')).not.toHaveClass(/open/);
  await page.screenshot({ path: join(SHOT_DIR, 'mobile-375-messages-no-entry.png') });

  expect(errors).toEqual([]);
});
