#!/usr/bin/env node
// rcpt-hover-inner.spec.mjs — Delegate 完工卡「hover 只显示内框」验收
// （作者令 2026-11-07：「现在 delegate 的气泡 hover，会同时显示外框和内框，
//   只显示内框就行了」）。
//
// 报障面（父子框定位，见 css/diffView.css「完工通知卡」段）：
//   .bubble.injected > .nf-rcpt-top > .nf-rcpt-head > {.nf-rcpt-pill, .nf-rcpt-pipe,
//   .nf-rcpt-note}；其中
//     · 外框 = .nf-rcpt-head:hover（背景 sapphire 0.08 + inset 0 0 0 1px
//       sapphire 0.22 hairline）——横跨整条卡头行；
//     · 内框 = .nf-rcpt-pill:hover（块底 sapphire 0.14 + 描边 sapphire 0.38）——
//       只包住「DELEGATE · INTERNAL · COMPLETED」胶囊。
//   两者嵌套 ⇒ hover 时同屏两圈框（截图报障形态：外圈大框 + 内圈胶囊）。
//
// 判据（同一脚本两态各跑一次；改前必红 / 改后必绿）：
//   t1  父子框结构读数（外框 .nf-rcpt-head 同时含 pill 与 note —— 定位证据）；
//   h1  rest 零外框（基线：卡头行恒无底无影，内框 hairline 常驻）；
//   h2  **hover 胶囊时外框不得出现**（head 背景全透明 + 无 box-shadow；改前必红）；
//   h3  hover 胶囊时内框仍在（块底 tint + 描边转 sapphire —— 保留判据）；
//   h4  hover 卡头空白区（| 分隔段）时同样无外框（改前必红）；
//   h4b 像素级：hover 该区前后同取景逐字节相同（外框零染色；改前必红）；
//   h5  hover diff 徽标时该块自身 hover 保留、外框不出现（改前红）；
//   h6  键盘 focus 可见性不回归（Tab 到 .nf-rcpt-top ⇒ focus ring 在场，
//       且不借外框回魂）；
//   h7  真暗档（colorScheme=dark 独立上下文，不靠手写属性）同判 h2/h3；
//   h8  手机档（375×812 触屏、body.mobile-layer 已激活）rest 形态不回归；
//   i1  交互不回归：点卡头展开/收起（aria-expanded 翻转 + 正文可见）；
//   i2  交互不回归：点 diff 徽标仍开左栏「变更文件」面板（aria-pressed=true）；
//   i3  其他气泡不受影响：非 delegate 的普通注入泡 hover 反馈（label opacity）
//       逐字节不变；
//   z1  全程零 pageerror。
//
// Run:
//   node tests/rcpt-hover-inner.spec.mjs                      # 改后（预期全绿）
//   RCPT_WEB=<改前 web 根副本> RCPT_PHASE=before node tests/rcpt-hover-inner.spec.mjs
// 取证：RCPT_EVIDENCE_DIR=<目录>（默认 /Users/kaiyu/.stand/evidence/delegate-hover-inner）。
// 自包含：node:http 静态伺服（独占端口，不碰宿主 gateway）+ routeWebSocket 桩 +
// 真渲染链（WS 帧 → main.js onMessage('user') → renderInjectedBubble →
// injectedRows.buildInjectedRow），禁静态替身、禁 sleep 轮询宿主。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.RCPT_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.RCPT_PHASE === 'before' ? 'before' : 'after';
const OUT = join(process.env.RCPT_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/delegate-hover-inner', PHASE);
mkdirSync(OUT, { recursive: true });

const SID = 's-rcpt-hover-inner';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };

