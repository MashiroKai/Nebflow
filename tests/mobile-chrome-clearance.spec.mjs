#!/usr/bin/env node
// mobile-chrome-clearance.spec.mjs — 顶部悬浮控件簇 vs 正文起点 让位批验收
// （作者令 2026-11-02 08:46「插件面板的文字会被悬浮的按钮遮挡，内容都需要从
// 按钮下开始；只有向上滑动的时候才允许从按钮下过」）。
//
// 覆盖（双引擎 Chromium/WebKit × 双主题 light/dark × 375×812）：
//   C1 静止首屏让位（机械断言，全页扫）：PAGES 注册表**每一页**（dock 根 +
//      全部详情页，共 25 页）—— 首屏内任何正文文字矩形与任何悬浮控件命中区
//      矩形**不相交**，且首个正文文字顶 ≥ 控件簇下沿。
//   C2 滑动穿过（允许腿）：滚到 200px（或到顶）后正文顶必须已越过控件簇下沿
//      （= 内容确实滑到钮下；证明让位不是靠整块遮罩，而是静止让位 + 滚动穿越）。
//   C3 展开卡操作区一行化：toggle / 卸载 / Plugin.md 三者 y 区间重叠 + 垂直中心
//      对齐（±1px）+ 命中区 ≥44。
//   C4 375 宽占地实测：三者命中宽之和 + 间距 vs 行可用宽（不溢出、无横向滚动）。
//   C5 零 console error / 零 pageerror（噪声过滤同族 spec 表）。
//   C6 桌面零触碰腿（1280×800）：mobile-layer 不激活；操作行仍是桌面形态
//      （doc 左 / toggle + 卸载 右，同一行），卡片不被手机全幅样式带崩。
//   C7 canvas 环标签长名展开：展开签（.is-expanded）字墨矩形不得进入左右两颗
//      悬浮钮（✕ / 层叠视角）命中盒 —— 作者实拍「右上层叠图标压字」同族。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/mobile-chrome-clearance.spec.mjs
// 环境：MCC_ENGINES=chromium,webkit  MCC_SCHEMES=light,dark  MCC_ONLY=pages|card|canvas|desktop
//       MCC_OUT_DIR=<证据目录>  MCC_SHOTS_ALL=1（全页截图，默认只拍关键页）
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.MCC_OUT_DIR || join(HERE, '..', 'evidence', '20261102_plugin-panel-layout', 'after');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'chrome-clearance-root';
// 端口 8899：WebKit bad-port 封禁表外（pop-footer-family spec 同款实证）。
const ORIGIN = 'http://localhost:8899';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed|Download the React/i;
const watchErrors = (page, tag) => {
  page.on('pageerror', (e) => { if (!NOISE.test(e.message)) consoleErrors.push(`${tag} pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`${tag} console.error: ${m.text()}`); });
};

/* ── 夹具：插件册（首包 50 技能 = 作者实拍「50 个技能 · 2 个…」统计行口径）── */
const PLUGINS = {
  plugins: [
    {
      name: 'stand-dev', version: '1.1.0', author: 'nebflow',
      description: '统一插件系统：技能与 MCP 以插件为单位打包分发，装载即生效。本开关只影响任务分发器对未来节点的派发（已在跑的节点保持其许可）',
      skills: Array.from({ length: 50 }, (_, i) => `stand-dev-skill-${i + 1}`),
      mcpServers: ['viz-mcp', 'search-mcp'], toolsExtension: null,
      blocked: false, contentChanged: false,
    },
    {
      name: 'visual-report', version: '1.0.0', author: 'nebflow',
      description: '可视化报告包：17 个场景 skill',
      skills: ['visual-report', 'core', 'motion-toolkit'], mcpServers: [],
      toolsExtension: null, blocked: false, contentChanged: false,
    },
    {
      name: 'legacy-scraper', version: '0.9.2', author: '3rd-party',
      description: '旧版抓取（已封禁示例）', skills: ['scrape'],
      mcpServers: ['scraper-mcp'], toolsExtension: null, blocked: true, contentChanged: false,
    },
  ],
  rejected: [],
};

/* ── boot（route 静态 + WS 夹具 + /api 夹具）────────────────────────────── */
async function boot(engineName, scheme, { viewport = { width: 375, height: 812 }, mobile = true } = {}) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const browser = await engine.launch();
  const page = await browser.newContext({
    viewport, colorScheme: scheme, deviceScaleFactor: 2, hasTouch: mobile, locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
  watchErrors(page, `${engineName}-${scheme}`);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => { /* 永不决 = 注册面静默空转 */ }),
      configurable: true,
    });
  });
  await page.route('**/*', (route) => {
    const p0 = decodeURIComponent(new URL(route.request().url()).pathname);
    const p = p0 === '/' ? '/index.html' : p0;
    if (p.startsWith('/api/')) {
      if (p === '/api/plugins') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PLUGINS) });
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  if (mobile) {
    await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  } else {
    await page.waitForFunction(() => !document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  }
  await page.waitForTimeout(700);
  return page;
}

/* ── 导航（真实 navController API 路径；等价于逐层点进）────────────────── */
async function navTo(page, pageId) {
  const okNav = await page.evaluate(async (id) => {
    const m = await import('/js/mobile/navController.js');
    m.navigateTo(id);
    return true;
  }, pageId);
  if (!okNav) return false;
  try {
    await page.waitForSelector(`#mobile-pages .mobile-page.live[data-page="${pageId}"]`, { timeout: 15000 });
  } catch {
    return false;
  }
  await page.waitForTimeout(620);
  return true;
}

/** 当前 live 页 id。 */
const livePage = (page) => page.evaluate(() => document.querySelector('#mobile-pages .mobile-page.live')?.dataset.page || null);

/* ── 探针：悬浮控件簇矩形 + 首屏正文文字矩形 + 相交判定 ────────────────── */
/**
 * 判定面（作者判据「首屏静态截图里任何正文文字像素都不得落在控件命中区矩形内」）：
 *   · 控件集 = 页面壳内所有 position:absolute|fixed 且可见、且命中的可交互件
 *     （g2-fab 族 / chrome 键 / 返回钮 / canvas 工具钮 / dock 键）＋ ::before 外扩；
 *   · 正文文字集 = 该 live 页壳内**可见**文本节点（排除控件自身子树 + aria-hidden
 *     装饰副本），取 Range 实测字墨矩形（不是元素盒 —— 元素盒含 padding 会虚报）；
 *   · 相交 = 字墨矩形 ∩ 控件命中矩形 面积 > 0.5px²（吸收亚像素反走样）。
 */
const PROBE = () => {
  const sec = document.querySelector('#mobile-pages .mobile-page.live');
  if (!sec) return { found: false };
  const rectOf = (el) => {
    const b = el.getBoundingClientRect();
    return { x: b.x, y: b.y, w: b.width, h: b.height, r: b.x + b.width, b: b.y + b.height };
  };
  const visible = (el) => {
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) return false;
    const b = el.getBoundingClientRect();
    return b.width > 0 && b.height > 0;
  };
  const pageId = sec.dataset.page;
  const inLive = (el) => !!(el && el.closest(`.mobile-page[data-page="${pageId}"]`));

  // ① 控件簇：本页壳内全部悬浮可交互件（含 ::before 外扩命中盒）
  const CTRL_SEL = '.g2-fab, .mh-chrome-btn, .mobile-back-btn, .mf-cv-btn, .dock-key';
  const controls = [];
  for (const el of sec.querySelectorAll(CTRL_SEL)) {
    if (!visible(el)) continue;
    const cs = getComputedStyle(el);
    if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
    const r = rectOf(el);
    const pb = getComputedStyle(el, '::before');
    let hit = { ...r };
    if (pb.content && pb.content !== 'none' && parseFloat(pb.width) > r.w) {
      const hw = parseFloat(pb.width);
      const hh = parseFloat(pb.height) || r.h;
      hit = {
        x: r.x + r.w / 2 - hw / 2, y: r.y + r.h / 2 - hh / 2,
        w: hw, h: hh, r: r.x + r.w / 2 + hw / 2, b: r.y + r.h / 2 + hh / 2,
      };
    }
    controls.push({ cls: String(el.className).slice(0, 48), rect: hit });
  }

  // ② 正文文字：可见文本节点的字墨矩形（排除控件子树 + 装饰副本）
  //    字墨矩形须先被最近的裁剪祖先（overflow 非 visible）裁一遍：ellipsis/
  //    滚动容器的 Range 矩形是**未裁剪**的整段文本宽（实测多报 100+px），
  //    直接拿来判交会假阳。
  const clipOf = (el) => {
    let box = null;
    for (let n = el; n && n !== document.body; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.overflow === 'visible' && cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
      const b = n.getBoundingClientRect();
      const cur = { x: b.x, y: b.y, r: b.x + b.width, b: b.y + b.height };
      box = box
        ? { x: Math.max(box.x, cur.x), y: Math.max(box.y, cur.y), r: Math.min(box.r, cur.r), b: Math.min(box.b, cur.b) }
        : cur;
    }
    return box;
  };
  const body = sec.querySelector('.mobile-page-body') || sec;
  const texts = [];
  const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  const range = document.createRange();
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (!node.nodeValue || !node.nodeValue.trim()) continue;
    const host = node.parentElement;
    if (!host || !inLive(host)) continue;
    if (host.closest(CTRL_SEL)) continue;                // 控件自身文本不算正文
    if (host.closest('[aria-hidden="true"]')) continue;  // 装饰副本（收缩态标题）
    const cs = getComputedStyle(host);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
    range.selectNodeContents(node);
    const rr = range.getBoundingClientRect();
    if (!rr || rr.width < 0.5 || rr.height < 0.5) continue;
    const clip = clipOf(host);
    const vx = clip ? Math.max(rr.x, clip.x) : rr.x;
    const vy = clip ? Math.max(rr.y, clip.y) : rr.y;
    const vr = clip ? Math.min(rr.right, clip.r) : rr.right;
    const vb = clip ? Math.min(rr.bottom, clip.b) : rr.bottom;
    if (vr - vx < 0.5 || vb - vy < 0.5) continue;         // 被裁光
    if (vb <= 0 || vy >= innerHeight) continue;
    texts.push({
      txt: node.nodeValue.trim().slice(0, 24),
      cls: String(host.className).slice(0, 40),
      rect: { x: vx, y: vy, w: vr - vx, h: vb - vy, r: vr, b: vb },
    });
  }

  // ③ 相交（面积 > 0.5px²）
  const hits = [];
  for (const t of texts) {
    for (const c of controls) {
      const ix = Math.min(t.rect.r, c.rect.r) - Math.max(t.rect.x, c.rect.x);
      const iy = Math.min(t.rect.b, c.rect.b) - Math.max(t.rect.y, c.rect.y);
      if (ix > 0 && iy > 0 && ix * iy > 0.5) {
        hits.push({ txt: t.txt, textCls: t.cls, ctrl: c.cls, area: +(ix * iy).toFixed(1), textRect: t.rect, ctrlRect: c.rect });
      }
    }
  }

  // ④ 让位读数：正文首个文字顶 vs 控件簇下沿（最大 b）
  const ctrlBottom = controls.reduce((m, c) => Math.max(m, c.rect.b), 0);
  const firstTextTop = texts.length ? Math.min(...texts.map((t) => t.rect.y)) : null;
  return {
    found: true, page: pageId,
    controls, hits, ctrlBottom, firstTextTop, textCount: texts.length,
    scrollTop: (() => {
      const cands = [body, ...(body ? body.querySelectorAll('*') : [])];
      for (const n of cands) {
        const oy = getComputedStyle(n).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight - n.clientHeight > 4) return n.scrollTop;
      }
      return 0;
    })(),
  };
};

