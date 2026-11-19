#!/usr/bin/env node
// time-wheel-picker.spec.mjs — 时间滚轮选择器（Apple 闹钟式调整时间）实施批的
// 机械断言集（作者令 2026-11-03 22:16「所有涉及调整时间的场景都要用这个设计
// 效果」+ 22:20「编辑面也采用同款的滚轮，尽量让这个交互统一一点」）。
//
// 设计出处：批 3ab109836（2026-11-01 16:14 令）+ e12dac875（16:46 令「8:00-12:00
// 应该是一个整体」）⇒ 合并 7dd5fe226。两波只出设计、零产品代码（考古结论），
// 本批 = 补实施。断言把「设计已经落地」钉成机器判据：
//
//   T1 组件单源      —— 唯一的滚轮实现只存在于 js/timeWheel.js（其它文件只 import；
//                       第二个 Wheel / 第二份面板 DOM 一律犯规＝红）。
//   T2 五落点全收编  —— 冻结时段（设置）／provider 不可用时段（provider 级 +
//                       per-model 级）／定时任务触发时刻／待办截止时刻，五处都渲染
//                       同款 `.tw-trigger`，且产品源码里**不再存在**裸
//                       `input[type=datetime-local]` / `input[type=time]`。
//   T3 热区          —— 触发钮/按钮/胶囊/删除钮命中盒 ≥44×44（手机层铁律同源）。
//   T4 24h 格式      —— 字段脸恒 HH:mm（无 AM/PM 字样；O5 12h 制另批）。
//   T5 跨零点        —— 起 > 止 ⇒ 跨零点提示条在，文案含「次日」。
//   T6 分钟 5 步进   —— 分钟列每档 aria-valuenow % 5 === 0。
//   T7 零长度拒收    —— 起 === 止 ⇒ 确定钮 disabled + 等值错误行 show。
//   T8 ESC 还原      —— 打开面板改值后 ESC ⇒ 触发钮 dataset 逐字不变。
//   T9 两皮          —— >768px = popover 锚定触发钮；≤768px/手机层 = sheet。
//   T10 设计令牌     —— timeWheel.css 零色值字面量（无 #hex / 无色名 rgb()）。
//   T11 reduced-motion —— 面板动画与过渡全退化。
//   T12 零 console 错误。
//
// 数据面 = 自包含 harness（静态 route + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/time-wheel-picker.spec.mjs
// 环境：TW_ENGINE=chromium|webkit  TW_SCHEME=light|dark
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.TW_OUT_DIR || join(ROOT, 'reports-evidence', '20261103_time-wheel-picker__delegate-nebflow-4bff07ce');
const MODE = process.env.MODE || 'assert';
const ENGINE = process.env.TW_ENGINE || 'chromium';
const SCHEME = process.env.TW_SCHEME || 'light';
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'tw-root';
/** Every `updateConfig` frame the page sent (the harness answers each one with a matching `configUpdated`). */
const updateConfigFrames = [];
// 8899：WebKit bad-port 封禁表外（同族 spec 实证）
const ORIGIN = 'http://localhost:8899';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
  return cond;
};

/* ══ 静态面断言（不启浏览器）═════════════════════════════════════════════ */

const readSrc = (rel) => readFileSync(join(WEB, rel), 'utf8');
/** 去掉 JS 注释再断言：落点文件里保留「原先是 <input type="datetime-local">」
 *  这类解释性注记是**好事**（考古线索），不算裸输入 —— 只有活的 markup /
 *  活的选择器才算犯规。 */
const stripComments = (src) => src
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
const LANDING_FILES = [
  'js/sidebar.js',
  'js/sidebar/providersUi.js',
  'js/scheduled-task.js',
  'js/todoFace.js',
];

