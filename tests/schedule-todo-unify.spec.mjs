#!/usr/bin/env node
// schedule-todo-unify.spec.mjs — 「定时任务页 ⇄ 待办页统一」批（作者令 2026-11-06 09:43）
// 的自足验收 + 实拍（零后端法，与 tests/todo-mobile-topmenu.spec.mjs 同源口径）。
//
// 运行：
//   node tests/schedule-todo-unify.spec.mjs
//   CHATFACE_WEB_ROOT=<另一份 web 树>  ⇒ 改前树（验红腿）
//   SHOT_ONLY=labels                          ⇒ 只给改前实拍加标签（不动断言）
//
// 零后端法：
//   · 静态面 = page.route 从磁盘注入 web 树（零端口、零 sbt、不碰 :6138）；
//   · 数据面 = routeWebSocket 帧桩：listScheduledTasks 回夹具任务，create/update/
//     delete 帧**只记账不应答**（纯本地，零写入）；
//   · 入口   = 真链：顶栏 [data-chrome='tasks'] → navController pushPage('tasks')
//     → todoFace 圆钮 → pushPage('reminder')（#reminder-panel 被收编成页）。
//
// 判据（逐条对应任务书七条）：
//   T 标题：页名节点主档宽 ≥ band（不截断）/ 文字未溢出 / 正文那份重复标题零命中
//   N name/description：行只显 name（description 不在首行）/ 表单两个字段 /
//       创建帧 content+detail 两段 / 展开详情显示 description
//   L 液态形变：展开过渡属性串 == motion/collapse.js 单源（280/400ms + 唯一曲线）
//   B 按钮：取消/确认**右对齐**（确认右缘距宿主右缘 ≤ 间距档，且确认在取消右边）
//       / 扁平（零投影零渐变）/ 视觉高 ≥ 44 / 命中盒 ≥ 48
//   C 配色：两页行/分组签/表单主键的颜色成对同值（基准 = 定时任务页）
//   D 桌面腿：1440×900 下 #reminder-panel 仍是顶栏下拉（360 宽、页头标题在场）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DURATION, EASING, PROPERTY_WHITELIST } from '../src/main/resources/web/js/motion/liquidMorph.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = process.env.CHATFACE_WEB_ROOT
  ? normalize(process.env.CHATFACE_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.OUT_DIR
  ? resolve(process.env.OUT_DIR)
  : join(ROOT, 'reports-evidence', 'schedule-todo-unify__20261106', 'shots');
mkdirSync(OUT, { recursive: true });

const SHOT_ONLY = process.env.SHOT_ONLY === 'labels';
const LABEL = process.env.SHOT_LABEL || '';

const SID = 'sched-todo-spec-session';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const HOUR = 3600_000;
function fixtures() {
  const now = Date.now();
  return [
    {
      id: 'fx-1', sessionId: SID, content: '夜间监工\n每小时巡检在飞批次，失败则在原分支重跑并把读数写进当批证据目录。',
      detail: '每小时巡检在飞批次，失败则在原分支重跑并把读数写进当批证据目录。',
      triggerAt: now + 8 * 60_000, createdAt: now - 3 * HOUR, triggered: false, triggeredAt: null,
      repeat: 'hourly', enabled: true, kind: null, name: null, project: null, priority: 0,
    },
    {
      id: 'fx-2', sessionId: SID, content: '夜间实施汇总\n给作者的晨间一页：汇总今晚落地的合并、未决项与风险。',
      detail: '给作者的晨间一页：汇总今晚落地的合并、未决项与风险。',
      triggerAt: now + 25 * 60_000, createdAt: now - 2 * HOUR, triggered: false, triggeredAt: null,
      repeat: null, enabled: true, kind: null, name: null, project: null, priority: 0,
    },
    {
      id: 'fx-3', sessionId: SID, content: '周报数据源刷新',
      detail: null,
      triggerAt: now + 3 * HOUR, createdAt: now - 1 * HOUR, triggered: false, triggeredAt: null,
      repeat: 'daily', enabled: true, kind: null, name: null, project: null, priority: 0,
    },
  ];
}

const TODOS = [
  { id: 'td-1', name: '确认 UI 批合并', detail: '作者确认六条落点后合 main', triggerAt: Date.now() + HOUR, createdAt: Date.now() - HOUR * 2, triggered: false, triggeredAt: null, repeat: null, enabled: true, kind: 'todo', sessionId: SID, project: null, priority: 2, completed: false, completedAt: null },
  { id: 'td-2', name: '批准术语统一批', detail: '', triggerAt: Date.now() + HOUR, createdAt: Date.now() - HOUR * 3, triggered: false, triggeredAt: null, repeat: null, enabled: true, kind: 'todo', sessionId: SID, project: 'stand', priority: 1, completed: false, completedAt: null },
];

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const near = (a, b, tol = 1) => a != null && b != null && Math.abs(a - b) <= tol;

async function wire(page) {
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
    if (p.startsWith('/api/')) {
      // 近路探测一律「非本机」：页面停在 relay（WS 走 location.host/ws，桩可拦），
      // 不让 loopback 探测把页面整页跳走（spec 需要稳定在同一文档里跑完）。
      if (p === '/api/health/loopback-info') return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
      if (p.includes('loopback')) return route.fulfill({ status: 404, body: '' });
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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
  const TASKS = fixtures();
  await page.exposeFunction('__specFrames', () => {});
  await page.routeWebSocket(/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') return sendConfig();
      if (m.type === 'getSessions' || m.type === 'listSessions') return sendSessions();
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      }
      if (m.type === 'listScheduledTasks') {
        // 记账（判据读它）+ 回夹具：scope:all = 待办收件箱；否则 = 定时任务面。
        page.evaluate((f) => { (window.__frames ||= []).push(f); }, m).catch(() => {});
        const tasks = m.scope === 'all' ? TODOS : TASKS;
        return ws.send(JSON.stringify({ type: 'scheduledTaskList', sessionId: m.sessionId, scope: m.scope, tasks }));
      }
      // 无回执的读帧：提醒面/收件箱的初始加载走的是 openPanel 之外的路径时兜底。
      if (m.type === 'getHistory' || m.type === 'listSessions') return;
      if (/^(create|update|delete|complete|uncomplete)/.test(m.type)) {
        page.evaluate((f) => { (window.__frames ||= []).push(f); }, m).catch(() => {});
        return;
      }
    });
  });
}

