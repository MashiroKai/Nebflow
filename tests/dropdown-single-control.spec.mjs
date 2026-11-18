#!/usr/bin/env node
// dropdown-single-control.spec.mjs — 「同一字段只渲染一个下拉控件」真渲染检查
// （2026-11-18 作者实拍报障「电脑端的下拉选框，怎么有重复」修复批，作者令：统一
//   必须形成自动化检测 ⇒ 本文件断言受影响字段的可见控件数，不是看源码）。
//
// 判据（每个受影响字段各一行）：可见下拉控件数 === 1，且那一个必须是统一 trigger
// （旧原生 select 不许是可见的那一个），且包裹层里不留第二行（触发件与包裹层同框）。
//   控件 = ① 统一 trigger（.nf-select-trigger，渲染中）+ ② 被替换的原生
//   <select>（若仍渲染 = 旧控件复活）+ ③ 同一行容器里的其余 <select>（半迁移残留）。
//
// 三个资产状态各测一遍：
//   · as-shipped            ：源树本来的样子（健康态，恒 1）；
//   · stack-ineffective     ：把 css/dropdown.css 整条不变量的**全部声明**都压掉
//                             （栈 display / 同格 grid-area / 隐藏 opacity+pointer-events）
//                             —— 即作者两张实拍的状态：收编照旧发生（trigger 的脸来自
//                             另一个文件 css/inputSurface.css），栈与隐藏一起失效 ⇒
//                             设置页模型卡 = 胶囊在上 + 通栏原生 select（同值 + 右侧大箭头）
//                             在下；delegate 弹窗头 = 胶囊的「字 + 箭头」与原生件并排。
//                             修复前此状态下每字段 = 2（且包裹层多出一行）；
//                             修复后不变量由收编器 inline 拥有 ⇒ 状态无关，恒为 1。
//   · hiding-ineffective    ：只压掉隐藏那一条（窄口径回归；修复前 = 2，两个控件叠在同一格）。
//
// 视口：桌面 1280×800 与手机 375×812（作者令两视口都要）；两处受影响面都测：
//   设置页模型配置卡（9 个字段）+ delegate 弹窗头（#bgagent-model-switch）。
// 用法：node tests/dropdown-single-control.spec.mjs
//   可选 env：WEB_DIR=<web 根>（改前树对照用）· SHOTS=1（写取景帧）·
//            SHOTS_DIR=<帧目录> · TAG=<前/后标签> · LOG=<日志路径>
// 产出：<LOG>（默认 /private/tmp/dropdown-single-control/log.txt）；SHOTS=1 时
//       <SHOTS_DIR>/<TAG>-<viewport>-<state>-<place>.png；任一 FAIL ⇒ exit 1。

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(process.env.WEB_DIR || join(ROOT, 'src/main/resources/web'));
const PORT = Number(process.env.PORT || 8397);
const BASE = `http://127.0.0.1:${PORT}`;
const LOG = process.env.LOG || '/private/tmp/dropdown-single-control/log.txt';
const SHOTS = process.env.SHOTS === '1';
const SHOTS_DIR = process.env.SHOTS_DIR || '/private/tmp/dropdown-single-control/shots';
const TAG = process.env.TAG || 'after';
const SID = 'dropdown-single-root';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(dirname(LOG), { recursive: true });
writeFileSync(LOG, '');
if (SHOTS) mkdirSync(SHOTS_DIR, { recursive: true });

