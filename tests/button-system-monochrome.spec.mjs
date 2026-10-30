// button-system-monochrome.spec.mjs — 全局按钮体系黑白科技向（去交互绿）实施批验收。
// 锚：docs/design/20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN.md
// §8 可断言验收点 → 本 spec 映射（静态 harness 加载**真实 css 全量**（index.html
// 同序），零 JS 应用面，纯计算样式断言 + 亮/暗双档截图）：
//   A1 主操作二值：#send-btn（连接态）bg ∈ {#111827 亮, #e6edf3 暗}；hover ∈
//      {#374151, #ffffff}（截图 + DOM 断言）
//   A2 全局零交互绿：全 button 元素扫描，green-dominant（G-R>30 且 G-B>30）的
//      background/border-color 计数 = 白名单数。白名单（逐项）：
//        ① .icon-btn.recording（录音 = 状态/身份，降级绿保留，input.css:701-710,1514）
//        ② .icon-btn.recording 的 voicePulse keyframes（同一条规则承载，非独立元素）
//        ③ copy-success .copied 态（chat.css:950 等 #34c759——harness 未渲染，
//          登记在案；非 button 元素或瞬态，不参与计数）
//      （.option-btn.picked 选中态 = 选择状态语义，与录音同族降级绿，若页面在场
//      同属白名单；本 harness 未渲染。）
//   A3 危险单源：#delete-confirm / .pp-btn.danger-line 的 color+border 解析自
//      --color-danger（亮 rgb(192,57,43) / 暗 rgb(242,107,107)）
//   A4 focus 蓝环：#send-btn focus 后 outline-color = rgb(59,130,246) 亮 /
//      rgb(96,165,250) 暗，offset 2px（截图 + DOM 断言）
//   A5 主题跟随面板：OS=light + html[data-theme="dark" ⇒ #send-btn bg = #e6edf3；
//      摘除属性回退 OS 档 = #111827
//   A6 掉线语义不回归：#send-btn.disconnected bg = --glass-send-bg-active 解析值
//      （亮 rgba(255,255,255,0.66) / 暗 rgba(255,255,255,0.15)），cursor not-allowed
//   A7 五类结构不回归：--btn-primary-bg/-hover/-ink、--btn-secondary-*、
//      --btn-ghost-*、--btn-danger-* 具名 token 全部存在于 :root 计算集
//   A8 墨族/蓝族暗档：--btn-ink-* 暗档 = 官网 --auth-btn-* 双档值
//
// 运行：node tests/button-system-monochrome.spec.mjs
// 可选 env：PORT（默认 8231）、SHOTS_DIR（默认 <repo>/evidence/20261030_button-system-monochrome/shots）

import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8231);
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/20261030_button-system-monochrome/shots');
mkdirSync(SHOTS_DIR, { recursive: true });

/** index.html 的 css 加载序（设计系统纪律：sapphire.css 最后）。 */
const CSS_ORDER = [
  'base', 'nav', 'sidebar', 'chat', 'modal', 'input', 'taskList', 'scrollUnderGlass',
  'reminder', 'neblink', 'dropbox', 'projectPanel', 'delegatePanorama', 'flowMap',
  'friends', 'split', 'ask', 'voice', 'mermaid', 'humanFace', 'voiceMode', 'lightbox',
  'usage', 'social', 'secrets', 'stand-tokens', 'perception', 'executors', 'diffView',
  'sapphire',
];

const HARNESS = `<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
${CSS_ORDER.map((n) => `<link rel="stylesheet" href="/css/${n}.css">`).join('\n')}
<style>
  body { padding: 24px; background: var(--color-bg); display: flex; flex-direction: column; gap: 14px; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  h4 { color: var(--color-text); font-size: 12px; font-weight: 600; margin: 6px 0 0; }
</style>
</head>
<body>
  <h4>主发送键族（B1/B3）</h4>
  <div class="row">
    <button id="send-btn"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M12 19V5M5 12l7-7 7 7"/></svg></button>
    <button id="send-btn" class="disconnected" data-alt="disconnected"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M12 19V5M5 12l7-7 7 7"/></svg></button>
    <button id="send-btn" disabled data-alt="disabled"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M12 19V5M5 12l7-7 7 7"/></svg></button>
    <div class="ask-input"><input placeholder="ask"><button class="ask-send"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"><path d="M12 19V5M5 12l7-7 7 7"/></svg></button></div>
    <button id="stop-btn" style="display:flex"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><rect x="7" y="7" width="10" height="10"/></svg></button>
  </div>
  <h4>对话框（B4/B5/B6）</h4>
  <div class="row">
    <button id="modal-cancel">Cancel</button>
    <button id="modal-confirm">Confirm</button>
    <button id="delete-cancel">Delete?</button>
    <button id="delete-confirm">Delete</button>
    <button id="memory-modal-save">Save</button>
  </div>
  <h4>设置面板（B7/B9/B13）</h4>
  <div class="row">
    <button class="cfg-btn">Ghost-ish</button>
    <button class="cfg-btn cfg-btn-primary">Primary</button>
    <div class="config-actions"><button class="btn-save">Save cfg</button></div>
    <div class="nb-toggle"></div>
    <div class="nb-toggle on"></div>
    <div class="cfg-toggle on"><span></span></div>
    <span class="mchain-chip playing">model-chip</span>
  </div>
  <h4>感知面板 / neblink / flow viewer / daemons</h4>
  <div class="row">
    <button class="pp-btn">Config</button>
    <button class="pp-btn primary">Connect</button>
    <button class="pp-btn danger-line">Remove</button>
    <button class="neblink-login-btn">Login</button>
    <button class="fm-login-btn">fm Login</button>
    <button class="flow-viewer-save">Save flow</button>
    <button class="daemon-btn start">Start</button>
  </div>
  <h4>白名单：录音状态绿（B8 保留）</h4>
  <div class="row">
    <button class="icon-btn recording"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><rect x="9" y="9" width="6" height="10"/></svg></button>
  </div>
</body>
</html>`;

