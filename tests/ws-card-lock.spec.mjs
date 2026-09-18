// ws-card-lock.spec.mjs — 共用卡层「已决 ⇒ 禁互动」真渲染验收（2026-09-18 作者报单）。
//
// 报单（逐字要点）：「面板在『已取消』或『已完成选择』之后仍可互动（选项还能点）
// ——预期：完成后应置灰、不可互动。」作者裁定：缺陷在共用卡组件层 ⇒ 修复必须落共用层，
// 让全部已决卡统一置灰禁点。
//
// 被测物 = 真实生产模块（无 stub、无打包）+ 真实 CSS，以临时静态服务（随机高位端口，
// never the 8080 host）载入 worktree 内真实源码；点击 = 真实鼠标输入事件（low-level），
// 另附程序化 .click() 复核。读数 = 出站帧 / 弹窗 DOM / 全文档 DOM 变更（MutationObserver）
// / 计算样式 / 可交互件清单。
//
// 双向红绿钉：LABEL=red 时断言方向反转（要求「可点复现」= 缺陷在场），LABEL=green 时
// 要求零动作。两向读数各落 .nebflow/evidence/20260918_wslock-impl/readings-<LABEL>.json。
//
// Run:
//   npx playwright test tests/ws-card-lock.spec.mjs                       # 绿向
//   LABEL=red npx playwright test tests/ws-card-lock.spec.mjs             # 红向（回退修法后）

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/ws-card-lock/harness.html';
const EVID = path.join(REPO_ROOT, '.nebflow', 'evidence', '20260918_wslock-impl');
const LABEL = process.env.LABEL ?? 'green';
const SHOT_DIR = path.join(EVID, `shots-${LABEL}`);
/** 红向（回退修法）时断言反转：要求缺陷在场（可点复现）。 */
const EXPECT_DEFECT = LABEL === 'red';

