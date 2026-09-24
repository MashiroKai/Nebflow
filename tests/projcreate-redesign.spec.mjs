// projcreate-redesign.spec.mjs — D1/D2/D4 实施侧自验 harness（projcreate-redesign 批）。
//
// 形态沿本仓既有静态 serve + mock WS 路线（同 tests/projcreate-fab.spec.mjs）：
// route 全域引真实 js/css/locale（**不 mock 副本**），routeWebSocket 注入
// configData/sessionList，并在 **Node 侧**捕获出站帧 / 注入应答帧
// （`routeWebSocket` 跑在 Node 而非页面上下文 ⇒ 帧台账与注入都留在测试作用域）。
// 🔴 零后端：不触任何真实监听（宿主 :8080 / :8097 零信号），纯 Playwright 进程内。
//
// Run:
//   node node_modules/@playwright/test/cli.js test tests/projcreate-redesign.spec.mjs --workers=1 --reporter=line
//
// 断言编号（R = 本批红验）：
//   R-D1-1 P1 钮消失（真渲染读数，DOM 计数 = 0）
//   R-D2-1 提交**真发** projectCreate 帧（出站帧 = 唯一硬判据）
//   R-D2-2 帧形：type/sessionId/workspace/description 四键齐备
//   R-D2-3 失败面：应答 ok:false ⇒ 层不关 + 错误行显示网关原文
//   R-D2-4 成功面：应答 ok:true ⇒ 层关闭
//   R-D3-1 零预填回归：提交后 #input **未被写入**（取代旧 R6）
//   R-D4-1 路径必选闸：未选路径提交 ⇒ 层不关 + 错误行可见 + 零帧
//   R-D4-2 选择器可开（真复用 workspacePicker.js）
//   R-D4-3 选择器回填：onPick 后回显该路径 + data-picked=1
//   R-D4-4 🔴 复用坑：选择器关闭后**本弹层仍在**（detach/reattach 生效）
//   R-D4-5 无手输路径框（路径只能来自选择器 ⇒ 无假控件）

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20260924_projcreate-redesign-impl');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'pc-root-session';
const T0 = Date.now();
/** 选择器夹具：`~` 解析到 PICK_HOME，其下唯一子目录 ws-alpha。 */
const PICK_HOME = '/tmp/pc-pick-home';
const PICK_CHILD = `${PICK_HOME}/ws-alpha`;

const PROJECTS = ['alpha', 'beta', 'gamma'].map((n, i) => ({
  name: n, workspace: `/w/${n}`, agentFile: n, description: '', createdAt: new Date(T0 - i * 1000).toISOString(),
}));

/** 每项目一枚节点载荷 ⇒ 卡片带 `data-open-flowmap`（无节点时该属性被摘除，
 *  「就地 Flow Map 视图」往返就无从进入）。形态同 flowmap-realtime.spec.mjs。 */
const FM = (name) => ({
  nodes: [{
    id: 'n1', name: '研究', agent: 'researcher', status: 'completed',
    in: [], out: 'Nebula', hasWorktree: false, worktree: null, description: '节点', hasResult: false,
    retries: 0, createdAt: T0, completedAt: T0, ttlLeftSec: 300,
  }],
  worktrees: [],
  meta: { project: name, updatedAt: Date.now() },
});

function write(f, obj) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, f), JSON.stringify(obj, null, 2));
}

