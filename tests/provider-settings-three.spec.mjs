#!/usr/bin/env node
// provider-settings-three.spec.mjs — 服务商/模型配置页三件回归（2026-11-18 作者令）。
//
// 作者原话（2026-11-18 09:16，逐字）：
//   「然后是生图的配置 首先是为什么现在我配置的api key都不可见啊 我在设置里的前端
//     应该要可见 大模型配置那边也是 包括端点的配置这些 另外是生图配置选择协议 点不动
//     然后底部的这些按钮的设计 大小有些失衡 包括设置页内部也是 有些丑陋了 这些按钮的
//     样式 大小应该要有极致的控制 统一的要求」
//
// 三件判据：
//   A 协议选择器「点得动」——根因 = 收编包裹层 `.nf-select` 是 shrink-to-fit 的
//     inline-grid，被包裹的 `.cfg-input` select 的 width:100% 塌成触发钮的 max-content
//     （实测 366→144 / 343→144）：整行 200 余 px 是死区。本腿钉**整行都是命中区**
//     （真实 pointer 命中测试）+ **能选**（真实点选落值）+ **能保存**（PUT 体里带上所选值）。
//   B 端点/协议**已配置可见、未配置显式标出**——卡面端点读数 + 编辑表单回填 +
//     协议选择器不含「存量适配器被静默丢掉」。
//   C 按钮**一档**——设置页动作族（.set-btn）与服务商编辑窗动作族（#cfg-modal
//     动作行）逐项同档（最小 44 命中盒 / 12 内距 / 12 字号 / pill / 不折行），
//     两视口实测。
//
// 零后端 harness（同 select-unify-f.spec.mjs 族）：route 供 web/ 源树 + routeWebSocket
// 帧桩 + localStorage 注入。Run: node tests/provider-settings-three.spec.mjs
//
// 🔴 不打印任何凭据值：夹具里的 key 只是「已配置/未配置」的**存在性**占位
// （'sk-…' 字样不进日志；断言只看 keyConfigured 布尔与端点 URL）。

import { chromium, webkit } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.PROVIDER_THREE_OUT || join(ROOT, 'evidence', 'provider-settings-three-20261118');
mkdirSync(OUT, { recursive: true });

const SID = 'provider-settings-three';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const TYPES = { domains: [{ id: 'llm', enabledSwitch: false }, { id: 'imagegen', enabledSwitch: true }], adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview', 'openai-images'] } };
const ENDPOINT = 'https://open.bigmodel.cn/api/paas/v4/';
const IMG = { enabled: true, compiledIn: true, defaultProvider: 'zhipu', providers: { zhipu: { type: 'cogview', model: 'cogview-4', baseUrl: ENDPOINT, keyRef: 'imagegen-zhipu-key', keyConfigured: true }, bare: { type: 'openai-images', model: 'gpt-image-1', keyRef: 'imagegen-bare-key', keyConfigured: false } } };
const CFG = JSON.stringify({ features: {}, llm: { providers: { 'openai-main': { baseUrl: 'https://api.openai.com/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'gpt-4o', contextWindow: 128000 }] } } }, imagegen: { enabled: true, providers: {} } });

let pass = 0, fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line); lines.push(line);
  if (cond) pass++; else fail++;
}

/** 起一页（mock 全站：web 源树 + API + WS），并记录 PUT /api/config/imagegen 的请求体。 */
async function makePage(browser, opts, ledger) {
  const page = await (await browser.newContext(opts)).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|fetching the script/.test(m.text())) errors.push(m.text()); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  });
  await page.route('**/*', (route) => {
    const req = route.request();
    let p = decodeURIComponent(new URL(req.url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/config/provider-types') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TYPES) });
      if (p === '/api/config/imagegen') {
        if (req.method() === 'PUT') { ledger.sectionPuts.push(JSON.parse(req.postData() || '{}')); return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' }); }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(IMG) });
      }
      if (/^\/api\/secrets\//.test(p)) { ledger.secretWrites.push(req.method()); return route.fulfill({ status: req.method() === 'POST' ? 409 : 200, contentType: 'application/json', body: '{}' }); }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: CFG, configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    config(); sessions();
    ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') config();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
      else if (m.type === 'updateConfig') ws.send(JSON.stringify({ type: 'configUpdated', requestId: m.requestId, success: true }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 })); });
  });
  await page.goto('http://127.0.0.1:8393/');
  await page.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws?.readyState === 1; }, SID, { timeout: 20000 });
  return { page, errors };
}

