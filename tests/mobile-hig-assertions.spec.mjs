#!/usr/bin/env node
// mobile-hig-assertions.spec.mjs — 手机端 Apple HIG 数值铁律的**机械断言集**
// （ui-unification-20261103-mobile；作者铁律：违反即返工）
//
// 六条规则，每条一组断言，**同一份脚本**在改前跑红、改后跑绿：
//   H1 正文字号 ≥17px        —— 枚举「正文」件（消息气泡 / 列表行主文案 / 身份名 /
//                               面板行主文案），computed font-size 逐件读数。
//   H2 可读字 ≥13px          —— 全页扫：任何可见文本节点的 font-size < 13 即违规。
//   H3 热区 ≥44×44           —— 全页扫：可交互件命中盒（本体 ∪ ::before 外扩）
//                               任一边 < 44 即违规。
//   H4 长名模糊淡出（禁省略号）—— 全页扫：任何可见元素 computed text-overflow
//                               为 ellipsis 即违规（铁律 = 用 mask 渐隐，不用「…」）。
//   H5 破坏性二次确认        —— 触发腿：插件卸载必须落到 __showConfirm（tone=danger）
//                               且确认前零请求；关标签页（.mf-deck-x / 页签 ✕）
//                               **豁免**——作者已裁定不算破坏性，点它不得弹确认。
//   H6 手势必有可点等价入口   —— 每条手势面（touch-action:none 的手势区）在其
//                               GESTURES 表里声明的可点等价控件必须**在位且可见**。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// 视口 = 390×844（iPhone 14 逻辑尺寸），hasTouch，chromium。
//
// Run（断言）：node tests/mobile-hig-assertions.spec.mjs
// Run（取证）：MODE=probe node tests/mobile-hig-assertions.spec.mjs      # 打印逐页读数
// 环境：HIG_ENGINE=chromium|webkit   HIG_SCHEME=light|dark   HIG_OUT_DIR=<证据目录>
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.HIG_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.HIG_OUT_DIR || join(HERE, '..', 'evidence', 'ui-unification-20261103-mobile');
const MODE = process.env.MODE || 'assert';
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'hig-root';
// 8899：WebKit bad-port 封禁表外（同族 spec 实证）
const ORIGIN = 'http://localhost:8899';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
  return cond;
};

/* ── 夹具 ─────────────────────────────────────────────────────────────── */
const FRIENDS = {
  friends: [
    { userId: 'u1', neblinkId: 'wxid_a', name: '好友甲', remark: null, avatarUrl: null, blocked: false, online: true },
    { userId: 'u2', neblinkId: 'wxid_b', name: '好友乙', remark: '备注乙', avatarUrl: null, blocked: false, online: false },
  ],
  blocked: [],
};
const PLUGINS = {
  plugins: [{
    name: 'stand-dev', version: '1.1.0', author: 'nebflow',
    description: '统一插件系统：技能与 MCP 以插件为单位打包分发，装载即生效。',
    skills: [{ id: 'stand-dev/s1', description: '技能一', preview: '' }],
    mcpServers: [], toolsExtension: null, blocked: false, contentChanged: false,
  }],
  rejected: [],
};
const MESSAGES = [
  { type: 'user', text: '这是用户发来的正文，用来量手机端正文字号是否达到 17px。', timestamp: Date.now() },
  { type: 'ai', text: '这是助手回复的正文。手机端正文档位必须 ≥17px，可读小字 ≥13px，长名走模糊淡出而不是省略号。', timestamp: Date.now() },
];

