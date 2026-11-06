// conversation-heads-unify.spec.mjs — 件二+件三「三种会话页头同构」机械验收
// （作者令 2026-11-02：「件二 +件三 ·三种会话页头同构（左返回 /右三点 /中悬浮身份
//  头），与主对话窗视觉完全一致，且不得有横贯顶栏带」）。
//
// 断言面（375×812 / hasTouch / dark；三种会话各开一次 + 主对话一次对照）：
//   ① 结构同构：返回钮左上（rect 左上象限）、三点钮右上；头像中心与屏幕中线偏差 ≤1px。
//   ② **不存在横贯顶栏带**：扫描全部 fixed 元素，不得存在「height≥40 && width≥90%
//      且有背景或描边」的元素；并显式列出实测到的 near-miss 元素读数。
//   ③ 三种会话身份头视觉属性与主会话一致：头像外径 56、名字胶囊（字号/padding/
//      圆角/背景 token）、居中偏差 ≤1px。
//   ④ 身份头**常显**（🔴 契约变更，依据作者 2026-11-04 20:49 裁定「按你的推荐
//      走」）：落底 / 中段 / 回顶三个滚动位置上，身份头与名字胶囊都必须可见
//      （opacity 1、无 visibility 腿），且 `--idhead-p` 零写入（渐隐驱动器已整条
//      退役）。**改前口径「滚动后必须 opacity<1（= 渐隐存在）」作废** —— 见腿内注释。
//   ⑤ 旧形态清场：会话页内无「行内小头像 + 名字」旧窗头（`.fm-modal-header > *`
//      零渲染）、会话页大标题退场、无名态胶囊不渲染。
//   ⑥ 右上三点菜单入口：好友/群 → 查看资料（件一 profileFace 导出函数）、
//      群 → 群设置、设备 → 设备描述（设备无 Profile 面，有据偏离）。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node node_modules/@playwright/test/cli.js test tests/conversation-heads-unify.spec.mjs --workers=1 --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo, settlePage } from './_mobile-dock-nav.mjs';

// `CONVHEADS_WEB_ROOT` = 反向验红机读口（身份头常显批 2026-11-04）：指向一棵
// 「把渐隐绑定装回去」的 web 树，腿 ④ 必须红。同族口径见
// tests/mobile-agent-head-visible.spec.mjs 的 AGENTHEAD_WEB_ROOT。
const WEB = process.env.CONVHEADS_WEB_ROOT
  ? normalize(process.env.CONVHEADS_WEB_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'conv-heads-session';

const SELF_DEV = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '' };
const PEERS = [
  { deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true },
];

const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: '', bio: '', since: 1762000000000,
};
const G_MAIN = {
  groupId: 'g-1', title: '项目群', role: 'owner', memberCount: 2,
  lastMessage: { id: 101, senderId: 'u-f1', kind: 'text', body: '今天开会', createdAt: 1762000000000 },
  unreadCount: 0, lastMessageId: 101, createdAt: 1762000000000,
  description: '', remark: '',
};

function seed() {
  const NOW = Date.now();
  // 好友会话塞 40 条消息 ⇒ `.fm-flow` 必有滚轴（④ 滚动渐隐要真滚起来）。
  const msgs = [];
  for (let i = 1; i <= 40; i += 1) {
    msgs.push({
      id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text',
      body: `第 ${i} 条消息 —— 用来把消息流撑出滚轴`, createdAt: NOW - (40 - i) * 60e3,
    });
  }
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [],
    friends: [F_MAIN],
    incoming: [],
    outgoing: [],
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
      { conversationId: 'g-1', kind: 'group', title: G_MAIN.title, memberCount: 2, myRole: 'owner', lastMessage: G_MAIN.lastMessage, unreadCount: 0 },
    ],
    messages: { 'c-f1': msgs, 'g-1': [] },
    groups: [{ ...G_MAIN }],
    groupInvites: [],
    groupMembers: {
      'g-1': [
        { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
        { userId: 'u-f1', username: null, display_name: '老友', avatar: null, role: 'member', joinedAt: 0 },
      ],
    },
    _now: NOW,
  };
}

async function bootApp(page) {
  const s = seed();
  await page.addInitScript((seedJson) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seedJson));
    localStorage.setItem('fm_api_mock_delay', '0');
  }, s);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            loggedIn: true,
            device: { ...SELF_DEV, avatarUrl: '', email: 'me@example.com', displayName: '我自己' },
            peers: PEERS,
          }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const m = await import('/js/state.js');
    const st = m.default;
    return st.activeSessionId === rootSid && st.ws && st.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(300);
  await expect.poll(async () => page.evaluate(() => document.body.classList.contains('mobile-layer')), { timeout: 20000 })
    .toBe(true);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

const CONV_PAGE = '#mobile-pages .mobile-page[data-page="conversation"]';

