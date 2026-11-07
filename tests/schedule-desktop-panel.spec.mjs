#!/usr/bin/env node
// schedule-desktop-panel.spec.mjs — 桌面「定时任务 / 待办」面板的**控件布局与弹窗比例**
// 真几何验收（2026-11-07 作者报障批：①「电脑端的这个定时任务的界面怎么也这么奇怪？」
// ②「这个界面也是，感觉大小比例失衡了。弹窗的内容比主界面还大。」）
//
// 零后端法（与 tests/schedule-todo-unify.spec.mjs 同源口径）：
//   · 静态面 = page.route 从磁盘注入本仓 web 树（零端口、零 sbt、不碰 :6138）；
//   · 数据面 = routeWebSocket 帧桩（listScheduledTasks 回夹具；写帧只记账不应答）；
//   · 入口   = 真链：顶栏 #reminder-btn → 面板「+」→ 行内创建表单；
//             待办面 = 顶带胶囊 #task-list → 展开 → 右下加号 → 行内创建 → 截止时间开关。
//   断言面 = 真 DOM 几何（getBoundingClientRect / offsetHeight / 计算样式）与真事件
//   （Playwright 键盘、真指针点击）。零静态替身。
//
// 用法：
//   node tests/schedule-desktop-panel.spec.mjs                  # 门禁（红/绿）
//   WEB_ROOT=<另一份 web 树> node tests/schedule-desktop-panel.spec.mjs   # 改前树验红
//   SHOTS=<dir> node tests/schedule-desktop-panel.spec.mjs      # 另存实拍
//
// 判据（逐条对应任务书）：
//   F 组 = 定时任务创建表单（图标槽 / 裸件造型 / 控件列对齐 / 确认键形态 / 可操作性）
//   K 组 = 键盘可达与焦点可见
//   W 组 = 时间滚轮弹窗（宿主比例 / 定位不出宿主 / 滚轮几何与取值一致 / 宽宿主回退档）
//   M 组 = 手机 375（sheet 档位不回归 / 抽屉表单 / 手机层无宿主档类）
//   T 组 = 主题（暗色同几何）与桌面宽度（1024/1280）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const ARG_WEB = (process.argv.slice(2).find((a) => a.startsWith('--web-root=')) || '').slice(11);
const WEB = process.env.WEB_ROOT ? normalize(process.env.WEB_ROOT) : (ARG_WEB ? normalize(ARG_WEB) : join(ROOT, 'src', 'main', 'resources', 'web'));
const OUT = process.env.SHOTS ? resolve(process.env.SHOTS) : join(ROOT, 'reports-evidence', 'schedule-desktop-panel__20261107_2046', 'shots');
mkdirSync(OUT, { recursive: true });

const SID = 'sched-desktop-spec';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}

/** 定时任务夹具（kind=null = 定时任务面）。 */
function taskFixture() {
  const now = Date.now();
  return [
    { id: 'fx-1', sessionId: SID, content: '夜间监工', detail: '每小时巡检在飞批次', triggerAt: now + 8 * 60_000, createdAt: now, triggered: false, triggeredAt: null, repeat: 'hourly', enabled: true, kind: null, name: null, project: null, priority: 0 },
    { id: 'fx-2', sessionId: SID, content: '晨间汇总', detail: '', triggerAt: now + 25 * 60_000, createdAt: now, triggered: false, triggeredAt: null, repeat: null, enabled: true, kind: null, name: null, project: null, priority: 0 },
  ];
}
const TODO_FIXTURE = [{ id: 'td-1', sessionId: SID, content: '确认 UI 批合并', detail: '作者确认六条落点后合 main', triggerAt: Date.now() + 3_600_000, createdAt: Date.now() - 7_200_000, triggered: false, triggeredAt: null, repeat: null, enabled: true, kind: 'todo', name: null, project: 'stand', priority: 2, completed: false, completedAt: null }];

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
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') return ws.send(JSON.stringify({ type: 'pong' }));
      if (m.type === 'getConfig') return sendConfig();
      if (m.type === 'getSessions' || m.type === 'listSessions') return sendSessions();
      if (m.type === 'getHistory') return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      if (m.type === 'listScheduledTasks') {
        const tasks = m.scope === 'all' ? TODO_FIXTURE : taskFixture();
        return ws.send(JSON.stringify({ type: 'scheduledTaskList', sessionId: m.sessionId, scope: m.scope, tasks }));
      }
    });
  });
}