let port;
const servers = [];
const READINGS = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
    srv.on('error', reject);
  });
}
function startStaticServer(dir) {
  return spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', dir], { stdio: 'ignore' });
}
async function waitUntilUp(url, tries = 80) {
  for (let i = 0; i < tries; i++) {
    try { const res = await fetch(url); if (res.ok) return; } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(EVID, { recursive: true });
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  servers.push(startStaticServer(REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});
test.afterAll(() => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

let readStart = 0;
test.beforeEach(() => { readStart = READINGS.length; });

/** 读数落盘：每测一件（抗 worker 重启/单测失败）+ 全量合并件（跨重启累积）。 */
test.afterEach(({}, testInfo) => {
  const own = READINGS.slice(readStart);
  if (!own.length) return;
  const safe = testInfo.title.replace(/[^A-Za-z0-9]+/g, '_').slice(0, 60);
  fs.writeFileSync(path.join(EVID, `readings-${LABEL}-${safe}.json`),
    JSON.stringify({ label: LABEL, test: testInfo.title, readings: own }, null, 2) + '\n');
  const allPath = path.join(EVID, `readings-${LABEL}-all.json`);
  let all = { label: LABEL, expectDefect: EXPECT_DEFECT, tests: [] };
  try { all = JSON.parse(fs.readFileSync(allPath, 'utf8')); } catch { /* first write */ }
  const entry = { test: testInfo.title, readings: own };
  all.tests = all.tests.filter((t) => t.test !== testInfo.title).concat([entry]);
  all.generatedAt = new Date().toISOString();
  fs.writeFileSync(allPath, JSON.stringify(all, null, 2) + '\n');
});

async function newPage(browser, colorScheme = 'dark') {
  const context = await browser.newContext({ locale: 'zh-CN', colorScheme, viewport: { width: 900, height: 1100 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 20000 });
  page.__errors = errors;
  return page;
}

const TARGET = '.option-box .ws-pick-target';
const BLANK = '.option-box .option-q';   // 非控件区（卡片空白区入口的代理点）

async function pointOf(page, sel) {
  const box = await page.locator(sel).first().boundingBox();
  if (!box) throw new Error(`no bounding box for ${sel}`);
  return { x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2) };
}

/**
 * 单入口探针：hover 读数 → arm → 真实鼠标点击 → collect → 程序化 .click() 复核。
 * @returns {Promise<object>} 读数记录（同时 push 进 READINGS）
 */
async function probeEntry(page, { card, entry, point, programmaticSel, styleSel }) {
  const rest = { x: 8, y: 8 };
  await page.mouse.move(rest.x, rest.y);
  const stylesRest = await page.evaluate((s) => window.__styles(s), styleSel);
  await page.mouse.move(point.x, point.y);          // hover（先 hover 再 arm：隔离点击自身的 DOM 影响）
  await page.waitForTimeout(120);
  const stylesHover = await page.evaluate((s) => window.__styles(s), styleSel);
  const before = await page.evaluate(() => window.__arm());
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForTimeout(400);
  const after = await page.evaluate(() => window.__collect());
  const inventory = await page.evaluate(() => window.__inventory());
  const rec = { card, entry, stylesRest, stylesHover, before, after, inventory };

  if (programmaticSel) {
    await page.evaluate(() => window.__arm());
    await page.evaluate((s) => { document.querySelector(s).click(); }, programmaticSel);
    await page.waitForTimeout(300);
    rec.programmatic = await page.evaluate(() => window.__collect());
  }
  READINGS.push(rec);
  return rec;
}

/** 零动作三连（判据①）：无弹窗 / 无新增出站帧 / 无 DOM 变更。 */
function expectZeroAction(rec) {
  expect(rec.after.overlayDelta, 'wsp overlay delta').toBe(0);
  expect(rec.after.newFrames, 'new outbound frames').toEqual([]);
  expect(rec.after.cardHtmlChanged, 'card html changed').toBe(false);
  expect(rec.after.mutations, 'document mutations').toEqual([]);
  if (rec.programmatic) {
    expect(rec.programmatic.overlayDelta, 'programmatic overlay delta').toBe(0);
    expect(rec.programmatic.newFrames, 'programmatic frames').toEqual([]);
    expect(rec.programmatic.mutations, 'programmatic mutations').toEqual([]);
  }
}

/** 红向对照：缺陷在场（点击开路 = 打开应用内浏览器 + 发 wsBrowse.list）。 */
function expectDefectAction(rec) {
  expect(rec.after.overlayDelta, 'wsp overlay delta (defect)').toBe(1);
  expect(rec.after.newFrames.filter((f) => f.type === 'wsBrowse.list').length, 'wsBrowse.list (defect)').toBe(1);
  expect(rec.after.mutations.length, 'document mutations (defect)').toBeGreaterThan(0);
}

/** 已决卡的可交互件必须全部交出交互（判据①/②/⑤ 的机械读数）。 */
function expectLockedInventory(rec) {
  const inv = rec.inventory ?? [];
  expect(inv.length, 'lockable inventory size').toBeGreaterThan(0);
  for (const el of inv) {
    if (EXPECT_DEFECT) {
      // 红向（回退修法）只钉既有 disabled 面；aria-disabled / tabindex / cursor 是本批
      // 新增面 —— 红向在场即是差异读数（下面 green 支才断言）。
      expect(el.disabled, `${el.el} disabled (pre-fix)`).toBe(true);
    } else {
      expect(el.disabled || el.ariaDisabled === 'true', `${el.el} disabled/aria-disabled`).toBeTruthy();
      expect(el.ariaDisabled, `${el.el} aria-disabled`).toBe('true');
      expect(el.tabIndex, `${el.el} tabindex`).toBe(-1);
      expect(el.cursor, `${el.el} cursor`).toBe('default');
    }
  }
}

// ============================================================
// T1 — pending 回归：两入口均活性（零放宽的对照面）
// ============================================================
test('T1 pending 卡两入口均活性（目标件 / 卡片空白区）', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderDir());
  const rec = { card: 'dirPicker', entry: 'pending-liveness' };

  rec.targetStyle = await page.evaluate((s) => window.__styles(s), TARGET);
  expect(rec.targetStyle.disabled).toBe(false);
  expect(rec.targetStyle.ariaDisabled).toBe(null);
  expect(rec.targetStyle.cursor).toBe('pointer');
  expect(rec.targetStyle.opacity).toBe('1');

  // 入口①：真实鼠标点目标件 → 应用内浏览器开 + wsBrowse.list 出站帧
  await page.evaluate(() => window.__arm());
  await page.locator(TARGET).click();
  await page.waitForTimeout(300);
  const a1 = await page.evaluate(() => window.__collect());
  rec.entry1 = a1;
  expect(a1.overlayDelta).toBe(1);
  expect(a1.newFrames.map((f) => f.type)).toEqual(['wsBrowse.list']);

  // 关闭弹窗回待选态
  await page.locator('.wsp-cancel').click();
  await page.waitForTimeout(200);
  expect(await page.locator('.wsp-overlay').count()).toBe(0);

  // 入口②：真实鼠标点卡片空白区（问题行）→ 同样开弹窗 + 发帧
  const blank = await pointOf(page, BLANK);
  await page.evaluate(() => window.__arm());
  await page.mouse.move(blank.x, blank.y);
  await page.mouse.down(); await page.mouse.up();
  await page.waitForTimeout(300);
  const a2 = await page.evaluate(() => window.__collect());
  rec.entry2 = a2;
  expect(a2.overlayDelta, 'blank-area entry opens picker (pending)').toBe(1);
  expect(a2.newFrames.map((f) => f.type)).toEqual(['wsBrowse.list']);

  await page.locator('.wsp-cancel').click();
  await page.screenshot({ path: path.join(SHOT_DIR, 't1-pending-card.png'), clip: (await page.evaluate(() => window.__boxRect())).box });
  rec.pageErrors = page.__errors;
  READINGS.push(rec);
});

// ============================================================
// T2 — 「已取消」态：两入口零动作 + 置灰读数
// ============================================================
test('T2 已取消态：两入口零动作 + 置灰', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderDir());
  const resolved = await page.evaluate(() => window.__resolveCancel());
  expect(resolved.className).toContain('resolved');
  await page.screenshot({ path: path.join(SHOT_DIR, 't2-cancel-resolved.png'), clip: (await page.evaluate(() => window.__boxRect())).box });

  const target = await pointOf(page, TARGET);
  const r1 = await probeEntry(page, { card: 'dirPicker', entry: 'cancel:target', point: target, programmaticSel: TARGET, styleSel: TARGET });
  const blank = await pointOf(page, BLANK);
  const r2 = await probeEntry(page, { card: 'dirPicker', entry: 'cancel:blank', point: blank, programmaticSel: BLANK, styleSel: TARGET });

  if (EXPECT_DEFECT) { expectDefectAction(r1); expectDefectAction(r2); }
  else {
    expectZeroAction(r1); expectZeroAction(r2);
    expectLockedInventory(r1);
    expect(r1.stylesRest.opacity).toBe('0.5');
    expect(r1.stylesHover.opacity).toBe('0.5');           // hover 不回弹
    expect(r1.stylesHover.boxShadow).toBe('none');        // hover 高亮收敛
    expect(r1.stylesHover.transform).toBe('none');        // hover 位移收敛
    expect(r1.stylesRest.cursor).toBe('default');
    expect(r1.stylesRest.pointerEvents).not.toBe('none'); // 🔴 未用整卡 pointer-events:none
  }
  READINGS.push({ card: 'dirPicker', entry: 'cancel-style-summary', rest: r1.stylesRest, hover: r1.stylesHover, pageErrors: page.__errors });
});