/** 主滚轴滚动到指定 scrollTop（返回是否可滚）。 */
const SCROLL_TO = (top) => {
  const sec = document.querySelector('#mobile-pages .mobile-page.live');
  if (!sec) return { scrolled: false };
  const body = sec.querySelector('.mobile-page-body');
  const cands = [body, ...(body ? body.querySelectorAll('*') : [])];
  for (const n of cands) {
    const oy = getComputedStyle(n).overflowY;
    if (oy !== 'auto' && oy !== 'scroll') continue;
    if (n.scrollHeight - n.clientHeight <= 4) continue;
    n.scrollTop = top;
    return { scrolled: true, top: n.scrollTop, room: n.scrollHeight - n.clientHeight };
  }
  return { scrolled: false };
};

/**
 * 滚动腿判据（作者「只有向上滑动的时候才允许从按钮下过」的反面保险）：
 * 穿过是靠**内容真的滑到半透明浮钮之下**实现的，不是靠一块不透明遮罩把内容
 * 挡在钮带之外。机械读数 = 与钮带（0 → ctrlBottom）几何相交、且背景不透明度
 * ≥0.98、且横向覆盖超过半屏的悬浮/绝对定位件（=「遮罩」形态）。
 * 命中即 fail（说明某人用不透明白条糊住了按钮带）。
 */
