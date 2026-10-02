// projcreate-fab.spec.mjs — 项目面板「+」钮 + 轻弹层 实施侧自验 harness。
//
// 形态沿本仓既有静态 serve + mock WS 路线（同 flowmap-realtime.spec.mjs /
// flowmap-archive-panel.spec.mjs）：route 全域引真实 js/css/locale（**不 mock 副本**），
// routeWebSocket 注入 configData/sessionList，走真实 ws.js 分发。
// 🔴 零后端：不触任何真实监听（宿主 :8080 / :8097 零信号），纯 Playwright 进程内。
//
// Run:
//   node node_modules/@playwright/test/cli.js test tests/projcreate-fab.spec.mjs --workers=1 --reporter=line
//
// 断言编号对应任务书 §6 红验（R1..R15）+ 分发器两条补正：
//   R1..R3 改前红 → 见 .nebflow/evidence/.../20-red-before.txt（静态读数，非本 harness）
//   补正① 可达性双证：静态证见 30-static-reachability.txt；真渲染证 = TR1/TR2（本 harness）
//   补正① -3 变异复红：MUT（把挂载语句移到 return 之后 ⇒ TR1 必红）
//   补正② 两钮不重叠：G1（新钮 vs 同角既有钮，逐档 getBoundingClientRect）

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20260923_projcreate-impl');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const ROOT_SID = 'pc-root-session';
const T0 = Date.now();
/** 目录选择器夹具（projcreate-redesign 批 D4 后，提交前须先选路径）。 */
const PICK_HOME = '/tmp/pc-pick-home';
const PICK_CHILD = `${PICK_HOME}/ws-alpha`;

// 卡片名单：3 个项目 ⇒ 末行卡在窄档也稳定存在（供遮挡判据用）。
const PROJECTS = ['alpha', 'beta', 'gamma'].map((n, i) => ({
  name: n, workspace: `/w/${n}`, agentFile: n, description: '', createdAt: new Date(T0 - i * 1000).toISOString(),
}));

// 每个项目一枚节点 ⇒ 卡片带 `data-open-flowmap`（无节点时该属性被摘除，
// 「就地 Flow Map 视图」负对照就无从进入）。载荷形态同 flowmap-realtime.spec.mjs。
const FM = (name) => ({
  nodes: [{
    id: 'n1', name: '研究', agent: 'researcher', status: 'completed',
    in: [], out: 'Nebula', hasWorktree: false, worktree: null, description: '节点', hasResult: false,
    retries: 0, createdAt: T0, completedAt: T0, ttlLeftSec: 300,
  }],
  worktrees: [],
  meta: { project: name, updatedAt: Date.now() },
});

test.setTimeout(120_000);

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
      const fmMatch = p.match(/^\/api\/projects\/([^/]+)\/flow-map$/);
      if (fmMatch) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(FM(decodeURIComponent(fmMatch[1]))) });
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
        // 目录浏览器取数列（D4 后提交前须先选路径）—— 确定性夹具。
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
  await page.waitForTimeout(300);
  return {
    outbox,
    reply: (msg) => reply && reply(msg),
    projectCreateFrames: () => outbox.filter((m) => m.type === 'projectCreate'),
  };
}

/** 走去真目录选择器选 PICK_CHILD（D4 路径必选闸 ⇒ 任何「提交成功」用例都需先选）。
 *  🔴 行定位必须用夹具名（`.wsp-row.wsp-dir` 的首个匹配是 `..` 上级项，
 *  workspacePicker.js:211 同样给它 `wsp-dir` ⇒ 会点进上级、落点漂移 —— 实测踩过）。 */
async function pickPath(page) {
  await page.click('.proj-create-overlay .proj-create-pick');
  await page.waitForSelector('.wsp-list', { timeout: 5000 });
  await page.click('.wsp-list .wsp-row:has(.wsp-row-name:text-is("ws-alpha"))');
  await page.waitForTimeout(350);
  await page.click('.wsp-foot .wsp-pick');
  await page.waitForTimeout(350);
}

/** 打开 projects 标签页并等列表就绪。
 *  🔴 用 `openProjectsTab()` 而不是点 `#projects-btn`：4 态机里「页签已在活动态时再点」
 *  = **收起页签**（canvas.js:456 state 2 toggle off）⇒ 点按钮不是幂等的「打开」。 */
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

const rects = (page, sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height, left: r.left, top: r.top, right: r.right, bottom: r.bottom };
}, sel);
const overlap = (a, b) => !(a.bottom <= b.top || a.left >= b.right || a.top >= b.bottom || a.right <= b.left);

