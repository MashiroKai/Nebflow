#!/usr/bin/env node
// contact-shell.spec.mjs — 会话窗「悬浮顶栏 + 悬浮输入条」自验 harness
// （contact-panel-unify 链 · 节点「顶栏与输入框设计（仿照主会话）」）
//
// 被测面（好友/群/设备三窗**同一份**实现）：
//   · 窗头 `.fm-modal-header`  —— js/messages.js:1320 建、css/friends.css 造型、
//     材质收编在 css/glass.css 的「顶部悬浮头」共享声明（与 `#header` 同一份）；
//   · 滚动区 `.fm-flow`         —— js/messages.js:1550 建、css/friends.css 布局；
//   · 输入条 `.fm-input-bar`    —— js/messages/fmComposer.js:54 建。
//
// 判据（节点任务书「验收」逐条）：
//   (i)   `.fm-modal-header` computed background 含玻璃底（非 transparent、非 rgba 洗色），
//         且 backdrop-filter 含 blur（= 吃唯一毛玻璃标准）；
//   (ii)  窗头无 border-bottom、`.fm-flow` 无 border-top（零边缘）；
//   (iii) 滚动后某条消息的 rect.top < 窗头 rect.bottom（内容从窗头下方穿过），
//         且窗头半透明（α < 1）⇒ 穿过是真的「透出来」而不是被盖住；
//   (iv)  `.fm-input-bar` border-radius ≥ 16px、背景 = `.nf-glass` 逐值相等
//         （走令牌而不是自写色值）、且无自写 border-top；
//   (v)   375 与 1440 两档无横向溢出（documentElement.scrollWidth ≤ innerWidth）。
//   另加几何链断言：窗头贴窗体顶缘、全宽、实测高 = `--fm-head-h` 公式值，
//   `.fm-flow` 的 padding-top = `--fm-head-clear`（顶栏让位由同一套令牌算出，
//   不是又一个手写数）。
//
// 手机档（body.mobile-layer，视口 375）：窗头 = `.mh-idhead`（零画带）+ 名字玻璃
// 胶囊，断言带本体不画东西（α=0）、名字胶囊是毛玻璃、输入条仍大圆角悬浮。
//
// 同一脚本跑两份树 ⇒ 改前必红 / 改后必绿（禁 MODE 分支，判据只有一套）：
//   node tests/contact-shell.spec.mjs                                  # 本仓树（改后）
//   node tests/contact-shell.spec.mjs --root <改前 web 树> --tag before # 改前树
// 环境变量：CS_WEB_ROOT / CS_OUT / CS_TAG（与参数等价）。
//
// 🔴 诚实申报：后端 = 本脚本侧的 `/api/*` 契约镜像 + WS 帧镜像（visual-harness 的
//   MockWS 走真实 ws.js 分发路径），**不是**生产网关；零实例依赖（不起服务、不碰
//   宿主 6138）。覆盖面 = 真 Chromium + 真实模块图 + 真实 CSS + 真实几何。

import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  loadPlaywright, launchBrowser, createSession, settle, setWebRoot, webRoot, shoot,
} from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const ROOT_DIR = valOf('--root', process.env.CS_WEB_ROOT || 'src/main/resources/web');
const TAG = valOf('--tag', process.env.CS_TAG || 'after');
const OUT = valOf('--out', process.env.CS_OUT || join('/tmp', `contact-shell-${TAG}`));
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

// ── 断言出口 ────────────────────────────────────────────────────────────────
let checks = 0;
let failures = 0;
const ok = (name, cond, extra = '') => {
  checks++;
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// ── 契约镜像夹具 ────────────────────────────────────────────────────────────
const FIXED = new Date(2026, 10, 6, 9, 0, 0).getTime();
const iso = (ms) => new Date(ms).toISOString();
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(FIXED - 86400000) };
/** 消息行（wire = id/senderId/kind/body/createdAt）；40 条 ⇒ 一定超一屏，可滚动。 */
const MSGS = Array.from({ length: 40 }, (_, i) => ({
  id: 100 + i,
  senderId: i % 2 ? 'me' : 'u-p',
  kind: 'text',
  body: `第 ${i + 1} 条：这一行用来量「内容从窗头底下穿过去」的那一段几何。`,
  createdAt: iso(FIXED - (40 - i) * 60000),
}));
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS[MSGS.length - 1], unreadCount: 0 },
];
const CONFIG = { llm: { providers: {} }, features: { friends: true } };