// ============================================================
// T3 — 「已完成选择」态：两入口零动作 + 置灰读数
// ============================================================
test('T3 已完成选择态：两入口零动作 + 置灰', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderDir());
  const resolved = await page.evaluate(() => window.__resolvePickComplete());
  expect(resolved.ok, JSON.stringify(resolved)).toBe(true);
  expect(resolved.className).toContain('resolved');
  expect(resolved.overlaysLeft).toBe(0);
  await page.screenshot({ path: path.join(SHOT_DIR, 't3-pick-resolved.png'), clip: (await page.evaluate(() => window.__boxRect())).box });

  const target = await pointOf(page, TARGET);
  const r1 = await probeEntry(page, { card: 'dirPicker', entry: 'pickComplete:target', point: target, programmaticSel: TARGET, styleSel: TARGET });
  const blank = await pointOf(page, BLANK);
  const r2 = await probeEntry(page, { card: 'dirPicker', entry: 'pickComplete:blank', point: blank, programmaticSel: BLANK, styleSel: TARGET });

  if (EXPECT_DEFECT) { expectDefectAction(r1); expectDefectAction(r2); }
  else {
    expectZeroAction(r1); expectZeroAction(r2);
    expectLockedInventory(r1);
    expect(r1.stylesHover.opacity).toBe('0.5');
    expect(r1.stylesHover.boxShadow).toBe('none');
    expect(r1.stylesHover.transform).toBe('none');
  }
  READINGS.push({ card: 'dirPicker', entry: 'pickComplete-summary', resolved, rest: r1.stylesRest, hover: r1.stylesHover, pageErrors: page.__errors });
});