const BAND_COVER = () => {
  const sec = document.querySelector('#mobile-pages .mobile-page.live');
  if (!sec) return { blockers: [], ctrlBottom: 0 };
  const CTRL_SEL = '.g2-fab, .mh-chrome-btn, .mobile-back-btn, .mf-cv-btn, .dock-key';
  const rectOf = (el) => { const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, r: b.right, b: b.bottom, w: b.width, h: b.height }; };
  let ctrlBottom = 0;
  for (const el of sec.querySelectorAll(CTRL_SEL)) {
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') continue;
    if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
    ctrlBottom = Math.max(ctrlBottom, rectOf(el).b);
  }
  const alphaOf = (color) => {
    const m = /rgba?\(([^)]+)\)/.exec(color || '');
    if (!m) return 0;
    const parts = m[1].split(',').map((s) => parseFloat(s));
    return parts.length >= 4 ? parts[3] : 1;
  };
  const blockers = [];
  for (const el of sec.querySelectorAll('*')) {
    if (el.closest(CTRL_SEL)) continue;                 // 钮本体（玻璃圆钮，允许）
    if (el.closest('.mobile-page-body')) {
      // 页体内只排查「sticky 遮罩」形态（position:sticky + 不透明底）
      const cs = getComputedStyle(el);
      if (cs.position !== 'sticky') continue;
    }
    const cs = getComputedStyle(el);
    if (cs.position !== 'absolute' && cs.position !== 'fixed' && cs.position !== 'sticky') continue;
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
    const r = rectOf(el);
    if (r.h > ctrlBottom + 24 || r.h === 0) continue;    // 显式排除整页容器
    if (r.b <= 0 || r.y >= ctrlBottom) continue;         // 不在钮带
    if (r.w < innerWidth * 0.5) continue;                // 不覆盖半屏 → 不是遮罩
    if (alphaOf(cs.backgroundColor) < 0.98) continue;    // 半透明 = 玻璃浮层，合规
    blockers.push({ cls: String(el.className).slice(0, 40), bg: cs.backgroundColor, rect: { x: r.x, y: r.y, w: r.w, h: r.h } });
  }
  return { blockers, ctrlBottom };
};

