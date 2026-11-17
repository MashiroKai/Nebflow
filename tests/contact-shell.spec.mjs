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
// 🔴 日期行上移批（2026-11-06 作者令②）：FIXED 同时是**页面内冻结时钟**
//   （createSession({clock})）——formatFooterDay 的 now = new Date() 恒 11-06
//   09:00 ⇒ 「昨天/星期二」分桶判定与断言跨日确定（不随跑测日期漂移）。
const FIXED = new Date(2026, 10, 6, 9, 0, 0).getTime();
const DAY_MS = 86400000;
const iso = (ms) => new Date(ms).toISOString();
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(FIXED - 86400000) };
/** 消息行（wire = id/senderId/kind/body/createdAt）；40 条**跨三个本地日历日**：
 *  i∈[0,12) = 11-03（本周周二）· i∈[12,28) = 11-05（昨天）· i∈[28,40) = 今天。
 *  ⇒ 日期行判据（跨天只显一次：恰 2 条居中日期行、今天无行）+ 纯时钟 footer 判据
 *  （改前昨天/更早的 footer 逐条带日期层 = 本组判据改前必红）。 */
const MSGS = Array.from({ length: 40 }, (_, i) => {
  const dayBack = i < 12 ? 3 : (i < 28 ? 1 : 0);
  return {
    id: 100 + i,
    senderId: i % 2 ? 'me' : 'u-p',
    kind: 'text',
    body: `第 ${i + 1} 条：这一行用来量「内容从窗头底下穿过去」的那一段几何。`,
    createdAt: iso(FIXED - dayBack * DAY_MS - (40 - i) * 60000),
  };
});
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS[MSGS.length - 1], unreadCount: 0 },
];
const CONFIG = { llm: { providers: {} }, features: { friends: true } };
/** sync-status 桩（3(a) 三态徽标真数据源）：body 由各会话按态给。 */
const syncStatusStub = (state) => ({
  status: 200,
  body: {
    neblink: true, selfDeviceId: 'dev-self', peers: 2,
    fields: {
      friendBio: { state, updatedAt: FIXED - 60000, peers: 2 },
      groupRemark: { state, updatedAt: FIXED - 60000, peers: 2 },
      groupDescription: { state, updatedAt: FIXED - 60000, peers: 2 },
      selfBio: { state, updatedAt: FIXED - 60000, peers: 2 },
    },
  },
});

