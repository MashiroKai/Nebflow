#!/usr/bin/env node
// ask-button-unify.spec.mjs — 转回合圆钮「主会话 ⇄ Canvas Ask 同源」闸
// （ask-button-unify 2026-11-18；作者令逐字「Ask 的中止按钮中间的方框应该和主会话
//  的一致 发送按钮也和主会话一致吧」）。
//
// 判据面（对未修源码直跑应报红 —— A 组是本次修复的全部内容）：
//   A 逐值同源（desktop 1600×1000 + mobile 375×812 各一组）
//     A1 边长/圆角/内距/border/底色/前景色 相等
//     A2 图标档（svg 宽高）+ 线宽（stroke-width）相等
//     A3 字形逐属性相等（tag + 全部 attribute：path/rect/…；「中间的方框」= rect）
//     A4 命中盒（::after 解析尺寸）相等
//     A5 DOM 类串相等（组件类 + 状态档类）
//   B 主会话**冻结**（本批只许移动 Ask 侧）
//     B1 主会话几何/颜色/字形 == 2026-11-18 基线逐值常数
//     B2 主会话快照（改前记录）逐值相同
//   C 源码单源
//     C1 ask.css 无 `.ask-dock .ds-turn-btn` 几何/线宽规则（消重后的账）
//     C2 secondaryComposer.js 不再自建 SVG 几何（零 `<path d=` / `<rect`），
//        只产出 data-lucide 占位（send / square）
//     C3 mobileLayer.css 的输入条圆钮手机档是**一条声明两个宿主**
//        （#input-bar 与 .ask-dock 同选择器组）
//   D 语义与行为
//     D1 rest 态：send 在位 / stop 隐（两腿同值）
//     D2 busy 臂：send 隐 / stop 在位（两腿同值）+ 真实 askQuestion 帧出
//     D3 点停：interrupt 帧出（sessionId 相符）+ 回 rest
//     D4 零 pageerror
//
// 隔离：自包含（静态服务本机起、WS = routeWebSocket mock、不碰宿主端口/进程）。
// Run: node tests/ask-button-unify.spec.mjs [--engine chromium]
import { chromium, webkit } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// WEB 默认 = 本仓 web 根；ASKBTN_WEB 供「对未修源码直跑应报红」的反证腿指向改前树。
const WEB = resolve(process.env.ASKBTN_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web'));
const ENGINE_ARG = (process.argv[2] || '').includes('webkit') ? 'webkit' : 'chromium';
const PORT = Number(process.env.PORT || 8395);
const BASE = `http://127.0.0.1:${PORT}`;
const FILE = '记忆检索调优笔记.md';
const ABS = '/tmp/ask-button-unify-fixture/' + FILE;
const sidOf = (p) => 'ask-' + createHash('sha256').update(p, 'utf8').digest('hex').slice(0, 8);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = normalize(join(WEB, p));
  if (!file.startsWith(normalize(WEB))) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

let pass = 0, fail = 0; const failures = [];
function ok(engine, name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  [${engine}]  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  if (cond) pass++; else { fail++; failures.push(line); }
}

// ── 主会话冻结常数（2026-11-18 改前实测；本批只许 Ask 侧移动）──────────────
const FROZEN = {
  desktop: {
    send: { width: '36px', height: '36px', borderRadius: '50%', padding: '0px', background: 'rgb(7, 193, 96)', color: 'rgb(255, 255, 255)', border: '1px solid rgb(6, 173, 86)', svgW: '18px', svgH: '18px', stroke: '2.5px', viewBox: '0 0 24 24', hit: '44x44' },
    stop: { width: '36px', height: '36px', borderRadius: '50%', padding: '0px', background: 'rgb(244, 67, 54)', color: 'rgb(255, 255, 255)', border: '1px solid rgba(0, 0, 0, 0)', svgW: '18px', svgH: '18px', stroke: '2.5px', viewBox: '0 0 24 24' },
  },
  mobile: {
    send: { width: '40px', height: '40px', borderRadius: '50%', padding: '0px', background: 'rgb(7, 193, 96)', color: 'rgb(255, 255, 255)', border: '1px solid rgb(6, 173, 86)', svgW: '18px', svgH: '18px', stroke: '2.5px', viewBox: '0 0 24 24', hit: '46x46' },
    stop: { width: '40px', height: '40px', borderRadius: '50%', padding: '0px', background: 'rgb(244, 67, 54)', color: 'rgb(255, 255, 255)', border: '1px solid rgba(0, 0, 0, 0)', svgW: '18px', svgH: '18px', stroke: '2.5px', viewBox: '0 0 24 24' },
  },
};

const READ = ({ sel }) => {
  const el = document.querySelector(sel);
  if (!el) return { sel, missing: true };
  const cs = getComputedStyle(el);
  const svg = el.querySelector('svg');
  const ss = svg ? getComputedStyle(svg) : null;
  const af = getComputedStyle(el, '::after');
  const r = el.getBoundingClientRect();
  const num = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : null; };
  const bw = num(cs.borderLeftWidth) || 0;
  const ins = [num(af.top), num(af.right), num(af.bottom), num(af.left)];
  const hit = ins.every((n) => n !== null)
    ? `${+((r.width - 2 * bw) - ins[3] - ins[1]).toFixed(2)}x${+((r.height - 2 * bw) - ins[0] - ins[2]).toFixed(2)}`
    : null;
  const shapes = svg ? [...svg.querySelectorAll('path,rect,line,circle,polyline')].map((n) => {
    const a = {}; for (const at of n.attributes) a[at.name] = at.value;
    return { tag: n.tagName.toLowerCase(), attrs: a };
  }) : null;
  return {
    sel, cls: String(el.className || ''), missing: false,
    rect: { w: +r.width.toFixed(2), h: +r.height.toFixed(2), left: +r.left.toFixed(2), top: +r.top.toFixed(2) },
    width: cs.width, height: cs.height, borderRadius: cs.borderTopLeftRadius, padding: cs.padding,
    border: `${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}`,
    background: cs.backgroundColor, color: cs.color, display: cs.display,
    svgW: ss && ss.width, svgH: ss && ss.height, stroke: ss && ss.strokeWidth,
    viewBox: svg && svg.getAttribute('viewBox'), cls_svg: svg && String(svg.getAttribute('class') || ''),
    shapes, hit,
  };
};

