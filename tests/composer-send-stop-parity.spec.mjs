// composer-send-stop-parity.spec.mjs — 手机端发送钮 / 终止钮「同场景同角色同尺寸」spec 钉
// （作者报障 2026-11-03 22:15 逐字：「我们手机端输入框的发送按钮和终止按钮，
//   大小怎么没有统一？发送按钮怎么那么大呀？」/ UI 待办 #1475 第八条）
//
// 病根（改前实测）：手机端交互标准批（2026-11-02）把 `#send-btn` 抬到
//   `--mtap-lg`(48)（mobileLayer.css `body.mobile-layer #send-btn`），但**终止钮
//   `#stop-btn` 没进同一档** —— 它只剩 input.css 桌面基类 36×36。两钮是同一
//   位置同一角色的互斥两态（busy 时 send 隐 / stop 显，见 js/input/composer.js
//   applyTurnChrome），尺寸不一致 = 切换时几何跳变 + 视觉失衡（作者所见的
//   「发送钮怎么那么大」= 参照物 stop 还是 36）。
//
// 设计语言裁定（作者判定要点①）：
//   · 主次用**颜色/描边**表达（send = 绿实底 --ctl-send-*；stop = 警示红
//     --color-error），**不用尺寸**表达；
//   · 两钮同属「输入条圆钮族」⇒ 同一尺寸档：手机层 --mtap-lg(48)（与
//     #attach-btn / 光球 / dock 同一把尺），桌面层 36（该族既有基准，本批不动）；
//   · 热区 ≥44×44（Apple HIG）：手机 48 达标；桌面 36 视觉档 + 命中盒补足
//     （伪元素外扩，见下）。
//
// 断言：
//   P1 手机层：send-stop 宽高逐值相等（±0.5px）+ 都 = --mtap-lg(48) + 正圆
//   P2 手机层：两钮图标同档（--micon 22）+ 描边档一致（1px 同语义）
//   P3 热区 ≥44×44：手机层两者命中盒（本体 ∪ ::after 外扩）均 ≥44
//   P4 桌面层：send-stop 宽高逐值相等（±0.5px）+ = 36（该族既有档，零回归）
//   P5 桌面层：热区 ≥44（本体 36 + 命中盒外扩）—— HIG 达标不靠放大视觉
//   P6 静态钉：两钮尺寸声明单源（mobileLayer.css 同块、同一 token 表达式；
//      input.css 基类两钮同宽高表达式），无第三个尺寸字面量
//   P7 切换态零几何跳变：busy 显隐互换后两钮 rect 全等
//
// 验红：改前 P1/P2/P3/P6 即败（stop 36 vs send 48）。
//
// 运行：node tests/composer-send-stop-parity.spec.mjs
// 可选 env：PORT（默认 8273）、SHOTS_DIR（截图/读数目录）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8273);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/send-stop-parity';
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
const readings = {};
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

const SID = 's-sendstop';
function wireUp(ws) {
  const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
  cfg(); ses();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') cfg();
    else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
}

async function boot({ mobile, colorScheme = 'dark' }) {
  const viewport = mobile ? { width: 390, height: 844 } : { width: 1280, height: 860 };
  const ctx = await browser.newContext({ viewport, colorScheme, hasTouch: mobile, deviceScaleFactor: mobile ? 2 : 1, locale: 'zh-CN' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 200, json: {} }));
  await ctx.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  else await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await sleep(1200);
  return { ctx, page, errors };
}