let failures = 0;
const checks = [];
function ok(name, cond, extra = '') {
  // eslint-disable-next-line no-console
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  checks.push({ name, ok: !!cond, extra });
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 真实形态载荷：files 面在场 ⇒ diff 徽标是 button（可点入口）。
const PAYLOAD = {
  nodeId: 'rcpt-hover-probe', generatedAt: 1794050843000, root: '/Users/kaiyu/Claude code/Stand',
  files: [
    { path: 'src/main/resources/web/js/chat/fixture-a.js', changeType: 'modified', added: 92, removed: 12, source: 'tool-ledger' },
    { path: 'src/main/resources/web/js/chat/fixture-b.js', changeType: 'modified', added: 74, removed: 9, source: 'tool-ledger' },
  ],
  merge: { commit: 'b6ceb9d0e', added: 166, removed: 21 },
};
const DELEGATE_MSG = {
  type: 'user', injected: true, timestamp: 1794050843000,
  source: 'delegate', eventType: 'completed', sender: 'nebflow',
  header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
  text: '委托完成：hover 内框探针。', fileChanges: PAYLOAD,
};
// 对照组：普通注入泡（非 delegate —— 走通用 label 路径，必须逐字节不受影响）。
const PLAIN_MSG = {
  type: 'user', injected: true, timestamp: 1794050844000,
  source: 'tool', eventType: 'result', sender: 'bash',
  header: 'TOOL · BASH · RESULT',
  text: '普通注入泡对照行：hover 反馈必须保持不变。',
};

const conns = [];
function wireUp(ws) {
  conns.push(1);
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'hover 内框验收' }], activeId: SID, folders: [] }));
}

const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (path.startsWith('/api/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// ── 读数工具 ────────────────────────────────────────────────────────────
const READ_FN = (el) => {
  const cs = getComputedStyle(el);
  const r = el.getBoundingClientRect();
  return {
    tag: el.tagName,
    bg: cs.backgroundColor,
    shadow: cs.boxShadow,
    borderColor: cs.borderColor,
    borderWidth: cs.borderWidth,
    cursor: cs.cursor,
    outlineStyle: cs.outlineStyle,
    outlineWidth: cs.outlineWidth,
    cx: r.left + r.width / 2,
    cy: r.top + r.height / 2,
    w: r.width,
    h: r.height,
  };
};
const alpha = (c) => {
  const m = String(c || '').match(/rgba?\(([^)]+)\)/);
  if (!m) return 0;
  const parts = m[1].split(/[,/]/).map((s) => s.trim()).filter(Boolean);
  return parts.length >= 4 ? Number(parts[3]) : 1;
};
const hasFrame = (r) => !!r && (alpha(r.bg) > 0.005 || (r.shadow && r.shadow !== 'none'));

const readings = {};
const pageErrors = [];
let page = null;

async function read(sel) {
  try { return await page.locator(sel).first().evaluate(READ_FN); } catch { return null; }
}
async function hover(sel) {
  const r = await read(sel);
  if (!r) return null;
  await page.mouse.move(r.cx, r.cy);
  await sleep(260); // transition 0.15s + hover 判定落定
  return r;
}
async function unhover() {
  await page.mouse.move(4, 4);
  await sleep(260);
}
/** 键盘 Tab 到目标元素（真键盘路径 ⇒ :focus-visible 命中）。 */
async function tabTo(sel, max = 60) {
  await page.evaluate(() => { document.activeElement?.blur?.(); });
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab');
    const hit = await page.evaluate((s) => !!document.activeElement && document.activeElement.matches(s), sel);
    if (hit) return true;
  }
  return false;
}
/** 取证截图的裁剪盒：目标元素 rect 外扩 pad（紧裁 + dpr2 ⇒ 视图可辨）。 */
function clipOf(sel, pad) {
  return (async () => {
    try {
      const r = await page.locator(sel).first().evaluate((el) => {
        const b = el.getBoundingClientRect();
        return { x: b.left, y: b.top, w: b.width, h: b.height };
      });
      return { x: Math.max(0, Math.floor(r.x - pad)), y: Math.max(0, Math.floor(r.y - pad)), width: Math.ceil(r.w + pad * 2), height: Math.ceil(r.h + pad * 2) };
    } catch { return undefined; }
  })();
}
/** 主题哨兵：dark 腿必须证明「真暗档」已生效（不靠手写属性）。 */
const THEME_PROBE = () => {
  const cs = getComputedStyle(document.documentElement);
  const body = getComputedStyle(document.body);
  return {
    prefersDark: !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches),
    bodyBg: body.backgroundColor,
    textColor: cs.getPropertyValue('--color-text').trim(),
    bubbleOurs: cs.getPropertyValue('--nf-bub-ours-bg').trim(),
  };
};