// ============================================================
// T4 — 引擎关闭态（askUserClosed → closeAskUserCard）零动作
// ============================================================
test('T4 引擎关闭态：两入口零动作', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderDir());
  const closed = await page.evaluate(() => window.__resolveEngineClose());
  expect(closed.ok).toBe(true);
  expect(closed.className).toContain('resolved');

  const target = await pointOf(page, TARGET);
  const r1 = await probeEntry(page, { card: 'dirPicker', entry: 'engineClosed:target', point: target, programmaticSel: TARGET, styleSel: TARGET });
  const blank = await pointOf(page, BLANK);
  const r2 = await probeEntry(page, { card: 'dirPicker', entry: 'engineClosed:blank', point: blank, programmaticSel: BLANK, styleSel: TARGET });

  if (EXPECT_DEFECT) { expectDefectAction(r1); expectDefectAction(r2); }
  else { expectZeroAction(r1); expectZeroAction(r2); expectLockedInventory(r1); }
  READINGS.push({ card: 'dirPicker', entry: 'engineClosed-summary', closed, pageErrors: page.__errors });
});

// ============================================================
// T5 — 历史回放孪生卡（renderAskUserHistory 两种记录态）
// ============================================================
test('T5 历史回放孪生卡：两入口零动作 + 置灰（answered / cancelled 两态）', async ({ browser }) => {
  for (const [stateName, answerText, ansRid] of [['answered', '/Users/e2e/ws-a', 'rid-hist-1'], ['cancelled', '__cancelled__', 'rid-hist-2']]) {
    const page = await newPage(browser);
    await page.evaluate(({ at }) => window.__renderHistory(at), { at: answerText });
    const boxClass = await page.evaluate(() => document.querySelector('.option-box').className);
    expect(boxClass, `${stateName} history twin resolved`).toContain('resolved');
    await page.screenshot({ path: path.join(SHOT_DIR, `t5-history-${stateName}.png`), clip: (await page.evaluate(() => window.__boxRect())).box });

    const target = await pointOf(page, TARGET);
    const r1 = await probeEntry(page, { card: 'dirPicker-history', entry: `history(${stateName}):target`, point: target, programmaticSel: TARGET, styleSel: TARGET });
    const blank = await pointOf(page, BLANK);
    const r2 = await probeEntry(page, { card: 'dirPicker-history', entry: `history(${stateName}):blank`, point: blank, programmaticSel: BLANK, styleSel: TARGET });

    // 回放卡的历史缺口：requestId 未持久化 ⇒ 修复前 startPick 靠「无 requestId」早退，
    // 动作面本来就零；差异在读「是否仍像可点」（inventory / cursor / tabindex）。
    expectZeroAction(r1); expectZeroAction(r2);
    if (EXPECT_DEFECT) {
      expect(r1.stylesRest.cursor, 'pre-fix: still looks clickable').toBe('pointer');
      expect(r1.stylesRest.disabled).toBe(false);
      expect(r1.stylesRest.tabIndex).toBe(0);
    } else {
      expectLockedInventory(r1);
      expect(r1.stylesRest.cursor).toBe('default');
      expect(r1.stylesRest.tabIndex).toBe(-1);
    }
    READINGS.push({ card: 'dirPicker-history', entry: `history(${stateName})-summary`, answerText, ansRid, rest: r1.stylesRest, pageErrors: page.__errors });
  }
});