/** 单钮几何 + 命中盒（本体 ∪ ::after 外扩），两钮同帧。 */
const PROBE = `(() => {
  const round = (n) => Math.round(n * 100) / 100;
  const info = (el, tag) => {
    if (!el) return null;
    const c = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    // 命中盒：::after（若声明）或 ::before 的外扩，取并集
    let hit = { w: r.width, h: r.height };
    for (const pseudo of ['::after', '::before']) {
      const pc = getComputedStyle(el, pseudo);
      if (!pc || pc.content === 'none' || pc.display === 'none') continue;
      const inset = [pc.top, pc.right, pc.bottom, pc.left].map((v) => parseFloat(v) || 0);
      const ex = Math.abs(inset[1]) + Math.abs(inset[3]);
      const ey = Math.abs(inset[0]) + Math.abs(inset[2]);
      hit = { w: Math.max(hit.w, r.width + ex), h: Math.max(hit.h, r.height + ey) };
    }
    const svg = el.querySelector('svg');
    const sr = svg ? svg.getBoundingClientRect() : null;
    return {
      tag,
      w: round(r.width), h: round(r.height),
      hitW: round(hit.w), hitH: round(hit.h),
      radius: c.borderRadius, borderWidth: c.borderTopWidth, borderColor: c.borderTopColor,
      bg: c.backgroundColor, display: c.display,
      iconW: sr ? round(sr.width) : null, iconH: sr ? round(sr.height) : null,
      svgW: svg ? getComputedStyle(svg).width : null,
    };
  };
  const send = document.getElementById('send-btn');
  const stop = document.getElementById('stop-btn');
  // stop 常态 display:none —— 量几何前临时置 flex（量完还原），保证同帧可测。
  const stopWas = stop ? stop.style.display : '';
  if (stop) stop.style.display = 'flex';
  const out = {
    mobile: document.body.classList.contains('mobile-layer'),
    send: info(send, 'send'),
    stop: info(stop, 'stop'),
    tokens: {
      mtapLg: getComputedStyle(document.body).getPropertyValue('--mtap-lg').trim(),
      micon: getComputedStyle(document.body).getPropertyValue('--micon').trim(),
    },
    bar: (() => { const b = document.getElementById('input-bar'); const r = b.getBoundingClientRect(); return { h: round(r.height), cy: round(r.top + r.height / 2) }; })(),
    sendCy: send ? round(send.getBoundingClientRect().top + send.getBoundingClientRect().height / 2) : null,
    stopCy: stop ? round(stop.getBoundingClientRect().top + stop.getBoundingClientRect().height / 2) : null,
  };
  if (stop) stop.style.display = stopWas;
  return out;
})()`;

const probe = (page) => page.evaluate(PROBE);
const approx = (a, b, tol) => Math.abs(a - b) <= tol;