let passed = 0, failed = 0;
const ok = (name, cond, detail = '') => {
  if (cond) { passed++; console.log(`  ok  ${name}`); }
  else { failed++; console.error(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`); }
};

const MIME = { '.css': 'text/css', '.html': 'text/html' };
function serve() {
  const srv = createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url === '/' || url === '/index.html') { res.setHeader('content-type', 'text/html'); res.end(HARNESS); return; }
    const p = join(WEB_DIR, url.replace(/^\//, ''));
    if (!p.startsWith(WEB_DIR)) { res.statusCode = 403; res.end(); return; }
    try {
      const body = readFileSync(p);
      const ext = p.slice(p.lastIndexOf('.'));
      res.setHeader('content-type', MIME[ext] || 'application/octet-stream');
      res.end(body);
    } catch { res.statusCode = 404; res.end('nf'); }
  });
  return new Promise((r) => srv.listen(PORT, () => r(srv)));
}

const rgbOf = (hex) => {
  const h = hex.replace('#', '');
  const n = parseInt(h, 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
};

async function readings(page) {
  return page.evaluate(() => {
    const cs = (sel, alt) => {
      const el = alt
        ? [...document.querySelectorAll(sel)].find((e) => e.dataset.alt === alt)
        : document.querySelector(sel);
      if (!el) return null;
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, border: s.borderTopColor, color: s.color, outline: s.outlineColor, outlineOffset: s.outlineOffset, cursor: s.cursor, outlineStyle: s.outlineStyle, outlineWidth: s.outlineWidth };
    };
    const root = getComputedStyle(document.documentElement);
    const tokens = {};
    for (const name of ['--btn-primary-bg', '--btn-primary-bg-hover', '--btn-primary-ink',
      '--btn-secondary-stroke', '--btn-ghost-ink', '--btn-danger-stroke', '--btn-danger-ink',
      '--btn-ink-bg', '--btn-ink-bg-hover', '--btn-ink-ink', '--btn-focus-ring', '--color-danger']) {
      tokens[name] = root.getPropertyValue(name).trim();
    }
    // 全 button 扫描：green-dominant 面计数（§8-2 白名单制）
    const isGreenDominant = (c) => {
      const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c || '');
      if (!m) return false;
      const [, r, g, b] = m.map(Number);
      return g - r > 30 && g - b > 30;
    };
    const greenBtns = [...document.querySelectorAll('button')]
      .filter((el) => {
        const s = getComputedStyle(el);
        return isGreenDominant(s.backgroundColor) || isGreenDominant(s.borderTopColor);
      })
      .map((el) => `${el.className || el.id}|${el.dataset.alt || ''}`);
    return {
      send: cs('#send-btn'),
      sendDisconnected: cs('#send-btn', 'disconnected'),
      sendDisabled: cs('#send-btn', 'disabled'),
      askSend: cs('.ask-input .ask-send'),
      modalConfirm: cs('#modal-confirm'),
      deleteConfirm: cs('#delete-confirm'),
      ppDanger: cs('.pp-btn.danger-line'),
      cfgPrimary: cs('.cfg-btn.cfg-btn-primary'),
      btnSave: cs('.config-actions .btn-save'),
      neblinkLogin: cs('.neblink-login-btn'),
      ppPrimary: cs('.pp-btn.primary'),
      nbToggleOn: cs('.nb-toggle.on'),
      tokens,
      greenBtns,
    };
  });
}

const srv = await serve();
const browser = await chromium.launch();
try {
  const ctxL = await browser.newContext({ viewport: { width: 1100, height: 900 }, colorScheme: 'light' });
  const page = await ctxL.newPage();
  await page.goto(`http://127.0.0.1:${PORT}/`);
  await page.waitForTimeout(150);

  const L = await readings(page);

  // ── A7 五类结构不回归（亮档先验）──
  ok('A7 具名 token 存在（:root 计算集）',
    L.tokens['--btn-primary-bg'] && L.tokens['--btn-primary-bg-hover'] && L.tokens['--btn-primary-ink']
    && L.tokens['--btn-secondary-stroke'] && L.tokens['--btn-ghost-ink']
    && L.tokens['--btn-danger-stroke'] && L.tokens['--btn-danger-ink'],
    JSON.stringify(L.tokens));

  // ── A1 主操作二值 · 亮档 ──
  ok('A1a #send-btn 亮档 bg = #111827', L.send.bg === rgbOf('#111827'), L.send.bg);
  ok('A1b .ask-send 亮档 bg = #111827（B3 token 同源）', L.askSend.bg === rgbOf('#111827'), L.askSend.bg);
  ok('A1c #modal-confirm 亮档 bg = #111827（B4 墨族）', L.modalConfirm.bg === rgbOf('#111827'), L.modalConfirm.bg);
  ok('A1d .cfg-btn-primary 亮档 bg = #111827（B13）', L.cfgPrimary.bg === rgbOf('#111827'), L.cfgPrimary.bg);
  ok('A1e .btn-save 亮档 bg = #111827（B7）', L.btnSave.bg === rgbOf('#111827'), L.btnSave.bg);
  ok('A1f .neblink-login-btn 亮档 bg = #111827', L.neblinkLogin.bg === rgbOf('#111827'), L.neblinkLogin.bg);
  ok('A1g .pp-btn.primary 亮档 bg = #111827（侦察补漏）', L.ppPrimary.bg === rgbOf('#111827'), L.ppPrimary.bg);
  ok('A1h 墨族白墨亮档 = #ffffff', L.send.color === rgbOf('#ffffff'), L.send.color);

  // hover 二值（亮档 #374151）
  await page.hover('#send-btn');
  await page.waitForTimeout(260);
  const Lhover = await page.evaluate(() => getComputedStyle(document.querySelector('#send-btn')).backgroundColor);
  ok('A1i #send-btn 亮档 hover bg = #374151', Lhover === rgbOf('#374151'), Lhover);

  // ── A4 focus 蓝环 · 亮档 ──
  await page.mouse.move(4, 4);
  await page.evaluate(() => document.querySelector('#send-btn').focus());
  await page.waitForTimeout(60);
  const focusL = await page.evaluate(() => {
    const s = getComputedStyle(document.querySelector('#send-btn'));
    return { color: s.outlineColor, offset: s.outlineOffset, width: s.outlineWidth };
  });
  ok('A4a #send-btn focus outline-color 亮档 = rgb(59,130,246)', focusL.color === 'rgb(59, 130, 246)', JSON.stringify(focusL));
  ok('A4b focus outline = 2px / offset 2px', focusL.width === '2px' && focusL.offset === '2px', JSON.stringify(focusL));

  // ── A3 危险单源 · 亮档 ──
  ok('A3a #delete-confirm 红 = --color-danger 亮档 rgb(192,57,43)',
    L.deleteConfirm.color === rgbOf('#c0392b') && L.deleteConfirm.border === rgbOf('#c0392b'),
    JSON.stringify(L.deleteConfirm));
  ok('A3b .pp-btn.danger-line 红统一（§5.3）',
    L.ppDanger.color === rgbOf('#c0392b') && L.ppDanger.border === rgbOf('#c0392b'),
    JSON.stringify(L.ppDanger));

  // ── A6 掉线语义 · 亮档 ──
  ok('A6a #send-btn.disconnected bg = --glass-send-bg-active 亮档 rgba(255,255,255,0.66)',
    L.sendDisconnected.bg === 'rgba(255, 255, 255, 0.66)', L.sendDisconnected.bg);
  ok('A6b disconnected cursor = not-allowed', L.sendDisconnected.cursor === 'not-allowed', L.sendDisconnected.cursor);

  // ── A2 零交互绿 · 亮档 ──
  ok('A2a 白名单计数 = 1（.icon-btn.recording）', L.greenBtns.length === 1, JSON.stringify(L.greenBtns));
  ok('A2b 白名单成员 = .icon-btn.recording', (L.greenBtns[0] || '').startsWith('icon-btn recording'), JSON.stringify(L.greenBtns));

  // ── A5 主题跟随面板（OS=light 强制 dark）──
  // （background 过渡 .2s ⇒ 等过渡收尾再读，防中值污染。）
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
  await page.waitForTimeout(400);
  const forced = await page.evaluate(() => getComputedStyle(document.querySelector('#send-btn')).backgroundColor);
  ok('A5a OS=light + data-theme=dark ⇒ #send-btn bg = #e6edf3', forced === rgbOf('#e6edf3'), forced);
  await page.evaluate(() => document.documentElement.removeAttribute('data-theme'));
  await page.waitForTimeout(400);
  const restored = await page.evaluate(() => getComputedStyle(document.querySelector('#send-btn')).backgroundColor);
  ok('A5b 摘除属性回退 OS 亮档 = #111827', restored === rgbOf('#111827'), restored);

  // ── 亮档截图 ──
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await page.waitForTimeout(200);
  await page.screenshot({ path: join(SHOTS_DIR, 'button-zoo-light.png'), fullPage: true });
  await page.evaluate(() => document.querySelector('#send-btn').focus());
  await page.waitForTimeout(60);
  const sendBtn = page.locator('#send-btn').first();
  await sendBtn.screenshot({ path: join(SHOTS_DIR, 'send-btn-focus-light.png') });
  await page.evaluate(() => document.activeElement && document.activeElement.blur());

  // ── 暗档全量 ──
  const ctxD = await browser.newContext({ viewport: { width: 1100, height: 900 }, colorScheme: 'dark' });
  const pageD = await ctxD.newPage();
  await pageD.goto(`http://127.0.0.1:${PORT}/`);
  await pageD.waitForTimeout(150);
  const D = await readings(pageD);

  ok('D1 #send-btn 暗档 bg = #e6edf3（官网浅墨反转）', D.send.bg === rgbOf('#e6edf3'), D.send.bg);
  ok('D2 #send-btn 暗档字墨 = #0d1117', D.send.color === rgbOf('#0d1117'), D.send.color);
  ok('D3 全主操作暗档一致（ask/modal/cfg/save/login/pp）',
    D.askSend.bg === rgbOf('#e6edf3') && D.modalConfirm.bg === rgbOf('#e6edf3')
    && D.cfgPrimary.bg === rgbOf('#e6edf3') && D.btnSave.bg === rgbOf('#e6edf3')
    && D.neblinkLogin.bg === rgbOf('#e6edf3') && D.ppPrimary.bg === rgbOf('#e6edf3'),
    JSON.stringify({ a: D.askSend.bg, m: D.modalConfirm.bg, c: D.cfgPrimary.bg }));
  await pageD.hover('#send-btn');
  await pageD.waitForTimeout(260);
  const Dhover = await pageD.evaluate(() => getComputedStyle(document.querySelector('#send-btn')).backgroundColor);
  ok('D4 #send-btn 暗档 hover bg = #ffffff', Dhover === rgbOf('#ffffff'), Dhover);
  await pageD.mouse.move(4, 4);
  await pageD.evaluate(() => document.querySelector('#send-btn').focus());
  await pageD.waitForTimeout(60);
  const focusD = await pageD.evaluate(() => getComputedStyle(document.querySelector('#send-btn')).outlineColor);
  ok('D5 focus 蓝环暗档 = rgb(96,165,250)', focusD === 'rgb(96, 165, 250)', focusD);
  ok('D6 危险红暗档 = rgb(242,107,107)（#delete-confirm + pp 同值）',
    D.deleteConfirm.color === rgbOf('#f26b6b') && D.ppDanger.color === rgbOf('#f26b6b'),
    JSON.stringify({ d: D.deleteConfirm.color, p: D.ppDanger.color }));
  ok('D7 disconnected 暗档 bg = rgba(255,255,255,0.15)', D.sendDisconnected.bg === 'rgba(255, 255, 255, 0.15)', D.sendDisconnected.bg);
  ok('D8 暗档零交互绿 = 白名单 1', D.greenBtns.length === 1, JSON.stringify(D.greenBtns));
  ok('D9 开关 ON 暗档 = 浅墨 tint', /rgba\(230, 237, 243, 0.35\)/.test(D.nbToggleOn.bg), D.nbToggleOn.bg);
  ok('D10 --btn-focus-ring 暗档 token = #60a5fa', D.tokens['--btn-focus-ring'] === '#60a5fa', D.tokens['--btn-focus-ring']);

  await pageD.evaluate(() => document.activeElement && document.activeElement.blur());
  await pageD.waitForTimeout(200);
  await pageD.screenshot({ path: join(SHOTS_DIR, 'button-zoo-dark.png'), fullPage: true });

  writeFileSync(join(SHOTS_DIR, '..', 'readings.json'), JSON.stringify({ light: L, dark: D }, null, 2));
  await ctxL.close(); await ctxD.close();
} finally {
  await browser.close();
  srv.close();
}
console.log(`\n=== button-system-monochrome: ${failed === 0 ? 'PASS' : 'FAIL'} (${passed} ok, ${failed} fail) · shots=${SHOTS_DIR} ===`);
process.exit(failed === 0 ? 0 : 1);