// ============================================================
// T6 — askUser 选项盒（.option-btn 族）：确认 / 取消后零动作（两向都应零）
// ============================================================
test('T6 选项盒（.option-btn 族）已决后零动作', async ({ browser }) => {
  for (const mode of ['confirm', 'cancel']) {
    const page = await newPage(browser);
    await page.evaluate(() => window.__renderOpt());
    await page.evaluate((m) => {
      document.querySelectorAll('.option-box .option-btn')[0].click();      // 选中 A
      if (m === 'confirm') document.querySelector('.option-box .option-confirm').click();
      else document.querySelector('.option-box .option-cancel').click();
    }, mode);
    await page.waitForTimeout(150);
    const cls = await page.evaluate(() => document.querySelector('.option-box').className);
    expect(cls, `${mode}: option box resolved`).toContain('resolved');

    const btn = await pointOf(page, '.option-box .option-btn');
    const r = await probeEntry(page, { card: 'optionBox', entry: `optionBtn:${mode}`, point: btn, programmaticSel: '.option-box .option-btn', styleSel: '.option-box .option-btn' });
    expect(r.inventory.length, 'lockable inventory enumerated').toBeGreaterThanOrEqual(3);
    expectZeroAction(r);
    expectLockedInventory(r);
    const frames = await page.evaluate(() => window.__captured.map((f) => f.type));
    READINGS.push({ card: 'optionBox', entry: `optionBox-summary:${mode}`, resolvedClass: cls, frameTypes: frames, pageErrors: page.__errors });
  }
});

// ============================================================
// T7 — 权限卡（permission prompt）：作答即整行移除 ⇒ 零残留
// ============================================================
test('T7 权限卡已决后零残留（整行移除）', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderPerm());
  const before = await page.evaluate(() => ({
    boxes: document.querySelectorAll('.option-box').length,
    buttons: Array.from(document.querySelectorAll('.option-box .option-btn')).map((b) => b.disabled),
  }));
  await page.evaluate(() => {
    document.querySelectorAll('.option-box .option-btn')[0].click();   // Allow
    document.querySelector('.option-box .option-confirm').click();
  });
  await page.waitForTimeout(200);
  const after = await page.evaluate(() => ({
    boxes: document.querySelectorAll('.option-box').length,
    frames: window.__captured.map((f) => f.type),
    rows: document.querySelectorAll('.row.ai').length,
  }));
  expect(before.buttons.every((d) => d === false)).toBe(true);
  expect(after.boxes, 'permission card removed ⇒ no clickable residue').toBe(0);
  expect(after.frames).toContain('permissionAnswer');
  READINGS.push({ card: 'permission', entry: 'permission-resolved', before, after, pageErrors: page.__errors });
});