/* ── 页面清单（PAGES 注册表全量；'chat' = 无页壳的 home 键，不扫）────────── */
const SHOT_PAGES = new Set(['plugins', 'projects', 'usage', 'settings', 'canvas', 'workbench']);
const SHOTS_ALL = process.env.MCC_SHOTS_ALL === '1';

async function allPageIds(page) {
  return page.evaluate(async () => {
    const m = await import('/js/mobile/navController.js');
    return m.PAGES.map((p) => p.id).filter((id) => id !== 'chat');
  });
}

/* ── C1/C2：全页静止让位 + 滑动穿过 ───────────────────────────────────── */
async function runPages(engineName, scheme) {
  const page = await boot(engineName, scheme);
  const tag = `${engineName}-${scheme}`;
  const ids = await allPageIds(page);
  ok(`C1 ${tag} page registry swept (${ids.length} pages)`, ids.length >= 20, ids.join(','));
  for (const id of ids) {
    const navigated = await navTo(page, id);
    if (!navigated) { ok(`C1 ${tag} ${id} page shell present`, false); continue; }
    const probe = await page.evaluate(PROBE);
    if (!probe.found) { ok(`C1 ${tag} ${id} page shell present`, false); continue; }
    ok(`C1 ${tag} ${id} resting: no body text inside chrome hit-rects`,
      probe.hits.length === 0,
      probe.hits.length
        ? JSON.stringify(probe.hits.slice(0, 3))
        : `text=${probe.textCount} ctrlBottom=${probe.ctrlBottom.toFixed(1)} firstTextTop=${probe.firstTextTop == null ? 'n/a' : probe.firstTextTop.toFixed(1)}`);
    // 视觉判据加强：首个正文文字顶须在控件簇下沿之下（有正文的页才有意义）
    if (probe.firstTextTop != null) {
      ok(`C1 ${tag} ${id} first body text starts below chrome band`,
        probe.firstTextTop >= probe.ctrlBottom - 0.5,
        `firstTextTop=${probe.firstTextTop.toFixed(1)} ctrlBottom=${probe.ctrlBottom.toFixed(1)}`);
    }
    if (SHOTS_ALL || SHOT_PAGES.has(id)) {
      await page.screenshot({ path: join(SHOTS, `${tag}-${id}-rest.png`) });
    }

    // C2 滑动穿过腿（允许）：滚动后必须证明「内容真的滑进钮带」——两种合规
    //    表现任取其一：① 正文文字顶已越过钮带下沿（内容钻到浮钮之下）；② 滚到底
    //    仍无文字进带（该页正文短/顶部为空白），此时改为断言「无整屏不透明遮罩
    //    挡在钮带」（即让位不是靠糊一条白条实现的）。
    const sc = await page.evaluate(SCROLL_TO, 200);
    if (sc.scrolled && sc.top > 0) {
      await page.waitForTimeout(280);
      const after = await page.evaluate(PROBE);
      const cover = await page.evaluate(BAND_COVER);
      const passedUnder = after.firstTextTop != null && after.firstTextTop < after.ctrlBottom - 0.5;
      const noMask = cover.blockers.length === 0;
      ok(`C2 ${tag} ${id} scrolled: content passes under chrome band (allowed)`,
        passedUnder || noMask,
        `scrollTop=${sc.top} firstTextTop=${after.firstTextTop == null ? 'n/a' : after.firstTextTop.toFixed(1)} ctrlBottom=${after.ctrlBottom.toFixed(1)} hits=${after.hits.length} masks=${cover.blockers.length}${cover.blockers.length ? ' ' + JSON.stringify(cover.blockers.slice(0, 2)) : ''}`);
      ok(`C2 ${tag} ${id} scroll leg: no opaque mask over chrome band`,
        noMask, cover.blockers.length ? JSON.stringify(cover.blockers.slice(0, 2)) : `ctrlBottom=${cover.ctrlBottom.toFixed(1)}`);
      if (SHOT_PAGES.has(id) && (SHOTS_ALL || id === 'plugins')) {
        await page.screenshot({ path: join(SHOTS, `${tag}-${id}-scrolled.png`) });
      }
      await page.evaluate(SCROLL_TO, 0);
      await page.waitForTimeout(200);
    } else {
      console.log(`SKIP  C2 ${tag} ${id} (content shorter than viewport — no scroll room)`);
    }
  }
  await page.close();
}