/** 起应用。返回 Node 侧的双向句柄：`outbox` = 出站帧台账，`reply` = 注入应答帧。 */
async function bootApp(page) {
  const outbox = [];
  let reply = null;
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
      }
      const fm = p.match(/^\/api\/projects\/([^/]+)\/flow-map$/);
      if (fm) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(FM(decodeURIComponent(fm[1]))) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    reply = (msg) => ws.send(JSON.stringify(msg));
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      outbox.push(msg);
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (msg.type === 'wsBrowse.list') {
        // 目录浏览器（workspacePicker.js）的取数列 —— 确定性夹具。
        const path = msg.path || '';
        const resolved = path === '~' ? PICK_HOME : path;
        const entries = resolved === PICK_HOME ? ['ws-alpha'] : [];
        ws.send(JSON.stringify({
          type: 'wsBrowseList', path: resolved, home: PICK_HOME,
          parent: resolved === PICK_HOME ? '/' : undefined, entries,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(250);
  return {
    outbox,
    reply: (msg) => reply && reply(msg),
    projectCreateFrames: () => outbox.filter((m) => m.type === 'projectCreate'),
  };
}

/** 打开 projects 标签页并等列表就绪（沿既有 spec 范式）。 */
async function openProjects(page) {
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    m.openProjectsTab();
  });
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  await page.waitForFunction(() => {
    const s = document.querySelector('.team-scroll');
    return s && s.dataset.projectsState === 'ready';
  }, null, { timeout: 8000 });
}

/** 走去真选择器选一个目录（PICK_CHILD），供需要「已选路径」的用例复用。
 *  🔴 行选择用 `.wsp-row-name` 文本（不能用 `.wsp-row.wsp-dir`：列表首行是
 *  `..` 上级项，其 class 同样含 `wsp-dir`（workspacePicker.js:211）⇒ 首个匹配
 *  会点进上级目录，落点变成 `/tmp` 而不是夹具子目录 —— 实测踩过）。 */
async function pickPath(page) {
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("ws-alpha"))');
  await page.waitForTimeout(350);
  await page.click('.wsp-foot .wsp-pick');         // 「选中此目录」
  await page.waitForTimeout(350);
}

test.setTimeout(120_000);