/* ── boot ─────────────────────────────────────────────────────────────── */
async function boot({ engine = 'chromium', scheme = 'light' } = {}) {
  const launcher = engine === 'webkit' ? webkit : chromium;
  const browser = await launcher.launch();
  const page = await browser.newContext({
    viewport: { width: 390, height: 844 }, colorScheme: scheme, deviceScaleFactor: 2,
    hasTouch: true, isMobile: false, locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource|ResizeObserver loop|Download the React/.test(m.text())) {
      errors.push(`console.error: ${m.text()}`);
    }
  });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
    // 二次确认探针：记录 __showConfirm 调用（H5），真实弹窗不再渲染。
    window.__higConfirms = [];
    window.addEventListener('nf-hig-tag-deleted', () => { window.__higTagDeleted = true; });
    Object.defineProperty(window, '__showConfirm', {
      configurable: true,
      get() { return window.__higConfirmImpl || null; },
      set(fn) {
        window.__higConfirmImpl = (...args) => {
          window.__higConfirms.push({ title: String(args[0] || ''), tone: args[3]?.tone || null });
        };
      },
    });
    window.__higRequests = [];
    const of = window.fetch;
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : String(input?.url || '');
      window.__higRequests.push(`${init?.method || 'GET'} ${url}`);
      return of(input, init);
    };
  });
  await page.route('**/*', (route) => {
    const p0 = decodeURIComponent(new URL(route.request().url()).pathname);
    const p = p0 === '/' ? '/index.html' : p0;
    if (p.startsWith('/api/')) {
      if (p === '/api/plugins') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PLUGINS) });
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p.startsWith('/api/friends')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(FRIENDS) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: MESSAGES, hasMore: false, offset: 0, total: MESSAGES.length }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(800);
  return { page, errors };
}

const navTo = async (page, id) => {
  // 先回 home 再进目标页：导航栈是 push 语义，逐页连跳会把上一页留在栈里
  // （.mobile-page.live 叠层 ⇒ 扫描面/点击命中会串页）。
  await page.evaluate(async (pid) => {
    const m = await import('/js/mobile/navController.js');
    m.goHome();
    await new Promise((r) => setTimeout(r, 120));
    m.navigateTo(pid);
  }, id);
  try {
    await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${id}"]`, { timeout: 12000 });
  } catch { return false; }
  await page.waitForTimeout(560);
  return true;
};
const allPageIds = (page) => page.evaluate(async () => {
  const m = await import('/js/mobile/navController.js');
  return m.PAGES.map((p) => p.id).filter((id) => id !== 'chat');
});

/* ── 全页探针（H2/H3/H4 的三条扫描一次算完）────────────────────────────── */
/**
 * 扫描根 = live 页壳（.mobile-page.live）或 chat 根（#chat 外层的手机壳）。
 * 判定面只取「可见」件：display/visibility/opacity 三态 + 盒尺寸 > 0。
 */