const API_STUBS = {
  '/api/conversations/cF/messages': { status: 200, body: MSGS },
  '/api/conversations': { status: 200, body: CONVS },
  '/api/friends': { status: 200, body: { friends: [FRIEND], incoming: [], outgoing: [] } },
  '/api/groups': { status: 200, body: [] },
  '/api/groups/invites': { status: 200, body: { incoming: [] } },
  '/api/neblink/status': {
    status: 200,
    body: {
      loggedIn: true,
      relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
      device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
      peers: [],
    },
  },
};

// ── 页面侧读数（一次 evaluate 取全，避免跨帧比对的抖动）────────────────────
const READ = () => {
  const alphaOf = (c) => {
    const s = String(c || '').trim();
    if (!s || s === 'transparent') return 0;
    const m = s.match(/^rgba?\(([^)]*)\)$/i);
    if (!m) return /^#([0-9a-f]{8})$/i.test(s) ? parseInt(s.slice(7, 9), 16) / 255 : 1;
    const p = m[1].split(/[,/]/).map((x) => x.trim()).filter(Boolean);
    if (p.length >= 4) return p[3].endsWith('%') ? parseFloat(p[3]) / 100 : parseFloat(p[3]);
    return 1;
  };
  const px = (v) => parseFloat(String(v || '0'));
  const rectObj = (r) => (r ? { top: +r.top.toFixed(2), bottom: +r.bottom.toFixed(2), left: +r.left.toFixed(2), right: +r.right.toFixed(2), width: +r.width.toFixed(2), height: +r.height.toFixed(2) } : null);
  const q = (s) => document.querySelector(s);
  const modal = q('.fm-modal');
  const header = q('.fm-modal-header');
  const flow = q('.fm-flow');
  const bar = q('.fm-input-bar');
  const idhead = q('.mh-idhead');
  const namecap = document.querySelector('.mh-idhead-name, .mh-idhead > #session-name');
  const mainHeader = q('#header');

  // `.nf-glass` 探针 = 唯一毛玻璃工具类的实测玻璃底（用来证明「走的是令牌」）
  const probe = document.createElement('div');
  probe.className = 'nf-glass';
  probe.style.cssText = 'position:fixed;left:-9999px;top:-9999px;width:10px;height:10px;';
  document.body.appendChild(probe);
  const probeCs = getComputedStyle(probe);
  const probeBg = probeCs.backgroundColor;
  probe.remove();

  const cs = (el) => (el ? getComputedStyle(el) : null);
  const hCs = cs(header);
  const fCs = cs(flow);
  const bCs = cs(bar);
  const iCs = cs(idhead);
  const nCs = cs(namecap);

  // 窗头让位令牌的解析值（几何链：实测高 = 公式值，flow 内距 = 公式值）
  const varOf = (el, name) => (el ? getComputedStyle(el).getPropertyValue(name).trim() : '');
  const resolveLen = (v) => {
    if (!v) return null;
    const d = document.createElement('div');
    d.style.cssText = `position:fixed;left:-9999px;top:-9999px;width:${v};height:0;`;
    document.body.appendChild(d);
    const w = d.getBoundingClientRect().width;
    d.remove();
    return +w.toFixed(2);
  };

  return {
    viewport: { w: window.innerWidth, h: window.innerHeight, mobile: document.body.classList.contains('mobile-layer') },
    doc: { scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth, bodyScrollWidth: document.body.scrollWidth },
    probeGlassBg: probeBg,
    probeGlassAlpha: alphaOf(probeBg),
    modal: rectObj(modal && modal.getBoundingClientRect()),
    header: header ? {
      rect: rectObj(header.getBoundingClientRect()),
      position: hCs.position,
      background: hCs.background.slice(0, 160),
      backgroundColor: hCs.backgroundColor,
      bgAlpha: alphaOf(hCs.backgroundColor),
      backdrop: hCs.backdropFilter || hCs.webkitBackdropFilter || 'none',
      borderBottomWidth: hCs.borderBottomWidth,
      borderBottomColor: hCs.borderBottomColor,
      borderBottomAlpha: alphaOf(hCs.borderBottomColor),
      borderRadius: hCs.borderRadius,
      padding: hCs.padding,
      pad: [hCs.paddingTop, hCs.paddingRight, hCs.paddingBottom, hCs.paddingLeft],
      headH: resolveLen(varOf(header, '--fm-head-h') || varOf(modal, '--fm-head-h')),
      headClear: resolveLen(varOf(flow, '--fm-head-clear')),
    } : null,
    flow: flow ? {
      rect: rectObj(flow.getBoundingClientRect()),
      borderTopWidth: fCs.borderTopWidth,
      borderTopColor: fCs.borderTopColor,
      paddingTop: px(fCs.paddingTop),
      scrollTop: flow.scrollTop,
      scrollHeight: flow.scrollHeight,
      clientHeight: flow.clientHeight,
    } : null,
    bar: bar ? {
      rect: rectObj(bar.getBoundingClientRect()),
      radius: bCs.borderRadius,
      radiusPx: px(bCs.borderTopLeftRadius),
      backgroundColor: bCs.backgroundColor,
      bgAlpha: alphaOf(bCs.backgroundColor),
      backdrop: bCs.backdropFilter || bCs.webkitBackdropFilter || 'none',
      borderTopWidth: bCs.borderTopWidth,
      borderTopColor: bCs.borderTopColor,
      borderTopAlpha: alphaOf(bCs.borderTopColor),
      borderBottomWidth: bCs.borderBottomWidth,
      borderBottomColor: bCs.borderBottomColor,
      borderLeftWidth: bCs.borderLeftWidth,
      borderLeftColor: bCs.borderLeftColor,
      borderRightWidth: bCs.borderRightWidth,
      borderRightColor: bCs.borderRightColor,
      distToModalBottom: modal ? +(modal.getBoundingClientRect().bottom - bar.getBoundingClientRect().bottom).toFixed(2) : null,
      distToModalLeft: modal ? +(bar.getBoundingClientRect().left - modal.getBoundingClientRect().left).toFixed(2) : null,
    } : null,
    idhead: idhead ? {
      rect: rectObj(idhead.getBoundingClientRect()),
      backgroundColor: iCs.backgroundColor,
      bgAlpha: alphaOf(iCs.backgroundColor),
      borderWidth: iCs.borderTopWidth,
    } : null,
    nameCap: namecap ? {
      rect: rectObj(namecap.getBoundingClientRect()),
      backgroundColor: nCs.backgroundColor,
      bgAlpha: alphaOf(nCs.backgroundColor),
      backdrop: nCs.backdropFilter || nCs.webkitBackdropFilter || 'none',
      radius: nCs.borderRadius,
    } : null,
    /* 主会话顶栏（参照物）：收编判据 = 两面同一条共享声明 ⇒ 圆角/内距/玻璃底/
       模糊四项**逐值相等**。改前两面各写各的（内距 10/16 vs 12/16/12/20、
       会话窗窗头零材质），这一组会红。 */
    mainHeader: mainHeader ? {
      rect: rectObj(mainHeader.getBoundingClientRect()),
      padding: (() => { const c = getComputedStyle(mainHeader); return `${c.paddingTop} ${c.paddingRight} ${c.paddingBottom} ${c.paddingLeft}`; })(),
      pad: (() => { const c = getComputedStyle(mainHeader); return [c.paddingTop, c.paddingRight, c.paddingBottom, c.paddingLeft]; })(),
      borderRadius: getComputedStyle(mainHeader).borderRadius,
      backgroundColor: getComputedStyle(mainHeader).backgroundColor,
      bgAlpha: alphaOf(getComputedStyle(mainHeader).backgroundColor),
      backdrop: getComputedStyle(mainHeader).backdropFilter || getComputedStyle(mainHeader).webkitBackdropFilter || 'none',
      borderBottomColor: getComputedStyle(mainHeader).borderBottomColor,
      borderBottomAlpha: alphaOf(getComputedStyle(mainHeader).borderBottomColor),
    } : null,
    msgCount: document.querySelectorAll('.fm-flow .fm-msg').length,
  };
};