/** 设置页 → 服务商面板 → 指定类型标签（面板在渐进披露体里，先展开）。 */
async function openPanel(page, type, mobile) {
  await sleep(800);
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  await page.evaluate(async () => { (await import('/js/sidebar.js')).openSettingsPanel?.(); });
  await sleep(700);
  await page.evaluate(() => document.querySelector('[data-settings-disclosure="providers"]')?.click());
  await sleep(900);
  await page.evaluate((ty) => document.querySelector(`[data-provider-type="${ty}"]`)?.click(), type);
  await sleep(800);
}

/** 协议字段 vs 兄弟字段的行宽（A 件的几何判据）。 */
const FIELD_W = `(() => {
  const g = (f) => { const el = document.querySelector('#cfg-modal [data-field="' + f + '"]'); if (!el) return null;
    const w = el.closest('.nf-select') || el; const r = w.getBoundingClientRect(); return Math.round(r.width); };
  return { protocol: g('type') || g('protocol'), baseUrl: g('baseUrl'), model: g('model') };
})()`;

/** 整行真实命中测试：在协议行的若干横向比例位上取 elementFromPoint，必须落在触发钮内。 */
const HIT_ROW = `(() => {
  const sel = document.querySelector('#cfg-modal [data-field="type"], #cfg-modal [data-field="protocol"]');
  const wrap = sel && sel.closest('.nf-select');
  const trg = wrap && wrap.querySelector('.nf-select-trigger');
  if (!trg) return { noTrigger: true };
  const r = trg.getBoundingClientRect();
  const hits = [0.08, 0.3, 0.5, 0.75, 0.95].map((f) => {
    const el = document.elementFromPoint(r.left + r.width * f, r.top + r.height / 2);
    return { f, in: !!el && (el === trg || trg.contains(el)) };
  });
  return { w: Math.round(r.width), h: Math.round(r.height), allInside: hits.every((x) => x.in), hits };
})()`;

/** 按钮实测：设置页动作族 + 编辑窗动作族。 */
const BTNS = `(() => {
  const one = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    return { label: (el.textContent || '').trim().slice(0, 16), w: +r.width.toFixed(1), h: +r.height.toFixed(1),
      minH: cs.minHeight, pad: cs.paddingLeft + '|' + cs.paddingRight, fs: cs.fontSize, radius: cs.borderRadius, ws: cs.whiteSpace }; };
  const settings = [...document.querySelectorAll('#settings-content .set-btn')].map(one);
  const modal = [...document.querySelectorAll('#cfg-modal .cfg-modal-actions button')].map(one);
  return { settings, modal };
})()`;

const browser = await chromium.launch();
const VIEWPORTS = [
  { tag: 'desktop', label: 'DESKTOP 1280x800', opts: { viewport: { width: 1280, height: 800 } } },
  { tag: 'mobile', label: 'MOBILE 375x812', opts: { viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true } },
];
const allErrors = [];