async function boot(browser, { width = 1440, height = 900, theme = 'light', mobile = false } = {}) {
  const ctx = await browser.newContext({
    viewport: { width, height }, deviceScaleFactor: 2,
    colorScheme: theme, hasTouch: mobile, isMobile: mobile,
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String((e && e.message) || e)));
  await wire(page);
  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => !!document.querySelector('body'));
  await page.evaluate((th) => {
    document.documentElement.setAttribute('data-theme', th);
    document.documentElement.style.colorScheme = th;
  }, theme);
  await sleep(900);
  if (!mobile) {
    await page.evaluate(async () => {
      const sb = await import('/js/sidebar.js');
      sb.switchToSession('sched-desktop-spec');
    });
    await sleep(400);
  } else {
    await page.evaluate(async () => {
      const sb = await import('/js/sidebar.js');
      sb.switchToSession('sched-desktop-spec');
    });
    await sleep(400);
  }
  return { page, ctx, errors };
}

/** 打开定时任务面 + 开行内创建表单（桌面腿）。 */
async function openTaskForm(page) {
  // 入口走真钮；用 JS 点击（窄桌面宽度下顶栏按 nb-header-hide 收起了这颗钮，
  // Playwright 的可见性门会拒点 —— 收起是顶栏的响应式行为，不是本批的面）。
  await page.evaluate(() => document.querySelector('#reminder-btn')?.click());
  await sleep(900);
  await page.evaluate(() => document.querySelector('#reminder-create-btn')?.click());
  await sleep(700);
}

const FORM_READ = `(() => {
  const q = (s) => document.querySelector(s);
  const rect = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), right: +b.right.toFixed(2), bottom: +b.bottom.toFixed(2) }; };
  const panel = q('#reminder-panel');
  const body = q('#reminder-panel .reminder-panel-body');
  const form = q('#reminder-panel .reminder-inline-create');
  const iconRows = {
    desc: q('.reminder-inline-desc-row > svg'),
    clock: q('.reminder-time-row > svg'),
    repeat: q('.reminder-repeat-row > svg'),
  };
  const iconInfo = {};
  for (const [k, el] of Object.entries(iconRows)) {
    if (!el) { iconInfo[k] = null; continue; }
    const b = el.getBoundingClientRect();
    iconInfo[k] = { x: +b.x.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), opacity: getComputedStyle(el).opacity };
  }
  const desc = q('.reminder-inline-desc');
  const descCs = desc && getComputedStyle(desc);
  const nameInput = q('.reminder-inline-input');
  const trigger = q('.tw-trigger');
  const seg = q('.reminder-repeat-seg');
  const segBtns = seg ? [...seg.querySelectorAll('button')].map((b) => ({ t: b.textContent.trim(), w: +b.getBoundingClientRect().width.toFixed(2), sw: b.scrollWidth, cw: b.clientWidth })) : [];
  const btn = (sel) => { const el = q(sel); if (!el) return null; const b = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    return { rect: rect(el), radius: cs.borderRadius, shadow: cs.boxShadow, bgImage: cs.backgroundImage, pad: cs.padding, fs: cs.fontSize }; };
  const acts = q('#reminder-panel .reminder-form-actions');
  return {
    panel: rect(panel), body: rect(body), form: rect(form),
    formScroll: form ? { sw: form.scrollWidth, cw: form.clientWidth } : null,
    bodyScroll: body ? { sh: body.scrollHeight, ch: body.clientHeight, top: body.scrollTop } : null,
    acts: rect(acts),
    icons: iconInfo,
    desc: desc ? { rect: rect(desc), bg: descCs.backgroundColor, borderW: descCs.borderTopWidth, shadow: descCs.boxShadow, radius: descCs.borderRadius } : null,
    name: nameInput ? { rect: rect(nameInput) } : null,
    trigger: rect(trigger),
    segBtns,
    cancel: btn('#reminder-panel .reminder-form-actions .reminder-form-btn:not(.reminder-form-btn-primary)'),
    confirm: btn('#reminder-panel .reminder-form-actions .reminder-form-btn-primary'),
    lastRow: (() => { const rows = document.querySelectorAll('#reminder-panel .reminder-row'); const el = rows[rows.length - 1]; return el ? rect(el) : null; })(),
  };
})()`;

