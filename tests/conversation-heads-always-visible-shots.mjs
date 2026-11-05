#!/usr/bin/env node
// conversation-heads-always-visible-shots.mjs — 好友 / 群 / 设备会话身份头**常显**批
// （作者 2026-11-04 20:49 裁定「按你的推荐走」）改前/改后实拍 harness。
//
// ── 拍什么（每个视口 × 每族 × 改前/改后一帧）───────────────────────────────
//   375×812（手机层激活）：好友会话 / 群会话 —— **已滚动到底**，身份头（头像 +
//       名字玻璃胶囊）必须在屏、可见、不透明。
//   1440×900（桌面）：好友 / 群 —— 会话窗打开并滚到底。桌面**不建**身份头
//       （messages.js 的 `isMobileLayer` 分支），本档是「改动零触及桌面」的回归实证。
//
// ── 改前/改后怎么来的 ────────────────────────────────────────────────────
//   `SHOTS_WEB_ROOT` 指向一棵 web 树：
//     · 改后（本批修复后的树）= 缺省，即本仓 `src/main/resources/web`
//     · 改前（渐隐驱动器装回去的树）= 由 `git show <base>:<path>` 还原四个文件
//       拼出的临时树（messages.js / sessionHead.js / chatChrome.js / mobileLayer.css）
//   同一条脚本、同一份夹具 ⇒ 两帧唯一差异 = 身份头是否可见。
//
// ── 状态纪律（「已滚动到底」必须是真的到底）────────────────────────────────
//   会话开窗后有 1200ms 的「进场落底武装窗」（chat/settleBottom.js：每帧回底，
//   用户一动手即解除）。故滚到底前先发一枚真实用户意图事件（touchstart）拆窗，
//   否则读数/画面永远停在同一处、档位成假标签。
//
// ── 零后端零端口 ────────────────────────────────────────────────────────
//   page.route 从磁盘注入静态资源 + routeWebSocket 假 WS + `fm_api_mock` 假好友
//   API ⇒ 不启任何服务、不碰宿主实例 6138（比「隔离 NEBFLOW_HOME + 独立端口」更严：
//   本脚本根本不存在服务端进程）。
//
// Run（改后）: node tests/conversation-heads-always-visible-shots.mjs
// Run（改前）: SHOTS_WEB_ROOT=/tmp/idhead-before/web SHOTS_STATE=before \
//              node tests/conversation-heads-always-visible-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = process.env.SHOTS_WEB_ROOT
  ? normalize(process.env.SHOTS_WEB_ROOT)
  : join(ROOT, 'src', 'main', 'resources', 'web');
const STATE = process.env.SHOTS_STATE || 'after';
const OUT = process.env.SHOTS_OUT
  ? normalize(process.env.SHOTS_OUT)
  : join(ROOT, 'reports-evidence', 'conversation-heads-always-visible__20261104_213000__delegate-nebflow-fec6393b');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const SID = 'idhead-shots-session';
const SELF_DEV = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '' };
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', userDescription: '', online: true, lastSeen: Date.now() }];
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

/** 每个会话都塞 40 条消息 ⇒ 两族都有真滚轴（「已滚动到底」才有意义）。 */
function seed() {
  const NOW = Date.now();
  const msgs = [];
  for (let i = 1; i <= 40; i += 1) {
    msgs.push({
      id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text',
      body: `第 ${i} 条消息 —— 用来把消息流撑出滚轴（身份头常显批实拍夹具）`,
      createdAt: NOW - (40 - i) * 60e3,
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
      { conversationId: 'g-1', kind: 'group', title: G_MAIN.title, memberCount: 2, myRole: 'owner', lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
    ],
    messages: { 'c-f1': msgs, 'g-1': msgs },
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
  await page.addInitScript((seedJson) => {
    for (const ns of ['nebflow', 'neblink', 'stand']) {
      localStorage.setItem(`${ns}_token`, 'e2e-token');
      localStorage.setItem(`${ns}_locale`, 'zh-CN');
    }
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('stand_sw', '0');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seedJson));
    localStorage.setItem('fm_api_mock_delay', '0');
  }, seed());

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (url.hostname !== 'localhost' && url.hostname !== '127.0.0.1') return route.continue();
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: 'Spider' }) });
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
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
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
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(300);
}