const read = (f) => JSON.parse(readFileSync(join(EVID, f), 'utf8'));
function write(f, obj) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, f), JSON.stringify(obj, null, 2));
}

// ════════════════════════════════════════════════════════════════════════════
// T1 · 钮存在（真渲染证 · 补正① -2）+ 仅列表视图（R4 / R10）
// ════════════════════════════════════════════════════════════════════════════
test('T1 钮存在（真渲染证）+ 仅列表视图', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjects(page);

  const fab = await rects(page, '.proj-create-fab');
  expect(fab, 'R4/补正①-2：列表视图下「+」钮必须存在').not.toBeNull();
  expect(fab.w, '补正①-2：getBoundingClientRect 宽度非零').toBeGreaterThan(0);
  expect(fab.h, '补正①-2：getBoundingClientRect 高度非零').toBeGreaterThan(0);

  // Seat check (projfab-ui-impl batch, 2026-09-25 author order): the FAB anchors
  // to the pane's bottom-right corner with a 16px inset on BOTH axes. Basis:
  // symmetric with the untouched bottom:16px on the 4px grid, and it matches the
  // sibling corner FAB of this very pane (.fm-fab = top:16px/right:16px,
  // flowMap.css:723). Geometric readout against the live pane rect (not just the
  // computed style) so the assertion carries weight: mutation-verified to FAIL
  // against the old right:64px seat.
  const paneRect = await page.evaluate(() => {
    const p = document.querySelector('.canvas-tab-pane[data-type="projects"]');
    const r = p.getBoundingClientRect();
    return { right: r.right, bottom: r.bottom };
  });
  const seat = {
    rightInset: paneRect.right - fab.right,
    bottomInset: paneRect.bottom - fab.bottom,
    computedRight: await page.evaluate(() => getComputedStyle(document.querySelector('.proj-create-fab')).right),
  };
  expect(Math.abs(seat.rightInset - 16), 'seat: FAB right edge inset from pane = 16px (edge-anchored)').toBeLessThan(0.5);
  expect(Math.abs(seat.bottomInset - 16), 'seat: FAB bottom edge inset from pane = 16px (unchanged axis)').toBeLessThan(0.5);
  expect(seat.computedRight, 'seat: computed style right = 16px').toBe('16px');

  const aria = await page.evaluate(() => {
    const b = document.querySelector('.proj-create-fab');
    return { title: b.getAttribute('title'), ariaLabel: b.getAttribute('aria-label'), tag: b.tagName,
      inPane: !!b.closest('.canvas-tab-pane[data-type="projects"]') };
  });

  // R10 负对照②：进 Flow Map 就地视图 ⇒ 钮为 null
  await page.click('.project-card[data-project="alpha"] [data-open-flowmap="alpha"]');
  await page.waitForSelector('.flowmap-view-body', { timeout: 8000 });
  const fabInFlowMap = await page.evaluate(() => document.querySelector('.proj-create-fab') !== null);
  // 回列表 ⇒ 钮回归（单点挂载的 DOM 生命周期正确性）
  await page.click('[data-back-to-projects]');
  await page.waitForSelector('.project-card[data-project="alpha"]', { timeout: 8000 });
  const fabBack = await page.evaluate(() => {
    const b = document.querySelector('.proj-create-fab');
    if (!b) return null;
    const r = b.getBoundingClientRect();
    return { w: r.width, h: r.height, count: document.querySelectorAll('.proj-create-fab').length };
  });

  write('40-T1-fab-existence.json', { fab, seat, aria, fabInFlowMap, fabBack, pageErrors });
  expect(fabInFlowMap, 'R10：就地 Flow Map 视图不得显示「+」钮').toBe(false);
  expect(fabBack, 'R4：回列表后钮必须回归').not.toBeNull();
  expect(fabBack.count, '幂等：列表视图内「+」钮恰好 1 枚').toBe(1);
  expect(aria.tag, '补正①-2：真实 button 元素').toBe('BUTTON');
  expect(aria.inPane, '挂载在 projects pane 内').toBe(true);
  expect(aria.title, 'R7 前置：title 非空').toBeTruthy();
  expect(aria.ariaLabel, 'R7 前置：aria-label 非空').toBeTruthy();
});