// ============================================================
// T8 — 卡级 capture 点击哨兵：已决后新增可点件自动纳入（判据③ 机制读数）
// ============================================================
test('T8 capture 哨兵覆盖「未来新增可点件」', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderDir());
  await page.evaluate(() => window.__resolveCancel());
  // 已决之后再插入一个门闸不认识的可点件（模拟未来新增入口）
  const injected = await page.evaluate(() => {
    const box = document.querySelector('.option-box');
    const b = document.createElement('button');
    b.className = 'nf-extra-probe';
    b.textContent = 'extra';
    window.__extraFired = false;
    b.addEventListener('click', () => { window.__extraFired = true; });
    box.appendChild(b);
    return true;
  });
  expect(injected).toBe(true);
  const p = await pointOf(page, '.nf-extra-probe');
  await page.evaluate(() => window.__arm());
  await page.mouse.move(p.x, p.y);
  await page.mouse.down(); await page.mouse.up();
  await page.waitForTimeout(150);
  const rawFired = await page.evaluate(() => window.__extraFired);
  await page.evaluate(() => { document.querySelector('.nf-extra-probe').click(); });
  await page.waitForTimeout(150);
  const progFired = await page.evaluate(() => window.__extraFired);
  const after = await page.evaluate(() => window.__collect());

  if (EXPECT_DEFECT) {
    expect(rawFired, 'pre-fix: injected clickable fires').toBe(true);
  } else {
    expect(rawFired, 'capture sentinel blocks injected clickable (real mouse)').toBe(false);
    expect(progFired, 'capture sentinel blocks injected clickable (programmatic)').toBe(false);
    expect(after.overlayDelta).toBe(0);
  }
  READINGS.push({ card: 'dirPicker', entry: 'capture-sentinel:injected', rawFired, progFired, after, pageErrors: page.__errors });
});

// ============================================================
// T9 — 可复制面 / 文本选中面（🔴 不许被禁互动连带杀死）
// ============================================================
test('T9 已决卡复制面与选中面仍可达（pending 同读数对照）', async ({ browser }) => {
  // (a) pending 选项盒：行级复制钮（R5 载荷）+ 卡内文本拖选
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderHistory('/Users/e2e/ws-a'));   // 行级 .duration-badge 复制钮（历史恢复行形态）
  const copyResolved = await page.evaluate(() => window.__copyProbe());
  expect(copyResolved.present).toBe(true);
  expect(copyResolved.disabled).toBe(false);
  expect(copyResolved.clip).toEqual(['askuser-copy-payload']);

  // 卡内答案行真实输入事件选中（双击选词，非程序化 Range）
  const ansRect = (await page.evaluate(() => window.__boxRect())).answer;
  await page.mouse.dblclick(ansRect.x + Math.min(24, ansRect.width / 2), ansRect.y + ansRect.height / 2);
  await page.waitForTimeout(100);
  const sel = await page.evaluate(() => String(window.getSelection()));
  expect(sel.length, 'resolved card text is still selectable').toBeGreaterThan(0);

  const boxStyles = await page.evaluate(() => {
    const b = document.querySelector('.option-box');
    const cs = getComputedStyle(b);
    return { pointerEvents: cs.pointerEvents, userSelect: cs.userSelect };
  });
  expect(boxStyles.pointerEvents, 'not wholesale pointer-events:none').not.toBe('none');

  // (b) pending 卡：同样的复制面读数为零回归基线
  const page2 = await newPage(browser);
  await page2.evaluate(() => window.__renderHistoryPending());
  const pendingCopy = await page2.evaluate(() => window.__copyProbe());
  const pendingStyles = await page2.evaluate(() => {
    const b = document.querySelector('.option-box');
    return { pointerEvents: getComputedStyle(b).pointerEvents, className: b.className };
  });
  READINGS.push({ card: 'cross-cutting', entry: 'copy-select-surface', copyResolved, selection: sel, boxStyles, pendingCopy, pendingStyles, pageErrors: page.__errors });
});