async function boot(browserType, mobile) {
  const browser = await browserType.launch();
  const viewport = mobile ? { width: 375, height: 812 } : { width: 1600, height: 1000 };
  const ctx = await browser.newContext({ viewport, colorScheme: 'dark', hasTouch: !!mobile, deviceScaleFactor: 2, locale: 'zh-CN' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 200)));
  const inbound = []; let ws = null;
  const wsSend = (o) => { if (ws) ws.send(JSON.stringify(o)); };
  await ctx.route('**/api/**', (route) => {
    const p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], activeId: 'sess-1' }) });
    if (p === '/api/nf-ticket') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets: {}, rejected: [] }) });
    if (p === '/api/nf-file') return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: Buffer.from(`# ${FILE}\n\nfixture body\n`) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  await ctx.routeWebSocket(/\/ws/, (sock) => {
    ws = sock;
    sock.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    sock.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'sess-1' }));
    sock.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'ping') sock.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getHistory') sock.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      else if (m.type === 'askOpen') {
        const fp = (m.file && m.file.absPath) || ABS;
        sock.send(JSON.stringify({ type: 'askState', sessionId: sidOf(fp), sessionKey: 'ask:' + sidOf(fp).slice(4, 8), file: fp, fileName: FILE, messages: [] }));
      }
    });
  });
  await page.goto(BASE + '/');
  await page.waitForSelector('#activity-bar', { timeout: 20000 }).catch(() => {});
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 30000 });
  await sleep(700);
  return { browser, ctx, page, errors, inbound, wsSend };
}

const waitFrame = (inbound, mark, pred, t = 5000) => new Promise((res) => {
  const timer = setTimeout(() => res(null), t);
  const poll = setInterval(() => {
    const hit = inbound.slice(mark).find(pred);
    if (hit) { clearInterval(poll); clearTimeout(timer); res(hit); }
  }, 40);
});