// ════════════════════════════════════════════════════════════════════════════
// T2 · 幂等（连调两次后按钮数 = 1）+ 可达性读数
// ════════════════════════════════════════════════════════════════════════════
test('T2 幂等：多次重渲后按钮数恒为 1', async ({ page }) => {
  await bootApp(page);
  await openProjects(page);
  // 幂等：反复走「打开面板」这条单点挂载路径（`openProjectTab` → `ensureScroll`）
  const counts = [];
  for (let i = 0; i < 3; i += 1) {
    await openProjects(page);
    counts.push(await page.evaluate(() => document.querySelectorAll('.proj-create-fab').length));
  }
  // 就地视图往返
  await page.click('.project-card[data-project="beta"] [data-open-flowmap="beta"]');
  await page.waitForTimeout(300);
  await page.click('[data-back-to-projects]');
  await page.waitForSelector('.project-card[data-project="beta"]', { timeout: 8000 });
  const afterRoundTrip = await page.evaluate(() => document.querySelectorAll('.proj-create-fab').length);
  // 语言切换（rebuildCardsOnce 一次性整体重建）后仍恰 1 枚
  await page.evaluate(async () => {
    const { setLocale } = await import('/js/i18n.js');
    setLocale('en');
  });
  await page.waitForTimeout(400);
  const afterLocale = await page.evaluate(() => document.querySelectorAll('.proj-create-fab').length);
  await page.evaluate(async () => {
    const { setLocale } = await import('/js/i18n.js');
    setLocale('zh-CN');
  });
  await page.waitForTimeout(400);
  const afterLocaleBack = await page.evaluate(() => document.querySelectorAll('.proj-create-fab').length);

  write('41-T2-idempotency.json', { counts, afterRoundTrip, afterLocale, afterLocaleBack });
  expect(counts.every((c) => c === 1), `幂等：连续 3 次打开后按钮数均为 1（实测 ${counts}）`).toBe(true);
  expect(afterRoundTrip, '幂等：就地视图往返后 = 1').toBe(1);
  expect(afterLocale, '幂等：语言切换重建后 = 1').toBe(1);
  expect(afterLocaleBack, '幂等：切回中文后 = 1').toBe(1);
});

// ════════════════════════════════════════════════════════════════════════════
// T3 · 弹层：只有描述框（R5）+ 可达性串（R7）
// ════════════════════════════════════════════════════════════════════════════
test('T3 弹层只有描述框 + 可达性串', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await openProjects(page);
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay .wsp-panel', { timeout: 4000 });

  const probe = await page.evaluate(() => {
    const ov = document.querySelector('.proj-create-overlay');
    const panel = ov.querySelector('.wsp-panel');
    const cs = getComputedStyle(ov);
    const pcs = getComputedStyle(panel);
    const fields = [...panel.querySelectorAll('input, textarea, select')].map((el) => ({ tag: el.tagName, type: el.getAttribute('type'), id: el.id }));
    return {
      textareas: panel.querySelectorAll('textarea').length,
      textInputs: panel.querySelectorAll('input[type="text"], input[type="url"], input:not([type])').length,
      selects: panel.querySelectorAll('select').length,
      fields,
      role: panel.getAttribute('role'),
      ariaModal: panel.getAttribute('aria-modal'),
      ariaLabel: panel.getAttribute('aria-label'),
      overlayBg: cs.backgroundColor,
      overlayBackdrop: cs.backdropFilter || cs.webkitBackdropFilter,
      panelBackdrop: pcs.backdropFilter || pcs.webkitBackdropFilter,
      panelBg: pcs.backgroundColor,
      zIndex: cs.zIndex,
      // 层内文案（逐字核对 C1 / 按钮 / 取消）
      placeholder: panel.querySelector('textarea').getAttribute('placeholder'),
      labelText: panel.querySelector('.proj-create-label')?.textContent,
      submitText: panel.querySelector('.wsp-pick')?.textContent,
      cancelText: panel.querySelector('.wsp-cancel')?.textContent,
      titleText: panel.querySelector('.wsp-title')?.textContent,
      countText: panel.querySelector('.proj-create-count')?.textContent,
      activeIsDesc: document.activeElement === panel.querySelector('textarea'),
      // 层内元素清单（"只有描述框" 的完整读数）
      inventory: [...panel.querySelectorAll('*')].map((e) => e.tagName + (e.className ? '.' + String(e.className).split(' ')[0] : '')),
    };
  });

  write('42-T3-dialog-probe.json', { probe, pageErrors });
  expect(probe.textareas, 'R5：层内 <textarea> 恰好 1').toBe(1);
  expect(probe.textInputs, 'R5：层内 text 输入框 = 0').toBe(0);
  expect(probe.selects, 'R5：层内 select = 0').toBe(0);
  expect(probe.role, 'R7：role=dialog').toBe('dialog');
  expect(probe.ariaModal, 'R7：aria-modal=true').toBe('true');
  expect(probe.ariaLabel, 'R7：aria-label 非空').toBeTruthy();
  expect(probe.overlayBg, '视觉铁律1：背景零暗化（透明）').toBe('rgba(0, 0, 0, 0)');
  expect(['none', '']).toContain(probe.overlayBackdrop);
  expect(probe.panelBackdrop, '视觉铁律1：面板本身毛玻璃').toContain('blur');
  expect(probe.placeholder).toBe('描述这个项目要做什么\u2014\u2014Agent会根据这段描述来分配任务到项目。');
  expect(probe.titleText).toBe('新建项目');
  expect(probe.submitText).toBe('创建项目');
  expect(probe.cancelText).toBe('取消');
  expect(probe.countText).toBe('0/500');
  expect(probe.activeIsDesc, '打开即聚焦描述文本域').toBe(true);
});

