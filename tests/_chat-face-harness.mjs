// _chat-face-harness.mjs — 主会话面（聊天列 / 输入区 / 侧栏 / canvas）几何 spec 共用启动器。
//
// 用途：UI 批次审计 D 类缺口（R69 / R70 / R71）三支几何 spec 的同一份 boot ——
//   · 静态文件面 = 本仓真 web 树（page.route 从磁盘注入，不起本地端口）；
//   · 数据面 = WS 帧桩（configData / sessionList / historyPage），零后端、零 sbt；
//   · 零宿主实例依赖：不碰 :6138 / :8080 / :8097（审计与生产实例零风险）。
//
// 🔴 诚实申报：这里不是生产服务端，是帧镜像；断言面 = 真实浏览器 + 真实模块图 +
//   真实 CSS + 真实几何（getBoundingClientRect / getComputedStyle 读数），不靠肉眼。

import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/** 被测 web 树：缺省 = 本仓；CHATFACE_WEB_ROOT 可指到导出树跑「改前腿」。 */
export const WEB = process.env.CHATFACE_WEB_ROOT
  ? normalize(process.env.CHATFACE_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');

export const SID = 'chat-face-geom-session';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

/** 历史夹具：user/ai 交替 n 轮（lastRole 可追加一条指定角色的末条）。 */
export function historyFixture(n = 8, { lastRole = 'ai' } = {}) {
  const now = Date.now();
  const out = [];
  for (let i = 0; i < n; i++) {
    const ai = i % 2 === 0;
    out.push({
      type: ai ? 'ai' : 'user',
      text: ai
        ? `第 ${i + 1} 条助手消息：用于撑出可滚消息列并量末条气泡几何。`
        : `第 ${i + 1} 条用户消息：收到。`,
      timestamp: now - 600_000 + i * 10_000,
    });
  }
  const last = out[out.length - 1];
  if (lastRole && last.type !== lastRole) {
    out.push({
      type: lastRole,
      text: `末条${lastRole === 'user' ? '用户' : '助手'}消息：本批判定对象。`,
      timestamp: now,
    });
  }
  return out;
}

/**
 * 启动主会话面并等到消息列落地。
 * @param {import('@playwright/test').Page} page
 * @param {{history?: any[], onWsRoute?: (ws: any) => void}} [opts]
 *   `onWsRoute`（可选，零回归）：拿到 WS 桩的 route 句柄 ⇒ 测试可在 boot 之后
 *   **主动推帧**（如 `{type:'askUser', …}`）驱动真实 onMessage 分支 —— askuser-media
 *   批 2026-11-18 为「真 ask 卡」验收加的可选钩子；既有调用方不传 = 逐字不变。
 */
export async function bootChat(page, { history = historyFixture(), onWsRoute } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      let body = readFileSync(file);
      const fault = process.env.ASK_HIT_FAULT;
      const mutations = {
        text: ['chat.js', 'fillOptionText(body, label, desc, true);', "fillOptionText(body, label, desc, true); body.querySelector('.option-text').addEventListener('click', e => e.stopPropagation());"],
        blank: ['chat.js', "btn.className = 'option-btn';", "btn.className = 'option-btn'; btn.addEventListener('click', e => { if (e.target === btn) e.stopImmediatePropagation(); });"],
        swipe: ['popArtifacts.js', 'Math.abs(dx) > 38', 'Math.abs(dx) > 10000'],
        expand: ['popArtifacts.js', "pill.addEventListener('click', () => setExpanded(!expanded));", "pill.addEventListener('click', () => {});"],
        keyboard: ['popArtifacts.js', 'if (!stack.suppressClick) openItem(items[front], front);', 'if (!stack.suppressClick) return;'],
      };
      if (fault && mutations[fault] && p.endsWith('/' + mutations[fault][0])) {
        const [, from, to] = mutations[fault];
        const text = body.toString();
        if (text.split(from).length !== 2) throw new Error(`Fault ${fault}: expected exactly one injection site`);
        body = Buffer.from(text.replace(from, to));
      }
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    if (typeof onWsRoute === 'function') onWsRoute(ws);
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: m.sessionId, messages: history,
          hasMore: false, offset: 0, total: history.length,
        }));
      }
    });
  });

  await page.goto('http://stand-fixture.test/');
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await page.waitForSelector('#input-bar', { state: 'visible', timeout: 20000 });
  await page.waitForTimeout(600); // 让位反算（chat padding-bottom）落地
}

/** 主会话面几何读数（输入区链 + 邻列底缘 + 末条气泡圆角）。 */
export async function probeChatFace(page) {
  return page.evaluate(() => {
    const r = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return {
        left: +b.left.toFixed(2), right: +b.right.toFixed(2),
        top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2),
        width: +b.width.toFixed(2), height: +b.height.toFixed(2),
      };
    };
    const cs = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const s = getComputedStyle(el);
      return {
        paddingLeft: s.paddingLeft, paddingRight: s.paddingRight, paddingBottom: s.paddingBottom,
        marginLeft: s.marginLeft, marginRight: s.marginRight, marginBottom: s.marginBottom,
        maxWidth: s.maxWidth, bottom: s.bottom,
      };
    };
    const rows = [...document.querySelectorAll('#chat .row')];
    const lastRow = rows[rows.length - 1] || null;
    const lastBubble = lastRow ? lastRow.querySelector('.bubble') : null;
    const radiusOf = (el) => {
      if (!el) return null;
      const s = getComputedStyle(el);
      return {
        borderRadius: s.borderTopLeftRadius + ' ' + s.borderTopRightRadius + ' '
          + s.borderBottomRightRadius + ' ' + s.borderBottomLeftRadius,
        bottomLeft: s.borderBottomLeftRadius,
        bottomRight: s.borderBottomRightRadius,
        cls: el.className,
      };
    };
    return {
      viewport: { w: window.innerWidth, h: window.innerHeight },
      bodyClass: document.body.className,
      mobileLayer: document.body.classList.contains('mobile-layer'),
      main: r('#main'), chat: r('#chat'), sidebar: r('#sidebar'), canvas: r('#canvas-panel'),
      inputArea: r('#input-area'), inputBar: r('#input-bar'),
      inputAreaCss: cs('#input-area'), inputBarCss: cs('#input-bar'), mainCss: cs('#main'),
      chatPadBottom: getComputedStyle(document.getElementById('chat')).paddingBottom,
      lastRowClass: lastRow ? lastRow.className : null,
      lastBubble: radiusOf(lastBubble),
      allBubbles: [...document.querySelectorAll('#chat .bubble')].map(radiusOf),
      chatScroll: { top: document.getElementById('chat').scrollTop, h: document.getElementById('chat').scrollHeight },
    };
  });
}