// ============================================================
// T10 — iframe 广播 `_nfAskState`（画布内嵌页消费面）不回归
// ============================================================
test('T10 已决动作仍广播 _nfAskState（形状不变）', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderOpt());
  await page.evaluate(() => {
    document.querySelectorAll('.option-box .option-btn')[0].click();
    document.querySelector('.option-box .option-confirm').click();
  });
  await page.waitForTimeout(200);
  const nf = await page.evaluate(() => window.__nfAskStateRaw());
  expect(nf.length).toBe(1);
  expect(nf[0]).toEqual({ sessionId: 'harness-lock-session', requestId: 'req-lock-1', answered: true });
  READINGS.push({ card: 'canvasBroadcast', entry: '_nfAskState', nf, pageErrors: page.__errors });
});

// ============================================================
// T11 — 画布答题通道（_nfAskAnswer 入站）⇒ 已决卡锁定
// ============================================================
test('T11 画布答题通道已决后卡面锁定', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderOpt());
  await page.evaluate(() => {
    window.postMessage({ _nfAskAnswer: { sessionId: 'harness-lock-session', requestId: 'req-lock-1', questionIndex: 0, answer: 'A' } }, '*');
  });
  await page.waitForTimeout(250);
  const cls = await page.evaluate(() => document.querySelector('.option-box').className);
  expect(cls, 'canvas answer path resolves the card').toContain('resolved');
  const btn = await pointOf(page, '.option-box .option-btn');
  const r = await probeEntry(page, { card: 'canvasChannel', entry: 'canvasAnswer:optionBtn', point: btn, programmaticSel: '.option-box .option-btn', styleSel: '.option-box .option-btn' });
  expectZeroAction(r);
  expectLockedInventory(r);
  READINGS.push({ card: 'canvasChannel', entry: 'canvasAnswer-summary', resolvedClass: cls, pageErrors: page.__errors });
});

// ============================================================
// T12 — pending 可作答回归（点选 / 自输入 / 确认帧载荷）
// ============================================================
test('T12 pending 卡照旧可作答（点选 + 自由输入 + 确认帧）', async ({ browser }) => {
  const page = await newPage(browser);
  await page.evaluate(() => window.__renderOpt());
  await page.locator('.option-box .option-btn').first().click();
  const confirmEnabled = await page.evaluate(() => !document.querySelector('.option-box .option-confirm').disabled);
  await page.locator('.option-box .option-confirm').click();
  await page.waitForTimeout(150);
  const frames1 = await page.evaluate(() => window.__captured.filter((f) => f.type === 'askUserAnswer'));

  const page2 = await newPage(browser);
  await page2.evaluate(() => window.__renderOpt());
  await page2.locator('.option-box .option-btn').last().click();   // 「其他…」→ 揭示自由输入面
  await page2.waitForTimeout(100);
  await page2.locator('.option-box .option-custom-input').fill('自定义答案');
  await page2.waitForTimeout(100);
  const confirmEnabled2 = await page2.evaluate(() => !document.querySelector('.option-box .option-confirm').disabled);
  await page2.locator('.option-box .option-confirm').click();
  await page2.waitForTimeout(150);
  const frames2 = await page2.evaluate(() => window.__captured.filter((f) => f.type === 'askUserAnswer'));

  expect(confirmEnabled).toBe(true);
  expect(frames1).toEqual([{ type: 'askUserAnswer', sessionId: 'harness-lock-session', answers: ['A'], requestId: 'req-lock-1' }]);
  expect(confirmEnabled2).toBe(true);
  expect(frames2).toEqual([{ type: 'askUserAnswer', sessionId: 'harness-lock-session', answers: ['自定义答案'], requestId: 'req-lock-1' }]);
  READINGS.push({ card: 'optionBox', entry: 'pending-answer-regression', confirmEnabled, frames1, confirmEnabled2, frames2, pageErrors: page.__errors });
});