const WHEEL_READ = `(() => {
  const p = document.querySelector('.tw-panel.in');
  if (!p) return null;
  const host = p.classList.contains('host-fit')
    ? (document.querySelector('#reminder-panel') || document.querySelector('.tfl-card'))
    : null;
  const hostFace = p.__host || null;
  const body = p.querySelector('.tw-body-point:not([hidden])') || p.querySelector('.tw-body-range:not([hidden])');
  const band = body && body.querySelector('.tw-band');
  const br = band && band.getBoundingClientRect();
  const cols = body ? [...body.querySelectorAll('.tw-col')].map((c) => {
    const strip = c.querySelector('.tw-strip');
    const items = [...c.querySelectorAll('.tw-item')];
    const center = br ? br.y + br.height / 2 : null;
    const hit = center == null ? null : items
      .map((i) => ({ t: i.textContent, cy: (() => { const r = i.getBoundingClientRect(); return r.y + r.height / 2; })() }))
      .filter((v) => Math.abs(v.cy - center) <= 4).map((v) => v.t);
    return { role: c.dataset.role, w: +c.offsetWidth, h: +c.offsetHeight, tf: strip ? strip.style.transform : null, aria: c.getAttribute('aria-valuetext'), centered: hit };
  }) : [];
  const item = body && body.querySelector('.tw-item');
  const r = p.getBoundingClientRect();
  return {
    rect: { x: +r.x.toFixed(2), y: +r.y.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2), right: +r.right.toFixed(2), bottom: +r.bottom.toFixed(2) },
    cls: p.className, cols, itemH: item ? item.offsetHeight : null,
    itemFs: item ? getComputedStyle(item).fontSize : null,
    timeFs: (() => { const c = body && body.querySelector('.tw-col:not(.tw-col-date) .tw-item'); return c ? getComputedStyle(c).fontSize : null; })(),
    dateFs: (() => { const c = body && body.querySelector('.tw-col-date .tw-item'); return c ? getComputedStyle(c).fontSize : null; })(),
    vw: innerWidth, vh: innerHeight,
  };
})()`;

/** 宿主面的 rect（弹窗是否越出宿主/视口的判据基准）。 */
const HOST_READ = (sel) => `(() => { const el = document.querySelector(${JSON.stringify(sel)}); if (!el) return null;
  const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2), right: +b.right.toFixed(2), bottom: +b.bottom.toFixed(2) }; })()`;

const inside = (inner, outer, tol = 1.5) => !!(inner && outer)
  && inner.x >= outer.x - tol && inner.right <= outer.right + tol
  && inner.y >= outer.y - tol && inner.bottom <= outer.bottom + tol;

async function shot(page, name) {
  const path = join(OUT, name + '.png');
  await page.screenshot({ path });
  return path;
}

async function shotPanel(page, name, sel = '#reminder-panel') {
  const box = await page.evaluate((s) => { const el = document.querySelector(s); if (!el) return null; const b = el.getBoundingClientRect(); return { x: Math.max(0, b.x - 8), y: Math.max(0, b.y - 8), width: b.width + 16, height: b.height + 16 }; }, sel);
  const path = join(OUT, name + '.png');
  if (box) await page.screenshot({ path, clip: box }); else await page.screenshot({ path });
  return path;
}