// ════════════════════════════════════════════════════════════════════════════
// R-D1 · P1 源码切换钮已移除（作者裁定 D1 = 只收 P1）
// ════════════════════════════════════════════════════════════════════════════
test('R-D1 P1 源码切换钮已移除（真渲染读数）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjects(page);

  const beforeOpen = await page.evaluate(() => ({
    toggleCount: document.querySelectorAll('.canvas-source-toggle').length,
    paneSourceMode: document.querySelector('.canvas-tab-pane[data-type="projects"]')?.dataset.sourceMode ?? null,
  }));

  // 往返一次（列表→Flow Map→回列表）后仍为 0（防「重建时又挂回来」）
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body', { timeout: 8000 });
  await page.click('[data-back-to-projects]');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  const afterRoundTrip = await page.evaluate(() => ({
    toggleCount: document.querySelectorAll('.canvas-source-toggle').length,
    fabCount: document.querySelectorAll('.proj-create-fab').length,
    paneSourceMode: document.querySelector('.canvas-tab-pane[data-type="projects"]')?.dataset.sourceMode ?? null,
  }));

  write('50-RD1-toggle-removed.json', { beforeOpen, afterRoundTrip, pageErrors });
  expect(beforeOpen.toggleCount, 'R-D1-1：projects pane 内 .canvas-source-toggle 计数 = 0').toBe(0);
  expect(afterRoundTrip.toggleCount, 'R-D1-1：往返后仍为 0').toBe(0);
  expect(afterRoundTrip.paneSourceMode, 'pane 不再持有源码态状态键').toBeNull();
  expect(afterRoundTrip.fabCount, '「+」钮不受影响（恰 1 枚）').toBe(1);
  expect(pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// R-D4 · 路径选择器（作者裁定 D4：复用既有 openPicker，禁自建）
// ════════════════════════════════════════════════════════════════════════════
test('R-D4 路径槽：必选闸 + 选择器复用 + detach 坑', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const h = await bootApp(page);
  await openProjects(page);

  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });

  const struct = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const pathEl = panel.querySelector('.proj-create-path');
    return {
      pickBtn: !!panel.querySelector('.proj-create-pick'),
      pickBtnText: panel.querySelector('.proj-create-pick')?.textContent ?? null,
      pathText: pathEl?.textContent ?? null,
      pathPicked: pathEl?.dataset.picked ?? null,
      textInputs: panel.querySelectorAll('input[type="text"], input[type="url"], input:not([type])').length,
      textareas: panel.querySelectorAll('textarea').length,
    };
  });

  // R-D4-1 必选闸：描述填了、路径未选 ⇒ 提交被拒 + 零帧
  await page.evaluate(() => {
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '路径未选时应被拒';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(200);
  const afterNoPath = await page.evaluate(() => ({
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    errorText: document.querySelector('.proj-create-error')?.hidden === false
      ? document.querySelector('.proj-create-error').textContent : null,
  }));
  const framesWhenNoPath = h.projectCreateFrames().length;

  // R-D4-2 选择器可开（真复用 workspacePicker.js）
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  const pickerOpen = await page.evaluate(() => ({
    pickerPresent: !!document.querySelector('.wsp-list'),
    dialogDetached: !document.querySelector('.proj-create-overlay'),
    overlayCount: document.querySelectorAll('.wsp-overlay').length,
    rows: [...document.querySelectorAll('.wsp-list .wsp-row-name')].map((e) => e.textContent),
  }));

  // 进入子目录 → 「选中此目录」⇒ 回填
  // 🔴 用工装夹具名定位（不能按 `.wsp-dir` 取首个：首行是 `..` 上级项，class 同含
  //    `wsp-dir` ⇒ 会点进上级，落点漂移 —— 实测踩过）。
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("ws-alpha"))');
  await page.waitForTimeout(350);
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(450);
  const afterPick = await page.evaluate(() => {
    const pathEl = document.querySelector('.proj-create-overlay .proj-create-path');
    return {
      dialogBack: !!document.querySelector('.proj-create-overlay'),
      pickerGone: !document.querySelector('.wsp-list'),
      pathText: pathEl?.textContent ?? null,
      pathPicked: pathEl?.dataset.picked ?? null,
      overlayCount: document.querySelectorAll('.wsp-overlay').length,
      activeIsPick: document.activeElement === document.querySelector('.proj-create-pick'),
    };
  });

  write('51-RD4-picker.json', { struct, afterNoPath, framesWhenNoPath, pickerOpen, afterPick, pageErrors });
  expect(struct.pickBtn, 'R-D4-2 前置：路径选择钮在').toBe(true);
  expect(struct.textInputs, 'R-D4-5：层内无手输路径框（路径只能来自选择器）').toBe(0);
  expect(struct.textareas, '描述框仍是层内唯一文本域').toBe(1);
  expect(struct.pathPicked, '初始态：未选（data-picked=0）').toBe('0');
  expect(afterNoPath.dialogOpen, 'R-D4-1：未选路径提交 ⇒ 层保持打开').toBe(true);
  expect(afterNoPath.errorText, 'R-D4-1：错误行可见且非空').toBeTruthy();
  expect(framesWhenNoPath, 'R-D4-1：未选路径 ⇒ 零 projectCreate 帧发出').toBe(0);
  expect(pickerOpen.pickerPresent, 'R-D4-2：选择器已打开（.wsp-list 在场）').toBe(true);
  expect(pickerOpen.dialogDetached, 'R-D4-4 前置：选择器在飞时本弹层已 detach').toBe(true);
  expect(pickerOpen.overlayCount, 'R-D4-4：文档内 overlay 恰 1 个（选择器自己）').toBe(1);
  expect(pickerOpen.rows, 'R-D4-2：读到夹具目录项').toContain('ws-alpha');
  expect(afterPick.pickerGone, '选择器已关闭').toBe(true);
  expect(afterPick.dialogBack, 'R-D4-4：🔴 关选择器后本弹层仍在（closePicker 未误杀）').toBe(true);
  expect(afterPick.overlayCount, '挂回后 overlay 仍恰 1 个').toBe(1);
  expect(afterPick.pathPicked, 'R-D4-3：回显置 data-picked=1').toBe('1');
  expect(afterPick.pathText, 'R-D4-3：回显 = 选中目录路径').toBe(PICK_CHILD);
  expect(afterPick.activeIsPick, '回挂后焦点交还「选择路径」钮').toBe(true);
  expect(pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// R-D2 · 直连创建：真发 projectCreate 帧 + 帧形 + 零预填 + 失败面
// ════════════════════════════════════════════════════════════════════════════
test('R-D2 直连创建：出站帧 + 帧形 + 零预填 + 失败面', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const h = await bootApp(page);
  await openProjects(page);

  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await pickPath(page);
  await page.evaluate(() => {
    document.getElementById('input').value = ''; // 零预填判据基线
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '直连创建自验项目';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(400);

  const frames = h.projectCreateFrames();
  const afterSubmit = await page.evaluate(() => ({
    inputValue: document.getElementById('input')?.value ?? null,
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    pendingHint: document.querySelector('.proj-create-hint')?.textContent ?? null,
  }));

  // 失败面：应答 ok:false ⇒ 层不关 + 错误原文可见（经真实 onMessage 消费者路径）
  h.reply({ type: 'projectCreateResult', ok: false, error: 'Workspace is already used by project "alpha"' });
  await page.waitForTimeout(300);
  const afterFail = await page.evaluate(() => ({
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    errorText: document.querySelector('.proj-create-error')?.hidden === false
      ? document.querySelector('.proj-create-error').textContent : null,
    hint: document.querySelector('.proj-create-hint')?.textContent ?? null,
    submitDisabled: document.querySelector('.proj-create-overlay .wsp-pick')?.hasAttribute('disabled') ?? null,
  }));

  write('52-RD2-direct-create.json', { frames, afterSubmit, afterFail, pageErrors });
  expect(frames.length, 'R-D2-1：提交后恰好发出 1 帧 projectCreate').toBe(1);
  expect(frames[0]?.sessionId, 'R-D2-2：帧带 sessionId（投递根来源）').toBe(ROOT_SID);
  expect(frames[0]?.workspace, 'R-D2-2：帧带 workspace = 选中路径').toBe(PICK_CHILD);
  expect(frames[0]?.description, 'R-D2-2：帧带 description').toBe('直连创建自验项目');
  expect(afterSubmit.inputValue, 'R-D3-1：零预填回归 —— #input 未被写入（取代旧 R6）').toBe('');
  expect(afterSubmit.pendingHint, '提交后显示进行中文案').toBe('正在创建…');
  expect(afterFail.dialogOpen, 'R-D2-3：网关失败 ⇒ 层不关（可修正重试）').toBe(true);
  expect(afterFail.errorText, 'R-D2-3：错误行显示网关原文').toBe('Workspace is already used by project "alpha"');
  expect(afterFail.submitDisabled, 'R-D2-3：失败后提交钮恢复可用').toBe(false);
  expect(pageErrors, '零页面异常').toEqual([]);
});

// ════════════════════════════════════════════════════════════════════════════
// R-D2b · 成功面（ok:true ⇒ 层关闭）
// ════════════════════════════════════════════════════════════════════════════
test('R-D2b 成功面：ok:true ⇒ 层关闭', async ({ page }) => {
  const h = await bootApp(page);
  await openProjects(page);
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await pickPath(page);
  await page.evaluate(() => {
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '成功面';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(300);
  h.reply({ type: 'projectCreateResult', ok: true, message: "Project 'x' created and mounted." });
  await page.waitForTimeout(400);
  const after = await page.evaluate(() => ({
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    pickerGone: !document.querySelector('.wsp-list'),
  }));
  write('53-RD2b-success.json', { after });
  expect(after.dialogOpen, 'R-D2-4：ok:true ⇒ 层已关闭').toBe(false);
  expect(after.pickerGone, '选择器不在场').toBe(true);
});