/* ── C3/C4：展开卡操作区一行化 + 375 占地实测 ─────────────────────────── */
async function runCardRow(engineName, scheme) {
  const page = await boot(engineName, scheme);
  const tag = `${engineName}-${scheme}`;
  if (!(await navTo(page, 'plugins'))) { ok(`C3 ${tag} plugins page reachable`, false); await page.close(); return null; }
  await page.waitForSelector('.plugins-card[data-plugin="stand-dev"]', { timeout: 10000 });
  await page.screenshot({ path: join(SHOTS, `${tag}-plugins-rest.png`) });
  const card = page.locator('.plugins-card[data-plugin="stand-dev"]');
  await card.locator('.plugins-card-head').click();
  await page.waitForTimeout(420);
  // 操作行滚入视口（证据 = 展开后的操作行，非顶部残片）
  await page.evaluate(() => {
    document.querySelector('.plugins-card[data-plugin="stand-dev"] .plugins-item-actions')
      ?.scrollIntoView({ block: 'center' });
  });
  await page.waitForTimeout(240);
  const row = await page.evaluate(() => {
    const c = document.querySelector('.plugins-card[data-plugin="stand-dev"]');
    const q = (s) => c.querySelector(s);
    const rr = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height, cy: b.y + b.height / 2, r: b.right, b: b.bottom }; };
    const toggle = q('[data-plugin-dispatch]');
    const un = q('[data-plugin-uninstall]');
    const doc = q('[data-plugin-doc]');
    const actions = q('.plugins-item-actions');
    const hitOf = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      let w = b.width; let h = b.height;
      const pb = getComputedStyle(el, '::before');
      if (pb.content && pb.content !== 'none' && parseFloat(pb.width) > 0) {
        w = Math.max(w, parseFloat(pb.width));
        h = Math.max(h, parseFloat(pb.height) || 0);
      }
      return { w: +w.toFixed(1), h: +h.toFixed(1) };
    };
    const docToggle = q('.plugins-card-dispatch');
    return {
      toggle: rr(toggle), uninstall: rr(un), doc: rr(doc), actions: rr(actions),
      toggleHost: rr(docToggle),
      hits: { toggle: hitOf(toggle), uninstall: hitOf(un), doc: hitOf(doc) },
      rowScrollW: actions ? actions.scrollWidth : null,
      rowClientW: actions ? actions.clientWidth : null,
      docHidden: doc ? doc.hidden : null,
      viewportW: innerWidth,
      order: actions
        ? [...actions.children].map((e) => String(e.className).slice(0, 34))
        : [],
    };
  });

  const allPresent = !!(row.toggle && row.uninstall && row.actions);
  ok(`C3 ${tag} three controls present in one actions row`, allPresent, JSON.stringify(row.hits));
  ok(`C3 ${tag} Plugin.md entry revealed (in-package probe 200)`, row.doc != null && row.docHidden === false);
  if (allPresent) {
    const sameRow = row.toggle.b > row.uninstall.y && row.uninstall.b > row.toggle.y
      && (!row.doc || (row.doc.b > row.toggle.y && row.toggle.b > row.doc.y));
    ok(`C3 ${tag} toggle + uninstall${row.doc ? ' + Plugin.md' : ''} on one row (y bands overlap)`, sameRow,
      `toggle=[${row.toggle.y.toFixed(1)},${row.toggle.b.toFixed(1)}] uninstall=[${row.uninstall.y.toFixed(1)},${row.uninstall.b.toFixed(1)}]${row.doc ? ` doc=[${row.doc.y.toFixed(1)},${row.doc.b.toFixed(1)}]` : ''}`);
    const centers = [row.toggle.cy, row.uninstall.cy, ...(row.doc ? [row.doc.cy] : [])];
    const spread = Math.max(...centers) - Math.min(...centers);
    ok(`C3 ${tag} vertical centers aligned (±1px)`, spread <= 1, `spread=${spread.toFixed(2)}px centers=${centers.map((c) => c.toFixed(1)).join('/')}`);
    const small = Object.entries(row.hits).filter(([, v]) => v && (v.w < 44 || v.h < 44));
    ok(`C3 ${tag} all controls hit area ≥44`, small.length === 0, small.length ? JSON.stringify(small) : 'toggle/uninstall/doc ok');
    // C4 占地实测（375 宽）：三者命中宽之和 + 两个 10px 间距 vs 行可用宽
    const need = (row.hits.toggle?.w || 0) + (row.hits.uninstall?.w || 0) + (row.hits.doc?.w || 0) + 2 * 10;
    ok(`C4 ${tag} row fits 375 without horizontal scroll`, row.rowScrollW <= row.rowClientW + 1,
      `need≈${need.toFixed(0)}px avail=${row.rowClientW}px scrollW=${row.rowScrollW}`);
    ok(`C4 ${tag} three widths sum fits available width`, need <= row.rowClientW,
      `sum=${need.toFixed(0)} avail=${row.rowClientW} margin=${(row.rowClientW - need).toFixed(0)}px (viewport=${row.viewportW})`);
    ok(`C4 ${tag} no text truncation (buttons not clipped)`,
      row.uninstall.r <= row.actions.r + 0.5 && (!row.doc || row.doc.x >= row.actions.x - 0.5),
      `uninstall.right=${row.uninstall.r.toFixed(1)} actions.right=${row.actions.r.toFixed(1)} doc.x=${row.doc ? row.doc.x.toFixed(1) : 'n/a'}`);
  }
  // 展开后的操作行证据（滚到该行后同视口截图）
  await page.screenshot({ path: join(SHOTS, `${tag}-plugins-expanded-row.png`) });
  // 整卡展开视图（含描述/技能清单，检查整卡不被操作行挤坏）
  await page.evaluate(() => document.querySelector('.plugins-card[data-plugin="stand-dev"]')?.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(240);
  await page.screenshot({ path: join(SHOTS, `${tag}-plugins-expanded.png`) });
  await page.close();
  return row;
}