const SWEEP = () => {
  const sec = document.querySelector('#mobile-pages .mobile-page.live');
  const roots = [];
  if (sec) roots.push(sec);
  const dock = document.getElementById('mobile-dock');
  if (dock) roots.push(dock);
  if (!sec) { const chat = document.getElementById('chat'); if (chat) roots.push(chat); }
  if (!roots.length) return { found: false };

  const visible = (el) => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return false;
    const b = el.getBoundingClientRect();
    return b.width > 0.5 && b.height > 0.5;
  };
  const inRoot = (el) => roots.some((r) => r.contains(el));
  const ident = (el) => {
    const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    return `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${cls ? `.${cls}` : ''}`;
  };
  const hitOf = (el) => {
    const b = el.getBoundingClientRect();
    let w = b.width; let h = b.height;
    // 命中盒 = 本体 ∪ 伪元素外扩（本仓手机层既有解法 = ::after 外扩到 --mhit，
    // 见 mobileLayer.css 待办面块；两种伪元素都读，才算「真实命中域」）。
    for (const pe of ['::before', '::after']) {
      const ps = getComputedStyle(el, pe);
      if (!ps.content || ps.content === 'none') continue;
      const pw = parseFloat(ps.width); const ph = parseFloat(ps.height);
      if (Number.isFinite(pw) && pw > w) w = pw;
      if (Number.isFinite(ph) && ph > h) h = ph;
    }
    return { w: +w.toFixed(1), h: +h.toFixed(1) };
  };

  // H2：可见文本节点 → 字号
  const smallTexts = [];
  const fsInventory = new Map();
  const walker = document.createTreeWalker(sec || roots[0], NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (!node.nodeValue || !node.nodeValue.trim()) continue;
    const host = node.parentElement;
    if (!host || !inRoot(host)) continue;
    if (host.closest('[aria-hidden="true"]')) continue;   // 装饰副本（收缩态标题等）
    if (!visible(host)) continue;
    const cs = getComputedStyle(host);
    const fs = parseFloat(cs.fontSize) || 0;
    const key = `${ident(host)}@${fs}`;
    fsInventory.set(key, (fsInventory.get(key) || 0) + 1);
    if (fs < 13) smallTexts.push({ el: ident(host), fs, txt: node.nodeValue.trim().slice(0, 18) });
  }

  // H3：可交互件 → 命中盒
  const INT_SEL = 'button, a[href], [role="button"], [role="link"], [role="switch"], [role="tab"], input:not([type="hidden"]), select, textarea, [data-hit]';
  const EXEMPT = ['.ud-cell'];   // 图形件豁免（理由见文件头 EXEMPT_TARGETS）
  const smallTargets = [];
  for (const el of roots.flatMap((r) => [...r.querySelectorAll(INT_SEL)])) {
    if (!visible(el)) continue;
    if (EXEMPT.some((s) => el.matches(s))) continue;
    const cs = getComputedStyle(el);
    if (cs.pointerEvents === 'none') continue;
    const hit = hitOf(el);
    if (hit.w < 44 || hit.h < 44) smallTargets.push({ el: ident(el), ...hit });
  }

  // H4：computed text-overflow = ellipsis（禁省略号）
  const ellipsis = [];
  for (const root of roots) {
    for (const el of root.querySelectorAll('*')) {
      const cs = getComputedStyle(el);
      if (cs.textOverflow !== 'ellipsis') continue;
      if (!visible(el)) continue;
      if (el.closest('[aria-hidden="true"]')) continue;
      ellipsis.push({ el: ident(el), txt: (el.textContent || '').trim().slice(0, 20) });
    }
  }

  return {
    found: true,
    page: sec ? sec.dataset.page : 'chat',
    fsInventory: [...fsInventory.entries()].sort((a, b) => parseFloat(a[0].split('@')[1]) - parseFloat(b[0].split('@')[1])),
    smallTexts, smallTargets, ellipsis,
  };
};

/* ── H1：正文件枚举（正文 ≥17px）─────────────────────────────────────────
   枚举口径 = 「用户要读的正文」：消息气泡正文 / 身份名 / 入口行主文案 /
   面板行主文案 / 设置行主文案 / 卡片标题。每条 = 选择器 + 最小期望值。 */
const BODY_SEL = [
  '#chat .bubble',            // 消息气泡正文（手机腿沿用桌面 14.5 档 = 违规常客）
  '.fm-row-name',             // 好友/会话行名
  '.entry-name',              // 设置入口行名
  '.tfl-row-title',           // 待办行标题
  '.plugins-card-name',       // 插件卡名
  '.mf-me-name',              // 个人主页名字
  '.mf-hub-row-label',        // 入口行主文案
  '.mf-me-field-label',       // 字段行主标签
  '.mobile-page-title',       // 页面大标题（28 档，下界校验）
];

/** 热区判据的**豁免表**（一条一理由；铁律的「图形/装饰件几何不受约束」条款）：
 *  · .ud-cell = 用量面热力图的格子（367 枚 20×20），本质是数据图形的像素单元，
 *    不是可点控件族；它带 button 标签只为键盘可达。图形件几何按 --mfs 标准块
 *    的例外条款豁免，断言面记在此表（不是静默放过）。 */
const EXEMPT_TARGETS = ['.ud-cell'];
const H1_PROBE = (sels) => {
  const out = [];
  for (const sel of sels) {
    for (const el of document.querySelectorAll(sel)) {
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      const b = el.getBoundingClientRect();
      if (b.width < 1 || b.height < 1) continue;
      out.push({ sel, fs: parseFloat(cs.fontSize) || 0, txt: (el.textContent || '').trim().slice(0, 16) });
    }
  }
  return out;
};