/** 开好友会话窗（与行点击同一条通路：`messages.openConversation`）。 */
async function openFriend(page) {
  await dockTo(page, 'messages');
  await page.evaluate(async () => {
    const m = await import('/js/messages.js');
    await m.openConversation('c-f1', null);
  });
  // 窗体在 `#fm-chat-overlay` 里建出 → navController.watchChatModal 随后把它
  // re-parent 进「会话」页体并把页面置 live（页 non-live 时 visibility:hidden
  // ⇒ 必须先等 live，再等身份头可交互）。
  await expect(page.locator(CONV_PAGE)).toHaveClass(/live/, { timeout: 10000 });
  await page.waitForSelector(`${CONV_PAGE} .fm-modal .mh-idhead`, { state: 'attached', timeout: 10000 });
  await settlePage(page, 'conversation');
  // 读数前置：消息流回顶（开窗默认贴底；本条要测的是**静止态**几何，故先把滚动
  // 归零再量）。身份头常显批 2026-11-04 起与滚动位无关 —— 归零只为几何读数稳定
  // （旧口径下这里是「渐隐进度已满档、身份头 visibility:hidden」的规避动作，
  // 那条腿已随常显契约作废，见腿 ④ 注释）。
  await page.evaluate((sel) => {
    const flow = document.querySelector(`${sel} .fm-flow`);
    if (flow) { flow.scrollTop = 0; flow.dispatchEvent(new Event('scroll')); }
  }, CONV_PAGE);
  await page.waitForTimeout(120);
}

/** 开群会话窗。 */
async function openGroup(page) {
  await dockTo(page, 'messages');
  await page.evaluate(async () => {
    const m = await import('/js/messages.js');
    await m.openConversation('g-1', null);
  });
  // 窗体在 `#fm-chat-overlay` 里建出 → navController.watchChatModal 随后把它
  // re-parent 进「会话」页体并把页面置 live（页 non-live 时 visibility:hidden
  // ⇒ 必须先等 live，再等身份头可交互）。
  await expect(page.locator(CONV_PAGE)).toHaveClass(/live/, { timeout: 10000 });
  await page.waitForSelector(`${CONV_PAGE} .fm-modal .mh-idhead`, { state: 'attached', timeout: 10000 });
  await settlePage(page, 'conversation');
  // 读数前置：消息流回顶（开窗默认贴底；本条要测的是**静止态**几何，故先把滚动
  // 归零再量）。身份头常显批 2026-11-04 起与滚动位无关 —— 归零只为几何读数稳定
  // （旧口径下这里是「渐隐进度已满档、身份头 visibility:hidden」的规避动作，
  // 那条腿已随常显契约作废，见腿 ④ 注释）。
  await page.evaluate((sel) => {
    const flow = document.querySelector(`${sel} .fm-flow`);
    if (flow) { flow.scrollTop = 0; flow.dispatchEvent(new Event('scroll')); }
  }, CONV_PAGE);
  await page.waitForTimeout(120);
}

/** 开设备会话窗（`openDeviceChat` = 联系人设备行同一条通路）。 */
async function openDevice(page) {
  await dockTo(page, 'messages');
  await page.evaluate(async (peer) => {
    const m = await import('/js/messages.js');
    m.openDeviceChat(peer);
  }, PEERS[0]);
  // 窗体在 `#fm-chat-overlay` 里建出 → navController.watchChatModal 随后把它
  // re-parent 进「会话」页体并把页面置 live（页 non-live 时 visibility:hidden
  // ⇒ 必须先等 live，再等身份头可交互）。
  await expect(page.locator(CONV_PAGE)).toHaveClass(/live/, { timeout: 10000 });
  await page.waitForSelector(`${CONV_PAGE} .fm-modal .mh-idhead`, { state: 'attached', timeout: 10000 });
  await settlePage(page, 'conversation');
  // 读数前置：消息流回顶（开窗默认贴底；本条要测的是**静止态**几何，故先把滚动
  // 归零再量）。身份头常显批 2026-11-04 起与滚动位无关 —— 归零只为几何读数稳定
  // （旧口径下这里是「渐隐进度已满档、身份头 visibility:hidden」的规避动作，
  // 那条腿已随常显契约作废，见腿 ④ 注释）。
  await page.evaluate((sel) => {
    const flow = document.querySelector(`${sel} .fm-flow`);
    if (flow) { flow.scrollTop = 0; flow.dispatchEvent(new Event('scroll')); }
  }, CONV_PAGE);
  await page.waitForTimeout(120);
}

/** 关窗（返回钮 = 唯一出页入口；真关窗判据 = `.fm-modal` 归零）。 */
async function closeConv(page) {
  await page.locator(`${CONV_PAGE} .mobile-back-btn`).click();
  await expect(page.locator(`${CONV_PAGE} .fm-modal`)).toHaveCount(0, { timeout: 8000 });
  await expect(page.locator(CONV_PAGE)).not.toHaveClass(/live/);
}