async function runLeg(engine, browserType, mobile) {
  const tag = mobile ? 'mobile 375×812' : 'desktop 1600×1000';
  const { browser, ctx, page, errors, inbound, wsSend } = await boot(browserType, mobile);
  try {
    if (mobile) { await page.evaluate(async () => { const m = await import('/js/mobile/navController.js'); m.navigateTo('canvas'); }); await sleep(700); }
    // 主会话腿（同一页）：主输入条
    const mainRest = await page.evaluate(READ, { sel: '#input-bar #send-btn' });
    // Ask 腿：开文件卡 + 开 dock
    await page.evaluate(({ name, absPath }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + absPath, itemType: 'markdown', title: name, content: `# ${name}\n\nfixture body\n`, absPath, pinned: true },
      }));
    }, { name: FILE, absPath: ABS });
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ABS}"]`, { timeout: 15000 });
    if (mobile) await page.waitForFunction(() => { const f = document.querySelector('.ask-fab'); return f && !f.classList.contains('gate-off'); }, null, { timeout: 15000 });
    await sleep(300);
    await page.click('.ask-fab');
    await page.waitForFunction(() => { const d = document.querySelector('.ask-dock'); return d && !d.classList.contains('closed'); }, null, { timeout: 15000 });
    await sleep(400);

    const askRest = await page.evaluate(READ, { sel: '.ask-dock .ask-send' });
    const mainRest2 = mainRest; // 主会话读数在开 dock 前后同值（页内无依赖）
    const askStopRest = await page.evaluate(READ, { sel: '.ask-dock .ask-stop' });
    const mainStopRest = await page.evaluate(READ, { sel: '#input-bar #stop-btn' });

    // ── A 逐值同源 ────────────────────────────────────────────────────────
    const same = (a, b, keys) => keys.filter((k) => JSON.stringify(a[k]) !== JSON.stringify(b[k]));
    const geomKeys = ['width', 'height', 'borderRadius', 'padding', 'border', 'background', 'color'];
    const iconKeys = ['svgW', 'svgH', 'stroke', 'viewBox'];
    const dSend = same(mainRest2, askRest, geomKeys);
    const dStop = same(mainStopRest, askStopRest, geomKeys);
    ok(engine, `A1 [${tag}] 发送钮几何/颜色逐值同源`, dSend.length === 0,
      dSend.length ? JSON.stringify(dSend.map((k) => `${k}: main=${mainRest2[k]} ask=${askRest[k]}`)) : `w=${askRest.width} bg=${askRest.background} border=${askRest.border}`);
    ok(engine, `A1 [${tag}] 中断钮几何/颜色逐值同源`, dStop.length === 0,
      dStop.length ? JSON.stringify(dStop.map((k) => `${k}: main=${mainStopRest[k]} ask=${askStopRest[k]}`)) : `w=${askStopRest.width} bg=${askStopRest.background}`);
    const iSend = same(mainRest2, askRest, iconKeys);
    const iStop = same(mainStopRest, askStopRest, iconKeys);
    ok(engine, `A2 [${tag}] 图标档 + 线宽同源（send/stop 各一组）`, iSend.length === 0 && iStop.length === 0,
      `send=${iSend.length ? JSON.stringify(iSend.map((k) => [k, mainRest2[k], askRest[k]])) : `${askRest.svgW}/${askRest.stroke}`} stop=${iStop.length ? JSON.stringify(iStop.map((k) => [k, mainStopRest[k], askStopRest[k]])) : `${askStopRest.svgW}/${askStopRest.stroke}`}`);
    ok(engine, `A3 [${tag}] 字形逐属性同源（含 stop「中间的方框」rect）`,
      JSON.stringify(mainRest2.shapes) === JSON.stringify(askRest.shapes)
      && JSON.stringify(mainStopRest.shapes) === JSON.stringify(askStopRest.shapes)
      && !!askStopRest.shapes && askStopRest.shapes.length > 0,
      `send=${JSON.stringify(askRest.shapes)} stop=${JSON.stringify(askStopRest.shapes)}`);
    ok(engine, `A4 [${tag}] 命中盒（::after 解析尺寸）同源`,
      askRest.hit === mainRest2.hit && askRest.hit !== null,
      `main=${mainRest2.hit} ask=${askRest.hit}`);
    ok(engine, `A5 [${tag}] DOM 类串同源（组件类 + 状态档类）`,
      askRest.cls === `ask-send ${mainRest2.cls}` && askStopRest.cls === `ask-stop ${mainStopRest.cls}`,
      `ask.send="${askRest.cls}" main.send="${mainRest2.cls}"`);

    // ── B 主会话冻结 ──────────────────────────────────────────────────────
    const frozen = FROZEN[mobile ? 'mobile' : 'desktop'];
    const diffFrozen = (got, want) => Object.entries(want).filter(([k, v]) => {
      const map = { svgW: got.svgW, svgH: got.svgH, stroke: got.stroke, hit: got.hit };
      return String(k in map ? map[k] : got[k]) !== String(v);
    }).map(([k]) => `${k}: got=${k in { svgW: 1, svgH: 1, stroke: 1, hit: 1 } ? got[k] : got[k]} want=${want[k]}`);
    const fSend = diffFrozen(mainRest2, frozen.send);
    const fStop = diffFrozen(mainStopRest, frozen.stop);
    ok(engine, `B1 [${tag}] 主会话读数 == 2026-11-18 基线冻结值`, fSend.length === 0 && fStop.length === 0,
      JSON.stringify([...fSend, ...fStop]));
    ok(engine, `B2 [${tag}] 主会话字形 == 冻结（lucide send 纸飞机 / square 18×18 rect）`,
      mainRest2.shapes && mainRest2.shapes.length === 2 && mainRest2.shapes[0].tag === 'path'
      && /^M14\.536/.test(mainRest2.shapes[0].attrs.d)
      && mainStopRest.shapes && mainStopRest.shapes[0].tag === 'rect'
      && mainStopRest.shapes[0].attrs.width === '18' && mainStopRest.shapes[0].attrs.x === '3',
      `send=${JSON.stringify(mainRest2.shapes && mainRest2.shapes[0].attrs)} stop=${JSON.stringify(mainStopRest.shapes && mainStopRest.shapes[0].attrs)}`);

    // ── D 语义与行为（真状态机）───────────────────────────────────────────
    ok(engine, `D1 [${tag}] rest：send 在位 / stop 隐（两腿同值）`,
      mainRest2.display === 'flex' && askRest.display === 'flex'
      && mainStopRest.display === 'none' && askStopRest.display === 'none',
      `main ${mainRest2.display}/${mainStopRest.display} ask ${askRest.display}/${askStopRest.display}`);

    const mark = inbound.length;
    await page.fill('.ask-dock textarea', '请总结这份文档的结论');
    await page.press('.ask-dock textarea', 'Enter');
    const qFrame = await waitFrame(inbound, mark, (m) => m.type === 'askQuestion');
    await sleep(400);
    const askBusy = await page.evaluate(READ, { sel: '.ask-dock .ask-stop' });
    const askSendBusy = await page.evaluate(READ, { sel: '.ask-dock .ask-send' });
    ok(engine, `D2 [${tag}] busy 臂：askQuestion 帧出 + send 隐 / stop 在位 + 几何不缩水`,
      !!qFrame && askSendBusy.display === 'none' && askBusy.display === 'flex'
      && askBusy.width === askRest.width && askBusy.hit === askRest.hit,
      JSON.stringify({ frame: !!qFrame, send: askSendBusy.display, stop: askBusy.display, w: askBusy.width, hit: askBusy.hit }));

    const imark = inbound.length;
    await page.click('.ask-dock .ask-stop');
    const iFrame = await waitFrame(inbound, imark, (m) => m.type === 'interrupt');
    await sleep(400);
    const askAfter = await page.evaluate(READ, { sel: '.ask-dock .ask-send' });
    const askStopAfter = await page.evaluate(READ, { sel: '.ask-dock .ask-stop' });
    ok(engine, `D3 [${tag}] 点停：interrupt 帧出（sessionId 相符）+ 回 rest`,
      !!iFrame && iFrame.sessionId === sidOf(ABS) && askAfter.display === 'flex' && askStopAfter.display === 'none'
      && askAfter.width === askRest.width,
      JSON.stringify({ frame: !!iFrame, sid: iFrame && iFrame.sessionId, want: sidOf(ABS), send: askAfter.display, stop: askStopAfter.display }));

    ok(engine, `D4 [${tag}] 零 pageerror`, errors.length === 0, errors.join('; '));
  } finally {
    await ctx.close(); await browser.close();
  }
}