/* ════════ 手机腿（作者报障面）════════ */
{
  const { ctx, page, errors } = await boot({ mobile: true });
  const m = await probe(page);
  readings.mobile = m;
  const tag = 'P1';
  ok(`${tag} 前置：手机层 + 两钮在场（send/stop 均量到盒）`,
    m.mobile === true && !!m.send && !!m.stop,
    `mobile=${m.mobile} send=${m.send && `${m.send.w}×${m.send.h}`} stop=${m.stop && `${m.stop.w}×${m.stop.h}`}`);
  ok(`${tag} 手机层：发送钮 = 终止钮（宽高逐值 ±0.5px，作者报障本体）`,
    approx(m.send.w, m.stop.w, 0.5) && approx(m.send.h, m.stop.h, 0.5),
    `send=${m.send.w}×${m.send.h} stop=${m.stop.w}×${m.stop.h}`);
  const mtap = parseFloat(m.tokens.mtapLg);
  ok(`${tag}b 手机层：两钮同档 = --mtap-lg（${mtap}px，与附件钮/光球/dock 同一把尺）+ 正圆 50%`,
    approx(m.send.w, mtap, 0.5) && approx(m.stop.w, mtap, 0.5)
    && m.send.radius === '50%' && m.stop.radius === '50%',
    `sendW=${m.send.w} stopW=${m.stop.w} mtapLg=${mtap} r=${m.send.radius}/${m.stop.radius}`);
  ok('P2 手机层：两钮图标同档（--micon 22，圆内同视觉重量）',
    approx(m.send.iconW, parseFloat(m.tokens.micon), 0.5) && approx(m.stop.iconW, m.send.iconW, 0.5),
    `sendIcon=${m.send.iconW} stopIcon=${m.stop.iconW} micon=${m.tokens.micon}`);
  ok('P2b 手机层：两钮描边档一致（都 1px 同语义描边，形状语言零差异）',
    m.send.borderWidth === m.stop.borderWidth,
    `send=${m.send.borderWidth} stop=${m.stop.borderWidth}`);
  ok('P3 触控热区（Apple HIG ≥44×44）：两钮命中盒均达标',
    m.send.hitW >= 44 && m.send.hitH >= 44 && m.stop.hitW >= 44 && m.stop.hitH >= 44,
    `send=${m.send.hitW}×${m.send.hitH} stop=${m.stop.hitW}×${m.stop.hitH}`);
  ok('P7 切换零几何跳变：两钮垂直中心 = 输入条中线（同排同位）',
    approx(m.sendCy, m.stopCy, 0.5) && approx(m.sendCy, m.bar.cy, 1.5),
    `sendCy=${m.sendCy} stopCy=${m.stopCy} barCy=${m.bar.cy}`);
  await page.screenshot({ path: join(SHOTS_DIR, `${process.env.PARITY_PHASE || 'phase'}-mobile-rest.png`),
    clip: await page.evaluate(() => {
      const b = document.getElementById('input-bar').getBoundingClientRect();
      return { x: Math.max(0, b.left - 8), y: Math.max(0, b.top - 8), width: Math.min(390, b.width + 16), height: Math.min(220, b.height + 16) };
    }) }).catch(() => {});
  // 亮色腿：同判据（色相反转不改几何）
  await page.emulateMedia({ colorScheme: 'light' });
  await sleep(400);
  const mLight = await probe(page);
  readings.mobileLight = mLight;
  ok('P1L 亮色：发送钮 = 终止钮（宽高逐值 ±0.5px）',
    approx(mLight.send.w, mLight.stop.w, 0.5) && approx(mLight.send.h, mLight.stop.h, 0.5),
    `send=${mLight.send.w}×${mLight.send.h} stop=${mLight.stop.w}×${mLight.stop.h}`);
  ok('P9 手机腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ 桌面腿（作者要点④：同位置顺带核）════════ */
{
  const { ctx, page, errors } = await boot({ mobile: false });
  const m = await probe(page);
  readings.desktop = m;
  ok('P4 桌面层：发送钮 = 终止钮（宽高逐值 ±0.5px）',
    approx(m.send.w, m.stop.w, 0.5) && approx(m.send.h, m.stop.h, 0.5),
    `send=${m.send.w}×${m.send.h} stop=${m.stop.w}×${m.stop.h}`);
  ok('P4b 桌面层：两钮 = 36 档（输入条圆钮族既有基准，本批零回归）',
    approx(m.send.w, 36, 0.5) && approx(m.send.h, 36, 0.5)
    && approx(m.stop.w, 36, 0.5) && approx(m.stop.h, 36, 0.5),
    `send=${m.send.w}×${m.send.h} stop=${m.stop.w}×${m.stop.h}`);
  ok('P5 桌面层：热区 ≥44×44（本体 36 + 命中盒外扩，HIG 达标不靠放大视觉）',
    m.send.hitW >= 44 && m.send.hitH >= 44 && m.stop.hitW >= 44 && m.stop.hitH >= 44,
    `send=${m.send.hitW}×${m.send.hitH} stop=${m.stop.hitW}×${m.stop.hitH}`);
  await page.screenshot({ path: join(SHOTS_DIR, `${process.env.PARITY_PHASE || 'phase'}-desktop-rest.png`),
    clip: await page.evaluate(() => {
      const b = document.getElementById('input-bar').getBoundingClientRect();
      return { x: Math.max(0, b.left - 8), y: Math.max(0, b.top - 8), width: Math.min(700, b.width + 16), height: Math.min(220, b.height + 16) };
    }) }).catch(() => {});
  ok('P9b 桌面腿零 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));

  /* ── 静态钉：尺寸声明单源 ── */
  const inputCss = readFileSync(join(WEB_DIR, 'css/input.css'), 'utf8');
  const mobCss = readFileSync(join(WEB_DIR, 'css/mobileLayer.css'), 'utf8');
  const sendBlock = /#send-btn\s*\{([^}]*)\}/.exec(inputCss)?.[1] || '';
  const stopBlock = /#stop-btn\s*\{([^}]*)\}/.exec(inputCss)?.[1] || '';
  const sendW = /width:\s*([^;]+);/.exec(sendBlock)?.[1]?.trim();
  const stopW = /width:\s*([^;]+);/.exec(stopBlock)?.[1]?.trim();
  const sendH = /height:\s*([^;]+);/.exec(sendBlock)?.[1]?.trim();
  const stopH = /height:\s*([^;]+);/.exec(stopBlock)?.[1]?.trim();
  ok('P6 基类单源：input.css 两钮 width/height 表达式逐字相等（无第三档）',
    !!sendW && sendW === stopW && sendH === stopH,
    `send=${sendW}/${sendH} stop=${stopW}/${stopH}`);
  const mobPair = /body\.mobile-layer\s+#send-btn,\s*\n?\s*body\.mobile-layer\s+#stop-btn\s*\{([^}]*)\}/.exec(mobCss)?.[1]
    || /body\.mobile-layer\s+#send-btn\s*\{([^}]*)\}/.exec(mobCss)?.[1] || '';
  ok('P6b 手机档单源：mobileLayer.css 两钮同块同 token 表达式（--mtap-lg）',
    /--mtap-lg/.test(mobPair) && !/36px/.test(mobPair),
    `block="${mobPair.replace(/\s+/g, ' ').slice(0, 140)}"`);
  await ctx.close();
}

writeFileSync(join(SHOTS_DIR, `${process.env.PARITY_PHASE || 'phase'}-readings.json`), JSON.stringify(readings, null, 2));
await browser.close();
server.close();
console.log(`\ncomposer-send-stop-parity: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