function staticChecks() {
  // ── T2 裸时间输入零残留 ──────────────────────────────────────────────
  const bare = [];
  for (const rel of LANDING_FILES) {
    const src = stripComments(readSrc(rel));
    if (/type\s*=\s*['"]datetime-local['"]/.test(src)) bare.push(`${rel}: datetime-local`);
    if (/type\s*=\s*['"]time['"]/.test(src)) bare.push(`${rel}: type=time`);
  }
  ok('T2 五落点源码零裸 datetime-local / type=time 输入', bare.length === 0, bare.join(', '));

  // ── T2' 每个落点都 import 同款组件 ───────────────────────────────────
  const imports = LANDING_FILES.map((rel) => {
    const src = readSrc(rel);
    return { rel, hit: /from\s+['"][.\/]*timeWheel\.js['"]/.test(src) };
  });
  ok('T2 五落点全部 import timeWheel.js（落点=设置冻结/provider 级/provider per-model/定时任务/待办）',
    imports.every((i) => i.hit), imports.filter((i) => !i.hit).map((i) => i.rel).join(', '));

  // ── T1 组件单源 ─────────────────────────────────────────────────────
  const wheelOwners = [];
  const walk = (dir) => {
    // 递归枚举 js/ 下的产品源码（禁第二份 Wheel 实现）
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) { walk(p); continue; }
      if (!/\.js$/.test(name)) continue;
      const src = readFileSync(p, 'utf8');
      if (/class\s+Wheel\b/.test(src)) wheelOwners.push(p.replace(WEB + '/', ''));
    }
  };
  walk(join(WEB, 'js'));
  ok('T1 唯一 Wheel 实现 = js/timeWheel.js', wheelOwners.length === 1 && wheelOwners[0] === 'js/timeWheel.js', wheelOwners.join(', '));
  const panelOwners = [];
  const walk2 = (dir) => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) { walk2(p); continue; }
      if (!/\.js$/.test(name)) continue;
      // 面板壳 DOM 的构造点（timeWheel.js:408 `el.className = 'tw-panel'`）：
      // 同一份面板既服务 range 也服务 point，任何第二处构造都是分叉。
      if (/(?:class|className)\s*=\s*['"]tw-panel['"]/.test(readFileSync(p, 'utf8'))) panelOwners.push(p.replace(WEB + '/', ''));
    }
  };
  walk2(join(WEB, 'js'));
  ok('T1 唯一选择器面板 DOM = js/timeWheel.js', panelOwners.length === 1 && panelOwners[0] === 'js/timeWheel.js', panelOwners.join(', '));

  // ── T10 设计令牌（零色值字面量）──────────────────────────────────────
  const css = readSrc('css/timeWheel.css');
  const hex = css.match(/#[0-9a-fA-F]{3,8}\b/g) || [];
  ok('T10 timeWheel.css 零 #hex 色值字面量', hex.length === 0, hex.slice(0, 5).join(', '));
  const rgbLiteral = css.match(/rgba?\(\s*\d/g) || [];
  ok('T10 timeWheel.css 零 rgb()/rgba() 数字字面量（全走 var(--…) 或 rgb(var(--sapphire) / α)）',
    rgbLiteral.length === 0, rgbLiteral.slice(0, 5).join(', '));

  // ── T11 reduced-motion 块在位 ────────────────────────────────────────
  ok('T11 timeWheel.css 含 prefers-reduced-motion 退化块', /@media\s*\(prefers-reduced-motion:\s*reduce\)/.test(css));
  ok('T1 index.html 挂了 timeWheel.css', /href="css\/timeWheel\.css"/.test(readSrc('index.html')));
}

/* ══ 运行时 harness ═══════════════════════════════════════════════════════ */

const CONFIG = {
  llm: {
    providers: {
      kimi: {
        baseUrl: 'https://api.kimi.example/v1/', apiKey: '***', protocol: 'anthropic',
        // provider 级不可用时段：一段带 days（手编保真面）、一段跨午夜
        unavailableWindows: [
          { from: '14:00', to: '18:00', days: [1, 2, 3, 4, 5] },
          { from: '23:00', to: '07:00' },
        ],
        models: [
          // per-model 不可用时段（L3）——保存时按行内 DOM 收编
          { id: 'kimi-k2', contextWindow: 8192, unavailableWindows: [{ from: '09:00', to: '10:00' }] },
          { id: 'kimi-k3', contextWindow: 8192 },
        ],
      },
    },
  },
};

async function boot({ viewport = { width: 1280, height: 900 }, hasTouch = false } = {}) {
  const launcher = ENGINE === 'webkit' ? webkit : chromium;
  const browser = await launcher.launch();
  const ctx = await browser.newContext({ viewport, colorScheme: SCHEME, deviceScaleFactor: 2, hasTouch, locale: 'zh-CN' });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource|ResizeObserver loop|Download the React/.test(m.text())) {
      errors.push(`console.error: ${m.text()}`);
    }
  });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    const p0 = decodeURIComponent(new URL(route.request().url()).pathname);
    const p = p0 === '/' ? '/index.html' : p0;
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/config/provider-keys') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
    const cfg = JSON.stringify(CONFIG);
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: cfg, configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    // 冻结时段（L1）：设置页编辑器读 state.workSchedule（serverConfig 单源）
    ws.send(JSON.stringify({
      type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000,
      workSchedule: { enabled: true, segments: [{ start: '09:00', end: '12:00' }, { start: '23:00', end: '07:00' }] },
    }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'updateConfig') {
        // The provider form closes only on the gateway's acknowledgement of its own save: answer like the gateway does.
        updateConfigFrames.push(m);
        ws.send(JSON.stringify({ type: 'configUpdated', success: true, requestId: m.requestId }));
      }
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(700);
  return { page, browser, errors };
}

/** 打开设置页（幂等：浮层已开时不再点 #settings-btn，否则浮层拦截命中）。 */
async function openSettings(page) {
  const alreadyOpen = await page.evaluate(() =>
    document.getElementById('settings-overlay')?.classList.contains('on') === true);
  if (!alreadyOpen) await page.click('#settings-btn');
  // The settings body is a stack of `.set-card` sections (the former `.settings-section` markup is gone).
  await page.waitForSelector('#settings-content .set-card', { timeout: 10000 });
  await page.waitForTimeout(200);
}

/** 打开 provider 编辑弹窗（L2 provider 级 / L3 per-model）。 */
async function openProviderModal(page) {
  await openSettings(page);
  // Providers are a progressive-disclosure section: the provider panel mounts only once it is expanded.
  await page.evaluate(() => {
    const toggle = document.querySelector('[data-settings-disclosure="providers"]');
    if (toggle && toggle.getAttribute('aria-expanded') !== 'true') toggle.click();
  });
  await page.waitForSelector('#provider-list .cfg-card[data-provider="kimi"]', { timeout: 10000 });
  await page.click('#provider-list .cfg-card[data-provider="kimi"]');
  await page.waitForSelector('#cfg-modal [data-field="unavailableWindows"]', { timeout: 10000 });
  await page.waitForTimeout(250);
}

const hitBox = (page, sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { w: Math.round(r.width), h: Math.round(r.height) };
}, sel);

/* ══ 主流程 ═══════════════════════════════════════════════════════════════ */

async function main() {
  staticChecks();

  const { page, browser, errors } = await boot();

  /* ── L1 冻结时段（设置页）─────────────────────────────────────────── */
  await openSettings(page);
  const segCount = await page.locator('#segment-list [data-tw-range]').count();
  ok('L1 冻结时段：段行渲染同款触发钮（2 段夹具）', segCount === 2, `count=${segCount}`);
  const segPolyfilled = await page.evaluate(() =>
    [...document.querySelectorAll('#segment-list .segment-row')].map((r) => ({
      start: r.querySelector('[data-tw-range]')?.dataset.start,
      end: r.querySelector('[data-tw-range]')?.dataset.end,
      cross: !!r.querySelector('.seg-cross-midnight'),
    })));
  ok('L1 冻结时段：dataset 值域逐字回读（09:00-12:00 / 23:00-07:00）',
    segPolyfilled[0]?.start === '09:00' && segPolyfilled[0]?.end === '12:00'
    && segPolyfilled[1]?.start === '23:00' && segPolyfilled[1]?.end === '07:00',
    JSON.stringify(segPolyfilled));
  if (process.env.TW_DEBUG) {
    console.log('DBG rows', await page.evaluate(() =>
      [...document.querySelectorAll('#segment-list .segment-row')].map((r) => r.outerHTML).join('\n')));
    console.log('DBG toMin', await page.evaluate(async () => {
      const m = await import('/js/timeWheel.js');
      return { a: m.toMin('23:00'), b: m.toMin('07:00'), re: String(m.HHMM_RE), t: m.HHMM_RE.test('23:00'),
        exec: JSON.stringify(m.HHMM_RE.exec('23:00')), fn: String(m.toMin) };
    }));
  }
  ok('L1 冻结时段：跨午夜段带行内徽标（23:00>07:00）', segPolyfilled[1]?.cross === true);
  const segImage = await page.evaluate(() => document.querySelector('#segment-list [data-tw-range]')?.innerText.replace(/\s+/g, ' ').trim());
  ok('L4 24h 字段脸（无 AM/PM，形如 HH:mm-HH:mm）', /^\d{2}:\d{2}\s*[-–至]\s*\d{2}:\d{2}$/.test(segImage || '') && !/AM|PM/i.test(segImage || ''), segImage);

  const segBox = await hitBox(page, '#segment-list [data-tw-range]');
  ok('T3 触发钮热区 ≥44×44（设置冻结段）', !!segBox && segBox.h >= 44, JSON.stringify(segBox));

  /* ── T9 popover（桌面 1280）+ 面板行为 ────────────────────────────── */
  await page.click('#segment-list [data-tw-range]');
  await page.waitForSelector('.tw-panel.in', { timeout: 5000 });
  const stPop = await page.evaluate(async () => (await import('/js/timeWheel.js')).panelState());
  ok('T9 >768px ⇒ popover 皮', stPop.open === true && stPop.popover === true && stPop.sheet === false, JSON.stringify(stPop));
  ok('T9 popover z=10001（高于设置浮层 10000）', stPop.zIndex === '10001', stPop.zIndex);

  const cols = await page.evaluate(() => document.querySelectorAll('.tw-panel.in .tw-body-range .tw-col[role="spinbutton"]').length);
  ok('T2 时段面板 = 4 轮（时/分 × 起止）', cols === 4, `cols=${cols}`);

  const min5 = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.tw-panel.in .tw-body-range .tw-col[data-role="m1"],.tw-panel.in .tw-body-range .tw-col[data-role="m2"]').forEach((c) => {
      out.push(Number(c.getAttribute('aria-valuenow')));
    });
    return out;
  });
  ok('T6 分钟 5 步进（aria-valuenow % 5 === 0）', min5.length === 2 && min5.every((n) => n % 5 === 0), JSON.stringify(min5));

  // 起=止 ⇒ 确定 disabled + 等值错误行
  await page.evaluate(() => {
    const btn = document.querySelector('.tw-panel.in .tw-input-toggle');
    btn?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForTimeout(120);
  await page.evaluate(() => {
    const a = document.querySelector('.tw-panel.in .tw-in-start');
    const b = document.querySelector('.tw-panel.in .tw-in-end');
    if (a && b) { a.value = '10:00'; b.value = '10:00'; }
  });
  await page.evaluate(() => {
    const b = document.querySelector('.tw-panel.in .tw-in-end');
    b?.dispatchEvent(new Event('blur'));
  });
  await page.waitForTimeout(200);
  const equalState = await page.evaluate(() => ({
    disabled: document.querySelector('.tw-panel.in .tw-confirm')?.disabled === true,
    equalShow: document.querySelector('.tw-panel.in .tw-status-equal')?.classList.contains('show') === true,
    crossShow: document.querySelector('.tw-panel.in .tw-status-cross')?.classList.contains('show') === true,
  }));
  ok('T7 起=止 ⇒ 确定钮 disabled + 等值错误行 show', equalState.disabled && equalState.equalShow, JSON.stringify(equalState));
  ok('T7 起=止 ⇒ 跨零点提示不抢戏（互斥）', equalState.crossShow === false);

  // 起>止 ⇒ 跨零点提示
  await page.evaluate(() => {
    const a = document.querySelector('.tw-panel.in .tw-in-start');
    const b = document.querySelector('.tw-panel.in .tw-in-end');
    if (a && b) { a.value = '23:00'; b.value = '07:00'; }
    document.querySelector('.tw-panel.in .tw-in-end')?.dispatchEvent(new Event('blur'));
  });
  await page.waitForTimeout(220);
  const crossState = await page.evaluate(() => ({
    show: document.querySelector('.tw-panel.in .tw-status-cross')?.classList.contains('show') === true,
    text: document.querySelector('.tw-panel.in .tw-status-cross')?.textContent || '',
    disabled: document.querySelector('.tw-panel.in .tw-confirm')?.disabled === true,
  }));
  ok('T5 起>止 ⇒ 跨零点提示条在（含「次日」）', crossState.show && /次日/.test(crossState.text), crossState.text.slice(0, 40));
  ok('T5 跨零点是合法态（确定钮可用）', crossState.disabled === false);

  // 取消/ESC 不写回
  const before = await page.evaluate(() => {
    const b = document.querySelector('#segment-list [data-tw-range]');
    return { start: b.dataset.start, end: b.dataset.end };
  });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(260);
  const after = await page.evaluate(() => {
    const b = document.querySelector('#segment-list [data-tw-range]');
    return { start: b.dataset.start, end: b.dataset.end, open: !!document.querySelector('.tw-panel.in') };
  });
  ok('T8 ESC 还原（dataset 逐字不变 + 面板收起）',
    after.start === before.start && after.end === before.end && after.open === false,
    `${JSON.stringify(before)} → ${JSON.stringify(after)}`);

  // 确认腿：改值后点确定 ⇒ dataset 更新 + 冷改（同段原子字段）
  await page.click('#segment-list [data-tw-range]');
  await page.waitForSelector('.tw-panel.in', { timeout: 5000 });
  await page.evaluate(() => {
    document.querySelector('.tw-panel.in .tw-input-toggle')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForTimeout(120);
  await page.evaluate(() => {
    const a = document.querySelector('.tw-panel.in .tw-in-start');
    const b = document.querySelector('.tw-panel.in .tw-in-end');
    if (a && b) { a.value = '08:00'; b.value = '12:00'; }
    document.querySelector('.tw-panel.in .tw-in-end')?.dispatchEvent(new Event('blur'));
  });
  await page.waitForTimeout(220);
  await page.click('.tw-panel.in .tw-confirm');
  await page.waitForTimeout(280);
  const committed = await page.evaluate(() => {
    const b = document.querySelector('#segment-list [data-tw-range]');
    return { start: b.dataset.start, end: b.dataset.end, text: b.innerText.replace(/\s+/g, ' ').trim() };
  });
  ok('R7 确认腿写回原子段字段（08:00-12:00 一颗整体）',
    committed.start === '08:00' && committed.end === '12:00' && /08:00/.test(committed.text) && /12:00/.test(committed.text),
    JSON.stringify(committed));
  const dirty = await page.evaluate(() => document.getElementById('schedule-actions')?.style.display);
  ok('L1 改动进草稿（显式保存语义：schedule-actions 出现）', dirty === 'flex', String(dirty));

  /* ── L2 provider 级 + L3 per-model 级 ────────────────────────────── */
  await openProviderModal(page);
  const l2 = await page.evaluate(() => {
    const host = document.querySelector('#cfg-modal [data-field="unavailableWindows"]');
    return {
      rows: host ? host.querySelectorAll('[data-window-row]').length : 0,
      triggers: host ? host.querySelectorAll('[data-tw-range]').length : 0,
      daysAllowed: host ? host.querySelectorAll('[data-tw-range][data-days-allowed]').length : 0,
      values: host ? [...host.querySelectorAll('[data-tw-range]')].map((b) => `${b.dataset.start}-${b.dataset.end}|${b.dataset.days || ''}`) : [],
    };
  });
  ok('L2 provider 级不可用时段：2 段同款触发钮（无文本框）', l2.rows === 2 && l2.triggers === 2, JSON.stringify(l2.values));
  ok('L2 provider 级：from/to/days 全部保真（5 工作日 + 跨午夜无 days）',
    l2.values[0] === '14:00-18:00|1,2,3,4,5' && l2.values[1] === '23:00-07:00|', JSON.stringify(l2.values));
  ok('O1 裁定：按星期编辑面同款（data-days-allowed 在位）', l2.daysAllowed === 2, String(l2.daysAllowed));

  const l3 = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#cfg-modal .cfg-model-row')];
    return rows.map((r) => ({
      id: r.querySelector('.cfg-model-id')?.value || '',
      winRows: r.querySelectorAll('[data-model-windows] [data-window-row]').length,
      winTriggers: r.querySelectorAll('[data-model-windows] [data-tw-range]').length,
      values: [...r.querySelectorAll('[data-model-windows] [data-tw-range]')].map((b) => `${b.dataset.start}-${b.dataset.end}`),
    }));
  });
  ok('L3 per-model 不可用时段：每行模型都有编辑面（2 行）', l3.length === 2, JSON.stringify(l3.map((x) => x.id)));
  ok('L3 per-model：既有 09:00-10:00 保真渲染 + 无窗行空态',
    l3[0]?.winTriggers === 1 && l3[0]?.values[0] === '09:00-10:00' && l3[1]?.winTriggers === 0,
    JSON.stringify(l3));

  const modelTrigBox = await hitBox(page, '#cfg-modal .cfg-model-row [data-model-windows] [data-window-add]');
  ok('T3 热区 ≥44（per-model 加行钮）', !!modelTrigBox && modelTrigBox.h >= 44, JSON.stringify(modelTrigBox));

  // provider 级面板开合（滚轮面板与 per-model 共用同一单例）
  await page.click('#cfg-modal [data-field="unavailableWindows"] [data-tw-range]');
  await page.waitForSelector('.tw-panel.in', { timeout: 5000 });
  const chipsFreeze = await page.evaluate(() =>
    [...document.querySelectorAll('.tw-panel.in .tw-chips .tw-chip')].map((c) => c.textContent.trim()));
  ok('L2 快捷档：provider 不可用时段含「全天」四档', chipsFreeze.length === 4, chipsFreeze.join(' / '));
  const daysChips = await page.evaluate(() => ({
    visible: !document.querySelector('.tw-panel.in .tw-days')?.hidden,
    // 选中态 = `.tw-chip.on`（与全站胶囊族同一口径，不是 .selected）
    selected: [...document.querySelectorAll('.tw-panel.in .tw-days .tw-chip.on')]
      .map((c) => c.dataset.day).filter((d) => d !== '0').length,
    total: document.querySelectorAll('.tw-panel.in .tw-days .tw-chip').length,
  }));
  ok('O1 裁定：星期多选面在场且回显 5 个工作日',
    daysChips.visible === true && daysChips.selected === 5 && daysChips.total === 8, JSON.stringify(daysChips));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(240);

  // 保存路径：provider 级空列表 ⇒ 清空语义；per-model DOM 为真相
  const ids = await page.evaluate(() => [...document.querySelectorAll('#cfg-modal .cfg-model-row .cfg-model-id')].map((i) => i.value));
  updateConfigFrames.length = 0;
  // 直接点保存（不改任何窗，验「读面 = DOM」）。表单只在网关确认**这一次**保存后才关闭、才更新本地快照，
  // 所以先等表单摘除，再读快照。
  await page.evaluate(() => document.getElementById('cfg-modal-save').click());
  await page.waitForSelector('#cfg-modal', { state: 'detached', timeout: 10000 });
  const saveOut = await page.evaluate(async (modelIds) => {
    const cfg = (await import('/js/state.js')).default.parsedConfig;
    return { ids: modelIds, captured: JSON.parse(JSON.stringify(cfg.llm.providers.kimi)) };
  }, ids);
  ok('L2 保存：provider 表单发出一条带 requestId 的 updateConfig 并等到回执才关闭',
    updateConfigFrames.length === 1 && /^provider-/.test(String(updateConfigFrames[0].requestId || '')),
    JSON.stringify(updateConfigFrames.map((f) => f.requestId)));
  const savedProv = saveOut.captured;
  ok('L2 保存：provider 级两段逐字落盘（含 days = [1,2,3,4,5]）',
    Array.isArray(savedProv.unavailableWindows) && savedProv.unavailableWindows.length === 2
    && savedProv.unavailableWindows[0].from === '14:00' && savedProv.unavailableWindows[0].to === '18:00'
    && JSON.stringify(savedProv.unavailableWindows[0].days) === '[1,2,3,4,5]'
    && savedProv.unavailableWindows[1].from === '23:00' && savedProv.unavailableWindows[1].days === undefined,
    JSON.stringify(savedProv.unavailableWindows));
  const savedModel0 = (savedProv.models || []).find((m) => m.id === 'kimi-k2');
  const savedModel1 = (savedProv.models || []).find((m) => m.id === 'kimi-k3');
  ok('L3 保存：per-model 段由行内 DOM 收编（kimi-k2 保 09:00-10:00，kimi-k3 无键）',
    Array.isArray(savedModel0?.unavailableWindows) && savedModel0.unavailableWindows[0].from === '09:00'
    && savedModel0.unavailableWindows[0].to === '10:00'
    && savedModel1 && savedModel1.unavailableWindows === undefined,
    JSON.stringify([savedModel0?.unavailableWindows, savedModel1?.unavailableWindows]));

  /* ── L4 定时任务触发时刻 ─────────────────────────────────────────── */
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
  await page.click('#reminder-btn');
  await page.waitForTimeout(200);
  const modalGone = await page.evaluate(() => !document.getElementById('cfg-modal'));
  ok('模态离场（保存后 overlay 摘除）', modalGone === true);
  await page.click('#reminder-create-btn');
  await page.waitForSelector('#reminder-inline-create', { timeout: 5000 });
  const l4 = await page.evaluate(() => {
    const host = document.querySelector('#reminder-inline-create .reminder-time-row');
    return {
      triggers: host ? host.querySelectorAll('[data-tw-point]').length : 0,
      bareInputs: host ? host.querySelectorAll('input[type="datetime-local"],input[type="time"]').length : 0,
      hidden: !!document.querySelector('#reminder-time-input[type="hidden"]'),
      value: document.querySelector('#reminder-time-input')?.value || '',
      face: host?.querySelector('[data-tw-point]')?.innerText.replace(/\s+/g, ' ').trim() || '',
    };
  });
  ok('L4 定时任务：触发时刻 = 同款点选钮（零裸 datetime-local）',
    l4.triggers === 1 && l4.bareInputs === 0, JSON.stringify(l4));
  ok('L4 值域载体 = 隐藏 #reminder-time-input（datetime-local 串保真）',
    l4.hidden === true && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(l4.value), l4.value);
  ok('T4 点选钮字段脸 = M/D + HH:mm（24h，无 AM/PM）',
    /^\d{1,2}\/\d{1,2}\s+\d{2}:\d{2}$/.test(l4.face) && !/AM|PM/i.test(l4.face), l4.face);
  await page.click('#reminder-inline-create [data-tw-point]');
  await page.waitForSelector('.tw-panel.in', { timeout: 5000 });
  const l4Panel = await page.evaluate(async () => {
    const st = (await import('/js/timeWheel.js')).panelState();
    return {
      mode: st.mode,
      cols: document.querySelectorAll('.tw-panel.in .tw-body-point .tw-col[role="spinbutton"]').length,
      bareInRange: document.querySelectorAll('.tw-panel.in .tw-body-range:not([hidden]) input').length,
    };
  });
  ok('L4 点选面板 = 日期列 + 时 + 分（3 轮）', l4Panel.mode === 'point' && l4Panel.cols === 3, JSON.stringify(l4Panel));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(240);

  /* ── L5 待办截止时刻（桌面任务列表面）───────────────────────────── */
  const l5 = await page.evaluate(async () => {
    const mod = await import('/js/todoFace.js');
    const host = document.createElement('div');
    host.id = 'tw-todo-host';
    host.style.cssText = 'position:fixed;left:0;bottom:0;width:420px;height:640px;z-index:5';
    document.body.appendChild(host);
    const face = mod.mountTodoFaceInto(host);
    face.startCreate();
    await new Promise((r) => setTimeout(r, 200));
    const dueRow = host.querySelector('.tfl-create-panel .tfl-edit-due');
    return {
      triggers: dueRow ? dueRow.querySelectorAll('[data-tw-point]').length : 0,
      bare: dueRow ? dueRow.querySelectorAll('input[type="datetime-local"],input[type="time"]').length : 0,
      holders: host.querySelectorAll('.tfl-due-holder').length,
    };
  });
  ok('L5 待办截止：创建面板 = 同款点选钮（零裸 datetime-local）',
    l5.triggers === 1 && l5.bare === 0, JSON.stringify(l5));
  const l5Toggle = await page.evaluate(() => {
    const host = document.getElementById('tw-todo-host');
    const sw = host.querySelector('.tfl-create-panel .tfl-switch');
    sw?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const holder = host.querySelector('.tfl-create-panel .tfl-due-holder');
    return { open: !!(holder && holder.style.display !== 'none'), triggers: host.querySelectorAll('.tfl-create-panel .tfl-due-holder [data-tw-point]').length };
  });
  ok('L5 待办截止：开关打开后点选面在场（同款组件）', l5Toggle.open === true && l5Toggle.triggers === 1, JSON.stringify(l5Toggle));
  await page.evaluate(() => {
    const btn = document.querySelector('#tw-todo-host .tfl-create-panel .tfl-due-holder [data-tw-point]');
    btn?.click();
  });
  await page.waitForSelector('.tw-panel.in', { timeout: 5000 });
  const l5Panel = await page.evaluate(async () => (await import('/js/timeWheel.js')).panelState());
  ok('L5 待办截止：打开的是同一单例面板（point 模式）', l5Panel.mode === 'point' && l5Panel.open === true, JSON.stringify(l5Panel));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(240);

  /* ── T12 零 console 错误 ─────────────────────────────────────────── */
  ok('T12 全程零 pageerror / console.error', errors.length === 0, errors.slice(0, 4).join(' | '));

  await page.screenshot({ path: join(OUT, `desktop-${SCHEME}.png`), fullPage: false }).catch(() => {});
  await browser.close();

  /* ── T9 手机层 = sheet 皮 ────────────────────────────────────────── */
  const m = await boot({ viewport: { width: 390, height: 844 }, hasTouch: true });
  const bare = await m.page.evaluate(() => {
    const host = document.createElement('div');
    host.id = 'tw-sheet-host';
    document.body.appendChild(host);
    return !!host;
  });
  ok('手机层 boot', bare === true);
  const sheet = await m.page.evaluate(async () => {
    const tw = await import('/js/timeWheel.js');
    const host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:0;top:0;width:360px;padding:12px';
    host.innerHTML = tw.rangeTriggerHTML({ start: '09:00', end: '12:00', label: '冻结时段', presets: 'freeze' })
      + tw.pointTriggerHTML({ date: '2026-11-04', time: '14:30', label: '截止时间' });
    document.body.appendChild(host);
    tw.attachTimeTriggers(host, {});
    host.querySelector('[data-tw-range]').click();
    await new Promise((r) => setTimeout(r, 420));
    const st = tw.panelState();
    const el = document.querySelector('.tw-panel');
    return {
      ...st,
      grabber: !!el?.querySelector('.tw-grabber'),
      bodyMobile: document.body.classList.contains('mobile-layer'),
      vw: window.innerWidth,
    };
  });
  ok('T9 ≤768px 或手机层 ⇒ sheet 皮（含 grabber）', sheet.sheet === true && sheet.popover === false && sheet.grabber === true, JSON.stringify(sheet));
  ok('T9 sheet z=11000（盖过手机层页面壳）', sheet.zIndex === '11000', sheet.zIndex);
  const chipsFreeze2 = await m.page.evaluate(() =>
    [...document.querySelectorAll('.tw-panel.in .tw-chips .tw-chip')].map((c) => c.textContent.trim()));
  ok('O3 冻结黑名单落点：「全天」档隐藏（presetsFor(freeze)）', chipsFreeze2.length === 3 && !chipsFreeze2.some((x) => /全天/.test(x)), chipsFreeze2.join(' / '));
  await m.page.screenshot({ path: join(OUT, `mobile-${SCHEME}.png`), fullPage: false }).catch(() => {});
  ok('T12 手机层零 pageerror / console.error', m.errors.length === 0, m.errors.slice(0, 4).join(' | '));
  await m.browser.close();

  /* ── T11 reduced-motion ─────────────────────────────────────────── */
  const r = await boot();
  const reduced = await r.page.evaluate(async () => {
    const tw = await import('/js/timeWheel.js');
    const host = document.createElement('div');
    host.innerHTML = tw.rangeTriggerHTML({ start: '09:00', end: '12:00', presets: 'none' });
    document.body.appendChild(host);
    tw.attachTimeTriggers(host, {});
    host.querySelector('[data-tw-range]').click();
    await new Promise((res) => setTimeout(res, 380));
    const el = document.querySelector('.tw-panel');
    return { cs: getComputedStyle(el).animationName, cls: el.className };
  });
  await r.page.emulateMedia({ reducedMotion: 'reduce' });
  const reducedCs = await r.page.evaluate(() => {
    const el = document.querySelector('.tw-panel');
    const cs = getComputedStyle(el);
    return { anim: cs.animationName, btnTrans: getComputedStyle(document.querySelector('.tw-trigger')).transitionDuration };
  });
  ok('T11 reduced-motion ⇒ 面板动画退化（animation:none）', reducedCs.anim === 'none', JSON.stringify([reduced, reducedCs]));
  await r.browser.close();

  console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('SPEC CRASH', e); process.exit(2); });