/* ── H6：手势面 → 可点等价入口表 ────────────────────────────────────────
   手势面（touch-action:none / 自管指针事件）与它的可点等价控件必须成对在位：
   · .mf-dots（左右滑换页 / 上滑进堆叠 / 下滑关标签）→ 工具条 ✕ + 层叠钮
   · .mf-deck（横拖换卡 / 纵拖收面）→ 卡内 ✕（等价关标签）= .mf-deck-x
   · .mf-stack-view 手势区 → 卡内 ✕ + 返回钮
   断言 = 手势面在位时，等价控件必须存在且可见可点（命中 ≥44 的部分由 H3 兜）。 */
const GESTURES = [
  { surface: '.mf-dots', equivalents: ['.mf-cv-close', '.mf-cv-stack'], page: 'canvas', state: 'reading' },
  { surface: '.mf-deck', equivalents: ['.mf-deck-x'], page: 'canvas', state: 'deck' },
];
const H6_PROBE = (arg) => {
  const { gests, state } = arg;   // Playwright evaluate 只收一个参数 ⇒ 打包传
  const out = [];
  const list = gests.filter((g) => !state || g.state === state);
  const vis = (el) => {
    if (!el) return false;
    const cs = getComputedStyle(el);
    const b = el.getBoundingClientRect();
    return cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.05
      && b.width > 1 && b.height > 1;
  };
  for (const g of list) {
    const surf = document.querySelector(`#mobile-pages .mobile-page.live ${g.surface}`);
    if (!vis(surf)) { out.push({ ...g, surfacePresent: false }); continue; }
    const eq = g.equivalents.map((sel) => {
      const el = document.querySelector(`#mobile-pages .mobile-page.live ${sel}`);
      return { sel, present: !!el, visible: vis(el) };
    });
    out.push({ ...g, surfacePresent: true, equivalents: eq });
  }
  return out;
};

/* ── H5 契约扫描表：破坏性调用点（手机腿可达）→ 必须落在确认窗口内 ─────────
   一条 = 一个真·破坏性写操作；豁免只有「关标签页」（作者裁定，另由 H5a3 自证）。 */
