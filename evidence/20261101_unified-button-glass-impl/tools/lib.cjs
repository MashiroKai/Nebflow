#!/usr/bin/env node
'use strict';
/* lib.cjs — 统一按钮玻璃批 harness 共享打桩库（2026-11-01）。
 *
 * 复刻 scripts/shot-header-name.cjs 的自包含路线：
 *   · page.route 从磁盘服务真实前端文件（零端口零进程，不碰任何 8080 实例）；
 *   · MockWebSocket 注入真实 ws.js 分发路径（走生产 boot 链）；
 *   · /api/* 一律 404（隔离真实例之外零后端依赖）。
 * 本文件只放共享件：MIME 表、route 处理器、boot 帧、气泡 fixture 注入、
 * console/pageerror 收集。shot.cjs / probe.cjs / scrollfps.cjs 引用之。
 */

const { readFileSync } = require('node:fs');
const { join, normalize, extname } = require('node:path');

/* tools → 批目录 → evidence → worktree 根 = 三层上级 */
const WEB = join(__dirname, '..', '..', '..', 'src', 'main', 'resources', 'web');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/tf',
};

/** page.route 处理器：磁盘真文件 + /api 404。 */
function makeRouter(page) {
  return page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
}

/** addInitScript：token/locale + MockWebSocket（真实 ws.js 分发路径入口）。 */
const INIT_SCRIPT = () => {
  localStorage.setItem('nebflow_token', 'shot-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  window.__shotErrors = [];
  window.addEventListener('error', (e) => window.__shotErrors.push(String(e.message)));
  class MockWS {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
    send() {} close() {} onopen = null; onmessage = null; onclose = null;
  }
  Object.defineProperty(window, 'WebSocket', { value: MockWS });
};

const ROOT_SID = 'glass-root';

/** boot 三帧（sessionList / configData / 空 historyPage）——与生产 boot 链同路。 */
async function bootSession(page) {
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(300);
  const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
  await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
  await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
  await page.waitForTimeout(400);
}

/** 气泡 fixture 注入（用生产类名 .row.user/.bubble.user、.row.ai/.bubble.ai）。
 *  走 DOM 注入而非 ws 帧的原因：harness 面向 CSS 材质取证，需要稳定的文案/
 *  数量且不依赖渲染链消息 schema；注入节点类名与生产渲染产物一致。 */
const BUBBLE_HTML = async (page, n = 12) => page.evaluate((cnt) => {
  const chat = document.getElementById('chat');
  if (!chat) return false;
  for (let i = 0; i < cnt; i++) {
    const isUser = i % 2 === 0;
    const row = document.createElement('div');
    row.className = isUser ? 'row user' : 'row ai';
    const bub = document.createElement('div');
    bub.className = isUser ? 'bubble user' : 'bubble ai';
    bub.textContent = isUser
      ? `用户消息 fixture #${i + 1} — 统一按钮玻璃批截图物料（含中英文 body 与较长段落，用于观察气泡圆角/底色/投影）。`
      : `AI 回复 fixture #${i + 1} — Frosted glass inventory shot material. 带 <code>行内代码</code> 与多行文本，覆盖 .bubble.ai 白面+whisper 边。`;
    row.appendChild(bub);
    chat.appendChild(row);
  }
  chat.scrollTop = chat.scrollHeight;
  return true;
}, n);

/** 主题落位：data-theme 显式档（权威）+ emulateMedia 跟随档（:root 兜底路径）。 */
async function applyTheme(page, theme) {
  await page.emulateMedia({ colorScheme: theme });
  await page.evaluate((t) => {
    document.documentElement.setAttribute('data-theme', t);
  }, theme);
}

/** node 侧 console/pageerror 收集器。
 *  /api/* 的资源 404 是打桩契约的一部分（无后端），单列 api404 计数，
 *  不计入 consoleErrors（真 JS 报错才入账）。 */
function attachErrorCollectors(page) {
  const errors = [];
  const state = { api404: 0 };
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    let url = '';
    try { url = msg.location() && msg.location().url || ''; } catch { /* no location */ }
    if (/\/api\//.test(url) || (msg.text() || '').includes('Failed to load resource')) { state.api404++; return; }
    errors.push(`console.error: ${msg.text()}`);
  });
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  errors.api404 = () => state.api404;
  return errors;
}

/** 引擎解析：'both' → ['chromium','webkit']。 */
function engines(opt) {
  const e = (opt || 'chromium').toLowerCase();
  return e === 'both' ? ['chromium', 'webkit'] : [e];
}

/** 主题解析：'both' → ['dark','light']。 */
function themes(opt) {
  const t = (opt || 'both').toLowerCase();
  return t === 'both' ? ['dark', 'light'] : [t];
}

module.exports = { WEB, MIME, makeRouter, INIT_SCRIPT, bootSession, BUBBLE_HTML, applyTheme, attachErrorCollectors, engines, themes, ROOT_SID };