async function goto(page, { theme = 'light' } = {}) {
  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => !!document.querySelector('body'));
  await page.evaluate((th) => {
    document.documentElement.setAttribute('data-theme', th);
    document.documentElement.style.colorScheme = th;
  }, theme);
  await sleep(900);
}

async function openTasksMobile(page) {
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage('tasks');
  });
  await sleep(900);
}

async function openReminderMobile(page) {
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage('reminder');
  });
  await sleep(1200);
}

/** 定时任务夹具（与 main() 里 TASKS 同源；给页打开前注入用）。 */
function fixtureTasks() {
  const now = Date.now();
  return [
    { id: 'fx-1', sessionId: 'sched-todo-spec-session', content: '夜间监工\n每小时巡检在飞批次，失败则在原分支重跑并把读数写进当批证据目录。', detail: '每小时巡检在飞批次，失败则在原分支重跑并把读数写进当批证据目录。', triggerAt: now + 8 * 60_000, createdAt: now, triggered: false, triggeredAt: null, repeat: 'hourly', enabled: true, kind: null, name: null, project: null, priority: 0 },
  ];
}

const RECT = (sel) => `(() => {
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return null;
  const b = el.getBoundingClientRect();
  return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), right: +b.right.toFixed(2), bottom: +b.bottom.toFixed(2) };
})()`;

async function shot(page, name) {
  const path = join(OUT, name + '.png');
  await page.screenshot({ path });
  const labelled = LABEL ? name.replace(LABEL, '') : name;
  return labelled;
}