const DESTRUCTIVE_SCAN = [
  { file: 'js/todoFace.js', re: /todoStore\.deleteTag\(/g, label: 'todo tag delete (swipe gesture)' },
  { file: 'js/contacts.js', re: /api\.removeFriend\(/g, label: 'friend delete' },
  { file: 'js/plugins.js', re: /uninstallPlugin\(/g, label: 'plugin uninstall' },
];

/* ── H5：破坏性二次确认（含关标签页豁免）────────────────────────────────── */
async function h5(page) {
  // (a) 插件卸载 → 必须走 __showConfirm（tone=danger），且确认前零 remove 请求
  const reached = await navTo(page, 'plugins');
  if (reached) {
    await page.waitForSelector('.plugins-card', { timeout: 10000 });
    await page.locator('.plugins-card').first().locator('.plugins-card-head').click();
    await page.waitForTimeout(400);
    const btn = page.locator('[data-plugin-uninstall]').first();
    if (await btn.count()) {
      await page.evaluate(() => { window.__higConfirms.length = 0; window.__higRequests.length = 0; });
      await btn.click();
      await page.waitForTimeout(400);
      const res = await page.evaluate(() => ({ confirms: window.__higConfirms.slice(), requests: window.__higRequests.slice() }));
      ok('H5a destructive (plugin uninstall) requires 2nd confirm',
        res.confirms.length === 1, JSON.stringify(res.confirms));
      ok('H5a confirm is danger-toned', res.confirms[0]?.tone === 'danger', JSON.stringify(res.confirms));
      ok('H5a zero /remove request fired before confirm',
        !res.requests.some((r) => /\/api\/plugins\/.*\/remove/.test(r)), JSON.stringify(res.requests.slice(0, 4)));
      // 收尾：关掉确认面（若真弹了）——探针已拦，无面可关
    } else {
      ok('H5a uninstall control present in plugins card', false);
    }
  } else {
    ok('H5a plugins page reachable', false);
  }

  // (a2) 破坏性调用点**契约扫描**（源码级，确定性）：手机腿可达的每个破坏性
  //      写操作调用点，±1200 字符窗口内必须有二次确认面（__showConfirm）在岗；
  //      唯一豁免 = 关标签页（作者裁定不算破坏性）。本腿覆盖「手势删除」这类
  //      没有可见按钮的破坏性入口（待办标签下划删除）—— 手势模拟在 headless
  //      下依赖胶囊面板真实在位（易碎），契约级断言同效且稳。
  for (const entry of DESTRUCTIVE_SCAN) {
    const { file, re, label } = entry;
    const abs = join(HERE, '..', 'src', 'main', 'resources', 'web', file);
    const src = readFileSync(abs, 'utf8');
    const hits = [];
    for (const m of src.matchAll(re)) {
      const from = Math.max(0, m.index - 1200);
      const win = src.slice(from, m.index + 1200);
      const line = src.slice(0, m.index).split('\n').length;
      hits.push({ at: line, guarded: win.includes('__showConfirm') });
    }
    const open = hits.filter((h) => !h.guarded);
    ok(`H5a2 destructive call site guarded (${label}: ${file})`, open.length === 0,
      open.length ? JSON.stringify(open) : `${hits.length} call site(s) inside a __showConfirm window`);
  }
  // (a2b) 守卫**接线**自证：「有个包了 __showConfirm 的方法」不等于「破坏性手势
  //      走的是它」——本批实测踩过：手势回调里写成裸 `confirmDeleteTag(tag)`
  //      （类方法无接收者 ⇒ 运行时 ReferenceError，遮罩一出现就吞删除），而
  //      (a2) 的窗口扫描照样绿灯（`todoStore.deleteTag(` 确实在 __showConfirm 窗口内）。
  //      故补一条接线断言：待办面手势回调必须以 `this.confirmDeleteTag(` 形态调守卫。
  {
    const abs = join(HERE, '..', 'src', 'main', 'resources', 'web', 'js', 'todoFace.js');
    const src = readFileSync(abs, 'utf8');
    const wired = /this\.confirmDeleteTag\(/.test(src);
    // 方法**定义行**（`  confirmDeleteTag(tag) {`）不算裸调用；裸调用 =
    // 无接收者前缀的引用（运行时 ReferenceError 或全局漏接）。
    const body = src.split('\n').filter((l) => l.includes('confirmDeleteTag'));
    const bare = body.some((l) => /confirmDeleteTag\(/.test(l) && !/this\.confirmDeleteTag\(/.test(l) && !/^\s*confirmDeleteTag\(tag\)\s*\{/.test(l));
    ok('H5a2b destructive gesture wired to the confirm wrapper (this.confirmDeleteTag)', wired && !bare,
      JSON.stringify({ wired, bareLines: bare ? body.filter((l) => /confirmDeleteTag\(/.test(l) && !/this\./.test(l)) : [] }));
  }
  // (a3) 豁免面自证：关标签页**本身**不算破坏性（作者裁定）—— closeTab 里若出现
  //      确认面，只能是「丢弃未保存改动」这一条（isTabDirty 守卫），不得是裸关闭。
  {
    const abs = join(HERE, '..', 'src', 'main', 'resources', 'web', 'js', 'canvas.js');
    const src = readFileSync(abs, 'utf8');
    const at = src.indexOf('export function closeTab(');
    const body = at >= 0 ? src.slice(at, at + 2600) : '';
    const confirmLines = body.split('\n')
      .map((l) => l.trim())
      .filter((l) => l.includes('__showConfirm') && !l.startsWith('//') && !l.startsWith('*'));
    const onlyDirty = confirmLines.every((l, i) => {
      const idx = body.indexOf(l);
      return body.slice(Math.max(0, idx - 400), idx + 400).includes('isTabDirty');
    });
    ok('H5a3 tab close confirm only guards unsaved-changes discard (plain close exempt)',
      at >= 0 && confirmLines.length > 0 && onlyDirty,
      at >= 0 ? `${confirmLines.length} confirm line(s): ${confirmLines.map((l) => l.trim().slice(0, 70)).join(' | ')}` : 'closeTab not found');
  }

  // (b) 关标签页豁免：卡片 ✕ 关闭不得弹确认（作者裁定不算破坏性）
  const canvasOk = await navTo(page, 'canvas');
  if (canvasOk) {
    await page.evaluate(() => { window.__higConfirms.length = 0; });
    const deckBtn = page.locator('.mf-cv-stack').first();
    if (await deckBtn.count()) {
      await deckBtn.click();
      await page.waitForTimeout(700);
      const x = page.locator('#mobile-pages .mobile-page.live .mf-deck-x').first();
      if (await x.count()) {
        await x.click();
        await page.waitForTimeout(500);
        const n = await page.evaluate(() => window.__higConfirms.length);
        ok('H5b tab close is exempt from confirm (author ruling)', n === 0, `confirms=${n}`);
      } else {
        ok('H5b deck card ✕ present (need a tab to close)', false);
      }
    } else {
      // 无标签页可关：走桌面页签腿（.canvas-tab-close）同样判豁免
      const tabX = page.locator('.canvas-tab-close').first();
      if (await tabX.count()) {
        await page.evaluate(() => { window.__higConfirms.length = 0; });
        await tabX.click();
        await page.waitForTimeout(400);
        const n = await page.evaluate(() => window.__higConfirms.length);
        ok('H5b tab close is exempt from confirm (author ruling)', n === 0, `confirms=${n}`);
      } else {
        console.log('SKIP  H5b (no tab / deck surface on this fixture)');
      }
    }
  } else {
    ok('H5b canvas page reachable', false);
  }
}

/* ── main ─────────────────────────────────────────────────────────────── */
const ENGINE = process.env.HIG_ENGINE || 'chromium';
const SCHEME = process.env.HIG_SCHEME || 'light';

(async () => {
  const { page, errors } = await boot({ engine: ENGINE, scheme: SCHEME });
  const tag = `${ENGINE}-${SCHEME}`;
  const dump = { pages: {}, h1: [], h6: [], smallTexts: [], smallTargets: [], ellipsis: [] };

  // 1) chat 根（无页壳）：气泡正文 + 输入区
  await page.waitForTimeout(600);
  const chatSweep = await page.evaluate(SWEEP);
  const chatH1 = await page.evaluate(H1_PROBE, BODY_SEL);
  dump.pages.chat = chatSweep;
  dump.h1.push(...chatH1.map((x) => ({ ...x, page: 'chat' })));
  for (const v of chatSweep.smallTexts || []) dump.smallTexts.push({ page: 'chat', ...v });
  for (const v of chatSweep.smallTargets || []) dump.smallTargets.push({ page: 'chat', ...v });
  for (const v of chatSweep.ellipsis || []) dump.ellipsis.push({ page: 'chat', ...v });

  // 1b) canvas 前置：打开三张卡（含超长标题），让小点带 / 工具钮 / 堆叠面在场
  await page.evaluate(async () => {
    const m = await import('/js/canvas.js');
    for (let i = 0; i < 3; i += 1) {
      m.openTab(`hig-doc-${i}`, `手机端超长文档标题第 ${i + 1} 号用于长名淡出与手势等价入口检验`,
        { type: 'markdown', pinned: i === 0 });
    }
  });
  await page.waitForTimeout(1200);

  // 2) 全量页遍历
  const ids = await allPageIds(page);
  for (const id of ids) {
    const reached = await navTo(page, id);
    if (!reached) { console.log(`SKIP  page ${id} not reachable`); continue; }
    const sweep = await page.evaluate(SWEEP);
    dump.pages[id] = sweep;
    if (['me', 'settings', 'tasks', 'plugins'].includes(id)) {
      const h1 = await page.evaluate(H1_PROBE, BODY_SEL);
      dump.h1.push(...h1.map((x) => ({ ...x, page: id })));
    }
    for (const v of sweep.smallTexts || []) dump.smallTexts.push({ page: id, ...v });
    for (const v of sweep.smallTargets || []) dump.smallTargets.push({ page: id, ...v });
    for (const v of sweep.ellipsis || []) dump.ellipsis.push({ page: id, ...v });
    if (id === 'canvas') {
      // 手势面本体（.mf-dots）已在阅读面；再点开堆叠面（.mf-cv-stack）让 .mf-deck 在场
      dump.h6.push(...(await page.evaluate(H6_PROBE, { gests: GESTURES, state: 'reading' })));
      const stackBtn = page.locator('#mobile-pages .mobile-page.live .mf-cv-stack').first();
      if (await stackBtn.count()) {
        await stackBtn.click();
        await page.waitForTimeout(700);
        dump.h6.push(...(await page.evaluate(H6_PROBE, { gests: GESTURES, state: 'deck' })));
        // 收面（.mf-stack-x）
        const back = page.locator('#mobile-pages .mobile-page.live .mf-stack-x').first();
        if (await back.count()) { await back.click(); await page.waitForTimeout(400); }
      }
    }
  }

  // 3) 断言
  if (MODE === 'probe') {
    writeFileSync(join(OUT, 'hig-probe.json'), JSON.stringify(dump, null, 2) + '\n');
    console.log(JSON.stringify({
      h1: dump.h1, smallTexts: dump.smallTexts, smallTargets: dump.smallTargets,
      ellipsis: dump.ellipsis, h6: dump.h6, pages: Object.keys(dump.pages),
    }, null, 2));
  } else {
    // H1 正文 ≥17
    const badH1 = dump.h1.filter((x) => x.fs < 17);
    ok('H1 body text ≥17px (every enumerated body element)', badH1.length === 0,
      badH1.length ? JSON.stringify(badH1.slice(0, 8)) : `${dump.h1.length} elements swept`);
    // H2 可读字 ≥13
    ok('H2 readable text ≥13px (no visible text node below 13)', dump.smallTexts.length === 0,
      dump.smallTexts.length ? JSON.stringify(dump.smallTexts.slice(0, 8)) : `${Object.keys(dump.pages).length} pages swept`);
    // H3 热区 ≥44×44
    ok('H3 touch targets ≥44×44 (hit box incl ::before)', dump.smallTargets.length === 0,
      dump.smallTargets.length ? JSON.stringify(dump.smallTargets.slice(0, 8)) : 'all interactive elements ok');
    // H4 禁省略号
    ok('H4 long names fade out (no computed text-overflow:ellipsis)', dump.ellipsis.length === 0,
      dump.ellipsis.length ? JSON.stringify(dump.ellipsis.slice(0, 8)) : 'no ellipsis element');
    // H6 手势等价入口
    for (const g of dump.h6) {
      if (!g.surfacePresent) continue;
      const missing = (g.equivalents || []).filter((e) => !e.present || !e.visible);
      ok(`H6 gesture ${g.surface} has tappable equivalent ${g.equivalents.map((e) => e.sel).join('|')}`,
        missing.length === 0, missing.length ? JSON.stringify(missing) : 'ok');
    }
    // H5 破坏性确认
    await h5(page);
    // 噪声腿
    ok('H7 zero pageerror / console.error', errors.length === 0, errors.slice(0, 3).join(' | '));
  }

  await page.context().browser()?.close?.();
  console.log(`\n${MODE === 'probe' ? 'PROBE DONE' : failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}`);
  process.exit(MODE === 'probe' ? 0 : failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error('HARNESS ERROR:', e);
  process.exit(2);
});