// ════════════════════════════════════════════════════════════════════════════
// T4 · 提交路径（R6）+ 必填（R11）+ 上限（R12）—— 500 边界
// ════════════════════════════════════════════════════════════════════════════
test('T4 提交路径 + 必填 + 500 字边界', async ({ page }) => {
  // 🔴 本用例两处断言被作者 2026-09-24 裁定 D2(b)+D4 **取代**（旧语义 = 乙案「预填
  //    聊天框 + 用户按发送」；新语义 = WS `projectCreate` 直连创建 + 路径必选闸）：
  //      · 旧 `after_submit_499/500.dialogOpen === false`（提交即关层）⇒ 新语义下
  //        未选路径时提交被拒、层保持打开。本用例改为显式选路径后再断言成功面。
  //      · 旧 `after_submit_499.value` 含 `createPrefill` 模板（#input 被写入）⇒
  //        新语义**零预填**（#input 不得被写入）；改判「#input 保持为空」。
  //    取代后的完整直连/选择器判据见 tests/projcreate-redesign.spec.mjs（R-D2/R-D4）。
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const h = await bootApp(page);
  await openProjects(page);

  const snapshot = () => page.evaluate(() => {
    const inp = document.getElementById('input');
    return {
      value: inp?.value ?? null,
      activeIsInput: document.activeElement === inp,
      dialogOpen: !!document.querySelector('.proj-create-overlay'),
      errorText: document.querySelector('.proj-create-error')?.hidden === false
        ? document.querySelector('.proj-create-error').textContent : null,
      countText: document.querySelector('.proj-create-count')?.textContent ?? null,
    };
  });

  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  const baseline = await snapshot();

  // R11 负对照③：空描述提交被拒 —— #input 未变 ∧ 错误行可见
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(120);
  const emptySubmit = await snapshot();

  // 边界 499 / 500 / 501（逐档给读数）
  // 🆕 D4 路径必选闸：先选好路径，本用例余下断言才落在「描述长度」这一维上
  //    （否则每档都会因缺路径被拒，长度判据就无从测）。
  await pickPath(page);
  const bounds = {};
  for (const n of [499, 500, 501]) {
    await page.evaluate((len) => {
      const ta = document.querySelector('.proj-create-desc');
      ta.value = 'x'.repeat(len);
      ta.dispatchEvent(new Event('input', { bubbles: true }));
    }, n);
    bounds[`count_at_${n}`] = (await snapshot()).countText;
    const over = await page.evaluate(() => document.querySelector('.proj-create-count').classList.contains('is-over'));
    bounds[`is_over_at_${n}`] = over;
    await page.evaluate(() => { document.getElementById('input').value = ''; });
    await page.click('.proj-create-overlay .wsp-pick');
    await page.waitForTimeout(150);
    const s = await snapshot();
    bounds[`after_submit_${n}`] = { value: s.value, dialogOpen: s.dialogOpen, errorText: s.errorText };
    // 提交后 = 已发出 projectCreate 帧、等应答；注入应答复位到确定态继续下一档
    h.reply({ type: 'projectCreateResult', ok: false, error: 'superseded-bound-probe' });
    await page.waitForTimeout(120);
  }

  // R6（**取代**：旧 = 描述进主输入框 + 聚焦；新 = 零预填 + 真发帧）
  await page.evaluate(() => {
    document.getElementById('input').value = '';
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '一个用于测试的项目描述';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(250);
  const okSubmit = await snapshot();
  const frames = h.projectCreateFrames();

  write('43-T4-submit-and-bounds.json', {
    baseline, emptySubmit, bounds, okSubmit, frames: frames.slice(-1), pageErrors,
  });
  expect(baseline.value, '改前：#input 为空').toBe('');
  expect(emptySubmit.dialogOpen, 'R11：空描述提交后层仍开（未通过）').toBe(true);
  expect(emptySubmit.errorText, 'R11：错误行可见且命中「必填」文案').toBe('请填写项目描述（Agent 会根据它分配任务）');
  expect(emptySubmit.value, 'R11：空描述提交后 #input 未变').toBe('');
  expect(bounds.count_at_499, '计数器 499 档').toBe('499/500');
  expect(bounds.count_at_500, '计数器 500 档（边界合规）').toBe('500/500');
  expect(bounds.count_at_501, '计数器 501 档（超限）').toBe('501/500');
  expect(bounds.is_over_at_499, '499 不染色').toBe(false);
  expect(bounds.is_over_at_500, '500 不染色（含于上限）').toBe(false);
  expect(bounds.is_over_at_501, '501 染色').toBe(true);
  expect(bounds.after_submit_501.value, '501 被拒 ⇒ #input 仍未写入（零预填，取代旧判据）').toBe('');
  expect(bounds.after_submit_501.errorText, '501 错误行命中「超 500 字」文案').toBe('描述超过 500 字，请缩短到 500 字以内。');
  // 🔴 取代判据（D2(b)）：提交不再写 #input —— 成功路径的唯一硬读数是「真发出帧」。
  expect(okSubmit.value, 'R6 取代：零预填 —— #input 未被写入（旧判据为「含 createPrefill 模板」）').toBe('');
  expect(okSubmit.dialogOpen, 'R6 取代：提交已发出、等应答 ⇒ 层暂不自行关闭（应答到达才关）').toBe(true);
  expect(frames.length, 'R6 取代：确实发出 projectCreate 帧（直连创建唯一硬判据）').toBeGreaterThan(0);
  expect(frames.at(-1)?.workspace, 'R6 取代：帧带已选路径').toBe(PICK_CHILD);
  expect(frames.at(-1)?.description, 'R6 取代：帧带描述').toBe('一个用于测试的项目描述');
});

// ════════════════════════════════════════════════════════════════════════════
// T5 · Esc 关层 + 焦点归还（R8）+ 点遮罩空白关层
// ════════════════════════════════════════════════════════════════════════════
test('T5 Esc 关层 + 焦点归还「+」钮 + 点空白关层', async ({ page }) => {
  await bootApp(page);
  await openProjects(page);

  // Esc 路径
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(150);
  const afterEsc = await page.evaluate(() => ({
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    focusIsFab: document.activeElement === document.querySelector('.proj-create-fab'),
    activeClass: document.activeElement?.className || null,
  }));

  // 点遮罩空白路径
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await page.mouse.click(4, 4); // 视口左上角 = overlay 自身（面板居中）
  await page.waitForTimeout(150);
  const afterOverlayClick = await page.evaluate(() => ({
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    focusIsFab: document.activeElement === document.querySelector('.proj-create-fab'),
  }));

  // 取消按钮路径
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await page.click('.proj-create-overlay .wsp-cancel');
  await page.waitForTimeout(150);
  const afterCancel = await page.evaluate(() => ({
    dialogOpen: !!document.querySelector('.proj-create-overlay'),
    focusIsFab: document.activeElement === document.querySelector('.proj-create-fab'),
  }));

  write('44-T5-close-and-focus.json', { afterEsc, afterOverlayClick, afterCancel });
  expect(afterEsc.dialogOpen, 'R8：Esc 后层已移除').toBe(false);
  expect(afterEsc.focusIsFab, 'R8：Esc 后焦点归还「+」钮').toBe(true);
  expect(afterOverlayClick.dialogOpen, '点遮罩空白 ⇒ 关层').toBe(false);
  expect(afterOverlayClick.focusIsFab, '点遮罩空白 ⇒ 焦点归还').toBe(true);
  expect(afterCancel.dialogOpen, '取消按钮 ⇒ 关层').toBe(false);
  expect(afterCancel.focusIsFab, '取消按钮 ⇒ 焦点归还').toBe(true);
});

// ════════════════════════════════════════════════════════════════════════════
// T6 · 几何：四档视口 —— 钮 vs 末行卡（R13）+ 钮 vs 同角既有钮（补正②）
// ════════════════════════════════════════════════════════════════════════════
test('T6 几何四档：末行卡不被遮挡 + 两钮不重叠 + 弹层不溢出', async ({ page }) => {
  await bootApp(page);
  await openProjects(page);

  const VIEWPORTS = [
    { w: 1440, h: 900, tag: 'desktop-1440x900' },
    { w: 1024, h: 768, tag: 'tablet-1024x768' },
    { w: 768, h: 1024, tag: 'tablet-narrow-768x1024' },
    { w: 375, h: 812, tag: 'mobile-375x812' },
  ];
  const out = {};

  for (const vp of VIEWPORTS) {
    await page.setViewportSize({ width: vp.w, height: vp.h });
    await page.waitForTimeout(250);
    // 视口变化后重新落位（点按钮不可用：已活动态再点 = 收起，见 openProjects 注释）。
    await openProjects(page).catch(() => {});
    await page.waitForTimeout(200);

    // 滚到底部（末行卡进入与钮同带）
    await page.evaluate(() => {
      const s = document.querySelector('.team-scroll');
      if (s) s.scrollTop = s.scrollHeight;
    });
    await page.waitForTimeout(200);

    const geom = await page.evaluate(() => {
      const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
        return { left: r.left, top: r.top, right: r.right, bottom: r.bottom, w: r.width, h: r.height }; };
      const fab = document.querySelector('.proj-create-fab');
      const toggle = document.querySelector('.canvas-source-toggle');
      const pane = document.querySelector('.canvas-tab-pane[data-type="projects"]');
      const scroll = document.querySelector('.team-scroll');
      const cards = [...document.querySelectorAll('.project-card')];
      // 末行 = 视口内最靠下的卡（多列布局下的末行最右卡）
      const sorted = cards.slice().sort((a, b) => {
        const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
        return (rb.top - ra.top) || (rb.right - ra.right);
      });
      const lastCard = sorted[0] || null;
      const cs = scroll ? getComputedStyle(scroll) : null;
      return {
        fab: rect(fab),
        toggle: rect(toggle),
        toggleExists: !!toggle,
        pane: rect(pane),
        scrollRect: rect(scroll),
        lastCard: rect(lastCard),
        lastCardName: lastCard?.dataset.project || null,
        cardCount: cards.length,
        scrollPaddingBottom: cs?.paddingBottom || null,
        fabRight: fab ? getComputedStyle(fab).right : null,
        fabZ: fab ? getComputedStyle(fab).zIndex : null,
      };
    });

    const ov = (a, b) => (a && b) ? !(a.bottom <= b.top || a.left >= b.right || a.top >= b.bottom || a.right <= b.left) : null;
    const g = {
      ...geom,
      fabVsLastCardOverlap: ov(geom.fab, geom.lastCard),
      fabVsToggleOverlap: geom.toggleExists ? ov(geom.fab, geom.toggle) : null,
      fabInViewport: geom.fab ? (geom.fab.left >= 0 && geom.fab.top >= 0 && geom.fab.right <= vp.w && geom.fab.bottom <= vp.h) : null,
    };

    // 弹层不溢出（打开态）
    const fabVisible = await page.evaluate(() => !!document.querySelector('.proj-create-fab'));
    if (!fabVisible) {
      g.dialog = { error: 'fab absent at this viewport — dialog not probed' };
      out[vp.tag] = g;
      continue;
    }
    // 🔴 增量口径（沿 message-search A9 v2.1 既有裁定）：`document.scrollWidth` 在窄档
    // 会被**与本特性无关的**既有面板本底溢出污染（#daemon-panel / #canvas-panel 在 375px
    // 视口的既有溢出）⇒ 断言必须是「打开态 === 关闭态基线」+「弹窗子树自身无溢出」，
    // 而不是「全文档 scrollWidth ≤ 视口宽」。
    const closedBaseline = await page.evaluate(() => ({
      docScrollW: document.documentElement.scrollWidth,
      docClientW: document.documentElement.clientWidth,
    }));
    await page.click('.proj-create-fab');
    await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
    const dlg = await page.evaluate((vw) => {
      const panel = document.querySelector('.proj-create-overlay .wsp-panel');
      const ovr = document.querySelector('.proj-create-overlay').getBoundingClientRect();
      const r = panel.getBoundingClientRect();
      // 弹窗子树自身溢出（增量面）：子树内 max scrollWidth vs 面板 client 宽
      const subtreeOverflow = [...panel.querySelectorAll('*')]
        .filter((e) => e.scrollWidth > e.clientWidth + 1)
        .map((e) => ({ cls: e.className || e.tagName, sw: e.scrollWidth, cw: e.clientWidth }));
      return {
        panel: { left: r.left, top: r.top, right: r.right, bottom: r.bottom, w: r.width, h: r.height },
        overlay: { left: ovr.left, top: ovr.top, right: ovr.right, bottom: ovr.bottom },
        overflowsViewport: !(r.left >= 0 && r.top >= 0 && r.right <= vw && r.bottom <= window.innerHeight),
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth,
        subtreeOverflow,
      };
    }, vp.w);
    dlg.closedBaseline = closedBaseline;
    // 增量：打开态相对关闭态的**增量**必须为 0（既有本底溢出不计入本特性）
    dlg.scrollWDelta = dlg.docScrollW - closedBaseline.docScrollW;
    g.dialog = dlg;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(120);

    out[vp.tag] = g;
  }

  write('45-T6-geometry-viewports.json', out);

  for (const [tag, g] of Object.entries(out)) {
    expect(g.fab, `[${tag}] 钮存在`).not.toBeNull();
    expect(g.fabVsLastCardOverlap, `[${tag}] R13：钮与末行卡两轴均不相交`).toBe(false);
    expect(g.scrollPaddingBottom, `[${tag}] 让位规则生效 padding-bottom=72px`).toBe('72px');
    expect(g.fabInViewport, `[${tag}] 钮在视口内`).toBe(true);
    // projfab-ui-impl batch: edge seat pinned at every viewport (2026-09-25 order).
    expect(g.fabRight, `[${tag}] seat: computed right = 16px (bottom-right edge anchor)`).toBe('16px');
    if (g.toggleExists) {
      expect(g.fabVsToggleOverlap, `[${tag}] 补正②：新钮与同角既有切换钮不重叠`).toBe(false);
    }
    expect(g.dialog.error, `[${tag}] 弹层探测已完成`).toBeUndefined();
    expect(g.dialog.overflowsViewport, `[${tag}] 弹层不溢出视口`).toBe(false);
    expect(g.dialog.scrollWDelta, `[${tag}] 弹层不制造横向溢出（增量口径：打开态−关闭态基线 delta=0）`).toBe(0);
    expect(g.dialog.subtreeOverflow, `[${tag}] 弹窗子树自身无横向溢出`).toEqual([]);
  }
});

// ════════════════════════════════════════════════════════════════════════════
// T7 · 负对照①：既有空态 CTA 仍工作（R9 / ⑧）
// ════════════════════════════════════════════════════════════════════════════
test('T7 负对照①：空态 CTA 仍工作（零网络）', async ({ page }) => {
  await bootApp(page);
  // 空态：/api/projects 返回空名单 ⇒ .team-empty + CTA
  await page.route('**/api/projects', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: [] }) }));
  let apiCalls = 0;
  page.on('request', (req) => { if (req.url().includes('/api/')) apiCalls += 1; });

  await page.click('#projects-btn');
  await page.waitForSelector('[data-projects-empty-cta]', { timeout: 8000 });
  const callsBefore = apiCalls;
  await page.click('[data-projects-empty-cta]');
  await page.waitForTimeout(200);
  const after = await page.evaluate(() => {
    const inp = document.getElementById('input');
    return { value: inp?.value ?? null, activeIsInput: document.activeElement === inp };
  });

  write('46-T7-empty-cta.json', { callsBefore, apiCalls, after });
  expect(after.value, 'R9：空态 CTA 写入既有文案（行为逐字不变）').toBe('帮我创建一个项目');
  expect(after.activeIsInput, 'R9：空态 CTA 后焦点在 #input').toBe(true);
  expect(apiCalls - callsBefore, 'R9：点击后零新增网络请求').toBe(0);
});