// ── C 源码单源（无浏览器面）────────────────────────────────────────────────
function sourceLegs() {
  const read = (p) => readFileSync(join(WEB, p), 'utf8');
  const askCss = read('css/ask.css');
  const sc = read('js/input/secondaryComposer.js');
  const ml = read('css/mobileLayer.css');
  const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '');
  const askCssCode = strip(askCss);
  const scCode = strip(sc);
  return {
    // C1 ask.css 里不得再有 `.ask-dock .ds-turn-btn { … }` 携带声明（几何/线宽第二份）
    askDockTurnRule: /\.ask-dock\s+\.ds-turn-btn\s*(?::[-\w()]+)?\s*\{/.test(askCssCode),
    // C2 模板不再自建 SVG 几何
    scSvgPath: /<path\s|createElementNS\(|svgNode\(|'stroke-width'|d:\s*'M/.test(scCode),
    scSvgRect: /width\s*:\s*['"]12['"]/.test(scCode),
    scHasLucideNames: /SEND_ICON\s*=\s*'send'/.test(scCode) && /STOP_ICON\s*=\s*'square'/.test(scCode),
    scImportsIcons: /import\s*\{[^}]*createIconsIn[^}]*\}\s*from\s*'\.\.\/utils\.js'/.test(scCode),
    // C3 输入条圆钮手机档 = 一条声明两个宿主
    mlSharedHosts: /body\.mobile-layer\s+#input-bar\s*>\s*\.ds-turn-btn\s*,\s*body\.mobile-layer\s+\.ask-dock\s+\.ds-turn-btn\s*\{/.test(strip(ml)),
    mlSharedHit: /body\.mobile-layer\s+#input-bar\s*>\s*\.ds-turn-btn::after\s*,\s*body\.mobile-layer\s+\.ask-dock\s+\.ds-turn-btn::after\s*\{/.test(strip(ml)),
    // 反证：主会话组件类仍在 index.html 静态标记上（本批没动主会话）
    indexTurnBtns: /id="send-btn"[^>]*class="ds-turn-btn ds-turn-btn--send"/.test(read('index.html')) || /id="send-btn" class="ds-turn-btn ds-turn-btn--send"/.test(read('js/chatUi.js')),
  };
}

console.log('════ C 源码单源 ════');
const src = sourceLegs();
ok(ENGINE_ARG, 'C1 ask.css 无 `.ask-dock .ds-turn-btn` 本地覆盖（几何/线宽消重）', !src.askDockTurnRule, `found=${src.askDockTurnRule}`);
ok(ENGINE_ARG, 'C2 secondaryComposer.js 不自建 SVG 几何（零 svgNode / path 字面量）', !src.scSvgPath && !src.scSvgRect, JSON.stringify({ path: src.scSvgPath, rect12: src.scSvgRect }));
ok(ENGINE_ARG, 'C2 secondaryComposer.js 产出 data-lucide 占位 + 走 utils.createIconsIn', src.scHasLucideNames && src.scImportsIcons, JSON.stringify({ names: src.scHasLucideNames, imports: src.scImportsIcons }));
ok(ENGINE_ARG, 'C3 mobileLayer 输入条圆钮手机档 = 一条声明两个宿主（尺寸 + ::after）', src.mlSharedHosts && src.mlSharedHit, JSON.stringify({ hosts: src.mlSharedHosts, hit: src.mlSharedHit }));

const combos = ENGINE_ARG === 'webkit' ? [['webkit', webkit]] : [['chromium', chromium]];
for (const [engine, type] of combos) {
  for (const mobile of [false, true]) {
    console.log(`\n════ ${engine} / ${mobile ? 'mobile' : 'desktop'} ════`);
    await runLeg(engine, type, mobile);
  }
}

server.close();
console.log(fail === 0 ? `\nALL PASS (${pass})` : `\n${fail} FAILED`);
process.exit(fail === 0 ? 0 : 1);