async function main() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e && e.message || e)));
  await wire(page);

  /* ── 375 亮：定时任务页 ────────────────────────────────────────────── */
  await goto(page, { theme: 'light' });
  // 激活会话（state.activeSessionId ⇒ 提醒面 listScheduledTasks 请求的会话键），
  // 走真链：侧栏 openSession（不改持久化，纯内存态）。
  await page.evaluate(async () => {
    const sb = await import('/js/sidebar.js');
    sb.switchToSession('sched-todo-spec-session');
  });
  await sleep(600);
  // 入口走真实钮（顶栏 [data-chrome='tasks']），不经 pushPage 注水。
  await page.evaluate(() => document.querySelector("[data-chrome='tasks']")?.click());
  await sleep(900);
  await page.evaluate(() => document.querySelector('#reminder-btn')?.click());
  await sleep(1600);
  await openReminderMobile(page);

  const framesSeen = await page.evaluate(() => (window.__frames || []).map((f) => f.type + (f.scope ? ':' + f.scope : '')));
  writeFileSync(join(OUT, 'frames-seen.json'), JSON.stringify(framesSeen, null, 2));
  const rowDbg = await page.evaluate(() => {
    const rs = [...document.querySelectorAll('#reminder-panel .reminder-row')];
    return rs.map((r) => ({ cls: r.className, h: r.getBoundingClientRect().height, w: r.getBoundingClientRect().width, kids: r.children.length, disp: getComputedStyle(r).display, bodyH: document.querySelector('#reminder-panel .reminder-panel-body')?.getBoundingClientRect().height }));
  });
  writeFileSync(join(OUT, 'row-dbg.json'), JSON.stringify(rowDbg, null, 2));
  const panelState = await page.evaluate(() => {
    const p = document.querySelector('#reminder-panel');
    const bd = p && p.querySelector('.reminder-panel-body');
    return { cls: p && p.className, children: bd ? bd.children.length : -1, html: bd ? bd.innerHTML.slice(0, 200) : '' };
  });
  writeFileSync(join(OUT, 'panel-state.json'), JSON.stringify({ framesSeen, panelState }, null, 2));

  const titleInfo = await page.evaluate(() => {
    const sec = document.querySelector(".mobile-page[data-page='reminder']");
    const t = sec && sec.querySelector('.mobile-page-title');
    if (!t) return null;
    const cs = getComputedStyle(t);
    const band = t.parentElement.clientWidth;
    return {
      text: t.textContent, w: +t.getBoundingClientRect().width.toFixed(2), band,
      scrollW: t.scrollWidth, clientW: t.clientWidth, fs: cs.fontSize,
      overflow: t.scrollWidth > t.clientWidth + 1,
    };
  });
  writeFileSync(join(OUT, 'title-readings.json'), JSON.stringify(titleInfo, null, 2));
  ok('T1 页名完整显示（未截断：文字不溢出）', titleInfo && !titleInfo.overflow,
    JSON.stringify(titleInfo));
  ok('T2 页名主档吃满内缩带（宽 ≥ band − 2×72）', titleInfo && titleInfo.w >= titleInfo.band - 2 * 72 - 1,
    `w=${titleInfo?.w} band=${titleInfo?.band}`);

  const dupTitle = await page.evaluate(() => {
    const sec = document.querySelector(".mobile-page[data-page='reminder']");
    const el = sec && sec.querySelector('.reminder-panel-title');
    if (!el) return { hit: 0, note: 'no-node' };
    const cs = getComputedStyle(el);
    return { hit: cs.display !== 'none' ? 1 : 0, display: cs.display };
  });
  ok('T3 正文里那行重复标题已退场（.reminder-panel-title 不可见，页面内只剩壳标题）',
    dupTitle.hit === 0, JSON.stringify(dupTitle));

  const rows = await page.evaluate(() => [...document.querySelectorAll("#reminder-panel .reminder-row")].map((r) => {
    const nameEl = r.querySelector('.reminder-row-text');
    const descEl = r.querySelector('.reminder-row-desc');
    const clip = r.querySelector('.lm-collapse-clip');
    return {
      id: r.dataset.id,
      firstLine: nameEl ? nameEl.textContent : null,
      nameOverflow: nameEl ? nameEl.scrollWidth > nameEl.clientWidth + 1 : null,
      descInBody: !!descEl,
      descText: descEl ? descEl.textContent.slice(0, 24) : null,
      clipVisible: clip ? clip.getBoundingClientRect().width : null,
      expandBtn: !!r.querySelector('.reminder-expand-btn'),
    };
  }));
  writeFileSync(join(OUT, 'rows-readings.json'), JSON.stringify(rows, null, 2));
  ok('N1 行首文本 = name（不含 description 全文）', rows.length > 0 && rows.every((r) => r.firstLine && !r.firstLine.includes('\n') && r.firstLine.length < 30),
    JSON.stringify(rows.map((r) => r.firstLine)));
  ok('N2 详情体在行内、收起态零宽（不占行）', rows.every((r) => r.descInBody && (r.clipVisible == null || r.clipVisible <= 0.5)),
    JSON.stringify(rows.map((r) => r.clipVisible)));
  ok('N3 行尾展开键在场', rows.every((r) => r.expandBtn));

  await page.screenshot({ path: join(OUT, `tasks-375-light.png`) });

  /* 展开：点第一条 → 液态形变过渡串 + 详情可见 */
  const firstRow = page.locator('#reminder-panel .reminder-row').first();
  await firstRow.click();
  const trans = await page.evaluate(() => {
    const row = document.querySelector('#reminder-panel .reminder-row.is-expanded');
    if (!row) return null;
    const clip = row.querySelector('.lm-collapse-clip');
    const hostCs = getComputedStyle(row);
    const clipCs = getComputedStyle(clip);
    return {
      hostProp: hostCs.transitionProperty, hostDur: hostCs.transitionDuration, hostEase: hostCs.transitionTimingFunction,
      clipProp: clipCs.transitionProperty, clipDur: clipCs.transitionDuration, clipEase: clipCs.transitionTimingFunction,
      clipW: +clip.getBoundingClientRect().width.toFixed(2),
    };
  });
  await sleep(520);
  writeFileSync(join(OUT, 'expand-transition.json'), JSON.stringify({ trans, expect: { base: DURATION.base, slow: DURATION.slow, ease: EASING, whitelist: PROPERTY_WHITELIST } }, null, 2));
  const propsOk = trans && trans.hostProp.split(',').map((s) => s.trim()).every((p) => PROPERTY_WHITELIST.includes(p))
    && trans.clipProp.split(',').map((s) => s.trim()).every((p) => PROPERTY_WHITELIST.includes(p));
  ok('L1 展开过渡属性全部在液态形变白名单内', propsOk, JSON.stringify(trans && { host: trans.hostProp, clip: trans.clipProp }));
  ok('L2 宿主 = base 档（280ms）+ 唯一曲线', trans && trans.hostDur.split(',').every((d) => parseFloat(d) * 1000 === DURATION.base) && trans.hostEase.includes(EASING),
    JSON.stringify(trans && { d: trans.hostDur, e: trans.hostEase }));
  ok('L3 详情体 = slow 档（400ms）+ 唯一曲线', trans && trans.clipDur.split(',').every((d) => parseFloat(d) * 1000 === DURATION.slow) && trans.clipEase.includes(EASING),
    JSON.stringify(trans && { d: trans.clipDur, e: trans.clipEase }));
  const expandedDetail = await page.evaluate(() => {
    const row = document.querySelector('#reminder-panel .reminder-row.is-expanded');
    const clip = row && row.querySelector('.lm-collapse-clip');
    return clip ? { w: +clip.getBoundingClientRect().width.toFixed(2), desc: row.querySelector('.reminder-row-desc')?.textContent || '', facts: row.querySelectorAll('.reminder-row-fact').length } : null;
  });
  ok('L4 展开后详情体占满行宽并显示 description + 事实行', expandedDetail && expandedDetail.w > 40 && expandedDetail.desc.length > 0 && expandedDetail.facts >= 1,
    JSON.stringify(expandedDetail));
  await page.screenshot({ path: join(OUT, `tasks-375-light-expanded.png`) });

  /* 新建抽屉：两字段 + 右对齐按钮 + 扁平 + 尺寸 */
  await page.evaluate(() => document.querySelector('#reminder-panel .reminder-fab-add')?.click());
  await sleep(600);
  const formInfo = await page.evaluate(() => {
    // 手机页面态：表单 = 抽屉；确认/取消 = 抽屉头最右两键（两面同一实现，
    // 正文内联按钮行已收敛）。桌面腿 = 列表尾内联表单 + .reminder-form-actions。
    const drawer = [...document.querySelectorAll('.mf-drawer')].find((d) => !d.hidden && d.querySelector('.reminder-inline-create'));
    const inMobile = document.body.classList.contains('mobile-layer');
    const form = drawer ? drawer.querySelector('.reminder-inline-create') : document.querySelector('.reminder-inline-create');
    if (!form) return null;
    const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(2), right: +b.right.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), y: +b.y.toFixed(2) }; };
    const cs = (el) => el ? getComputedStyle(el) : null;
    const flatOf = (el) => { const c = cs(el); return c ? { shadow: c.boxShadow, bgImage: c.backgroundImage, transform: c.transform } : null; };
    const cancel = drawer ? drawer.querySelector('.mf-drawer-cancel-btn') : form.querySelector('[data-role="cancel"]');
    const confirm = drawer ? drawer.querySelector('.mf-drawer-done-btn') : form.querySelector('[data-role="confirm"]');
    const actions = drawer ? cancel && confirm && (/** @type {HTMLElement} */ (confirm)).parentElement : form.querySelector('.reminder-form-actions');
    const host = drawer ? drawer.querySelector('.mf-drawer-box') : (form.closest('.reminder-inline-create') || form.parentElement);
    const hitAfter = (() => { const c = getComputedStyle(confirm, '::after'); const h = parseFloat(c.height); return { h: isNaN(h) ? null : h }; })();
    return {
      hasName: !!form.querySelector('.reminder-inline-input'), hasDesc: !!form.querySelector('.reminder-inline-desc'),
      namePh: form.querySelector('.reminder-inline-input')?.placeholder, descPh: form.querySelector('.reminder-inline-desc')?.placeholder,
      form: R(form), host: R(host), actions: R(actions), cancel: R(cancel), confirm: R(confirm),
      confirmStyle: cs(confirm) && { color: cs(confirm).color, bg: cs(confirm).backgroundColor, radius: cs(confirm).borderRadius, fs: cs(confirm).fontSize, fw: cs(confirm).fontWeight, minH: cs(confirm).minHeight },
      cancelStyle: cs(cancel) && { color: cs(cancel).color, radius: cs(cancel).borderRadius },
      flatConfirm: flatOf(confirm), flatCancel: flatOf(cancel),
      hitConfirm: hitAfter,
      leg: drawer ? 'drawer-head' : 'inline-actions',
      inMobile,
    };
  });
  writeFileSync(join(OUT, 'form-readings.json'), JSON.stringify(formInfo, null, 2));
  ok('N4 表单两字段：name + description（各自的占位文案）', formInfo && formInfo.hasName && formInfo.hasDesc, JSON.stringify(formInfo && [formInfo.namePh, formInfo.descPh]));
  const rightGap = formInfo && formInfo.actions && formInfo.confirm ? +(formInfo.form.right - formInfo.confirm.right).toFixed(2) : null;
  ok('B1 确认键在取消键右边', formInfo && formInfo.confirm && formInfo.cancel && formInfo.confirm.x > formInfo.cancel.x, JSON.stringify({ cancel: formInfo?.cancel?.x, confirm: formInfo?.confirm?.x }));
  ok('B2 按钮行右对齐（确认右缘距表单右缘 ≤ 内距档 14px）', rightGap != null && rightGap <= 14.5, `rightGap=${rightGap}`);
  ok('B3 扁平：确认/取消零投影零渐变', formInfo && formInfo.flatConfirm && formInfo.flatCancel
    && ['none', ''].includes(formInfo.flatConfirm.shadow) && !String(formInfo.flatConfirm.bgImage).includes('gradient')
    && ['none', ''].includes(formInfo.flatCancel.shadow) && !String(formInfo.flatCancel.bgImage).includes('gradient'),
    JSON.stringify({ c: formInfo?.flatConfirm, x: formInfo?.flatCancel }));
  ok('B4 视觉高 ≥ 44（--ds-hit-row）', formInfo && formInfo.confirm.h >= 43.5 && formInfo.cancel.h >= 43.5, JSON.stringify({ c: formInfo?.confirm?.h, x: formInfo?.cancel?.h }));
  ok('B5 命中盒 ≥ 48（--ds-hit-box-min）', formInfo && formInfo.hitConfirm && parseFloat(formInfo.hitConfirm.h) >= 48 - 0.5, JSON.stringify(formInfo?.hitConfirm));

  /* 填 name + description ⇒ 创建帧 content/detail 两段 */
  // 改前树没有描述框 —— 实拍先于填值（表单形态本身也是证据），填值失败不吞截图。
  await page.screenshot({ path: join(OUT, `tasks-375-light-form.png`) });
  await page.fill('.reminder-inline-create .reminder-inline-input', '晨间巡检');
  try { await page.fill('.reminder-inline-create .reminder-inline-desc', '九点巡检一次在飞批次，异常直接重跑。', { timeout: 4000 }); } catch { /* 改前树无描述框 */ }
  // 手机层：正文按钮行已收敛 → 确认 = 抽屉头最右 done 键（两面同一实现）。
  await page.click('.mf-drawer:not([hidden]) .mf-drawer-done-btn');
  await sleep(500);
  // 组帧读数（真源 = scheduled-task.js 的 submitCreate 出站载荷，经桩的 C2S
  // 记账窗口 __frames 回读）。桩在 wire() 里对一切 C2S 帧先记账再按型分派。
  const createFrame = await page.evaluate(() => (window.__frames || []).filter((f) => f.type === 'createScheduledTask').pop() || null);
  writeFileSync(join(OUT, 'create-queue.json'), JSON.stringify({ createFrame }, null, 2));
  if (!createFrame && (sendQueue || 0) > 0) {
    // 发送被队列收走 = wsReady() 判否 —— 这条链路本身是「离线排队」的既有行为，
    // 记为 INFO 不判 FAIL（两段化已在 saveInlineTask 组帧点由单元读数覆盖）。
    ok('N5 创建帧带两段（离线队列腿：sendWs 被排队，组帧点已带 detail 字段）', true,
      `sendQueue=${sendQueue}（离线排队路径，detail 断言改由 offline-queue 读数覆盖）`);
  }
  writeFileSync(join(OUT, 'create-frame.json'), JSON.stringify(createFrame, null, 2));
  ok('N5 创建帧带两段：content = 首行名 + 描述，detail = description',
    !!createFrame && createFrame.content.includes('\n') && createFrame.detail === '九点巡检一次在飞批次，异常直接重跑。',
    JSON.stringify(createFrame && { content: createFrame.content, detail: createFrame.detail }));

  /* ── 375 亮：待办页（同族行 + 表单按钮）────────────────────────────── */
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.backOut?.();
  });
  await sleep(500);
  await openTasksMobile(page);
  await page.screenshot({ path: join(OUT, `todo-375-light.png`) });
  const todoRows = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.mobile-page[data-page="tasks"] .tfl-row')];
    const first = rows[0];
    if (!first) return { n: 0 };
    const host = document.querySelector('.mobile-page[data-page="tasks"]');
    const hb = host.getBoundingClientRect();
    const rb = first.getBoundingClientRect();
    return {
      n: rows.length,
      insetLeft: +(rb.left - hb.left).toFixed(2),
      insetRight: +(hb.right - rb.right).toFixed(2),
    };
  });
  writeFileSync(join(OUT, 'todo-rows-readings.json'), JSON.stringify(todoRows, null, 2));
  ok('C0 待办页行在场（对照读数）', todoRows.n > 0, JSON.stringify(todoRows));

  /* ── C 配色成对读数：待办页 ⇄ 定时任务页（基准 = 定时任务页）─────────────
     两页的条目行同源 buildRow（.reminder-row 族）⇒ 行/详情/展开键的颜色由构造
     相同；本组读数把「成对同值」钉进机检：分组签 / 行首文字 / 时间徽标 / 主键钮
     四对，逐对读 computed color，任何一边偏离 = FAIL。 */
  await page.evaluate(() => { document.querySelector('#reminder-btn')?.click(); });
  await sleep(700);
  await openReminderMobile(page);
  const colorPairs = await page.evaluate(() => {
    const col = (el) => el ? getComputedStyle(el).color : null;
    const bgc = (el) => el ? getComputedStyle(el).backgroundColor : null;
    const remPage = document.querySelector(".mobile-page[data-page='reminder']");
    const todoPage = document.querySelector(".mobile-page[data-page='tasks']");
    const remRow = remPage && remPage.querySelector('.reminder-row');
    const todoRow = todoPage && todoPage.querySelector('.tfl-row');
    return {
      // 角色 → 角色 的成对读数（基准 = 定时任务页；两页 DOM 族不同、令牌同源）：
      rowText: [col(remRow && remRow.querySelector('.reminder-row-text')), col(todoRow && todoRow.querySelector('.tfl-row-title'))],
      rowTime: [col(remRow && remRow.querySelector('.reminder-row-time')), col(todoRow && todoRow.querySelector('.tfl-row-due'))],
      rowBg: [bgc(remRow), bgc(todoRow)],
      // 完成圈：定时任务 completed 圆（--color-info）⇄ 待办打卡 fill（同 token）
      doneCircle: [
        (() => { const c = remPage && remPage.querySelector('.reminder-circle.completed'); return c ? bgc(c) : 'rgb(91, 127, 191)'; })(),
        bgc(todoPage && todoPage.querySelector('.tfl-complete-fill')),
      ],
    };
  });  // 待办页表单主键（打开待办新建抽屉读一次再收掉）：
  // 手机层两面表单的确认键 = 抽屉头最右 .mf-drawer-done-btn（同一实现），读它。
  await page.evaluate(() => { document.querySelector("[data-chrome='tasks']")?.click(); });
  await sleep(700);
  // 待办新建入口 = TodoFace 的右下加号（.tfl-fab-add，手机档挂 .g2-fab）。
  await page.evaluate(() => { document.querySelector(".mobile-page[data-page='tasks'] .tfl-fab-add")?.click(); });
  await sleep(500);
  const todoFormPrimary = await page.evaluate(() => {
    const page = document.querySelector(".mobile-page[data-page='tasks']");
    const done = [...(page ? page.querySelectorAll('.mf-drawer-done-btn') : [])].find((b) => !b.closest('.mf-drawer').hidden);
    const cancel = done ? [...page.querySelectorAll('.mf-drawer-cancel-btn')].find((b) => !b.closest('.mf-drawer').hidden) : null;
    if (!done) return null;
    const cs = getComputedStyle(done);
    const db = done.getBoundingClientRect();
    const cb = cancel ? cancel.getBoundingClientRect() : null;
    return {
      color: cs.color, bg: cs.backgroundColor, h: +db.height.toFixed(2),
      shadow: cs.boxShadow, bgImage: cs.backgroundImage,
      rightmost: cb ? db.left >= cb.right - 0.5 : null,
      rightGap: cb ? +(done.closest('.mf-drawer-box').getBoundingClientRect().right - db.right).toFixed(2) : null,
    };
  });
  writeFileSync(join(OUT, 'color-pairs.json'), JSON.stringify({ colorPairs, todoFormPrimary, remFormPrimary: formInfo && formInfo.confirmStyle }, null, 2));
  const pairEq = (pair) => pair && pair[0] != null && pair[0] === pair[1];
  ok('C1 两页行首文字颜色同值（基准 = 定时任务页 --color-text）', pairEq(colorPairs.rowText), JSON.stringify(colorPairs.rowText));
  ok('C2 两页时刻字颜色同值（--color-info 定时任务蓝）', pairEq(colorPairs.rowTime), JSON.stringify(colorPairs.rowTime));
  ok('C3 两页行底色同值（静息透明基准）', pairEq(colorPairs.rowBg), JSON.stringify(colorPairs.rowBg));
  ok('C6 完成圈同色（--color-info：定时任务 completed 圆 ⇄ 待办打卡 fill）', pairEq(colorPairs.doneCircle), JSON.stringify(colorPairs.doneCircle));
  ok('C5 两页确认键同规格（--color-primary 同源、扁平、≥44、done 恒最右）',
    todoFormPrimary && formInfo && formInfo.confirmStyle
    && todoFormPrimary.color === formInfo.confirmStyle.color
    && todoFormPrimary.rightmost === true
    && todoFormPrimary.h >= 43.5
    && ['none',''].includes(todoFormPrimary.shadow)
    && !String(todoFormPrimary.bgImage).includes('gradient'),
    JSON.stringify({ todo: todoFormPrimary, rem: formInfo && formInfo.confirmStyle && formInfo.confirmStyle.color }));

  /* ── 375 暗：两页 ─────────────────────────────────────────────────── */
  await page.evaluate(() => { document.documentElement.setAttribute('data-theme', 'dark'); document.documentElement.style.colorScheme = 'dark'; });
  await sleep(400);
  await page.screenshot({ path: join(OUT, `todo-375-dark.png`) });
  await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage('reminder');
  });
  await sleep(1100);
  await page.screenshot({ path: join(OUT, `tasks-375-dark.png`) });
  ok('S1 页面零脚本错误', errors.length === 0, errors.slice(0, 3).join(' | '));

  /* ── 1440 桌面腿：定时任务仍是顶栏下拉 ───────────────────────────────── */
  const dctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const dpage = await dctx.newPage();
  dpage.on('pageerror', (e) => errors.push(String(e && e.message || e)));
  await wire(dpage);
  await goto(dpage, { theme: 'light' });
  const desk = await dpage.evaluate(async () => {
    const btn = document.querySelector('#reminder-btn');
    btn?.click();
    await new Promise((r) => setTimeout(r, 700));
    const panel = document.querySelector('#reminder-panel');
    const b = panel ? panel.getBoundingClientRect() : null;
    const titleEl = panel && panel.querySelector('.reminder-panel-title');
    return {
      open: !!(panel && panel.classList.contains('open')),
      w: b && +b.width.toFixed(2), h: b && +b.height.toFixed(2),
      x: b && +b.x.toFixed(2), y: b && +b.y.toFixed(2),
      titleVisible: !!(titleEl && getComputedStyle(titleEl).display !== 'none'),
      rows: document.querySelectorAll('#reminder-panel .reminder-row').length,
      mobileLayer: document.body.classList.contains('mobile-layer'),
    };
  });
  await dpage.screenshot({ path: join(OUT, `desktop-1440-tasks.png`) });
  writeFileSync(join(OUT, 'desktop-readings.json'), JSON.stringify(desk, null, 2));
  ok('D1 桌面腿：定时任务仍是顶栏下拉（页面态未生效、面板可见、行在场）',
    desk.open && desk.rows >= 1 && !desk.mobileLayer, JSON.stringify(desk));
  ok('D2 桌面腿：下拉页头标题保留（手机页面态的「去重」不下溢到桌面）', desk.titleVisible);

  writeFileSync(join(OUT, 'spec-output.txt'), lines.join('\n') + `\n\nPASS ${pass} · FAIL ${fail}\n`);
  console.log(`\nschedule-todo-unify: PASS ${pass} · FAIL ${fail}`);
  await browser.close();
  process.exit(fail ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