async function main() {
  const browser = await chromium.launch();
  const allErrors = [];

  /* ── ① 桌面 1440 亮：定时任务创建表单 ─────────────────────────────── */
  {
    const { page, ctx, errors } = await boot(browser, { width: 1440, height: 900, theme: 'light' });
    await openTaskForm(page);
    const f = await page.evaluate(FORM_READ);
    writeFileSync(join(OUT, 'form-readings-1440-light.json'), JSON.stringify(f, null, 2));
    await shotPanel(page, 'form-1440-light');
    await shot(page, 'viewport-1440-light');

    // F1 图标槽一致（描述行图标 = 与时钟/重复同一 12px 淡档，且三行同列）
    const ic = f.icons || {};
    const iconSized = ['desc', 'clock', 'repeat'].every((k) => ic[k] && ic[k].w <= 14 && ic[k].h <= 14 && Number(ic[k].opacity) <= 0.5);
    const sameCol = ['desc', 'clock', 'repeat'].every((k) => ic[k] && Math.abs(ic[k].x - ic.desc.x) <= 1);
    ok('F1 描述/时刻/重复三行图标同档同列（≤14px、淡档、同一 x）',
      iconSized && sameCol, JSON.stringify(ic));

    // F2 描述件 = 裸件（无底无边无影，与同行 name 输入件同一造型）
    const descBare = f.desc && f.desc.bg === 'rgba(0, 0, 0, 0)' && f.desc.borderW === '0px'
      && (f.desc.shadow === 'none' || f.desc.shadow === '') && f.desc.radius === '0px';
    ok('F2 描述输入件为零底零边零影裸件（不再长成行内白枕头）',
      !!descBare, JSON.stringify(f.desc && { bg: f.desc.bg, borderW: f.desc.borderW, shadow: f.desc.shadow, radius: f.desc.radius }));

    // F3 控件列对齐（描述文字列 = 时刻控件列，误差 ≤ 2px）
    const colAligned = f.desc && f.trigger && Math.abs(f.desc.rect.x - f.trigger.x) <= 2;
    ok('F3 描述件与时刻控件左缘同列（≤2px）', !!colAligned,
      JSON.stringify({ descX: f.desc && f.desc.rect.x, triggerX: f.trigger && f.trigger.x }));

    // F4 确认键 = 胶囊档（不是 44×44 正圆）；视觉高 ≥44、命中盒 ≥48；扁平
    const confirmPill = f.confirm && f.confirm.rect.w >= f.confirm.rect.h + 12 && f.confirm.rect.h >= 44
      && parseFloat(f.confirm.radius) >= 44;
    const flat = f.confirm && (f.confirm.shadow === 'none' || f.confirm.shadow === '') && !String(f.confirm.bgImage).includes('gradient');
    ok('F4 确认键为胶囊（宽 ≥ 高+12、高 ≥44、pill 圆角、扁平）',
      !!confirmPill && !!flat, JSON.stringify(f.confirm && { w: f.confirm.rect.w, h: f.confirm.rect.h, radius: f.confirm.radius }));
    const hit = await page.evaluate(() => {
      const el = document.querySelector('#reminder-panel .reminder-form-actions .reminder-form-btn-primary');
      if (!el) return null;
      const cs = getComputedStyle(el, '::after');
      return { w: cs.width, h: cs.height };
    });
    ok('F4b 确认键命中盒 ≥ 48×48（::after 外扩仍在）', !!hit && parseFloat(hit.w) >= 48 && parseFloat(hit.h) >= 48, JSON.stringify(hit));

    // F5 取消/创建完整可见（开单后表单整块进入面板可视区；面板体可滚）
    const actsVisible = f.acts && f.body && f.panel && f.acts.y >= f.body.y - 1
      && f.acts.bottom <= Math.min(f.body.bottom, f.panel.bottom) + 1;
    ok('F5 取消/创建两键完整落在面板可视区内（含列表非空时）',
      !!actsVisible, JSON.stringify({ acts: f.acts && { top: f.acts.y, bottom: f.acts.bottom }, body: f.body && { y: f.body.y, bottom: f.body.bottom }, scrollTop: f.bodyScroll && f.bodyScroll.top }));
    ok('F5b 面板体是真滚动容器（内容超出可滚，不再被 overflow:hidden 硬裁）',
      !!(f.bodyScroll && f.bodyScroll.sh > f.bodyScroll.ch && f.bodyScroll.top > 0),
      JSON.stringify(f.bodyScroll));

    // F6 无横向溢出（表单自身与面板右缘）
    const noOverflow = f.formScroll && f.formScroll.sw <= f.formScroll.cw + 1
      && f.form && f.panel && f.form.right <= f.panel.right + 0.5;
    ok('F6 表单无横向溢出、右缘不越面板', !!noOverflow,
      JSON.stringify({ formScroll: f.formScroll, formRight: f.form && f.form.right, panelRight: f.panel && f.panel.right }));

    // F7 重复四档文字不裁切（scrollWidth ≤ clientWidth + 1）
    const segOk = f.segBtns && f.segBtns.length === 4 && f.segBtns.every((b) => b.sw <= b.cw + 1);
    ok('F7 重复四档按钮文字不溢出（单次/每小时/每天/每周 全可见）', !!segOk, JSON.stringify(f.segBtns));

    await page.locator('.reminder-inline-input').fill('测试标题');
    await page.locator('.reminder-inline-desc').click();
    await page.locator('.reminder-inline-desc').fill('测试描述');
    ok('F8 点击描述不误触保存，名称和描述可编辑',
      await page.locator('.reminder-inline-desc').inputValue() === '测试描述'
        && await page.locator('.reminder-inline-input').inputValue() === '测试标题');

    /* ── K 键盘可达 ─────────────────────────────────────────────── */
    await page.evaluate(() => document.querySelector('#reminder-inline-input')?.focus());
    const seq = [];
    for (let i = 0; i < 9; i++) {
      seq.push(await page.evaluate(() => {
        const a = document.activeElement;
        if (!a) return null;
        return a.id || (a.className && String(a.className).split(' ').filter(Boolean).slice(-1)[0]) || a.tagName;
      }));
      await page.keyboard.press('Tab');
    }
    const wantSeq = ['reminder-inline-input', 'reminder-inline-desc', 'tw-trigger', 'active', 'BUTTON', 'BUTTON', 'BUTTON', 'reminder-form-btn', 'reminder-form-btn-primary'];
    ok('K1 表单 Tab 序：名称 → 描述 → 时刻 → 重复档 → 取消 → 创建（全链可达）',
      wantSeq.every((w, i) => seq[i] === w), JSON.stringify(seq));
    const focusRing = await page.evaluate(() => {
      const el = document.querySelector('.tw-trigger');
      el.focus();
      const cs = getComputedStyle(el);
      return { outline: cs.outlineStyle + ' ' + cs.outlineWidth, shadow: cs.boxShadow, border: cs.borderColor };
    });
    ok('K2 键盘焦点可见（时刻字段 focus-visible 有环/描边）',
      focusRing.outline !== 'none 0px' || (focusRing.shadow && focusRing.shadow !== 'none'), JSON.stringify(focusRing));

    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── ② 桌面暗色：同几何 ──────────────────────────────────────────── */
  {
    const { page, ctx, errors } = await boot(browser, { width: 1440, height: 900, theme: 'dark' });
    await openTaskForm(page);
    const f = await page.evaluate(FORM_READ);
    writeFileSync(join(OUT, 'form-readings-1440-dark.json'), JSON.stringify(f, null, 2));
    await shotPanel(page, 'form-1440-dark');
    const descBare = f.desc && f.desc.bg === 'rgba(0, 0, 0, 0)' && f.desc.borderW === '0px' && (f.desc.shadow === 'none' || f.desc.shadow === '');
    const actsVisible = f.acts && f.body && f.panel
      && f.acts.bottom <= Math.min(f.body.bottom, f.panel.bottom) + 1;
    const pill = f.confirm && f.confirm.rect.w >= f.confirm.rect.h + 12;
    ok('T1 暗色：描述件仍裸件 + 两键完整可见 + 确认键仍胶囊',
      !!descBare && !!actsVisible && !!pill,
      JSON.stringify({ desc: f.desc && f.desc.bg, acts: f.acts && { top: f.acts.y, bottom: f.acts.bottom }, bodyBottom: f.body && f.body.bottom, confirm: f.confirm && { w: f.confirm.rect.w, h: f.confirm.rect.h } }));
    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── ③ 桌面 1024 / 1280：窄桌面宽度不挤压 ─────────────────────────── */
  for (const [w, h] of [[1280, 800], [1024, 768]]) {
    const { page, ctx, errors } = await boot(browser, { width: w, height: h, theme: 'light' });
    await openTaskForm(page);
    const f = await page.evaluate(FORM_READ);
    writeFileSync(join(OUT, `form-readings-${w}.json`), JSON.stringify(f, null, 2));
    await shotPanel(page, `form-${w}`);
    const segOk = f.segBtns && f.segBtns.length === 4 && f.segBtns.every((b) => b.sw <= b.cw + 1);
    const actsVisible = f.acts && f.body && f.panel && f.acts.y >= f.body.y - 1
      && f.acts.bottom <= Math.min(f.body.bottom, f.panel.bottom) + 1;
    const noOverflow = f.formScroll && f.formScroll.sw <= f.formScroll.cw + 1;
    ok(`T2 ${w} 宽：重复四档不裁切 + 两键完整可见 + 零横向溢出`,
      !!segOk && !!actsVisible && !!noOverflow,
      JSON.stringify({ seg: f.segBtns && f.segBtns.map((b) => b.w + '/' + b.sw), acts: f.acts && { top: f.acts.y, bottom: f.acts.bottom }, body: f.body && { y: f.body.y, bottom: f.body.bottom }, formScroll: f.formScroll }));
    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── ④ 时间滚轮：定时任务宿主（360×480 面板）──────────────────────── */
  {
    const { page, ctx, errors } = await boot(browser, { width: 1440, height: 900, theme: 'light' });
    await openTaskForm(page);
    await page.click('.tw-trigger');
    await sleep(700);
    const wd = await page.evaluate(WHEEL_READ);
    const host = await page.evaluate(HOST_READ('#reminder-panel'));
    writeFileSync(join(OUT, 'wheel-readings-task-host.json'), JSON.stringify({ wheel: wd, host }, null, 2));
    await shot(page, 'wheel-task-host');
    ok('W1 定时任务宿主：弹窗右/下缘不越宿主、且完整落在宿主体内',
      inside(wd.rect, host), JSON.stringify({ wheel: wd.rect, host }));
    ok('W2 弹窗按宿主收档（宽 ≤ 宿主宽 − 24、高 ≤ 宿主高）',
      !!wd && wd.rect.w <= host.w - 24 && wd.rect.h <= host.h, JSON.stringify({ w: wd.rect.w, h: wd.rect.h, hostW: host.w, hostH: host.h }));
    ok('W3 滚轮字号/列高收档（时分档 ≤17px、日期档 ≤13px、列高 ≤160px；不再取面级 20px/200px 档）',
      !!wd && parseFloat(wd.timeFs) <= 17 && parseFloat(wd.dateFs) <= 13 && wd.cols.every((c) => c.h <= 160),
      JSON.stringify({ timeFs: wd.timeFs, dateFs: wd.dateFs, cols: wd.cols.map((c) => ({ role: c.role, w: c.w, h: c.h })) }));
    // W4 几何一致：带中心项 = aria 值；键盘一档 = 一个项高
    const before = wd.cols.find((c) => c.role === 'pm');
    await page.evaluate(() => document.querySelector('.tw-col[data-role="pm"]')?.focus());
    await page.keyboard.press('ArrowDown');
    await sleep(420);
    const wd2 = await page.evaluate(WHEEL_READ);
    const after = wd2.cols.find((c) => c.role === 'pm');
    const dy = Math.abs(parseFloat(String(after.tf).replace(/[^0-9.-]/g, '')) - parseFloat(String(before.tf).replace(/[^0-9.-]/g, '')));
    ok('W4 滚轮行程与 CSS 档位一致（键盘一档位移 = 项高，零错位）',
      Math.abs(dy - wd.itemH) <= 0.5,
      JSON.stringify({ itemH: wd.itemH, movedPx: +dy.toFixed(2), beforeCentered: before.centered, afterCentered: after.centered }));
    ok('W4b 带中心项即当前值（日期/时/分三列都有命中）',
      wd.cols.filter((c) => c.centered && c.centered.length > 0).length === 3,
      JSON.stringify(wd.cols.map((c) => ({ role: c.role, c: c.centered, aria: c.aria }))));
    // W5 确认写回
    const triggerBefore = await page.evaluate(() => document.querySelector('.tw-trigger')?.textContent.trim());
    await page.click('.tw-panel.in .tw-confirm');
    await sleep(400);
    const triggerAfter = await page.evaluate(() => document.querySelector('.tw-trigger')?.textContent.trim());
    ok('W5 确认把新值写回触发钮（功能未破）', !!triggerAfter && triggerAfter !== triggerBefore && /^\d{1,2}\/\d{1,2}\s+\d{2}:\d{2}$/.test(triggerAfter), JSON.stringify({ triggerBefore, triggerAfter }));
    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── ⑤ 时间滚轮：待办宿主（胶囊展开面）───────────────────────────── */
  {
    const { page, ctx, errors } = await boot(browser, { width: 1280, height: 900, theme: 'light' });
    await page.click('#task-list .tfl-capsule');
    await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 10_000 });
    await sleep(900);
    await page.evaluate(() => (document.querySelector('.tfl-add-btn') || document.querySelector('.tfl-fab-add'))?.click());
    await page.waitForSelector('.tfl-create-row', { timeout: 10_000 });
    await sleep(600);
    await page.evaluate(() => document.querySelector('.tfl-create-row .nb-toggle')?.click());
    await sleep(400);
    await page.click('.tfl-create-row .tw-trigger');
    await page.waitForSelector('.tw-panel.in', { timeout: 10_000 });
    await sleep(700);
    const wd = await page.evaluate(WHEEL_READ);
    const host = await page.evaluate(HOST_READ('.tfl-card'));
    writeFileSync(join(OUT, 'wheel-readings-todo-host.json'), JSON.stringify({ wheel: wd, host }, null, 2));
    await shot(page, 'wheel-todo-host');
    await shotPanel(page, 'todo-panel-wheel', '.tfl-card');
    ok('W6 待办宿主：弹窗完整落在宿主面内（不再挂在面板外半截）',
      inside(wd.rect, host), JSON.stringify({ wheel: wd.rect, host }));
    ok('W7 待办宿主：弹窗高 ≤ 宿主整面高（「弹窗比主界面还大」不再成立）',
      wd.rect.h <= host.h, JSON.stringify({ wheelH: wd.rect.h, hostH: host.h }));
    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── ⑥ 宽宿主回退档（≥480 的面 = 原档，收档不外溢到别的落点）───────── */
  {
    const { page, ctx, errors } = await boot(browser, { width: 1440, height: 900, theme: 'light' });
    await page.evaluate(async () => {
      const tw = await import('/js/timeWheel.js');
      const host = document.createElement('div');
      host.className = 'scroll-under-glass';
      host.id = 'spec-wide-host';
      host.style.cssText = 'position:fixed;left:40px;top:120px;width:600px;height:520px;padding:16px;z-index:400';
      host.innerHTML = tw.pointTriggerHTML({ date: '2026-11-08', time: '10:00', label: '时刻' });
      document.body.appendChild(host);
      tw.attachTimeTriggers(host, {});
    });
    await sleep(300);
    await page.click('#spec-wide-host .tw-trigger');
    await sleep(700);
    const wd = await page.evaluate(WHEEL_READ);
    const host = await page.evaluate(HOST_READ('#spec-wide-host'));
    writeFileSync(join(OUT, 'wheel-readings-wide-host.json'), JSON.stringify({ wheel: wd, host }, null, 2));
    await shot(page, 'wheel-wide-host');
    ok('W8 宽宿主（600px）仍取原档：弹窗 300 宽、无 host-fit 类、列高 200',
      !!wd && !/host-fit/.test(wd.cls) && Math.round(wd.rect.w) === 300 && wd.cols.every((c) => c.h === 200),
      JSON.stringify({ cls: wd && wd.cls, w: wd && wd.rect.w, cols: wd && wd.cols.map((c) => c.h) }));
    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── ⑦ 手机 375：sheet 档位不回归 + 抽屉表单 ─────────────────────── */
  {
    const { page, ctx, errors } = await boot(browser, { width: 375, height: 812, mobile: true });
    await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.pushPage('tasks');
    });
    await sleep(900);
    await page.evaluate(() => document.querySelector('#reminder-btn')?.click());
    await sleep(1200);
    await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.pushPage('reminder');
    });
    await sleep(1400);
    await shot(page, 'mobile-reminder-page');
    const pageRows = await page.evaluate(() => {
      const rows = [...document.querySelectorAll('#reminder-panel .reminder-row')];
      const title = document.querySelector(".mobile-page[data-page='reminder'] .mobile-page-title");
      return { rows: rows.length, titleOverflow: title ? title.scrollWidth > title.clientWidth + 1 : null };
    });
    ok('M1 手机提醒面不回归（行仍在、页名不截断）',
      pageRows.rows > 0 && pageRows.titleOverflow === false, JSON.stringify(pageRows));

    // 抽屉新建 + 时间滚轮 sheet
    await page.evaluate(() => document.querySelector('.reminder-fab-add')?.click());
    await sleep(900);
    const drawer = await page.evaluate(() => {
      const form = document.querySelector('.reminder-inline-create');
      const box = form && form.closest('.mf-drawer-box');
      if (!box) return null;
      const rect = (el) => { const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
      const pick = (s) => { const el = box.querySelector(s); return el ? rect(el) : null; };
      return {
        box: rect(box),
        cancel: pick('.mf-drawer-cancel-btn'), done: pick('.mf-drawer-done-btn'),
        name: pick('.reminder-inline-input'), desc: pick('.reminder-inline-desc'), trigger: pick('.tw-trigger'),
        iconW: (() => { const i = box.querySelector('.reminder-inline-desc-row > svg'); return i ? +i.getBoundingClientRect().width.toFixed(1) : null; })(),
      };
    });
    writeFileSync(join(OUT, 'mobile-drawer-readings.json'), JSON.stringify(drawer, null, 2));
    await shot(page, 'mobile-create-drawer');
    ok('M2 手机抽屉表单在场，抽屉头两键 44 档（触控尺寸未缩）',
      !!drawer && drawer.cancel && drawer.cancel.w >= 44 && drawer.cancel.h >= 44
      && drawer.done && drawer.done.w >= 44 && drawer.done.h >= 44
      && !!drawer.trigger && drawer.trigger.h >= 44,
      JSON.stringify(drawer));

    await page.click('.tw-trigger');
    await sleep(900);
    const sheet = await page.evaluate(WHEEL_READ);
    writeFileSync(join(OUT, 'mobile-wheel-sheet-readings.json'), JSON.stringify(sheet, null, 2));
    await shot(page, 'mobile-wheel-sheet');
    ok('M3 手机 sheet 腿零回归：无 host-fit 类、项高 40、列高 200、日期列 120 / 时分列 62',
      !!sheet && !/host-fit/.test(sheet.cls)
      && sheet.itemH === 40
      && sheet.cols.every((c) => c.h === 200)
      && sheet.cols.some((c) => c.role === 'd1' && c.w === 120)
      && sheet.cols.filter((c) => c.role === 'ph' || c.role === 'pm').every((c) => c.w === 62),
      JSON.stringify({ cls: sheet && sheet.cls, itemH: sheet && sheet.itemH, cols: sheet && sheet.cols.map((c) => ({ r: c.role, w: c.w, h: c.h })) }));
    allErrors.push(...errors);
    await ctx.close();
  }

  /* ── 汇总 ─────────────────────────────────────────────────────────── */
  ok('S1 全腿零脚本错误', allErrors.length === 0, allErrors.slice(0, 3).join(' | '));
  writeFileSync(join(OUT, 'gate-summary.json'), JSON.stringify({ pass, fail, web: WEB, lines }, null, 2));
  await browser.close();
  console.log(`\nschedule-desktop-panel: PASS ${pass} · FAIL ${fail}  (web=${WEB})`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error('SPEC CRASH', e); process.exit(2); });