/** 滚动后取「穿过」证据：**在滚动区可视范围内**（未被 pad 区/裁剪吞掉）且
 *  与窗头带相交的消息。只认这种行 —— 仅看 rect 不看裁剪会把「已经滚出可视区、
 *  被 overflow 裁掉」的行也算成穿过（假阳性）。 */
const PIERCE = () => {
  const header = document.querySelector('.fm-modal-header');
  const flow = document.querySelector('.fm-flow');
  if (!header || !flow) return null;
  const hb = header.getBoundingClientRect();
  const fb = flow.getBoundingClientRect();
  flow.scrollTop = Math.min(220, Math.max(0, flow.scrollHeight - flow.clientHeight));
  const rows = [...flow.querySelectorAll('.fm-msg')].map((el) => {
    const r = el.getBoundingClientRect();
    return { top: +r.top.toFixed(2), bottom: +r.bottom.toFixed(2) };
  });
  // ① 可见：行顶在滚动区可视范围内（≥ flow 顶缘）——即真渲染出来了
  // ② 穿过：行顶 < 窗头下缘，且行底 > 窗头顶缘（与窗头带真的有交叠）
  const crossed = rows.filter((r) => r.top >= fb.top - 0.5 && r.top < hb.bottom - 0.5 && r.bottom > hb.top + 0.5);
  // ③ 叠压次序：窗头带内取一点，命中元素必须落在窗头子树里（= 窗头画在内容之上）
  const cx = fb.left + fb.width / 2;
  const cy = hb.top + Math.min(8, hb.height / 4);
  const hit = document.elementFromPoint(cx, cy);
  return {
    scrollTop: flow.scrollTop,
    headerTop: +hb.top.toFixed(2),
    headerBottom: +hb.bottom.toFixed(2),
    flowTop: +fb.top.toFixed(2),
    overlapping: crossed.length,
    deepestTop: rows.length ? Math.min(...rows.map((r) => r.top)) : null,
    firstOverlappers: crossed.slice(0, 4),
    hitInsideHeader: !!(hit && header.contains(hit)),
    hitTag: hit ? `${hit.className || hit.tagName}`.slice(0, 60) : null,
  };
};