const API_STUBS = {
  '/api/conversations/cF/messages': { status: 200, body: MSGS },
  '/api/conversations': { status: 200, body: CONVS },
  '/api/friends': { status: 200, body: { friends: [FRIEND], incoming: [], outgoing: [] } },
  '/api/groups': { status: 200, body: [] },
  '/api/groups/invites': { status: 200, body: { incoming: [] } },
  '/api/profile/sync-status': syncStatusStub('synced'),
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
  // FCC-REQ-007: on desktop the conversation is hosted in the main area and the page header is its header.
  const header = q('.fm-modal-header') || q('#header');
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
      fromModalTop: modal ? +(header.getBoundingClientRect().bottom - modal.getBoundingClientRect().top).toFixed(2) : null,
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
  const header = document.querySelector('.fm-modal-header') || document.querySelector('#header');
  const flow = document.querySelector('.fm-flow');
  if (!header || !flow) return null;
  const hb = header.getBoundingClientRect();
  const fb = flow.getBoundingClientRect();
  flow.scrollTop = Math.min(220, Math.max(0, flow.scrollHeight - flow.clientHeight));
  // 日期行上移批（2026-11-06）补充：跨天日期行让「滚动 220px」落在行间隙的
  // 概率出现（行位随日期行高度漂移）⇒ 滚后做一次**确定性对齐**——把第 4 条
  // 消息行的顶缘对到窗头带内 8px，被测语义（一条可见消息真穿过窗头带）不再
  // 依赖行布局的绝对位置。
  const alignRow = [...flow.querySelectorAll('.fm-msg')][3];
  if (alignRow) {
    const ar = alignRow.getBoundingClientRect();
    flow.scrollTop += ar.top - (hb.top + 8);
  }
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

/** 日期行上移批（作者令②）读数：日期行/纯时钟 footer/会话档搜索的机械证据。 */
const DAYLINES = () => {
  const flow = document.querySelector('.fm-flow');
  const q = (s) => document.querySelector(s);
  const seps = flow ? [...flow.querySelectorAll('.fm-day-sep')] : [];
  const flowRect = flow ? flow.getBoundingClientRect() : null;
  const footers = [...document.querySelectorAll('.fm-msg-time')].map((e) => ({
    text: e.textContent || '',
    hasDayFormat: e.hasAttribute('data-ts-text-format'),
  }));
  return {
    seps: seps.map((s) => {
      const r = s.getBoundingClientRect();
      return {
        dayKey: s.dataset.dayKey || '',
        text: (s.querySelector('.duration-badge-time') || s).textContent || '',
        pill: s.classList.contains('duration-badge'),
        centerX: +(r.left + r.width / 2).toFixed(1),
      };
    }),
    flowCenterX: flowRect ? +(flowRect.left + flowRect.width / 2).toFixed(1) : null,
    footerCount: footers.length,
    footersWithDateLayer: footers.filter((f) => !/^[0-9]{1,2}:[0-9]{2}(\s?(AM|PM))?$/.test(f.text.trim())).length,
    footersWithDayFormatAttr: footers.filter((f) => f.hasDayFormat).length,
    footerSample: footers.slice(0, 3).map((f) => f.text),
    searchTitle: q('#search-modal-title') ? q('#search-modal-title').textContent : null,
    convScope: document.getElementById('search-overlay')?.dataset.convScope || '',
    scopeDisplay: q('#search-scope') ? getComputedStyle(q('#search-scope')).display : null,
  };
};

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
  // clock 冻结（日期行上移批）：页面内 new Date() 恒 FIXED ⇒ 跨天分桶/日期行
  // 文本断言跨跑测日确定（FIXED = 2026-11-06 周五 09:00 本地）。
  const s = await createSession(browser, { width: 1440, height: 900, colorScheme: 'light', apiStubs: API_STUBS, clock: FIXED });
  try {
    await s.boot({ config: CONFIG });
    // The header's glass as it is while the Agent is shown, to compare with the header of the open conversation.
    s.agentHeader = await s.page.evaluate(() => {
      const cs = getComputedStyle(document.getElementById('header'));
      return { backgroundColor: cs.backgroundColor, backdrop: cs.backdropFilter || cs.webkitBackdropFilter || 'none' };
    });
    await openConv(s.page);
    s.mainWidth = await s.page.evaluate(() => Math.round(document.getElementById('main').getBoundingClientRect().width));
    const before = await s.page.evaluate(READ);
    const pierce = await s.page.evaluate(PIERCE);
    await settle(s.page, 150);
    writeFileSync(join(SHOTS, `desktop-1440-${TAG}.png`), await shoot(s.page, { selector: '#fm-chat-overlay' }));
    readings.desktop = { ...before, pierce };

    const h = before.header;
    const f = before.flow;
    const b = before.bar;

    ok('1440 · 窗头在场（桌面 = 主区顶栏 #header；手机层 = .fm-modal-header）', !!h);
    ok('.fm-flow 在场', !!f);
    ok('.fm-input-bar 在场', !!b);
    ok('消息 ≥ 20 条（可滚动）', before.msgCount >= 20, `count=${before.msgCount}`);
    // FCC-REQ-007: the window fills the main column instead of being a 560px dialog.
    ok('窗体 = 主区整列宽（真渲染，非 560px 对话框）', before.modal && before.modal.width > 400 && Math.abs(before.modal.width - s.mainWidth) <= 2, `modal=${before.modal && before.modal.width}px main=${s.mainWidth}`);

    // (i) 玻璃底
    ok('(i) 窗头背景非 transparent', h && h.bgAlpha > 0.05, `backgroundColor=${h && h.backgroundColor} α=${h && h.bgAlpha}`);
    ok('(i) 窗头背景 α 落唯一材质带 [.90,1.0]（单一材质批 2026-11-15）', h && h.bgAlpha >= 0.90 && h.bgAlpha <= 1, `α=${h && h.bgAlpha}`);
    // FCC-REQ-007: the window's header is the main #header. The `.nf-glass` utility is the input-surface glass (inside
    // #main it measures rgba(255, 255, 255, 0.97) in the light theme) and is not the header's material (0.55), so the
    // header of the open conversation is compared with the same header while the Agent is shown (read before the
    // conversation was opened): opening a conversation must not change its glass.
    ok('(i) 窗头 background = 同一顶栏在 Agent 态的玻璃底（开会话不改材质，逐值相等）', h && s.agentHeader && h.backgroundColor === s.agentHeader.backgroundColor,
      `open=${h && h.backgroundColor} / agent=${s.agentHeader && s.agentHeader.backgroundColor}`);
    ok('(i) 窗头 backdrop-filter = 同一顶栏在 Agent 态的滤镜（逐值相等）', h && s.agentHeader && h.backdrop === s.agentHeader.backdrop,
      `open=${h && h.backdrop} / agent=${s.agentHeader && s.agentHeader.backdrop}`);
    ok('(i) 窗头 backdrop-filter 含 blur', h && /blur\(/.test(h.backdrop), `filter=${h && h.backdrop}`);

    // (ii) 零边缘
    ok('(ii) 窗头无 border-bottom', h && (h.borderBottomWidth === '0px' || h.borderBottomAlpha === 0),
      `width=${h && h.borderBottomWidth} color=${h && h.borderBottomColor}`);
    ok('(ii) .fm-flow 无 border-top', f && (f.borderTopWidth === '0px' || f.borderTopColor === 'rgba(0, 0, 0, 0)'),
      `width=${f && f.borderTopWidth} color=${f && f.borderTopColor}`);

    // 几何链
    ok('窗头 position:absolute（离流悬浮）', h && h.position === 'absolute', `position=${h && h.position}`);
    // FCC-REQ-007: the header is #header, inset from the top edge by its own margin.
    ok('窗头在窗体顶缘之内（#header 自带上外距）', h && before.modal && h.rect.top >= before.modal.top - 1 && h.rect.top - before.modal.top <= 24,
      `header.top=${h && h.rect.top} modal.top=${before.modal && before.modal.top}`);
    ok('窗头全宽（与窗体同宽）', h && before.modal && Math.abs(h.rect.width - before.modal.width) <= 2,
      `header.w=${h && h.rect.width} modal.w=${before.modal && before.modal.width}`);
    ok('窗头让位 --fm-head-h = 窗头下缘到窗体顶缘的实测距离', h && h.headH != null && h.fromModalTop != null && Math.abs(h.fromModalTop - h.headH) <= 1,
      `实测=${h && h.fromModalTop} 令牌=${h && h.headH}`);
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
    // FCC-REQ-007: in the main area the input bar spans the column like the Agent's (same edge slot as the header).
    ok('(iv) 输入条与顶栏同缘同宽（主区边距槽，非对话框内距）', b && before.mainHeader && Math.abs(b.rect.left - before.mainHeader.rect.left) <= 1 && Math.abs(b.rect.right - before.mainHeader.rect.right) <= 1,
      `bar=${b && b.rect.left}..${b && b.rect.right} header=${before.mainHeader && before.mainHeader.rect.left}..${before.mainHeader && before.mainHeader.rect.right}`);

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
    ok('收编 · 模糊配方逐值相等（两面同一 --nf-glass-input-filter）', mh && h && mh.backdrop === h.backdrop,
      `#header=${mh && mh.backdrop} / 会话窗=${h && h.backdrop}`);
    ok('收编 · 主会话顶栏仍零下边缘（回归）', mh && mh.borderBottomAlpha === 0,
      `borderBottom=${mh && mh.borderBottomColor}`);

    // ── 日期行上移批（2026-11-06 作者令②「跨天只显示一次」）────────────────────
    // 夹具 40 条跨三个本地日历日（11-03 / 11-05 昨天 / 今天），时钟冻结在
    // FIXED = 11-06 09:00 ⇒ 期望恰 2 条居中日期行（今天 = 纯时钟档无行），
    // 且 footer 全部为纯时钟（改前昨天/更早 footer 逐条带日期层 = 改前必红）。
    const dl = await s.page.evaluate(DAYLINES);
    readings.desktopDay = dl;
    const sepKeys = dl.seps.map((x) => x.dayKey);
    const dayKeyOfTs = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; };
    const wantKeys = [...new Set(MSGS.map((m) => dayKeyOfTs(Date.parse(m.createdAt))))].filter((k) => k !== dayKeyOfTs(FIXED));
    ok('D1 · 跨天日期行在场且数量 = 天数−1（今天无行）',
      dl.seps.length === wantKeys.length && wantKeys.every((k) => sepKeys.includes(k)),
      `seps=${dl.seps.length}(${sepKeys.join(',')}) want=${wantKeys.length}(${wantKeys.join(',')})`);
    ok('D2 · 日期行文本 = 分层档（星期X/昨天，今天无）',
      dl.seps.map((x) => x.text).join('|') === '星期二|昨天',
      `texts=${dl.seps.map((x) => x.text).join('|')}`);
    ok('D3 · 日期行居中（中心 x = flow 中心 x ±2px）',
      dl.seps.length > 0 && dl.seps.every((x) => Math.abs(x.centerX - dl.flowCenterX) <= 2),
      `centers=${dl.seps.map((x) => x.centerX).join(',')} flow=${dl.flowCenterX}`);
    ok('D4 · 日期行壳 = 主会话 footer 同一枚 .duration-badge（零第二套 pill）',
      dl.seps.length > 0 && dl.seps.every((x) => x.pill),
      `pill=${dl.seps.map((x) => x.pill).join(',')}`);
    ok('D5 · footer 全纯时钟（40 条无一携带日期层；改前 = 昨天/更早逐条「昨天 hh:mm」）',
      dl.footerCount === MSGS.length && dl.footersWithDateLayer === 0,
      `footers=${dl.footerCount} 带日期层=${dl.footersWithDateLayer} sample=${dl.footerSample.join('/')}`);
    ok('D6 · footer 零 data-ts-text-format="day"（12/24 切换按纯时钟重排）',
      dl.footersWithDayFormatAttr === 0,
      `dayFormatAttrs=${dl.footersWithDayFormatAttr}`);

    // ── 搜索页标题两字档（2026-11-06 作者令「用搜索两个字吧」）──────────────────
    // 真入口：主对话顶栏搜索钮 → i18n 单点 search.title 渲染到 #search-modal-title。
    // 🔴 桌面会话窗是页级模态（cfg-modal-overlay 盖住顶栏）⇒ 先关窗再点主顶栏
    //    搜索钮（主会话搜索的本态 = 主对话在场）。
    await s.page.click('#activity-avatar');
    await settle(s.page, 300);
    await s.page.click('#search-btn');
    await s.page.waitForSelector('#search-overlay.on', { timeout: 8000 });
    await settle(s.page, 200);
    const st = await s.page.evaluate(DAYLINES);
    readings.desktopSearch = { title: st.searchTitle, convScope: st.convScope };
    ok('S1 · 搜索面板标题 = 「搜索」二字（search.title，整族「搜索聊天记录」退役）',
      st.searchTitle === '搜索', `title=${JSON.stringify(st.searchTitle)}`);
    ok('S2 · 主对话入口无会话档（convScope 清空 ⇒ 全局形态零变化）',
      st.convScope === '', `data-conv-scope=${JSON.stringify(st.convScope)}`);
    await s.page.click('#search-modal-close');
    await settle(s.page, 150);
  } finally {
    await s.close();
  }
}