/** 开一族会话（好友 / 群），并把它滚到底（先拆进场落底武装窗）。 */
async function openAndScrollBottom(page, kind) {
  const dock = page.locator('#mobile-dock .dock-key[data-dock="messages"]');
  if (await dock.count()) {
    await dock.click();
    await page.waitForTimeout(200);
  }
  await page.evaluate(async (k) => {
    const m = await import('/js/messages.js');
    if (k === 'friend') await m.openConversation('c-f1', null);
    else await m.openConversation('g-1', null);
  }, kind);
  await page.waitForTimeout(600);
  // 拆进场落底武装窗（真实用户意图事件 = touchstart；窗期内每帧回底）。
  await page.evaluate(() => { document.dispatchEvent(new Event('touchstart', { bubbles: true })); });
  await page.waitForTimeout(150);
  const scrolled = await page.evaluate(() => {
    const modal = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const flow = modal ? modal.querySelector('.fm-flow') : null;
    if (!flow) return null;
    flow.scrollTop = flow.scrollHeight;
    flow.dispatchEvent(new Event('scroll'));
    return { top: Math.round(flow.scrollTop), max: Math.round(flow.scrollHeight - flow.clientHeight) };
  });
  await page.waitForTimeout(300);
  return scrolled;
}

/** 身份头读数（同屏打印，供日志与文件名对照）。
 *  🔴 必须**按会话窗作用域**取：主对话的 `#chat > .g2-chat-head` 本体也带
 *  `.mh-idhead`（四会话同一 CSS 块），`document.querySelector('.mh-idhead')` 会
 *  先命中它 —— 那是另一族的头，读出来的读数与本次截图对象不符（本 harness
 *  初版正是踩了这个坑：name 恒 null）。 */
async function headReading(page) {
  return page.evaluate(() => {
    const modal = document.querySelector('.mobile-page[data-page="conversation"] .fm-modal')
      || document.querySelector('#fm-chat-overlay .fm-modal');
    const head = modal ? modal.querySelector('.mh-idhead') : null;
    const flow = modal ? modal.querySelector('.fm-flow') : null;
    if (!head) return { present: false, mobileLayer: document.body.classList.contains('mobile-layer'), scrollTop: flow ? Math.round(flow.scrollTop) : null };
    const cs = getComputedStyle(head);
    const pill = head.querySelector(':is(.mh-idhead-name, #session-name)');
    const b = head.getBoundingClientRect();
    const pb = pill ? pill.getBoundingClientRect() : null;
    return {
      present: true,
      mobileLayer: document.body.classList.contains('mobile-layer'),
      p: head.style.getPropertyValue('--idhead-p'),
      opacity: cs.opacity,
      visibility: cs.visibility,
      rect: { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) },
      name: pill ? (pill.textContent || '').trim() : null,
      nameVisible: !!pb && pb.width > 0 && pb.height > 0 && getComputedStyle(pill).visibility !== 'hidden',
      scrollTop: flow ? Math.round(flow.scrollTop) : null,
      scrollHeight: flow ? Math.round(flow.scrollHeight) : null,
    };
  });
}

async function shoot(browser, { w, h, kind, tag }) {
  const page = await browser.newPage({
    viewport: { width: w, height: h },
    hasTouch: w < 600,
    colorScheme: 'dark',
    locale: 'zh-CN',
  });
  try {
    await bootApp(page);
    const scrolled = await openAndScrollBottom(page, kind);
    const d = await headReading(page);
    const file = join(OUT, `${w}-${kind}-${tag}.png`);
    await page.screenshot({ path: file });
    console.log(`[shot] ${w}x${h} ${kind} ${tag} → ${file}`);
    console.log(`       滚动 ${JSON.stringify(scrolled)} · 身份头 present=${d.present} mobileLayer=${d.mobileLayer} --idhead-p=${JSON.stringify(d.p)} opacity=${d.opacity} visibility=${d.visibility} rect=${JSON.stringify(d.rect)} name=${JSON.stringify(d.name)} nameVisible=${d.nameVisible} scrollTop=${d.scrollTop}/${d.scrollHeight}`);
    return { file, reading: d, scrolled };
  } finally {
    await page.close();
  }
}

mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
const rows = [];
try {
  for (const kind of ['friend', 'group']) {
    rows.push(await shoot(browser, { w: 375, h: 812, kind, tag: STATE }));
    rows.push(await shoot(browser, { w: 1440, h: 900, kind, tag: STATE }));
  }
} finally {
  await browser.close();
}

console.log(`═══ conversation-heads-always-visible-shots (state=${STATE}) ═══`);
for (const r of rows) console.log(`${r.file}  ${JSON.stringify(r.reading)}`);