// ════════════════════════════════════════════════════════════════════════════
// T8 · 剩余负对照：零新端点 / reduced-motion（R2 / R15）
// ════════════════════════════════════════════════════════════════════════════
test('T8 零新端点 + reduced-motion 降级', async ({ page }) => {
  // 🔴 本用例的提交段被作者 2026-09-24 裁定 D2(b) **取代**：旧语义「提交 = 本地写值 +
  //    聚焦（零网络）」⇒ 新语义「提交 = 发一帧 WS projectCreate 直连创建」。
  //    故判据从「零非 GET API 请求」升级为「**零 /api/ 写请求**（直连面走 WS，不经 REST）」
  //    + 「确实发出 projectCreate 帧」。层关闭改由应答驱动（旧语义靠提交自身关层，
  //    会把后续点击挡住 —— 这正是 T8 旧版 120s 超时的原因）。
  const h = await bootApp(page);
  await openProjects(page);

  // R2（取代）：提交全链不落 /api/ 写请求 —— 直连创建走 WS 命令面，不经新 REST 端点
  let apiWrites = 0;
  const apiWriteUrls = [];
  page.on('request', (req) => {
    if (req.url().includes('/api/') && req.method() !== 'GET') { apiWrites += 1; apiWriteUrls.push(req.method() + ' ' + req.url()); }
  });
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  await pickPath(page);
  await page.evaluate(() => {
    const ta = document.querySelector('.proj-create-desc');
    ta.value = '零端点验证';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.click('.proj-create-overlay .wsp-pick');
  await page.waitForTimeout(250);
  const framesAfterSubmit = h.projectCreateFrames().length;
  // 注入应答把层收干净 ⇒ 后续步骤不被残留遮罩挡住（取代前的失败根因）
  h.reply({ type: 'projectCreateResult', ok: true, message: 'ok' });
  await page.waitForTimeout(300);
  const layerClosed = await page.evaluate(() => !document.querySelector('.proj-create-overlay'));

  // R15：reduced-motion 下弹层无 ≥0.05s 动画
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.click('.proj-create-fab');
  await page.waitForSelector('.proj-create-overlay', { timeout: 4000 });
  const rm = await page.evaluate(() => {
    const panel = document.querySelector('.proj-create-overlay .wsp-panel');
    const fab = document.querySelector('.proj-create-fab');
    const cs = getComputedStyle(panel);
    const fcs = getComputedStyle(fab);
    return {
      panelAnimDur: cs.animationDuration,
      panelAnimName: cs.animationName,
      fabTransitionDur: fcs.transitionDuration,
    };
  });
  await page.keyboard.press('Escape');
  await page.emulateMedia({ reducedMotion: null });

  write('47-T8-no-endpoint-and-reduced-motion.json', {
    apiWrites, apiWriteUrls, framesAfterSubmit, layerClosed, rm,
  });
  // 🔴 唯一允许的非 GET /api/ 请求 = `PUT /api/canvas-tabs` —— canvas.js:1372 的
  //    **既有**标签页持久化（防抖写），与本批无关、非本批引入（改前红基线 `21-red-t8-rerun.log`
  //    已实测同一读数，见 `23-red-t8-attrib.log`）。除此之外必须零写请求 ⇒ 零新端点。
  const unexpected = apiWriteUrls.filter((u) => !/^PUT \S+\/api\/canvas-tabs$/.test(u));
  expect(unexpected, 'R2 取代：除既有 canvas-tabs 防抖持久化外，零 /api/ 写请求（零新端点）').toEqual([]);
  expect(framesAfterSubmit, 'R2 取代：提交确实发出 projectCreate 帧').toBeGreaterThan(0);
  expect(layerClosed, 'R2 取代：应答 ok:true ⇒ 层关闭（供后续步骤继续）').toBe(true);
  const durs = rm.panelAnimDur.split(',').map((s) => parseFloat(s));
  expect(durs.every((v) => v <= 0.01), `R15：弹层动画时长 ≤ 0.01s（实测 ${rm.panelAnimDur}）`).toBe(true);
});

// ════════════════════════════════════════════════════════════════════════════
// T9 · 让位形态的反悔判据（分发器补充 ①）：`data-type` 在**刷新后仍恒定**
// harness = 真 reload（不是重新 openTab），走 canvas-tab-restore 恢复路径。
// ════════════════════════════════════════════════════════════════════════════
test('T9 反悔判据：data-type 刷新后恒定 + 让位规则跨刷新仍生效', async ({ page }) => {
  await bootApp(page);
  await openProjects(page);

  const before = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane[data-type="projects"]');
    const scroll = document.querySelector('.team-scroll');
    return {
      paneExists: !!pane,
      dataType: pane?.getAttribute('data-type') ?? null,
      paddingBottom: scroll ? getComputedStyle(scroll).paddingBottom : null,
      fab: !!document.querySelector('.proj-create-fab'),
    };
  });

  // 真刷新：canvas.js 持久化/恢复 `t.type` ⇒ pane 重建后 data-type 必须仍在
  await page.reload();
  await page.waitForTimeout(1200);
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 15000 }).catch(() => {});
  // 恢复路径 = canvas-tab-restore ⇒ openProjectTab ⇒ ensureScroll
  await page.evaluate(async () => { (await import('/js/projectTab.js')).openProjectsTab(); });
  await page.waitForTimeout(800);

  const after = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane[data-type="projects"]');
    const scroll = document.querySelector('.team-scroll');
    return {
      paneExists: !!pane,
      dataType: pane?.getAttribute('data-type') ?? null,
      paddingBottom: scroll ? getComputedStyle(scroll).paddingBottom : null,
      fab: !!document.querySelector('.proj-create-fab'),
      fabCount: document.querySelectorAll('.proj-create-fab').length,
      staleKey: localStorage.getItem('nebflow_tabs') ? 'tabs-cached' : 'no-cache',
    };
  });

  write('48-T9-datatype-reload-stability.json', { before, after });
  expect(before.dataType, '刷新前 pane data-type=projects').toBe('projects');
  expect(before.paddingBottom, '刷新前让位生效 72px').toBe('72px');
  expect(after.dataType, '🔴 反悔判据：刷新后 data-type 仍为 projects（不恒定则须改回新类形态）').toBe('projects');
  expect(after.paddingBottom, '刷新后让位规则仍生效 72px').toBe('72px');
  expect(after.fabCount, '刷新后「+」钮回归且恰 1 枚').toBe(1);
});