const url = setWebRoot(ROOT_DIR);
console.log(`# contact-shell · tag=${TAG} · static root = ${url}`);

const { mod } = loadPlaywright();
const { browser, label } = await launchBrowser(mod);
console.log(`# browser = ${label}`);

/** 开窗：点消息入口 → 点会话行。整条走产品真实入口，不直写 DOM。
    桌面 = 活动栏 `#messages-btn`；手机层（body.mobile-layer，≤768px）= 底部
    dock 消息键 `[data-dock="messages"]`（同一份 `#fm-conversations` 列表，
    收编进 dock 页面壳 —— 见 js/mobile/navController.js:82 DOCK_ORDER）。 */
async function openConv(page) {
  const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  await page.waitForSelector(mobile ? '[data-dock="messages"]' : '#messages-btn', { timeout: 15000 });
  await page.click(mobile ? '[data-dock="messages"]' : '#messages-btn');
  await page.waitForSelector('#fm-conversations .fm-conv-row[data-conversation-id="cF"]', { timeout: 15000 });
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="cF"]');
  await page.waitForSelector('.fm-modal .fm-flow .fm-msg', { timeout: 15000 });
  await settle(page, 400);
}

const readings = { tag: TAG, webRoot: url, browser: label, desktop: null, mobile: null };