// ═══ 手机档 375×812 ═══════════════════════════════════════════════════════
{
  const s = await createSession(browser, { width: 375, height: 812, colorScheme: 'light', apiStubs: API_STUBS, clock: FIXED });
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
    // 日期行上移批（手机档同批判据）：跨天日期行在手机档同样只显一次。
    const mdl = await s.page.evaluate(DAYLINES);
    readings.mobileDay = mdl;
    ok('375 · 跨天日期行恰 2 条（11-03 + 昨天；今天无行）', mdl.seps.length === 2,
      `seps=${mdl.seps.length}(${mdl.seps.map((x) => x.text).join(',')})`);
    ok('375 · footer 全纯时钟', mdl.footerCount === MSGS.length && mdl.footersWithDateLayer === 0,
      `footers=${mdl.footerCount} 带日期层=${mdl.footersWithDateLayer}`);
  } finally {
    await s.close();
  }
}

// ═══ 会话内独立搜索（3(b) 作者令：只搜当前会话，scope 隔离不串全局）══════════
// 真入口全程：dock 消息页 → 会话行 → 右上三点（好友=直开资料面）→ 「搜索聊天记录」
// 行 → 搜索面板（带 convScope）。判据：
//   C1 面板挂 data-conv-scope="1"（会话档落位）；
//   C2 范围/agent select 折叠（scope 词汇在会话档不存在）；
//   C3 状态行前缀 = 会话名（scope 隔离可见化）；
//   C4 关键词命中的行全部来自会话桩（/api/conversations/cF/messages）——**隔离
//      机械证明 = 面板打开期间零请求打到 /api/sessions/**/history**（agent 会话
//      数据源完全进不了视野；改前该面板走 state.activeSessionId = 串全局）；
//   C5 点结果行跳会话窗并定位（messages.revealMessage 既有缝，1.6s 闪烁）。
{
  const s = await createSession(browser, { width: 375, height: 812, colorScheme: 'light', apiStubs: API_STUBS, clock: FIXED });
  try {
    await s.boot({ config: CONFIG });
    await openConv(s.page);
    // 右上三点（手机层好友会话 = 直开好友信息面，friend-info-panel 批同款入口）。
    await s.page.click('.mh-sess-more');
    await s.page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 8000 });
    await settle(s.page, 300);

    // 3(a) 同面板顺带断言：徽标真值回填（本会话桩 = synced）。
    // 🔴 改前树无 data-pf-sync-badge（写死「本地缓存·未同步」）⇒ 等待超时 ⇒
    // try/catch 收口成 FAIL 行（逐条红，不栈崩中断）。
    let badge = { state: '', text: '' };
    try {
      await s.page.waitForFunction(() => {
        const b = document.querySelector('#profile-face-overlay [data-pf-sync-badge="bio"]');
        return b && b.dataset.syncState === 'synced';
      }, null, { timeout: 8000 });
      badge = await s.page.evaluate(() => {
        const b = document.querySelector('#profile-face-overlay [data-pf-sync-badge="bio"]');
        return { state: b?.dataset.syncState || '', text: b?.textContent || '' };
      });
    } catch { /* 改前树：徽标面不存在 ⇒ 红 */ }
    ok('B1 · 三态徽标读真数据源（synced 桩 ⇒ data-sync-state=synced · 文案=已同步）',
      badge.state === 'synced' && badge.text === '已同步', JSON.stringify(badge));

    // 开会话档搜索（真入口行）。
    await s.page.click('#profile-face-overlay [data-pf-nav="search"]');
    await s.page.waitForSelector('#search-overlay.on', { timeout: 8000 });
    await settle(s.page, 400);
    const sc = await s.page.evaluate(DAYLINES);
    // 状态行独立读（DAYLINES 不带它；textContent 兜 ''，改前树无 #search-status 也不断链）。
    const statusText = await s.page.locator('#search-status').textContent().catch(() => '');
    readings.convSearch = {
      convScope: sc.convScope, scopeDisplay: sc.scopeDisplay, status: statusText,
    };
    ok('C1 · 面板挂 data-conv-scope="1"（会话档落位）', sc.convScope === '1', `convScope=${sc.convScope}`);
    ok('C2 · 范围/agent select 折叠（scope 词汇在会话档不存在）', sc.scopeDisplay === 'none', `display=${sc.scopeDisplay}`);
    ok('C3 · 状态行前缀 = 会话名（scope 隔离可见化）', (statusText || '').includes('分页君'), `status=${statusText}`);

    // C4 的机械证据：面板打开期间记录全部 /api 请求。
    const apiHits = [];
    const onReq = (r) => { const u = r.url(); if (u.includes('/api/')) apiHits.push(new URL(u).pathname + new URL(u).search); };
    s.page.on('request', onReq);
    // 🔴 改前红可读：等待腿全部 try/catch 收口成 FAIL 行（改前树无会话档 ⇒
    // 结果区恒空 ⇒ waitFor* 超时——要的是「逐条红」，不是栈崩中断）。
    let resInfo = { rows: 0, texts: [] };
    try {
      await s.page.fill('#search-keyword', '第 9 条');
      await settle(s.page, 900); // 300ms 去抖 + 全量拉取 + 渲染
      await s.page.waitForFunction(() => document.querySelectorAll('#search-results .search-result').length > 0, null, { timeout: 8000 });
      resInfo = await s.page.evaluate(() => ({
        rows: document.querySelectorAll('#search-results .search-result').length,
        texts: [...document.querySelectorAll('#search-results .search-result')].slice(0, 3).map((e) => (e.textContent || '').slice(0, 40)),
      }));
    } catch { /* 改前树：主会话桩缺席 ⇒ 零结果行（这本身就是红） */ }
    const historyHits = apiHits.filter((p) => /\/api\/sessions\/.+\/history/.test(p));
    ok('C4 · 会话档关键词命中（fixture 恰 1 行含「第 9 条」）', resInfo.rows === 1,
      `rows=${resInfo.rows} sample=${JSON.stringify(resInfo.texts)}`);
    ok('C4b · 隔离机械证明：会话档搜索零请求打 /api/sessions/**/history（改前串全局）',
      historyHits.length === 0, `sessionHistoryHits=${historyHits.length} apiCalls=${apiHits.length}`);
    readings.convSearch.rows = resInfo.rows;
    readings.convSearch.sessionHistoryHits = historyHits;

    // C5 跳转：点首行 → 关面板（手机 = 搜索页弹栈）→ 开会话窗 → revealMessage 定位闪烁。
    let flashed = false;
    try {
      if (resInfo.rows > 0) {
        await s.page.click('#search-results .search-result');
        await s.page.waitForSelector('.fm-flow .fm-msg.fm-reveal-flash', { timeout: 8000 });
        flashed = true;
      }
    } catch { /* 定位失败 = 下判据红 */ }
    ok('C5 · 结果行跳会话窗并定位（revealMessage 闪烁在场）', flashed);
    readings.convSearch.flashed = flashed;
    s.page.off?.('request', onReq);
  } finally {
    await s.close();
  }
}