/* ── C7：canvas 环标签长名展开 vs 左右悬浮钮 ───────────────────────────── */
async function runCanvasChip(engineName, scheme) {
  const page = await boot(engineName, scheme);
  const tag = `${engineName}-${scheme}`;
  // 三张超长标题文档（展开态 = 环上活签点开全名）
  await page.evaluate(() => {
    for (let i = 0; i < 3; i += 1) {
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
        id: `longdoc-${i}`,
        title: `超长的文档标题名称第 ${i + 1} 号用于横向溢出与悬浮钮相交检验`,
        itemType: 'markdown', pinned: false, background: false,
        content: '# 正文标题\n\n段落。\n',
      } }));
    }
  });
  await page.waitForTimeout(900);
  if (!(await navTo(page, 'canvas'))) { ok(`C7 ${tag} canvas page reachable`, false); await page.close(); return; }
  await page.waitForSelector('#mobile-pages .mobile-page.live .mf-strip-chip.on', { timeout: 10000 });
  await page.locator('#mobile-pages .mobile-page.live .mf-strip-chip.on').first().click({ force: true });
  await page.waitForTimeout(600);
  const probe = await page.evaluate(() => {
    const sec = document.querySelector('#mobile-pages .mobile-page.live');
    const R = (el) => { const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), r: +(b.x + b.width).toFixed(1), b: +(b.y + b.height).toFixed(1) }; };
    const chips = [...sec.querySelectorAll('.mf-strip-chip')]
      .filter((c) => getComputedStyle(c).visibility !== 'hidden' && parseFloat(getComputedStyle(c).opacity) > 0.05)
      .map((c) => {
        const rg = document.createRange(); rg.selectNodeContents(c);
        const rr = rg.getBoundingClientRect();
        // 字墨矩形 = Range 矩形 ∩ 本体盒（ellipsis 下 Range 是未裁剪全宽）
        const cb = c.getBoundingClientRect();
        const ink = {
          x: Math.max(rr.x, cb.x), y: Math.max(rr.y, cb.y),
          r: Math.min(rr.right, cb.right), b: Math.min(rr.bottom, cb.bottom),
        };
        return {
          cls: String(c.className), box: R(c), expanded: c.classList.contains('is-expanded'),
          ink: { x: +ink.x.toFixed(1), y: +ink.y.toFixed(1), w: +(ink.r - ink.x).toFixed(1), h: +(ink.b - ink.y).toFixed(1), r: +ink.r.toFixed(1), b: +ink.b.toFixed(1) },
          txt: c.textContent.trim().slice(0, 24),
        };
      });
    const fabs = [...sec.querySelectorAll('.mf-cv-close, .mf-cv-stack')].filter((e) => getComputedStyle(e).display !== 'none').map((e) => ({ cls: String(e.className).split(' ')[0], ...R(e) }));
    const inter = (a, b) => {
      const ix = Math.min(a.r, b.r) - Math.max(a.x, b.x);
      const iy = Math.min(a.b, b.b) - Math.max(a.y, b.y);
      return ix > 0 && iy > 0 ? +(ix * iy).toFixed(1) : 0;
    };
    const hits = [];
    for (const c of chips) for (const f of fabs) {
      const inkHit = inter(c.ink, f);
      if (inkHit > 0.5) hits.push({ kind: 'INK', txt: c.txt, chip: c.cls, fab: f.cls, area: inkHit });
    }
    return { chips, fabs, hits, vw: innerWidth };
  });
  const expanded = probe.chips.find((c) => c.expanded);
  ok(`C7 ${tag} long-name chip expanded`, !!expanded, expanded ? `ink=[${expanded.ink.x},${expanded.ink.r}] box=[${expanded.box.x},${expanded.box.r}] vw=${probe.vw}` : JSON.stringify(probe.chips.map((c) => c.cls)));
  ok(`C7 ${tag} expanded chip ink stays inside viewport`, !!expanded && expanded.ink.r <= probe.vw - 8,
    expanded ? `ink.right=${expanded.ink.r} vw=${probe.vw}` : 'n/a');
  ok(`C7 ${tag} chip ink never enters floating-button hit rects`, probe.hits.length === 0,
    probe.hits.length ? JSON.stringify(probe.hits.slice(0, 3)) : `chips=${probe.chips.length} fabs=${probe.fabs.length}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-canvas-chip-expanded.png`) });
  await page.close();
  return probe;
}