/** 身份头 + 双钮几何读数（一次 evaluate 取全，避免 N 次往返的抖动）。 */
async function headReadings(page) {
  return page.evaluate((convSel) => {
    const page_ = document.querySelector(convSel);
    const modal = page_?.querySelector('.fm-modal');
    const head = modal?.querySelector('.mh-idhead');
    const avatar = head?.querySelector('#agent-avatar-btn, .mh-idhead-avatar > .fm-modal-avatar');
    const namePill = head?.querySelector('.mh-idhead-name');
    const back = page_?.querySelector('.mobile-back-btn');
    const more = modal?.querySelector('.mh-sess-more');
    const r = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return {
        x: +b.x.toFixed(1), y: +b.y.toFixed(1),
        w: +b.width.toFixed(1), h: +b.height.toFixed(1),
        cx: +(b.x + b.width / 2).toFixed(1), cy: +(b.y + b.height / 2).toFixed(1),
        right: +b.right.toFixed(1), bottom: +b.bottom.toFixed(1),
      };
    };
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const nameCs = cs(namePill);
    return {
      vw: window.innerWidth, vh: window.innerHeight,
      back: r(back), more: r(more), avatar: r(avatar), head: r(head), name: r(namePill),
      headStyle: head ? {
        position: cs(head).position, background: cs(head).backgroundColor,
        border: cs(head).borderTopWidth + ' ' + cs(head).borderTopColor,
        opacity: cs(head).opacity, zIndex: cs(head).zIndex,
      } : null,
      nameStyle: nameCs ? {
        fontSize: nameCs.fontSize, fontWeight: nameCs.fontWeight,
        paddingTop: nameCs.paddingTop, paddingRight: nameCs.paddingRight,
        paddingBottom: nameCs.paddingBottom, paddingLeft: nameCs.paddingLeft,
        borderRadius: nameCs.borderRadius, background: nameCs.backgroundColor,
        text: (namePill.textContent || '').trim(),
        display: nameCs.display, visibility: nameCs.visibility,
        visible: nameCs.display !== 'none' && nameCs.visibility !== 'hidden',
      } : null,
      avatarCount: head ? head.querySelectorAll('#agent-avatar-btn, .mh-idhead-avatar > .fm-modal-avatar').length : 0,
      // 旧形态清场读数：会话页窗头行（行内小头像 + 名字）必须零渲染。
      oldHeader: {
        headerExists: !!modal?.querySelector('.fm-modal-header'),
        headerRect: r(modal?.querySelector('.fm-modal-header')),
        visibleChildren: [...(modal?.querySelectorAll('.fm-modal-header > *') || [])]
          .filter((el) => getComputedStyle(el).display !== 'none').length,
      },
      pageTitleVisible: (() => {
        // 标题单元素化批 2026-11-02：吸附态装饰副本 .mobile-page-title-mini 退役
        // （页名只剩 .mobile-page-title 一个节点），这里只探主标题。
        const t = page_?.querySelector('.mobile-page-title');
        return t ? getComputedStyle(t).display !== 'none' : false;
      })(),
      flow: r(page_?.querySelector('.fm-flow')),
      flowScrollTop: page_?.querySelector('.fm-flow')?.scrollTop ?? 0,
      flowScrollable: (() => {
        const f = page_?.querySelector('.fm-flow');
        return f ? f.scrollHeight > f.clientHeight + 8 : false;
      })(),
    };
  }, CONV_PAGE);
}

/** ② 横贯顶栏带扫描：fixed 元素中「height≥40 && width≥90% 且有背景或描边」。
 *  🔴 判据只针对**顶栏**：作者禁令 = 「不得有横贯顶栏带」。底 dock
 *  （`#mobile-dock`，fixed / 93.6% 宽 / 54 高 / 玻璃底 + 描边）天然命中同一条
 *  几何式，但它是**底栏**（纵向位置在视口下半），不属于本条禁令 ⇒ 扫描把命中的
 *  fixed 元素按「竖直位置是否落在上半屏」二分：上半屏命中 = bands（必须为空），
 *  下半屏命中 = exempt（显式列出读数，零隐藏）。 */