// ═══ 3(a) 三态徽标 × 其余两态（待同步 / 仅本地——同一面板同一实现）══════════
{
  const badgeCase = async (state, wantText, label) => {
    const stubs = { ...API_STUBS, '/api/profile/sync-status': syncStatusStub(state) };
    const s = await createSession(browser, { width: 375, height: 812, colorScheme: 'light', apiStubs: stubs, clock: FIXED });
    try {
      await s.boot({ config: CONFIG });
      await openConv(s.page);
      await s.page.click('.mh-sess-more');
      await s.page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 8000 });
      let badge = { state: '', text: '' };
      try {
        await s.page.waitForFunction((want) => {
          const b = document.querySelector('#profile-face-overlay [data-pf-sync-badge="bio"]');
          return b && b.dataset.syncState === want;
        }, state, { timeout: 8000 });
        badge = await s.page.evaluate(() => {
          const b = document.querySelector('#profile-face-overlay [data-pf-sync-badge="bio"]');
          return { state: b?.dataset.syncState || '', text: b?.textContent || '' };
        });
      } catch { /* 改前树：徽标面不存在 ⇒ 红 */ }
      ok(`${label} · 徽标随真值变（${state} 桩 ⇒ ${wantText}）`,
        badge.state === state && badge.text === wantText, JSON.stringify(badge));
    } finally {
      await s.close();
    }
  };
  await badgeCase('syncing', '待同步', 'B2');
  await badgeCase('local-only', '仅本地', 'B3');
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
const dd = readings.desktopDay || {};
console.log(`1440 日期行: ${dd.seps ? JSON.stringify(dd.seps.map((x) => `${x.text}@${x.centerX}`)) : '—'} flowCenter=${dd.flowCenterX} footer带日期层=${dd.footersWithDateLayer}/${dd.footerCount}`);
const md = readings.mobileDay || {};
console.log(`375 日期行:  ${md.seps ? md.seps.map((x) => x.text).join(',') : '—'} footer带日期层=${md.footersWithDateLayer}/${md.footerCount}`);
const cs2 = readings.convSearch || {};
console.log(`会话档搜索: convScope=${cs2.convScope} scopeDisplay=${cs2.scopeDisplay} rows=${cs2.rows} sessionHistoryHits=${cs2.sessionHistoryHits} flashed=${cs2.flashed}`);
console.log(`\n合计 ${checks} 项 · FAIL ${failures} 项 · 读数 ${join(OUT, `readings-${TAG}.json`)}`);
process.exit(failures ? 1 : 0);