// ═══ 桌面档 1440×900 ═══════════════════════════════════════════════════════
{
  const s = await createSession(browser, { width: 1440, height: 900, colorScheme: 'light', apiStubs: API_STUBS });
  try {
    await s.boot({ config: CONFIG });
    await openConv(s.page);
    const before = await s.page.evaluate(READ);
    const pierce = await s.page.evaluate(PIERCE);
    await settle(s.page, 150);
    writeFileSync(join(SHOTS, `desktop-1440-${TAG}.png`), await shoot(s.page, { selector: '#fm-chat-overlay' }));
    readings.desktop = { ...before, pierce };

    const h = before.header;
    const f = before.flow;
    const b = before.bar;

    ok('1440 · 窗头在场（.fm-modal-header）', !!h);
    ok('.fm-flow 在场', !!f);
    ok('.fm-input-bar 在场', !!b);
    ok('消息 ≥ 20 条（可滚动）', before.msgCount >= 20, `count=${before.msgCount}`);
    ok('窗体宽 1440（真渲染）', before.modal && before.modal.width > 400 && before.modal.width <= 600, `modal=${before.modal && before.modal.width}px`);

    // (i) 玻璃底
    ok('(i) 窗头背景非 transparent', h && h.bgAlpha > 0.05, `backgroundColor=${h && h.backgroundColor} α=${h && h.bgAlpha}`);
    ok('(i) 窗头背景 α 落玻璃带 [.45,.75]', h && h.bgAlpha >= 0.45 && h.bgAlpha <= 0.75, `α=${h && h.bgAlpha}`);
    ok('(i) 窗头 background = .nf-glass 玻璃底（逐值相等）', h && h.backgroundColor === before.probeGlassBg,
      `header=${h && h.backgroundColor} / .nf-glass=${before.probeGlassBg}`);
    ok('(i) 窗头 backdrop-filter 含 blur', h && /blur\(/.test(h.backdrop), `filter=${h && h.backdrop}`);

    // (ii) 零边缘
    ok('(ii) 窗头无 border-bottom', h && (h.borderBottomWidth === '0px' || h.borderBottomAlpha === 0),
      `width=${h && h.borderBottomWidth} color=${h && h.borderBottomColor}`);
    ok('(ii) .fm-flow 无 border-top', f && (f.borderTopWidth === '0px' || f.borderTopColor === 'rgba(0, 0, 0, 0)'),
      `width=${f && f.borderTopWidth} color=${f && f.borderTopColor}`);

    // 几何链
    ok('窗头 position:absolute（离流悬浮）', h && h.position === 'absolute', `position=${h && h.position}`);
    ok('窗头贴窗体顶缘', h && before.modal && Math.abs(h.rect.top - before.modal.top) <= 1,
      `header.top=${h && h.rect.top} modal.top=${before.modal && before.modal.top}`);
    ok('窗头全宽（与窗体同宽）', h && before.modal && Math.abs(h.rect.width - before.modal.width) <= 2,
      `header.w=${h && h.rect.width} modal.w=${before.modal && before.modal.width}`);
    ok('窗头高度 = --fm-head-h 公式值', h && h.headH != null && Math.abs(h.rect.height - h.headH) <= 1,
      `实测=${h && h.rect.height} 令牌=${h && h.headH}`);
    ok('.fm-flow padding-top = --fm-head-clear 公式值', h && h.headClear != null && Math.abs(f.paddingTop - h.headClear) <= 1,
      `padding-top=${f && f.paddingTop} 令牌=${h && h.headClear}`);

    // (iii) 内容穿过
    ok('(iii) 滚动后存在**可见**消息 rect.top < 窗头 rect.bottom', pierce && pierce.overlapping > 0,
      `overlapping=${pierce && pierce.overlapping} flowTop=${pierce && pierce.flowTop} headerBottom=${pierce && pierce.headerBottom} deepest.top=${pierce && pierce.deepestTop}`);
    ok('(iii) 窗头画在内容之上（带内命中点落在窗头子树）', pierce && pierce.hitInsideHeader,
      `hit=${pierce && pierce.hitTag}`);
    ok('(iii) 窗头半透明（α < 1 ⇒ 穿过真能透出来）', h && h.bgAlpha < 1, `α=${h && h.bgAlpha}`);
    ok('(iii) 滚动区顶缘与窗体同顶（流从窗头底下开始）', f && before.modal && Math.abs(f.rect.top - before.modal.top) <= (h ? 1 : 30),
      `flow.top=${f && f.rect.top} modal.top=${before.modal && before.modal.top}`);

    // (iv) 输入条
    ok('(iv) 输入条 border-radius ≥ 16px', b && b.radiusPx >= 16, `radius=${b && b.radius}`);
    ok('(iv) 输入条背景 = .nf-glass 玻璃底（走令牌）', b && b.backgroundColor === before.probeGlassBg,
      `bar=${b && b.backgroundColor} / .nf-glass=${before.probeGlassBg}`);
    ok('(iv) 输入条 backdrop-filter 含 blur', b && /blur\(/.test(b.backdrop), `filter=${b && b.backdrop}`);
    // 「删除自写 border-top」的机械口径：**没有单独一条上边** —— 四边是同一份
    // 玻璃细边（`.nf-glass` 的 border，与主会话 #input-bar 同族）；改前那条
    // `border-top: 1px solid var(--glass-etched-border)` 会是「只有上边、颜色不同」。
    ok('(iv) 输入条无「自写的上边」（四边同一份玻璃细边）',
      b && b.borderTopWidth === b.borderBottomWidth && b.borderTopWidth === b.borderLeftWidth
        && b.borderTopColor === b.borderBottomColor && b.borderTopColor === b.borderLeftColor,
      `top=${b && b.borderTopWidth}/${b && b.borderTopColor} bottom=${b && b.borderBottomWidth}/${b && b.borderBottomColor} left=${b && b.borderLeftWidth}/${b && b.borderLeftColor}`);
    ok('(iv) 输入条悬浮（左右各留内距 > 0）', b && b.distToModalLeft != null && b.distToModalLeft > 0,
      `distToLeft=${b && b.distToModalLeft}`);

    // (v) 无横向溢出
    ok('(v) 1440 无横向溢出', before.doc.scrollWidth <= before.doc.innerWidth + 1,
      `scrollWidth=${before.doc.scrollWidth} innerWidth=${before.doc.innerWidth}`);

    // ── 收编判据：两面同一条共享声明（令牌/材质/几何逐值相等）────────────────
    const mh = before.mainHeader;
    ok('收编 · 主会话 #header 在场（同页对照件）', !!mh);
    ok('收编 · 圆角逐值相等（两面同一 --nf-head-radius）', mh && h && mh.borderRadius === h.borderRadius,
      `#header=${mh && mh.borderRadius} / 会话窗=${h && h.borderRadius}`);
    ok('收编 · 内距逐值相等（两面同一 --nf-head-pad-*）', mh && h && mh.pad && h.pad && mh.pad.join() === h.pad.join(),
      `#header=${mh && mh.pad && mh.pad.join('/')} / 会话窗=${h && h.pad && h.pad.join('/')}`);
    ok('收编 · 玻璃底逐值相等（两面同一 --nf-head-bg 的底）', mh && h && mh.backgroundColor === h.backgroundColor,
      `#header=${mh && mh.backgroundColor} / 会话窗=${h && h.backgroundColor}`);
    ok('收编 · 模糊配方逐值相等（两面同一 --nf-glass-filter）', mh && h && mh.backdrop === h.backdrop,
      `#header=${mh && mh.backdrop} / 会话窗=${h && h.backdrop}`);
    ok('收编 · 主会话顶栏仍零下边缘（回归）', mh && mh.borderBottomAlpha === 0,
      `borderBottom=${mh && mh.borderBottomColor}`);
  } finally {
    await s.close();
  }
}

// ═══ 手机档 375×812 ═══════════════════════════════════════════════════════
{
  const s = await createSession(browser, { width: 375, height: 812, colorScheme: 'light', apiStubs: API_STUBS });
  try {
    await s.boot({ config: CONFIG });
    await openConv(s.page);
    const m = await s.page.evaluate(READ);
    await settle(s.page, 150);
    writeFileSync(join(SHOTS, `mobile-375-${TAG}.png`), await s.page.screenshot({ animations: 'disabled' }));
    readings.mobile = m;

    ok('375 · body.mobile-layer 已激活', m.viewport.mobile === true);
    ok('375 · 悬浮身份头 .mh-idhead 在场', !!m.idhead, m.idhead ? `top=${m.idhead.rect.top} h=${m.idhead.rect.height}` : 'missing');
    ok('375 · 身份头本体零画（无横贯顶栏带）', m.idhead && m.idhead.bgAlpha === 0, `bg=${m.idhead && m.idhead.backgroundColor}`);
    ok('375 · 名字胶囊 = 毛玻璃', m.nameCap && m.nameCap.bgAlpha > 0.05 && /blur\(/.test(m.nameCap.backdrop),
      `bg=${m.nameCap && m.nameCap.backgroundColor} filter=${m.nameCap && m.nameCap.backdrop}`);
    ok('375 · 输入条 border-radius ≥ 16px', m.bar && m.bar.radiusPx >= 16, `radius=${m.bar && m.bar.radius}`);
    ok('375 · 输入条背景 = .nf-glass 玻璃底（走令牌）', m.bar && m.bar.backgroundColor === m.probeGlassBg,
      `bar=${m.bar && m.bar.backgroundColor} / .nf-glass=${m.probeGlassBg}`);
    ok('375 · 输入条悬浮（左侧留距 > 0）', m.bar && m.bar.distToModalLeft != null && m.bar.distToModalLeft > 0,
      `distToLeft=${m.bar && m.bar.distToModalLeft}`);
    ok('375 · 输入条不越窗（右缘 ≤ 窗右缘）', m.bar && m.modal && m.bar.rect.right <= m.modal.right + 1,
      `bar.right=${m.bar && m.bar.rect.right} modal.right=${m.modal && m.modal.right}`);
    ok('375 · 无横向溢出', m.doc.scrollWidth <= m.doc.innerWidth + 1,
      `scrollWidth=${m.doc.scrollWidth} innerWidth=${m.doc.innerWidth}`);
  } finally {
    await s.close();
  }
}

await browser.close();

// ── 读数落盘 + 摘要 ────────────────────────────────────────────────────────
writeFileSync(join(OUT, `readings-${TAG}.json`), JSON.stringify(readings, null, 2));
console.log(`\n── 读数（${TAG}）──────────────────────────────────────────────`);
const d = readings.desktop || {};
console.log(`1440 窗头: h=${d.header && d.header.rect.height} top=${d.header && d.header.rect.top} bg=${d.header && d.header.backgroundColor} α=${d.header && d.header.bgAlpha} filter=${d.header && d.header.backdrop} borderBottom=${d.header && d.header.borderBottomWidth}/${d.header && d.header.borderBottomColor}`);
console.log(`1440 流:   top=${d.flow && d.flow.rect.top} paddingTop=${d.flow && d.flow.paddingTop} borderTop=${d.flow && d.flow.borderTopWidth} 穿过消息=${d.pierce && d.pierce.overlapping}`);
console.log(`1440 输入条: h=${d.bar && d.bar.rect.height} w=${d.bar && d.bar.rect.width} radius=${d.bar && d.bar.radius} 距底=${d.bar && d.bar.distToModalBottom} 距左=${d.bar && d.bar.distToModalLeft} bg=${d.bar && d.bar.backgroundColor}`);
const mo = readings.mobile || {};
console.log(`375 身份头:  ${mo.idhead && JSON.stringify(mo.idhead.rect)} 名字胶囊 bg=${mo.nameCap && mo.nameCap.backgroundColor} filter=${mo.nameCap && mo.nameCap.backdrop}`);
console.log(`375 输入条:  w=${mo.bar && mo.bar.rect.width} radius=${mo.bar && mo.bar.radius} 距左=${mo.bar && mo.bar.distToModalLeft} 距底=${mo.bar && mo.bar.distToModalBottom} scrollWidth=${mo.doc && mo.doc.scrollWidth}`);
console.log(`\n合计 ${checks} 项 · FAIL ${failures} 项 · 读数 ${join(OUT, `readings-${TAG}.json`)}`);
process.exit(failures ? 1 : 0);