async function scanFixedBands(page) {
  return page.evaluate(() => {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const rows = [];
    for (const el of document.querySelectorAll('*')) {
      const cs = getComputedStyle(el);
      if (cs.position !== 'fixed') continue;
      if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue;
      const b = el.getBoundingClientRect();
      if (!b.width || !b.height) continue;
      const hasBg = cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent';
      const hasBorder = parseFloat(cs.borderTopWidth) > 0
        || parseFloat(cs.borderBottomWidth) > 0
        || parseFloat(cs.borderLeftWidth) > 0
        || parseFloat(cs.borderRightWidth) > 0;
      const entry = {
        sel: el.id ? `#${el.id}` : (el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).join('.')}` : el.tagName.toLowerCase()),
        w: +b.width.toFixed(1), h: +b.height.toFixed(1),
        wPct: +(b.width / vw * 100).toFixed(1),
        top: +b.top.toFixed(1),
        bg: cs.backgroundColor, border: hasBorder,
        geometricBand: false, upperHalf: b.top < vh / 2, band: false,
      };
      entry.geometricBand = b.height >= 40 && b.width >= vw * 0.9 && (hasBg || hasBorder);
      // 顶栏带判据 = 几何命中 ∩ 落在视口上半屏（顶栏只能在上半屏）。
      entry.band = entry.geometricBand && entry.upperHalf;
      rows.push(entry);
    }
    const strip = ({ sel, w, h, wPct, top, bg, border }) => ({ sel, w, h, wPct, top, bg, border });
    return {
      vw, vh,
      bands: rows.filter((r) => r.band).map(strip),
      // 命中几何式但在下半屏（= 底 dock 一类）—— 显式列读数，不算违规。
      exempt: rows.filter((r) => r.geometricBand && !r.upperHalf).map(strip),
      // 未命中几何式但接近（near-miss）：高 ≥24 且宽 ≥60% —— 全量列读数。
      nearMiss: rows.filter((r) => !r.geometricBand && r.h >= 24 && r.wPct >= 60).map(strip),
      totalFixed: rows.length,
    };
  });
}

test.describe('三种会话页头同构 + 悬浮身份头（375×812）', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

  test('① ② ③ ⑤ 好友/群/设备与主对话同构：双钮 + 悬浮身份头，无横贯顶栏带', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);

    // ── 主对话（对照基准）：chatChrome 的悬浮身份头 ──
    // 中键 = chat home（dock 根 id 里没有 'chat' 页，故直点中键而非走 dockTo）。
    await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
    await expect(page.locator('.mobile-page.live')).toHaveCount(0);
    await expect(page.locator('#chat > .g2-chat-head')).toHaveCount(1);
    const main = await page.evaluate(() => {
      const head = document.querySelector('#chat > .g2-chat-head');
      const avatar = head?.querySelector('#agent-avatar-btn');
      const name = head?.querySelector('#session-name');
      const r = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return {
          w: +b.width.toFixed(1), h: +b.height.toFixed(1),
          cx: +(b.x + b.width / 2).toFixed(1), cy: +(b.y + b.height / 2).toFixed(1),
          y: +b.y.toFixed(1),
        };
      };
      const ncs = name ? getComputedStyle(name) : null;
      return {
        cls: head?.className || null,
        pos: head ? getComputedStyle(head).position : null,
        bg: head ? getComputedStyle(head).backgroundColor : null,
        avatar: r(avatar),
        name: r(name),
        nameStyle: ncs ? {
          fontSize: ncs.fontSize, fontWeight: ncs.fontWeight,
          paddingTop: ncs.paddingTop, paddingRight: ncs.paddingRight,
          paddingBottom: ncs.paddingBottom, paddingLeft: ncs.paddingLeft,
          borderRadius: ncs.borderRadius, background: ncs.backgroundColor,
        } : null,
        // 入口收敛批 todo-unify-entry-collapse 2026-11-04：主对话左上「任务」键
        // 退役 ⇒ 该读数恒 null（只作日志留痕，无断言依赖；现役左槽为空）。
        taskKey: r(document.querySelector('.mh-chrome-btn[data-chrome="tasks"]')),
        searchKey: r(document.querySelector('.mh-chrome-btn[data-chrome="search"]')),
      };
    });
    // 主对话身份头 = 同一 CSS 块的 .mh-idhead（.g2-chat-head 本体即 .mh-idhead）
    expect(main.cls, '主对话身份头带 .mh-idhead 类（与三会话同一块）').toContain('mh-idhead');
    expect(main.pos, '主对话身份头 = 悬浮（absolute），非 fixed 顶栏').toBe('absolute');
    expect(main.bg, '身份头本体零背景（不得成为顶栏带）').toBe('rgba(0, 0, 0, 0)');
    expect(main.avatar.w, '主对话头像外径 56').toBe(56);
    expect(main.nameStyle, '主对话有名字胶囊读数').not.toBeNull();

    const bandsMain = await scanFixedBands(page);
    console.log('[②/main] fixed 总数', bandsMain.totalFixed, '顶栏带命中', JSON.stringify(bandsMain.bands),
      '下半屏豁免', JSON.stringify(bandsMain.exempt), 'near-miss', JSON.stringify(bandsMain.nearMiss));
    expect(bandsMain.bands, `主对话：无「height≥40 && width≥90% && 有背景/描边」的 fixed 元素（near-miss 读数见答复）`).toEqual([]);

    console.log('[③/主对话] 头像', JSON.stringify(main.avatar), '胶囊', JSON.stringify(main.nameStyle),
      '左键', JSON.stringify(main.taskKey), '右键', JSON.stringify(main.searchKey));

    // ── 三会话各开一次 ──
    const heads = {};

    // (a) 好友
    await openFriend(page);
    heads.friend = await headReadings(page);
    await closeConv(page);

    // (b) 群
    await openGroup(page);
    heads.group = await headReadings(page);
    // 群三点菜单：两个入口（查看资料 + 群设置）
    await page.locator(`${CONV_PAGE} .mh-sess-more`).click();
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    const groupMenu = await page.$$eval('.fm-context-menu button:not(.mfs-back)', (els) => els.map((e) => e.textContent.trim()));
    expect(groupMenu, '群三点菜单 = 查看资料 + 群设置（禁第二套抽屉/Profile 实现）')
      .toEqual(['查看资料', '群设置']);
    await page.locator('.fm-context-menu button').filter({ hasText: '群设置' }).first().click();
    await page.waitForSelector(`${CONV_PAGE} .fm-group-settings`, { timeout: 5000 });
    // 抽屉入口仍通到件一 Profile 面（同一函数）
    await page.locator(`${CONV_PAGE} .fm-group-settings .fm-gs-profile`).click();
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 5000 });
    await page.locator('#profile-face-close').click();
    await expect(page.locator('#profile-face-overlay')).toBeHidden();
    await closeConv(page);

    // (c) 设备
    await openDevice(page);
    heads.device = await headReadings(page);
    await page.locator(`${CONV_PAGE} .mh-sess-more`).click();
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    const devMenu = await page.$$eval('.fm-context-menu button:not(.mfs-back)', (els) => els.map((e) => e.textContent.trim()));
    expect(devMenu, '设备三点菜单 = 设备描述（设备无 Profile 面，有据偏离申报）').toEqual(['设备描述']);
    await page.locator('.fm-context-menu button').filter({ hasText: '设备描述' }).first().click();
    await page.waitForSelector(`${CONV_PAGE} .fm-device-desc-panel`, { timeout: 5000 });
    const bandsDevice = await scanFixedBands(page);
    expect(bandsDevice.bands, '设备会话（含描述面板展开态）：无横贯顶栏带').toEqual([]);
    await closeConv(page);

    // 好友三点 = **直达好友信息面**（三点入口批 2026-11-05 作者令：「点击右上角那个
    // 三个点的按钮。进去应该直接就是好友信息的界面。不需要多点击一次」）⇒ 旧断言
    // 「好友三点菜单 = ['查看资料'] 且再点一次进面」整组换代：
    //   · 点一次三点后**不得**出现 `.fm-context-menu`；
    //   · 好友信息面**当场打开**（面本体仍是 `profileFace.openFriendProfile` 同一函数）；
    //   · 群/设备保留菜单（各有第二个目的地：群设置 / 设备描述）。
    await openFriend(page);
    await page.locator(`${CONV_PAGE} .mh-sess-more`).click();
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 5000 });
    expect(await page.locator('.fm-context-menu').count(), '好友三点不再弹单条菜单（直达，零第二跳）').toBe(0);
    await page.locator('#profile-face-close').click();
    await expect(page.locator('#profile-face-overlay')).toBeHidden();

    for (const [kind, h] of Object.entries(heads)) {
      console.log(`[①②③/${kind}] 返回钮`, JSON.stringify(h.back), '三点钮', JSON.stringify(h.more),
        '头像', JSON.stringify(h.avatar), '胶囊', JSON.stringify(h.name), JSON.stringify(h.nameStyle),
        '旧窗头可见子节点', h.oldHeader.visibleChildren, '大标题可见', h.pageTitleVisible);
    }

    // ── ① 结构同构（三会话逐条）──
    for (const [kind, h] of Object.entries(heads)) {
      expect(h.back, `${kind}：左上返回钮在场`).not.toBeNull();
      expect(h.more, `${kind}：右上三点钮在场`).not.toBeNull();
      // 返回钮左上象限、三点钮右上象限
      expect(h.back.cx < h.vw / 2 && h.back.cy < h.vh / 2, `${kind}：返回钮在左上象限（cx=${h.back.cx}, cy=${h.back.cy}）`).toBe(true);
      expect(h.more.cx > h.vw / 2 && h.more.cy < h.vh / 2, `${kind}：三点钮在右上象限（cx=${h.more.cx}, cy=${h.more.cy}）`).toBe(true);
      // 头像中心与屏幕中线偏差 ≤1px
      const dev = Math.abs(h.avatar.cx - h.vw / 2);
      expect(dev, `${kind}：头像中心 vs 屏幕中线偏差 ${dev.toFixed(2)}px ≤ 1px`).toBeLessThanOrEqual(1);
      // 副标题/头像唯一性 + 旧窗头清场
      expect(h.avatarCount, `${kind}：身份头内恰一枚头像`).toBe(1);
      expect(h.oldHeader.visibleChildren, `${kind}：会话窗头行零可见子节点（旧「行内小头像 + 名字」形态退场）`).toBe(0);
      expect(h.pageTitleVisible, `${kind}：页面壳大标题退场（页头只余双钮 + 身份头）`).toBe(false);
    }

    // 双钮几何：三会话与主对话同档（48×48 @ top 16+safe / 左右 16）
    for (const [kind, h] of Object.entries(heads)) {
      // 按钮尺寸读数容差 ±1px：按压 spring / 亚像素取整会带来 <0.5px 抖动
      // （实测设备面 48.2 —— 同一类在几何上是 48，不是分叉）。
      expect(Math.abs(h.back.w - 48) <= 1 && Math.abs(h.back.h - 48) <= 1,
        `${kind}：返回钮 48×48（读数 ${h.back.w}×${h.back.h}）`).toBe(true);
      expect(Math.abs(h.more.w - 48) <= 1 && Math.abs(h.more.h - 48) <= 1,
        `${kind}：三点钮 48×48（读数 ${h.more.w}×${h.more.h}）`).toBe(true);
      expect(Math.round(h.back.y), `${kind}：返回钮 top = 16`).toBe(16);
      expect(Math.round(h.more.y), `${kind}：三点钮 top = 16`).toBe(16);
      expect(Math.round(h.back.x), `${kind}：返回钮 left = 16`).toBe(16);
      expect(Math.round(h.vw - h.more.right), `${kind}：三点钮 right = 16`).toBe(16);
    }

    // ── ③ 三会话身份头与主会话视觉属性一致 ──
    for (const [kind, h] of Object.entries(heads)) {
      expect(h.headStyle.position, `${kind}：身份头 absolute（悬浮，非 fixed）`).toBe('absolute');
      expect(h.headStyle.background, `${kind}：身份头本体零背景`).toBe('rgba(0, 0, 0, 0)');
      expect(h.headStyle.border.startsWith('0px'), `${kind}：身份头本体零描边`).toBe(true);
      expect(h.avatar.w, `${kind}：头像外径 = 主对话 56（读数 ${h.avatar.w}）`).toBe(main.avatar.w);
      expect(h.avatar.h, `${kind}：头像高 = 56`).toBe(main.avatar.h);
      // 名字玻璃胶囊：字号/padding/圆角/背景 token 与主对话逐值相等
      expect(h.nameStyle, `${kind}：名字胶囊在场`).not.toBeNull();
      expect(h.nameStyle.fontSize, `${kind}：胶囊字号 = ${main.nameStyle.fontSize}`).toBe(main.nameStyle.fontSize);
      expect(h.nameStyle.fontWeight, `${kind}：胶囊字重 = ${main.nameStyle.fontWeight}`).toBe(main.nameStyle.fontWeight);
      expect(
        [h.nameStyle.paddingTop, h.nameStyle.paddingRight, h.nameStyle.paddingBottom, h.nameStyle.paddingLeft],
        `${kind}：胶囊 padding 与主对话逐值相等`,
      ).toEqual([main.nameStyle.paddingTop, main.nameStyle.paddingRight, main.nameStyle.paddingBottom, main.nameStyle.paddingLeft]);
      expect(h.nameStyle.borderRadius, `${kind}：胶囊圆角 = ${main.nameStyle.borderRadius}`).toBe(main.nameStyle.borderRadius);
      expect(h.nameStyle.background, `${kind}：胶囊背景 token = ${main.nameStyle.background}`).toBe(main.nameStyle.background);
      // 名字胶囊居中偏差 ≤1px（相对头像中心）
      const pillDev = Math.abs((h.name.cx) - (h.avatar.cx));
      expect(pillDev, `${kind}：名字胶囊中心 vs 头像中心偏差 ${pillDev.toFixed(2)}px ≤ 1px`).toBeLessThanOrEqual(1);
      // 头像中心纵向同档（主对话 vs 三会话 ≤1px）——「视觉完全一致」的正面读数
      const cyDev = Math.abs(h.avatar.cy - main.avatar.cy);
      expect(cyDev, `${kind}：头像中心 y vs 主对话偏差 ${cyDev.toFixed(2)}px ≤ 1px`).toBeLessThanOrEqual(1);
    }

    // ── ② 横贯顶栏带（三会话各扫一次 + 主对话一次，已在上/下面取读数）──
    const bandsConv = [];
    console.log('[②/设备(描述面板展开)] 顶栏带', JSON.stringify(bandsDevice.bands), '下半屏豁免', JSON.stringify(bandsDevice.exempt));
    await openFriend(page);
    bandsConv.push(await scanFixedBands(page));
    await closeConv(page);
    await openGroup(page);
    bandsConv.push(await scanFixedBands(page));
    await closeConv(page);
    bandsConv.forEach((b, i) => {
      console.log(`[②/会话#${i + 1}] fixed 总数 ${b.totalFixed} 顶栏带 ${JSON.stringify(b.bands)} 下半屏豁免 ${JSON.stringify(b.exempt)} near-miss ${JSON.stringify(b.nearMiss)}`);
    });
    for (const b of bandsConv) {
      expect(b.bands, '三会话：无「height≥40 && width≥90% && 有背景/描边」的 fixed 元素').toEqual([]);
    }

    // ⑤ 无名态：名字清空 ⇒ 胶囊不渲染，头像仍居中
    await openFriend(page);
    const emptyState = await page.evaluate((sel) => {
      const head = document.querySelector(`${sel} .mh-idhead`);
      const pill = head?.querySelector('.mh-idhead-name');
      if (!pill) return null;
      const prev = pill.textContent;
      pill.textContent = '';
      const csPill = getComputedStyle(pill);
      const avatar = head.querySelector('.mh-idhead-avatar');
      const b = avatar.getBoundingClientRect();
      const out = {
        display: csPill.display,
        avatarCx: +(b.x + b.width / 2).toFixed(1),
        vw: window.innerWidth,
      };
      pill.textContent = prev;
      return out;
    }, CONV_PAGE);
    console.log('[⑤/无名态] 胶囊 display', emptyState.display, '头像中心', emptyState.avatarCx, '屏幕中线', emptyState.vw / 2);
    expect(emptyState.display, '无名态：胶囊整枚退出渲染（禁幽灵胶囊）').toBe('none');
    expect(Math.abs(emptyState.avatarCx - emptyState.vw / 2), '无名态：头像仍居中（偏差 ≤1px）').toBeLessThanOrEqual(1);

    // ── ④ 身份头常显（好友面，消息流有真滚轴）──────────────────────────────
    // 🔴 **契约变更**（身份头常显批 2026-11-04，依据作者 20:49 裁定「按你的推荐
    // 走」）：本条原口径 = 「滚动后 opacity<1 且 visibility:hidden（= 渐隐存在）」。
    // 该口径随作者铁律「腾空间用缩高或上移，**不许让元素消失**」整条作废 ——
    // 身份头是身份/入口类元素（头像 = profile 面入口、名字胶囊 = 群设置第二热区），
    // 必须常显。现口径 = **任意滚动位置都可见**（断言方向反转）。
    // 旧口径为什么在 main 上一直红（= 缺陷没被拦住）：它的前置条件「滚动前
    // opacity=1」在真机上不可满足 —— 会话一打开消息流就贴底（实测
    // scrollTop=2137 ⇒ --idhead-p=1 ⇒ opacity=0），旧口径连自己的前置都过不去。
    // 反向验红机读口：CONVHEADS_WEB_ROOT=<把绑定装回去的树> ⇒ 本腿必红。
    const headState = () => page.evaluate((sel) => {
      const head = document.querySelector(`${sel} .mh-idhead`);
      const flow = document.querySelector(`${sel} .fm-flow`);
      const cs = head ? getComputedStyle(head) : null;
      const namePill = head?.querySelector('.mh-idhead-name');
      const avatar = head?.querySelector('#agent-avatar-btn, .mh-idhead-avatar > .fm-modal-avatar');
      const effVisible = (el) => {
        if (!el) return false;
        const b = el.getBoundingClientRect();
        const s = getComputedStyle(el);
        return b.width > 0 && b.height > 0 && s.display !== 'none'
          && s.visibility !== 'hidden' && Number(s.opacity) > 0.05;
      };
      return {
        p: head ? head.style.getPropertyValue('--idhead-p') : null,
        opacity: cs ? cs.opacity : null,
        visibility: cs ? cs.visibility : null,
        headVisible: effVisible(head),
        nameVisible: effVisible(namePill),
        avatarVisible: effVisible(avatar),
        scrollTop: flow?.scrollTop ?? 0,
        scrollHeight: flow?.scrollHeight ?? 0,
        scrollable: flow ? flow.scrollHeight > flow.clientHeight + 8 : false,
      };
    }, CONV_PAGE);
    const scrollFlowTo = async (top) => {
      await page.evaluate(({ sel, t }) => {
        const flow = document.querySelector(`${sel} .fm-flow`);
        flow.scrollTop = t === 'bottom' ? flow.scrollHeight : t;
        flow.dispatchEvent(new Event('scroll'));
      }, { sel: CONV_PAGE, t: top });
      await page.waitForTimeout(160); // rAF 合帧窗口（渐隐若复活必然在此写入 --idhead-p）
    };
    const assertAlwaysVisible = (d, label) => {
      // ① 渐隐驱动器不许复活：`--idhead-p` 零写入（唯一进度源；写入即回归）
      expect(d.p, `④/${label}：--idhead-p 不许被写入（读数 ${JSON.stringify(d.p)} = 渐隐驱动器复活）`).toBe('');
      // ② 身份头本体常显（改前此处 opacity 0 + visibility hidden）
      expect(Number(d.opacity), `④/${label}：身份头 opacity = 1（读数 ${d.opacity}）`).toBe(1);
      expect(d.visibility, `④/${label}：身份头 visibility 不许 hidden（读数 ${d.visibility}）`).not.toBe('hidden');
      expect(d.headVisible, `④/${label}：身份头有效不可见`).toBe(true);
      // ③ 入口类子件同拍可见（头像 = profile 入口 / 名字胶囊 = 群设置热区）
      expect(d.avatarVisible, `④/${label}：头像槽不可见（入口退场 = 功能损失，不只是视觉）`).toBe(true);
      expect(d.nameVisible, `④/${label}：名字胶囊不可见`).toBe(true);
    };

    // 状态 A：打开即落底（手机端会话常态 —— 就是旧口径下 100% 不可见的那一档）
    const opened = await headState();
    console.log('[④/打开即落底] scrollTop', opened.scrollTop, '/', opened.scrollHeight,
      '--idhead-p', JSON.stringify(opened.p), 'opacity', opened.opacity, 'visibility', opened.visibility,
      '可见 头/名/头像', opened.headVisible, opened.nameVisible, opened.avatarVisible);
    expect(opened.scrollable, '④ 前置：好友会话消息流可滚（40 条消息）').toBe(true);
    assertAlwaysVisible(opened, '打开即落底');

    // 显式滚动前先**拆掉进场落底武装窗**（chat/settleBottom.js：开窗后 1200ms 内每帧
    // 回底，用户一动手即解除）。不拆则下面三次写入会被逐帧拽回底部 —— 读数全是
    // 「已落底」，中段/回顶两档就成了假标签。这里发一枚真实用户意图事件（touchstart）
    // 拆窗，正是真机上「作者一划就走」的同一条腿。
    await page.evaluate(() => {
      document.dispatchEvent(new Event('touchstart', { bubbles: true }));
    });
    await page.waitForTimeout(120);

    // 状态 B：显式滚到底（「已滚动到底」= 本批实拍与断言共同覆盖的那一档）
    await scrollFlowTo('bottom');
    const bottom = await headState();
    console.log('[④/滚到底] scrollTop', bottom.scrollTop, '/', bottom.scrollHeight,
      '--idhead-p', JSON.stringify(bottom.p), 'opacity', bottom.opacity, 'visibility', bottom.visibility,
      '可见 头/名/头像', bottom.headVisible, bottom.nameVisible, bottom.avatarVisible);
    expect(bottom.scrollTop, '④ 前置：已滚动到底（scrollTop > 64 = 旧渐隐行程满档区）').toBeGreaterThan(64);
    assertAlwaysVisible(bottom, '滚到底');

    // 状态 C：中段（落底与回顶之间的中途 —— 旧口径下同样是「已隐」区）
    await scrollFlowTo(500);
    const middle = await headState();
    console.log('[④/中段] scrollTop', middle.scrollTop, '--idhead-p', JSON.stringify(middle.p),
      'opacity', middle.opacity, 'visibility', middle.visibility,
      '可见 头/名/头像', middle.headVisible, middle.nameVisible, middle.avatarVisible);
    expect(Math.abs(middle.scrollTop - 500), `④ 前置：中段写入生效（scrollTop=${middle.scrollTop}；被落底窗拽回 = 档位假标签）`).toBeLessThanOrEqual(8);
    assertAlwaysVisible(middle, '中段');

    // 状态 D：回顶（旧口径「一隐不返」的反向读数，在常显口径下 = 与 A/B/C 同值）
    await scrollFlowTo(0);
    const top = await headState();
    console.log('[④/回顶] scrollTop', top.scrollTop, '--idhead-p', JSON.stringify(top.p),
      'opacity', top.opacity, 'visibility', top.visibility,
      '可见 头/名/头像', top.headVisible, top.nameVisible, top.avatarVisible);
    expect(top.scrollTop, '④ 前置：回顶写入生效（scrollTop=0）').toBe(0);
    assertAlwaysVisible(top, '回顶');

    // ── ⑤ 旧形态清场：全文档零旧顶栏带节点 + 会话页旧窗头零可见子节点（已在上） ──
    const leftovers = await page.evaluate(() => ({
      // 221391120 的 fixed 顶栏形态特征 = .g2-chat-head 为 fixed（主对话侧）
      chatHeadFixed: (() => {
        const h = document.querySelector('#chat > .g2-chat-head');
        return h ? getComputedStyle(h).position === 'fixed' : false;
      })(),
      // 会话页内不得有第二枚「行内小头像」（身份头之外）
      strayAvatars: [...document.querySelectorAll('.mobile-page[data-page="conversation"] .fm-modal .fm-modal-header .fm-modal-avatar')]
        .filter((el) => getComputedStyle(el.closest('.fm-modal-header')).height !== '1px' && getComputedStyle(el).display !== 'none').length,
    }));
    expect(leftovers.chatHeadFixed, '旧 fixed 顶栏不复活（主对话身份头非 fixed）').toBe(false);
    expect(leftovers.strayAvatars, '会话页旧窗头内零可见头像（唯一头像在身份头内）').toBe(0);

    expect(errors).toEqual([]);
  });
});