try {
  for (const vp of VIEWPORTS) {
    const ledger = { sectionPuts: [], secretWrites: [] };
    const { page, errors } = await makePage(browser, vp.opts, ledger);
    const mobile = vp.tag === 'mobile';
    try {
      await openPanel(page, 'imagegen', mobile);

      /* ── B：卡面端点读数（已配置可见 / 未配置显式标出）── */
      const cards = await page.evaluate(() => [...document.querySelectorAll('#provider-list .cfg-card')].map((c) => ({
        name: c.querySelector('.cfg-card-title')?.textContent.trim(),
        subs: [...c.querySelectorAll('.cfg-card-sub')].map((s) => s.textContent.trim()),
        hasKey: !!c.querySelector('.st-badge'),
      })));
      const zhipu = cards.find((c) => c.name === 'zhipu') || {};
      const bare = cards.find((c) => c.name === 'bare') || {};
      ok(`B1[${vp.tag}] 已配置端点如实显示在卡面（zhipu → ${ENDPOINT}）`,
        (zhipu.subs || []).includes(ENDPOINT), JSON.stringify(cards));
      ok(`B2[${vp.tag}] 未配置端点显式标出「默认端点」（不留白）`,
        (bare.subs || []).some((s) => s === '默认端点'), JSON.stringify(bare));

      /* ── B：编辑既有 provider —— 端点/协议回填 ── */
      await page.evaluate((n) => document.querySelector(`#provider-list .cfg-card[data-provider="${n}"]`)?.click(), 'zhipu');
      await sleep(800);
      const edit = await page.evaluate(() => ({
        baseUrl: document.querySelector('#cfg-modal [data-field="baseUrl"]')?.value,
        protocol: document.querySelector('#cfg-modal [data-field="type"]')?.value,
        protocolOpts: [...(document.querySelector('#cfg-modal [data-field="type"]')?.options || [])].map((o) => o.value),
      }));
      ok(`B3[${vp.tag}] 编辑表单回填已配置端点与协议（baseUrl + type 都在位上）`,
        edit.baseUrl === ENDPOINT && edit.protocol === 'cogview', JSON.stringify(edit));
      await page.evaluate(() => document.querySelector('#cfg-modal .cfg-modal-cancel')?.click());
      await sleep(300);

      /* ── A：新增表单 —— 协议行几何 + 整行命中 + 真实点选 + 保存 ── */
      await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
      await sleep(800);
      const w = await page.evaluate(FIELD_W);
      ok(`A1[${vp.tag}] 协议字段与兄弟字段同行宽（不再是 144px 死区塌块）`,
        w.protocol != null && w.protocol === w.baseUrl && w.protocol === w.model,
        JSON.stringify(w));
      const hit = await page.evaluate(HIT_ROW);
      ok(`A2[${vp.tag}] 协议行 5 个横向位点全部命中触发钮（整行可点）`,
        hit.allInside === true, JSON.stringify(hit));

      await page.fill('#cfg-modal [data-field="name"]', 'probeimg');
      await page.fill('#cfg-modal [data-field="model"]', 'm1');
      await page.fill('#cfg-modal [data-field="apiKey"]', 'sk-x');
      // 真实 pointer：点整行的 80% 处开菜单，再真实点选第二个适配器
      const box = await page.locator('#cfg-modal .nf-select:has(select[data-field="type"]) .nf-select-trigger').boundingBox();
      await page.mouse.click(box.x + box.width * 0.8, box.y + box.height / 2);
      await sleep(400);
      const opened = await page.evaluate(() => { const m = document.querySelector('.nf-select-menu'); return m ? [...m.querySelectorAll('.nf-select-opt')].map((b) => b.textContent.replace('✓', '')) : null; });
      ok(`A3[${vp.tag}] 点行右侧（80%）即开选项单，选项 = 后端适配器清单 + 占位`,
        Array.isArray(opened) && opened.includes('cogview') && opened.includes('openai-images'), JSON.stringify(opened));
      await page.screenshot({ path: join(OUT, `${vp.tag}-protocol-menu-open.png`) });
      const picked = await page.evaluate(() => {
        const btn = [...document.querySelectorAll('.nf-select-menu .nf-select-opt')].find((b) => b.textContent.replace('✓', '') === 'openai-images');
        if (btn) btn.click();
        return !!btn;
      });
      await sleep(350);
      const landed = await page.evaluate(() => ({
        value: document.querySelector('#cfg-modal select[data-field="type"]')?.value,
        menuGone: !document.querySelector('.nf-select-menu'),
      }));
      ok(`A4[${vp.tag}] 真实点选落值（openai-images）且单收`,
        picked && landed.value === 'openai-images' && landed.menuGone, `${picked} / ${JSON.stringify(landed)}`);

      // 保存 → PUT 体里带上所选协议
      await page.evaluate(() => document.getElementById('cfg-modal-save')?.click());
      await sleep(900);
      const put = ledger.sectionPuts[ledger.sectionPuts.length - 1] || {};
      ok(`A5[${vp.tag}] 保存把所选协议写进 PUT /api/config/imagegen`,
        (put.providers || {}).probeimg?.type === 'openai-images', JSON.stringify(put).slice(0, 300));

      /* ── C：按钮一档（设置页动作族 + 编辑窗动作族，两视口实测）── */
      await page.evaluate(() => document.querySelector('#cfg-modal .cfg-modal-cancel')?.click()).catch(() => {});
      await sleep(200);
      await page.evaluate(() => document.querySelector('[data-provider-type="llm"]')?.click());
      await sleep(700);
      await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
      await sleep(800);
      const btns = await page.evaluate(BTNS);
      // 只取**真的排版出来**的钮（折叠节里的钮零盒，不参与几何比较）。
      const laid = (list) => (list || []).filter((b) => b.w > 0 && b.h > 0);
      const settingsFam = laid(btns.settings), modalFam = laid(btns.modal);
      const shape = (b) => `${b.h}|${b.pad}|${b.fs}|${b.radius}|${b.ws}`;
      const settingsShapes = [...new Set(settingsFam.map(shape))];
      const modalShapes = [...new Set(modalFam.map(shape))];
      ok(`C1[${vp.tag}] 设置页动作族内部一档（${settingsFam.length} 枚，形态集合 = ${settingsShapes.join(' / ')}）`,
        settingsFam.length >= 3 && settingsShapes.length === 1, JSON.stringify(settingsFam));
      ok(`C2[${vp.tag}] 编辑窗动作族内部一档（${modalFam.length} 枚，形态集合 = ${modalShapes.join(' / ')}）`,
        modalFam.length >= 2 && modalShapes.length === 1, JSON.stringify(modalFam));
      ok(`C3[${vp.tag}] 两族逐项同档（同高同内距同字号同圆角同不折行）`,
        settingsShapes.length === 1 && modalShapes.length === 1 && settingsShapes[0] === modalShapes[0],
        `settings=${settingsShapes.join('/')} modal=${modalShapes.join('/')}`);
      ok(`C4[${vp.tag}] 命中盒 ≥ 44 且不折行`,
        settingsFam.concat(modalFam).every((b) => b.h >= 44 && b.ws === 'nowrap'),
        JSON.stringify(settingsFam.concat(modalFam).filter((b) => !(b.h >= 44 && b.ws === 'nowrap'))));
      if (vp.tag === 'desktop') writeFileSync(join(OUT, 'button-geometry-desktop.json'), JSON.stringify(btns, null, 2));
      else writeFileSync(join(OUT, 'button-geometry-mobile.json'), JSON.stringify(btns, null, 2));
      await page.screenshot({ path: join(OUT, `${vp.tag}-buttons.png`) });

      ok(`Z[${vp.tag}] 全程零页面错误`, errors.length === 0, errors.join(' | ').slice(0, 200));
    } catch (err) {
      ok(`SPEC-ABORT[${vp.tag}]`, false, String(err && err.stack ? err.stack.split('\n').slice(0, 4).join(' | ') : err));
    }
    allErrors.push(...errors);
    await page.close();
  }
} finally {
  await browser.close();
}

