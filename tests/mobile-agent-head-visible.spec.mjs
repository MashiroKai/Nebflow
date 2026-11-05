// mobile-agent-head-visible.spec.mjs — 主 agent 对话窗口「顶部身份头」可见性门禁
// （作者报障 2026-11-04 19:54 逐字：「然后手机端的顶部的头像怎么又不显示了」；
//   20:19 钉面逐字：「跟主agent的对话窗口 agent头像和agent name」）
//
// ── 这条门禁钉的是哪一档坏法（改前必现、改后不现）─────────────────────────
//   身份块 `.g2-chat-head.mh-idhead.mh-agent-head`（头像 #agent-avatar-btn +
//   名字 #session-name）在**当日 11-03 classpath 资产**上的实测读数：
//     #chat.scrollTop=152（任何超过一屏的会话进场即落底的常态）
//     ⇒ --idhead-p = 1 ⇒ 整块 opacity 0 + visibility:hidden
//     ⇒ 头像 visibility:hidden（logo naturalWidth 403 却在屏上不可见）
//     + 名字 visibility:hidden（文本在 DOM 里却不可见）
//   即「头像和 name 一起不显示」。根因 = mobileLayer.css 的
//   `opacity: calc(1 - var(--idhead-p))` + sessionHead.bindIdHeadFade 的
//   scrollTop 0→64 映射（2026-11-02 会话页头同构批 df7d817fa 接入主对话腿）。
//
// ── 断言（三腿，全部结构/读数判据，零外部资源）──────────────────────────
//   L1 手机 375×812 · 滚动到底的真实长度会话：
//      身份块存在 + 有效可见（visibility/opacity/几何）；
//      **头像 img.naturalWidth > 0 且头像在屏可见**；
//      **#session-name 文本非空且可见**（两者同时成立才算过 —— 作者验收口径）。
//   L2 手机 375 · 本地头像条目损坏（dataURL 解不出位图、sha 与服务端相符）：
//      槽位仍必须显示**可绘制**的图（回落 stand logo，naturalWidth > 0），
//      禁「只有芯片底色的空圆」（agentProfile.paintSlot 的 onerror 兜底）。
//   L3 桌面 1440×900 回归腿：body.mobile-layer 不激活、#header 内头像
//      naturalWidth > 0、#session-name 非空可见（手机批不越界）。
//
// ── 改前红口径（反向验红，机械对照）────────────────────────────────────
//   AGENTHEAD_WEB_ROOT=<改前 web 树>  ⇒ L1/L2 断言按改前形态跑，必红。
//
// Self-contained：page.route 从磁盘注入静态 + mock WS + mock /api，零后端零外网。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-agent-head-visible.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.AGENTHEAD_WEB_ROOT
  ? normalize(process.env.AGENTHEAD_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const SID = 'agent-head-visible-root';
const ROOT_NAME = 'Spider'; // 已起名的主 agent（名字非空是断言前提，不是产品值）
/** 1×1 PNG：正常/伤损腿的注入字节（同源 dataURL，零网络）。 */
const AVATAR_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
/** 解不出位图的 dataURL（截断 base64）。 */
const BROKEN_PNG = 'data:image/png;base64,AAAAAAAA';

/** 静态路由（磁盘/指定树）+ WS 夹具 + /api 夹具。 */
async function bootApp(page, { avatarEntry, metaStatus } = {}) {
  const engine = page.context().browser()?.browserType().name() || 'chromium';
  const ORIGIN = engine === 'webkit' ? 'http://localhost:8391/' : 'http://localhost:8390/';
  await page.addInitScript(({ entry }) => {
    localStorage.setItem('stand_token', 'e2e-token');
    localStorage.setItem('stand_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    if (entry) {
      localStorage.setItem('stand_agent_avatar_Nebula', JSON.stringify({ dataUrl: entry, sha: 'e2e-fix', at: Date.now() }));
    }
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => { /* 注册面静默空转（同族 spec 口径） */ }),
      configurable: true,
    });
  }, { entry: avatarEntry || '' });

  await page.route('**/*', async (route) => {
    const ru = new URL(route.request().url());
    if (ru.hostname !== 'localhost' && ru.hostname !== '127.0.0.1') return route.continue();
    let p = decodeURIComponent(ru.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p.endsWith('/avatar/meta')) {
        return route.fulfill({
          status: metaStatus,
          contentType: 'application/json',
          body: metaStatus === 200 ? JSON.stringify({ sha256: 'e2e-fix' }) : '{}',
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    // 主 agent 名字的权威读点（rootName.initRootNameProbe 的相对取数面）。
    if (p.replace(/^\/+/, '') === 'agents/Nebula/agent.json') {
      return route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: ROOT_NAME }),
      });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sess = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    cfg(); sess();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sess();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: 'all', tasks: [] }));
    });
  });

  await page.goto(ORIGIN);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(900);
  return ORIGIN;
}

/** 身份头读数（同一口径供三腿复用）。 */
const HEAD_PROBE = () => {
  const read = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const img = el.querySelector('img');
    return {
      rect: { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top) },
      visibility: cs.visibility,
      opacity: Number(cs.opacity),
      display: cs.display,
      visible: r.width > 0 && r.height > 0 && cs.visibility !== 'hidden'
        && cs.display !== 'none' && Number(cs.opacity) > 0.05,
      text: (el.textContent || '').trim(),
      img: img ? { cls: img.className, nw: img.naturalWidth, complete: img.complete } : null,
    };
  };
  const head = document.querySelector('.g2-chat-head');
  const chat = document.querySelector('#chat');
  return {
    mobileLayer: document.body.classList.contains('mobile-layer'),
    scrollTop: chat ? Math.round(chat.scrollTop) : null,
    idheadP: head ? head.style.getPropertyValue('--idhead-p') : null,
    head: read(head),
    avatar: read(document.querySelector('#agent-avatar-btn')),
    name: read(document.querySelector('.g2-chat-head #session-name') || document.querySelector('#header #session-name')),
  };
};