const SETTINGS_FIELDS = [
  'cfg-language', 'cfg-thinking-effort',
  'model-role-select-Nebula', 'model-role-select-kernel', 'model-role-select-project-dispatcher',
  'model-role-select-node', 'model-role-select-subagent',
  'small-llm-select', 'imagegen-settings-select',
];
const DELEGATE_FIELD = 'bgagent-model-switch';
const PROVIDERS = {
  zhipu: { baseUrl: 'https://api.zhipu.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'glm-5.3-flash' }] },
  cmdcode: { baseUrl: 'https://api.cmd.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'IricalGpt/gpt-6.1-terra' }, { id: 'IricalGpt/gpt-6.1-luna' }] },
};
// 显式独立配置的槽（复刻作者实拍：subagent 钉 terra、通用小 LLM 钉 luna）。
const EXPLICIT = {
  Nebula: { preferred: 'cmdcode/IricalGpt/gpt-6.1-luna', fallbacks: [] },
  subagent: { preferred: 'cmdcode/IricalGpt/gpt-6.1-terra', fallbacks: [] },
  'small-llm': { preferred: 'cmdcode/IricalGpt/gpt-6.1-luna', fallbacks: [] },
};
// 三个资产状态（非 important 的压条：修复前它们就是生效声明，修复后被收编器的
// inline 声明压住 —— 这正是「不变量归谁拥有」的分界）。
const STATES = {
  'as-shipped': '',
  // 整份 dropdown.css 的不变量全部压掉：栈 + 同格 + 隐藏（作者实拍形态）。
  'stack-ineffective': `.nf-select { display: inline; vertical-align: baseline; max-width: none; }
.nf-select > * { grid-area: auto; }
.nf-select .nf-select-native { opacity: 1; pointer-events: auto; }`,
  // 只压隐藏一条（窄口径）。
  'hiding-ineffective': '.nf-select .nf-select-native { opacity: 1; pointer-events: auto; }',
};

let pass = 0;
let fail = 0;
const out = [];
const log = (line) => { console.log(line); out.push(line); };

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const webServer = createServer((req, res) => {
  const p0 = decodeURIComponent(new URL(req.url, BASE).pathname);
  const p = p0 === '/' ? '/index.html' : p0;
  if (p.startsWith('/api/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/** 页内读数：每个受影响字段的可见下拉控件数 + 包裹层有没有多出的第二行。 */
const MEASURE = (fields) => fields.map((id) => {
  const sel = document.getElementById(id);
  if (!sel) return { id, present: false, count: 0 };
  const rendered = (el) => {
    if (!el) return false;
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return cs.display !== 'none' && cs.visibility !== 'hidden' && Number(cs.opacity) > 0.01 && r.width > 0 && r.height > 0;
  };
  const wrap = sel.closest('.nf-select');
  const trigger = wrap ? wrap.querySelector('.nf-select-trigger') : null;
  const container = (wrap || sel).parentElement;
  const others = container ? [...container.querySelectorAll('select')].filter((s) => s !== sel) : [];
  const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { top: +r.top.toFixed(1), left: +r.left.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
  const wb = box(wrap); const tb = box(trigger);
  // 第二行残留：包裹层比触发件高，或触发件不在包裹层顶部（栈失效 ⇒ 旧控件另起一行）。
  const residualRow = !!(wb && tb && (wb.h - tb.h > 1 || tb.top - wb.top > 1));
  return {
    id, present: true,
    triggerRendered: rendered(trigger),
    nativeRendered: rendered(sel),
    othersRendered: others.filter(rendered).length,
    count: (rendered(trigger) ? 1 : 0) + (rendered(sel) ? 1 : 0) + others.filter(rendered).length,
    residualRow,
  };
});

async function boot(browser, viewport) {
  const ctx = await browser.newContext({ viewport, colorScheme: 'light' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 200)}`));
  await page.route('**/agents/*/agent.json', (r) => r.fulfill({ json: {} }));
  await page.route('**/api/**', (r) => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    if (p === '/api/sessions') return r.fulfill({ json: { sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID } });
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      const role = decodeURIComponent(p.split('/')[3]);
      const own = EXPLICIT[role] || null;
      const effective = own || EXPLICIT.Nebula;
      return r.fulfill({ json: { mode: own ? 'explicit' : 'follow', chain: own, effectiveChain: effective, resolvedFrom: 'seed', current: effective.preferred, settable: true } });
    }
    return r.fulfill({ json: {} });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({
        llm: { providers: PROVIDERS },
        imagegen: { defaultProvider: 'zhipu', providers: { zhipu: { type: 'openai', model: 'cogview-4' } } },
      }),
    }));
    sendConfig();
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: SID, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await sleep(700);
  return { ctx, page, errors };
}

async function openSettings(page, mobile) {
  if (mobile) {
    await page.evaluate(async () => (await import('/js/mobile/navController.js')).pushPage('settings'));
    await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings"]')?.classList.contains('live'), null, { timeout: 15000 });
  } else {
    await page.click('#settings-btn');
  }
  await sleep(400);
  const disclosure = page.locator('#model-chain-section [data-settings-disclosure="models"]');
  if (await disclosure.count() && (await disclosure.getAttribute('aria-expanded')) !== 'true') await disclosure.click();
  await page.waitForSelector('#model-chain-section-body [data-role="subagent"]', { state: 'attached', timeout: 15000 });
  await page.evaluate(() => document.getElementById('model-chain-section')?.scrollIntoView({ block: 'start' }));
  await sleep(600);
}

/** delegate 弹窗头（作者实拍第一处）：真模块 openStepPopup。 */
async function openDelegate(page) {
  await page.evaluate(async ([sid]) => {
    const m = await import('/js/bgAgentPopup.js');
    m.openStepPopup(sid, 'Beta发布', '下拉重复复核');
  }, [SID]);
  await page.waitForSelector(`#${DELEGATE_FIELD}`, { state: 'attached', timeout: 15000 });
  await sleep(700);
}

const clipOf = (page, boxes, pad) => {
  const view = page.viewportSize();
  const xs = boxes.filter(Boolean);
  if (!xs.length) return null;
  const left = Math.max(0, Math.min(...xs.map((b) => b.x)) - pad);
  const top = Math.max(0, Math.min(...xs.map((b) => b.y)) - pad);
  const right = Math.min(view.width, Math.max(...xs.map((b) => b.x + b.width)) + pad);
  const bottom = Math.min(view.height, Math.max(...xs.map((b) => b.y + b.height)) + pad);
  const clip = { x: left, y: top, width: right - left, height: bottom - top };
  return clip.width >= 8 && clip.height >= 8 ? clip : null; // 全在视口外 ⇒ 不拍空帧
};
const boxesIn = (page, sels) => page.evaluate((args) => args.flatMap((sel) => [...document.querySelectorAll(sel)].map((el) => {
  const r = el.getBoundingClientRect();
  return { x: r.x, y: r.y, width: r.width, height: r.height };
})), sels);

/** 取景帧：设置页两处受影响卡（delegate 打开前）。 */
async function shootSettings(page, viewport, state) {
  await page.evaluate(() => document.querySelector('[data-role="subagent"]')?.scrollIntoView({ block: 'center' }));
  await sleep(300);
  await page.screenshot({ path: join(SHOTS_DIR, `${TAG}-${viewport}-${state}-settings-viewport.png`) });
  const clip = clipOf(page, await boxesIn(page, ['[data-role="subagent"]', '#small-llm-settings-row']), 10);
  if (clip) await page.screenshot({ path: join(SHOTS_DIR, `${TAG}-${viewport}-${state}-settings.png`), clip });
  else log(`INFO  [${viewport}/${state}] settings 取景跳过（区域在视口外）`);
}

/** 取景帧：delegate 弹窗头部一行（作者实拍第一处）。 */
async function shootDelegate(page, viewport, state) {
  const clip = clipOf(page, await boxesIn(page, ['.flow-agent-header', `#${DELEGATE_FIELD}`, '.nf-select-trigger']), 14);
  if (clip) await page.screenshot({ path: join(SHOTS_DIR, `${TAG}-${viewport}-${state}-delegate.png`), clip });
  else log(`INFO  [${viewport}/${state}] delegate 取景跳过（区域在视口外）`);
}

/** 一条腿 = 一个视口 × 一个状态。 */
async function leg(browser, viewport, mobile, label, state) {
  const { ctx, page, errors } = await boot(browser, viewport);
  try {
    await openSettings(page, mobile);
    if (STATES[state]) { await page.addStyleTag({ content: STATES[state] }); await sleep(300); }
    const settings = await page.evaluate(MEASURE, SETTINGS_FIELDS);
    const present = settings.filter((r) => r.present);
    const bad = present.filter((r) => r.count !== 1 || r.nativeRendered || r.residualRow);
    log(`${bad.length ? 'FAIL' : 'PASS'}  [${label}/settings/${state}] 受影响字段单控件 = ${present.length - bad.length}/${present.length}`
      + (bad.length ? ` — 不合格：${bad.map((r) => `${r.id}(count=${r.count}, trigger=${r.triggerRendered}, native=${r.nativeRendered}, extra=${r.othersRendered}, 第二行=${r.residualRow})`).join('; ')}` : ''));
    if (bad.length) fail++; else pass++;
    if (SHOTS) await shootSettings(page, label, state);

    await openDelegate(page);
    if (STATES[state]) { await page.addStyleTag({ content: STATES[state] }); await sleep(300); }
    const header = await page.evaluate(MEASURE, [DELEGATE_FIELD]);
    const h = header.find((r) => r.present);
    if (!h || (!h.triggerRendered && !h.nativeRendered && h.othersRendered === 0)) {
      // 该视口这一面根本没渲染（如手机层头部只留会话 chrome）⇒ 无「两个控件」可言，
      // 计为 n/a（不计入分母），并把它写清楚而不是静默放过。
      log(`N/A   [${label}/delegate/${state}] #${DELEGATE_FIELD} 未渲染（该视口这一面无下拉控件）`);
    } else {
      const hBad = h.count !== 1 || h.nativeRendered || h.residualRow;
      log(`${hBad ? 'FAIL' : 'PASS'}  [${label}/delegate/${state}] #${DELEGATE_FIELD} 单控件 = ${hBad ? '0/1' : '1/1'}`
        + ` — count=${h.count}, trigger=${h.triggerRendered}, native=${h.nativeRendered}, extra=${h.othersRendered}, 第二行=${h.residualRow}`);
      if (hBad) fail++; else pass++;
    }
    if (SHOTS) await shootDelegate(page, label, state);
    log(`INFO  [${label}/${state}] 字段清单：${present.map((r) => r.id).join(', ') || '(无)'}`);
    log(`INFO  [${label}/${state}] 页面 JS 异常 = ${errors.length}${errors.length ? ' — ' + errors.slice(0, 3).join(' | ') : ''}`);
  } finally {
    await ctx.close();
  }
}

const browser = await chromium.launch();
try {
  for (const state of Object.keys(STATES)) {
    await leg(browser, { width: 1280, height: 800 }, false, 'desktop-1280x800', state);
    await leg(browser, { width: 375, height: 812 }, true, 'mobile-375x812', state);
  }
} finally {
  await browser.close();
  webServer.close();
}
log(`SUMMARY pass=${pass} fail=${fail} · web=${WEB_DIR} · tag=${TAG} · log=${LOG}`);
writeFileSync(LOG, out.join('\n') + '\n');
process.exit(fail === 0 ? 0 : 1);