/* ── WebKit（Safari 引擎）腿：作者浏览器 = Safari，同一 A 件判据 ── */
try {
  const wb = await webkit.launch();
  const ledger = { sectionPuts: [], secretWrites: [] };
  const { page } = await makePage(wb, { viewport: { width: 1280, height: 800 } }, ledger);
  await openPanel(page, 'imagegen', false);
  await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
  await sleep(800);
  const w = await page.evaluate(FIELD_W);
  const hit = await page.evaluate(HIT_ROW);
  ok('A6[webkit] Safari 引擎下协议行同样满宽且整行可点',
    w.protocol != null && w.protocol === w.baseUrl && hit.allInside === true, JSON.stringify({ w, hit }));
  const trg = page.locator('#cfg-modal .nf-select:has(select[data-field="type"]) .nf-select-trigger');
  await trg.click({ timeout: 8000 }).catch(() => {});
  await sleep(400);
  ok('A7[webkit] Safari 引擎下真实点击开单', await page.evaluate(() => !!document.querySelector('.nf-select-menu')));
  await page.close(); await wb.close();
} catch (err) {
  ok('SPEC-ABORT[webkit]', false, String(err && err.message ? err.message : err));
}

const summary = `\n合计 ${pass} PASS / ${fail} FAIL`;
console.log(summary); lines.push(summary);
writeFileSync(join(OUT, 'spec-output.txt'), lines.join('\n') + '\n');
process.exit(fail > 0 ? 1 : 0);