/** 把 #chat 变成「真实长度会话」并落到底（手机端常态：进场即落底）。 */
async function makeLongConversation(page) {
  await page.evaluate(() => {
    const chat = document.querySelector('#chat');
    for (let i = 0; i < 24; i += 1) {
      const d = document.createElement('div');
      d.className = 'row headgate-filler';
      d.style.cssText = 'height:80px;margin:6px 0;border-radius:12px';
      d.textContent = `filler ${i}`;
      chat.appendChild(d);
    }
    chat.scrollTop = chat.scrollHeight;
    chat.dispatchEvent(new Event('scroll'));
  });
  await page.waitForTimeout(400);
}

test.describe('主 agent 对话窗口顶部身份头（头像 + name）可见性', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, locale: 'zh-CN' });

  test('L1 手机 375：会话落底后 头像 naturalWidth>0 与 name 非空同时可见', async ({ page }) => {
    await bootApp(page, { avatarEntry: '', metaStatus: 404 });
    await makeLongConversation(page);
    const d = await page.evaluate(HEAD_PROBE);

    expect(d.mobileLayer, 'body.mobile-layer 未激活 = 跑在桌面档，门禁无效').toBe(true);
    expect(d.scrollTop, '会话未落底 ⇒ 本断言没打到真实滚动量').toBeGreaterThan(64);
    expect(d.head, '.g2-chat-head 身份块不存在').toBeTruthy();

    // ① 整块不许被滚动渐隐吃掉（改前此处 opacity 0 / visibility hidden）
    expect(d.head.visibility, `身份块 visibility=${d.head.visibility}`).not.toBe('hidden');
    expect(d.head.opacity, `身份块 opacity=${d.head.opacity}`).toBeGreaterThan(0.9);
    expect(d.head.visible, '身份块有效不可见').toBe(true);

    // ② 头像：在场 + 可绘制 + 在屏
    expect(d.avatar, '#agent-avatar-btn 不存在').toBeTruthy();
    expect(d.avatar.visible, '头像槽有效不可见').toBe(true);
    expect(d.avatar.img, '头像槽内无 <img>').toBeTruthy();
    expect(d.avatar.img.nw, `头像 img.naturalWidth=${d.avatar.img && d.avatar.img.nw}`).toBeGreaterThan(0);

    // ③ 名字：非空 + 可见（与头像同时成立 —— 作者验收口径）
    expect(d.name, '#session-name 不存在').toBeTruthy();
    expect(d.name.text, 'agent name 为空').not.toBe('');
    expect(d.name.visible, `agent name 有效不可见（text=${d.name.text}）`).toBe(true);
    expect(d.name.rect.w, 'agent name 盒宽为 0').toBeGreaterThan(0);
  });

  test('L2 手机 375：本地头像字节损坏 ⇒ 回落可绘制的 stand logo（禁空圆）', async ({ page }) => {
    await bootApp(page, { avatarEntry: BROKEN_PNG, metaStatus: 200 });
    await makeLongConversation(page);
    const d = await page.evaluate(HEAD_PROBE);
    expect(d.avatar, '#agent-avatar-btn 不存在').toBeTruthy();
    expect(d.avatar.visible, '头像槽有效不可见').toBe(true);
    expect(d.avatar.img, '头像槽内无 <img>（连回落件都没有 = 空圆）').toBeTruthy();
    expect(d.avatar.img.nw, `损坏字节未被兜底：naturalWidth=${d.avatar.img && d.avatar.img.nw}`).toBeGreaterThan(0);
  });
});

test.describe('桌面回归腿', () => {
  test.use({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });

  test('L3 桌面 1440：手机身份头不越界，桌面头头像可绘制 + name 非空可见', async ({ page }) => {
    await bootApp(page, { avatarEntry: '', metaStatus: 404 });
    const d = await page.evaluate(HEAD_PROBE);
    expect(d.mobileLayer, 'body.mobile-layer 在桌面视口被激活 = 手机规则越界').toBe(false);
    const desk = await page.evaluate(() => {
      const btn = document.querySelector('#header #agent-avatar-btn');
      const img = btn && btn.querySelector('img');
      const nameEl = document.querySelector('#header #session-name');
      const r = btn ? btn.getBoundingClientRect() : null;
      const nr = nameEl ? nameEl.getBoundingClientRect() : null;
      return {
        btnPresent: !!btn,
        visible: !!r && r.width > 0 && r.height > 0,
        nw: img ? img.naturalWidth : 0,
        nameText: nameEl ? (nameEl.textContent || '').trim() : '',
        nameVisible: !!nr && nr.width > 0 && nr.height > 0,
      };
    });
    expect(desk.btnPresent, '桌面 #header #agent-avatar-btn 不存在').toBe(true);
    expect(desk.visible, '桌面头像槽不可见').toBe(true);
    expect(desk.nw, `桌面头像 naturalWidth=${desk.nw}`).toBeGreaterThan(0);
    expect(desk.nameText, '桌面 agent name 为空').not.toBe('');
    expect(desk.nameVisible, '桌面 agent name 不可见').toBe(true);
  });
});