/** 开一个隔离上下文 → 真页面 → 注入注入泡（真渲染链）。 */
async function bootAndInject(contextOptions, msgs) {
  const ctx = await browser.newContext(contextOptions);
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const p = await ctx.newPage();
  p.on('pageerror', (e) => pageErrors.push(String(e.message).slice(0, 200)));
  await p.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
  await p.goto(BASE + '/index.html');
  await p.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);
  for (const m of msgs) {
    await p.evaluate((msg) => import('/js/state.js').then((mod) => {
      const s = mod.default;
      s.ws.onmessage({ data: JSON.stringify({ ...msg, sessionId: s.activeSessionId }) });
    }), m);
  }
  await p.waitForSelector('.nf-rcpt-pill', { timeout: 15000 });
  await sleep(320);
  return { ctx, p };
}

const browser = await chromium.launch();
try {
  // ── 相位 A：桌面亮档（1280×800，dpr2 供取证清晰度）────────────────────
  const light = await bootAndInject({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 }, [DELEGATE_MSG, PLAIN_MSG]);
  page = light.p;
  ok('boot 后在线 + 完工卡与对照泡均已渲染（真渲染链）',
    conns.length > 0 && (await page.locator('.nf-rcpt-pill').count()) === 1 && (await page.locator('.bubble.injected .injected-source-label').count()) >= 1);
  const themeLight = await page.evaluate(THEME_PROBE);
  readings.theme = { light: themeLight };

  // ── t1 父子框结构 ────────────────────────────────────────────────────
  const struct = await page.evaluate(() => {
    const head = document.querySelector('.nf-rcpt-head');
    const top = document.querySelector('.nf-rcpt-top');
    const pill = document.querySelector('.nf-rcpt-pill');
    const note = document.querySelector('.nf-rcpt-note');
    const bubble = document.querySelector('.bubble.injected');
    const inner = (a, b) => !!(a && b && a.contains(b));
    return {
      bubbleHasTop: inner(bubble, top),
      topHasHead: inner(top, head),
      headHasPill: inner(head, pill),
      headHasNote: inner(head, note),
      pillText: pill ? pill.textContent : null,
      noteText: note ? note.textContent : null,
      topRole: top ? top.getAttribute('role') : null,
      topTabindex: top ? top.getAttribute('tabindex') : null,
    };
  });
  readings.struct = struct;
  ok('t1 结构确认：外框 .nf-rcpt-head 同时含内框 .nf-rcpt-pill 与 diff 徽标（嵌套父子框=报障源）',
    struct.bubbleHasTop && struct.topHasHead && struct.headHasPill && struct.headHasNote,
    JSON.stringify(struct));

  // ── h1 rest 基线 ─────────────────────────────────────────────────────
  await unhover();
  const rest = { head: await read('.nf-rcpt-head'), pill: await read('.nf-rcpt-pill') };
  readings.rest = rest;
  ok('h1 rest：卡头行零底零影（外框不常驻）+ 胶囊 hairline 边框常驻（内框基线）',
    !hasFrame(rest.head) && rest.pill && parseFloat(rest.pill.borderWidth) >= 1,
    `head(bg=${rest.head?.bg}, shadow=${rest.head?.shadow}) pill(border=${rest.pill?.borderColor})`);
  await page.screenshot({ path: join(OUT, 'desktop-rest.png'), clip: await clipOf('.nf-rcpt-head', 18) }).catch(() => {});
  // 像素级对照基线：| 分隔段邻域（外框若在场必染色此区），rest 态取一帧。
  const pipeRestShot = await page.screenshot({ clip: await clipOf('.nf-rcpt-pipe', 10) });

  // ── h2/h3 hover 胶囊：外框必须消失、内框必须保留 ───────────────────
  await hover('.nf-rcpt-pill');
  const hoverPill = { head: await read('.nf-rcpt-head'), pill: await read('.nf-rcpt-pill') };
  readings.hoverPill = hoverPill;
  const pillTinted = alpha(hoverPill.pill?.bg) > alpha(rest.pill?.bg);
  const pillBorderShift = hoverPill.pill?.borderColor !== rest.pill?.borderColor;
  ok('h2 hover 胶囊：外框不出现（head 背景全透明 + 无 box-shadow；改前必红）',
    !hasFrame(hoverPill.head), `head(bg=${hoverPill.head?.bg}, shadow=${hoverPill.head?.shadow})`);
  ok('h3 hover 胶囊：内框仍在（块底 tint 加深 + 描边色变）',
    pillTinted && pillBorderShift,
    `pill bg ${rest.pill?.bg} → ${hoverPill.pill?.bg}；border ${rest.pill?.borderColor} → ${hoverPill.pill?.borderColor}`);
  await page.screenshot({ path: join(OUT, 'desktop-hover-pill.png'), clip: await clipOf('.nf-rcpt-head', 18) }).catch(() => {});

  // ── h4/h4b hover 卡头空白区（| 分隔段）────────────────────────────
  await unhover();
  await hover('.nf-rcpt-pipe');
  const hoverPipe = await read('.nf-rcpt-head');
  readings.hoverPipe = hoverPipe;
  ok('h4 hover 卡头空白区（| 分隔段）：外框不出现（改前必红）',
    !hasFrame(hoverPipe), `head(bg=${hoverPipe?.bg}, shadow=${hoverPipe?.shadow})`);
  const pipeHoverShot = await page.screenshot({ clip: await clipOf('.nf-rcpt-pipe', 10) });
  writeFileSync(join(OUT, 'pipe-rest.png'), pipeRestShot);
  writeFileSync(join(OUT, 'pipe-hover.png'), pipeHoverShot);
  readings.pipePixels = { restBytes: pipeRestShot.length, hoverBytes: pipeHoverShot.length, identical: pipeRestShot.equals(pipeHoverShot) };
  ok('h4b 像素级：hover 卡头空白区前后该取景逐字节相同（外框零染色；改前必红）',
    pipeRestShot.equals(pipeHoverShot), JSON.stringify(readings.pipePixels));
  await page.screenshot({ path: join(OUT, 'desktop-hover-headblank.png'), clip: await clipOf('.nf-rcpt-head', 18) }).catch(() => {});

  // ── h5 hover diff 徽标：块自身反馈保留、外框不出现 ──────────────────
  await unhover();
  const noteRest = await read('.nf-rcpt-note');
  await hover('.nf-rcpt-note');
  const hoverNote = { note: await read('.nf-rcpt-note'), head: await read('.nf-rcpt-head') };
  readings.hoverNote = { rest: noteRest, hover: hoverNote };
  ok('h5 hover diff 徽标：该块自身 hover 保留（块底 tint 加深）且外框不出现（改前红）',
    alpha(hoverNote.note?.bg) > alpha(noteRest?.bg) && !hasFrame(hoverNote.head),
    `note bg ${noteRest?.bg} → ${hoverNote.note?.bg}；head(bg=${hoverNote.head?.bg}, shadow=${hoverNote.head?.shadow})`);
  await page.screenshot({ path: join(OUT, 'desktop-hover-note.png'), clip: await clipOf('.nf-rcpt-head', 18) }).catch(() => {});

  // ── h6 键盘 focus 可见性 ────────────────────────────────────────────
  await unhover();
  const focused = await tabTo('.nf-rcpt-top');
  const focusRead = { top: await read('.nf-rcpt-top'), head: await read('.nf-rcpt-head') };
  readings.focus = { focused, ...focusRead };
  ok('h6 键盘 Tab 可达卡头（role=button/tabindex=0 不变）', focused, `focused=${focused}`);
  ok('h6b 键盘 focus ring 在场（outline 可见，不靠外框）',
    focusRead.top && focusRead.top.outlineStyle !== 'none' && parseFloat(focusRead.top.outlineWidth) >= 1.5,
    `outline=${focusRead.top?.outlineStyle} ${focusRead.top?.outlineWidth}`);
  ok('h6c focus 态不借外框回魂（head 无底无影）', !hasFrame(focusRead.head),
    `head(bg=${focusRead.head?.bg}, shadow=${focusRead.head?.shadow})`);
  await page.screenshot({ path: join(OUT, 'desktop-focus.png'), clip: await clipOf('.nf-rcpt-head', 18) }).catch(() => {});

  // ── i1/i2 交互不回归 ────────────────────────────────────────────────
  await page.click('.nf-rcpt-top');
  await sleep(400);
  const expanded = await page.evaluate(() => {
    const top = document.querySelector('.nf-rcpt-top');
    const content = document.querySelector('.injected-content');
    return { aria: top?.getAttribute('aria-expanded'), contentShown: !!content && content.style.display !== 'none' };
  });
  await page.click('.nf-rcpt-top');
  await sleep(400);
  const collapsed = await page.evaluate(() => {
    const top = document.querySelector('.nf-rcpt-top');
    const content = document.querySelector('.injected-content');
    return { aria: top?.getAttribute('aria-expanded'), contentShown: !!content && content.style.display !== 'none' };
  });
  readings.interaction = { expanded, collapsed };
  ok('i1 点卡头：展开/收起仍工作（aria-expanded 翻转 + 正文显隐）',
    expanded.aria === 'true' && expanded.contentShown && collapsed.aria === 'false' && !collapsed.contentShown,
    JSON.stringify({ expanded, collapsed }));

  await page.click('.nf-rcpt-note');
  await sleep(800);
  const noteE2e = await page.evaluate(() => {
    const p = document.getElementById('panel-filechanges');
    return { panelActive: !!p && p.classList.contains('active'), rows: p ? p.querySelectorAll('.nf-fc').length : -1, pressed: document.querySelector('.nf-rcpt-note')?.getAttribute('aria-pressed') };
  });
  readings.noteE2e = noteE2e;
  ok('i2 点 diff 徽标：左栏「变更文件」仍打开（行数=2、aria-pressed=true）',
    noteE2e.panelActive && noteE2e.rows === 2 && noteE2e.pressed === 'true',
    JSON.stringify(noteE2e));
  await page.keyboard.press('Escape').catch(() => {});
  await sleep(300);

  // ── i3 其他气泡不受影响 ─────────────────────────────────────────────
  await unhover();
  const plainRest = await read('.bubble.injected .injected-source-label');
  await hover('.bubble.injected .injected-source-label');
  const plainHover = await read('.bubble.injected .injected-source-label');
  const plainOpacity = await page.locator('.bubble.injected .injected-source-label').first().evaluate((el) => getComputedStyle(el).opacity);
  readings.plain = { rest: plainRest, hover: plainHover, opacityOnHover: plainOpacity };
  ok('i3 普通注入泡（非 delegate）hover 反馈不变（label 透明度 0.8、无块底无描边）',
    plainHover && plainHover.bg === plainRest?.bg && plainHover.shadow === plainRest?.shadow && plainOpacity === '0.8',
    JSON.stringify({ rest: plainRest?.bg, hover: plainHover?.bg, opacity: plainOpacity }));
  await page.screenshot({ path: join(OUT, 'desktop-plain-bubble.png'), clip: await clipOf('.bubble.injected:last-of-type .injected-source-label', 10) }).catch(() => {});

  await unhover();
  await page.screenshot({ path: join(OUT, 'desktop-stream.png') }).catch(() => {});
  await hover('.nf-rcpt-pill');
  await page.screenshot({ path: join(OUT, 'desktop-stream-hover.png') }).catch(() => {});
  await light.ctx.close();

  // ── 相位 B：真暗档（colorScheme=dark 独立上下文）────────────────────
  const dark = await bootAndInject({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2, colorScheme: 'dark' }, [DELEGATE_MSG]);
  page = dark.p;
  const themeDark = await page.evaluate(THEME_PROBE);
  readings.theme.dark = themeDark;
  ok('h7-0 真暗档已生效（prefers-color-scheme: dark 命中且主题哨兵与亮档不同）',
    themeDark.prefersDark === true && (themeDark.bodyBg !== themeLight.bodyBg || themeDark.textColor !== themeLight.textColor),
    JSON.stringify({ dark: themeDark, light: themeLight }));
  await unhover();
  const darkRest = { head: await read('.nf-rcpt-head'), pill: await read('.nf-rcpt-pill') };
  await hover('.nf-rcpt-pill');
  const darkHover = { head: await read('.nf-rcpt-head'), pill: await read('.nf-rcpt-pill') };
  readings.dark = { rest: darkRest, hover: darkHover };
  ok('h7 暗档：hover 胶囊时外框不出现（改前必红）', !hasFrame(darkHover.head),
    `head(bg=${darkHover.head?.bg}, shadow=${darkHover.head?.shadow})`);
  ok('h7b 暗档：内框仍在（tint 加深 + 描边色变）',
    alpha(darkHover.pill?.bg) > alpha(darkRest.pill?.bg) && darkHover.pill?.borderColor !== darkRest.pill?.borderColor,
    `pill bg ${darkRest.pill?.bg} → ${darkHover.pill?.bg}`);
  await page.screenshot({ path: join(OUT, 'dark-hover-pill.png'), clip: await clipOf('.nf-rcpt-head', 18) }).catch(() => {});
  await dark.ctx.close();

  // ── 相位 C：手机档（触屏 375×812）──────────────────────────────────
  const mobileEnv = await bootAndInject({
    viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
  }, [DELEGATE_MSG]);
  page = mobileEnv.p;
  await sleep(400);
  const mobile = await page.evaluate(() => {
    const head = document.querySelector('.nf-rcpt-head');
    const pill = document.querySelector('.nf-rcpt-pill');
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const a = (c) => { const m = String(c || '').match(/rgba?\(([^)]+)\)/); if (!m) return 0; const p = m[1].split(/[,/]/).map((x) => x.trim()).filter(Boolean); return p.length >= 4 ? Number(p[3]) : 1; };
    const hs = cs(head); const ps = cs(pill);
    return {
      mobileLayer: !!window.__nfMobileLayer && window.__nfMobileLayer.active,
      headBg: hs && hs.backgroundColor, headShadow: hs && hs.boxShadow,
      pillBorderWidth: ps && ps.borderWidth, pillBg: ps && ps.backgroundColor,
      headFramed: !!hs && (a(hs.backgroundColor) > 0.005 || (hs.boxShadow && hs.boxShadow !== 'none')),
      pillRect: pill ? { h: pill.getBoundingClientRect().height, w: pill.getBoundingClientRect().width } : null,
    };
  });
  readings.mobile = mobile;
  ok('h8 手机档：mobile 交互层已激活（body.mobile-layer）', mobile.mobileLayer === true, JSON.stringify({ active: mobile.mobileLayer }));
  ok('h8b 手机档：rest 形态不回归（胶囊 hairline 边框在场、卡头零外框）',
    parseFloat(mobile.pillBorderWidth) >= 1 && !mobile.headFramed,
    JSON.stringify(mobile));
  await page.screenshot({ path: join(OUT, 'mobile-rest.png') }).catch(() => {});
  await mobileEnv.ctx.close();

  ok('z1 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
} finally {
  await browser.close().catch(() => {});
  await new Promise((r) => server.close(r));
}

writeFileSync(join(OUT, 'readings.json'), JSON.stringify({ phase: PHASE, web: WEB, readings, checks }, null, 2));
writeFileSync(join(OUT, 'run.log'), checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n') + '\n');
const pass = checks.filter((c) => c.ok).length;
// eslint-disable-next-line no-console
console.log(`\n${PHASE}: ${pass} PASS / ${checks.length - pass} FAIL`);
process.exit(failures ? 1 : 0);