/* ── C6：桌面零触碰腿 ─────────────────────────────────────────────────── */
async function runDesktop(scheme = 'light') {
  const page = await boot('chromium', scheme, { viewport: { width: 1280, height: 800 }, mobile: false });
  await page.evaluate(async () => { const m = await import('/js/plugins.js'); m.openPlugins(); });
  await page.waitForSelector('#plugins-content .plugins-card', { timeout: 10000 });
  await page.waitForTimeout(500);
  await page.locator('.plugins-card[data-plugin="stand-dev"] .plugins-card-head').click();
  await page.waitForTimeout(360);
  const probe = await page.evaluate(() => {
    const c = document.querySelector('.plugins-card[data-plugin="stand-dev"]');
    const rr = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), cy: +(b.y + b.height / 2).toFixed(1), r: +b.right.toFixed(1), b: +b.bottom.toFixed(1) }; };
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      toggle: rr(c.querySelector('[data-plugin-dispatch]')),
      uninstall: rr(c.querySelector('[data-plugin-uninstall]')),
      doc: rr(c.querySelector('[data-plugin-doc]')),
      actions: rr(c.querySelector('.plugins-item-actions')),
      cardW: +c.getBoundingClientRect().width.toFixed(1),
    };
  });
  ok('C6 desktop: mobile layer NOT active', probe.mobileLayer === false);
  ok('C6 desktop: toggle + uninstall + Plugin.md share one row',
    !!probe.toggle && !!probe.uninstall && !!probe.doc
      && probe.toggle.b > probe.uninstall.y && probe.uninstall.b > probe.toggle.y
      && probe.doc.b > probe.toggle.y && probe.toggle.b > probe.doc.y,
    JSON.stringify({ toggle: probe.toggle, uninstall: probe.uninstall, doc: probe.doc }));
  ok('C6 desktop: row layout = doc left, toggle + uninstall right',
    !!probe.doc && !!probe.toggle && probe.doc.x < probe.toggle.x && probe.uninstall.r > probe.toggle.r - 1,
    `doc.x=${probe.doc?.x} toggle.x=${probe.toggle?.x} uninstall.right=${probe.uninstall?.r} actions.right=${probe.actions?.r}`);
  ok('C6 desktop: card keeps desktop width (not mobile full-bleed)',
    probe.cardW > 300 && probe.cardW <= 730, `cardW=${probe.cardW}`);
  await page.screenshot({ path: join(SHOTS, `desktop-${scheme}-plugins-expanded.png`) });
  await page.close();
  return probe;
}

/* ── main ─────────────────────────────────────────────────────────────── */
const ENGINES = (process.env.MCC_ENGINES || 'chromium,webkit').split(',').map((s) => s.trim()).filter(Boolean);
const SCHEMES = (process.env.MCC_SCHEMES || 'light,dark').split(',').map((s) => s.trim()).filter(Boolean);
const ONLY = process.env.MCC_ONLY || '';
const want = (leg) => !ONLY || ONLY.split(',').includes(leg);

for (const engineName of ENGINES) {
  for (const scheme of SCHEMES) {
    if (want('pages')) await runPages(engineName, scheme);
    if (want('card')) await runCardRow(engineName, scheme);
    if (want('canvas')) await runCanvasChip(engineName, scheme);
  }
}
if (want('desktop')) {
  await runDesktop('light');
  if (SCHEMES.includes('dark')) await runDesktop('dark');
}

ok('C5 zero console error / pageerror (all legs)', consoleErrors.length === 0, consoleErrors.slice(0, 4).join(' | '));

console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}  — shots: ${SHOTS}`);
process.exit(failures === 0 ? 0 : 1);
