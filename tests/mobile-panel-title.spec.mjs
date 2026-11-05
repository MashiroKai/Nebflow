#!/usr/bin/env node
// mobile-panel-title.spec.mjs — 面板头两段式标题批验收（作者令 2026-11-02，实拍
// IMG_1677「设置」/ IMG_1678「项目」）：B 批「iOS 大标题式」两态（左对齐大标题 +
// 小字渐隐带）升级为**两段式**：
//   静止态 = 页体流内**水平居中**大标题（28px/600）；吸附态 = 同页名的装饰副本
//   收成一颗**毛玻璃胶囊**（高 48、左缘 72、垂直中心 40），绝对悬浮、不占高度；
//   两段之间 = 滚动跟随的连续插值 --mtitle-p 0→1（区间 8→56），回滚反向展开。
//   顶缘渐隐带 .mobile-page-title-band 整族退役（DOM 不再建）。
//
// ⚠ 面板头吸附胶囊高度批（2026-11-02 作者令 09:33「面板的进入胶囊状态的胶囊大小
//   要和返回按钮上下宽度差不多大」）：胶囊高 32 → 48 = 返回钮高，垂直中心仍 40，
//   左右内距 16 → 24（= 新半径 h/2）。本 spec 的 32 档读数随之改 48（改动逐条见
//   P1/P2/P3 注释与 docs/20261102_title-capsule-height__<chain>.md）；宽度策略、
//   大标题、插值区间、内容让位、返回钮全部不变。
//
// 覆盖（双引擎 Chromium/WebKit × 双主题 light/dark × 3 面板 × 三态）：
//   P1 静止态：大标题 = 页体流内首件（static）、水平居中（text-align center +
//      左右内缩 72）、28px/600、字距负档、墨色 = --color-frame-text-bright、
//      opacity 1；盒高 52、盒顶 12、盒底 64 = 返回钮底缘、文字中心 = 钮中心 40；
//      胶囊 opacity 0、几何恒 48 高；渐隐带节点零；「<」返回钮在场。
//   P2 阈值 + 插值：主滚轴 scrollTop 40（区间内）⇒ p≈0.667、不切 .rolled、
//      大标题 opacity = 1−p、胶囊 opacity = p；scrollTop 300 ⇒ p=1、.rolled 成立、
//      大标题收高（pane 收高 leg：h=0）、胶囊满透明且几何仍 48/中心 40/左缘 ≥72、
//      返回钮仍可点（z 序 6 > 胶囊 5 / 胶囊 pointer-events none）。
//   P3 真滚轮回顶：mouse.wheel 上滚到顶 ⇒ p=0、.rolled 消失、大标题复现。
//   P4 面板切换对账：A 面板吸附态离场 → 进 B 面板 ⇒ B 静止态（p=0，不继承吸附）。
//   P5 reduced-motion：进度二值（只有 0/1），且大标题无位移量。
//   P6 胶囊几何七条机械断言（胶囊高度批）：①高差 ≤2 ②中心偏差 ≤1 ③水平缝 8±1
//      ④宽度随内容收缩（< vw×0.9）+ 圆角 999 ⑤文字不被裁 ⑥内容从胶囊下方流过
//      ⑦19 页壳一致（短/中/长页名各档）。
//   P7 截图存证：375×812 每面板 静止/吸附中/回顶 三拍（引擎×主题×面板 = 36 张）。
//   P8 零 console error，P9 桌面零触碰腿。
//
// 数据面 = 自包含 harness（route 静态 + WS 夹具 + /api 夹具），零后端、零 sbt。
// Run: node tests/mobile-panel-title.spec.mjs
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const OUT = process.env.MPT_OUT_DIR || join(HERE, '..', 'evidence', `${TS}_mobile-panel-title`);
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mobile-panel-title-root';
// 端口 8899：WebKit bad-port 封禁表外（pop-footer-family spec 同款实证）。
const ORIGIN = 'http://localhost:8899';
// 视口 = 验收口径 375×812（作者实拍机型档）；--mtitle-inset 72 与之无关（固定档）。
const VW = 375;
const VH = 812;
// 阈值/几何契约（与 mobileLayer.css token 块、navController.js 探针常量同源）。
const ROLL_START = 8;
const ROLL_SNAP = 56;
const INSET = 72;
// 胶囊高度批 2026-11-02：32 → 48（= 返回钮高；作者令「胶囊大小要和返回按钮上下
// 宽度差不多大」）。中心 40 不动（高度只向上下对称生长）。
const CAP_H = 48;
const CAP_CENTER_Y = 40;
// 返回钮几何（--mcap-h 的取值基准）：left 16 / 48×48 ⇒ 右缘 64、中心 40。
const BACK_LEFT = 16;
const BACK_SIZE = 48;
const BACK_RIGHT = BACK_LEFT + BACK_SIZE; // 64
const CAP_GAP = 8; // 胶囊左缘 72 − 返回钮右缘 64
const CAP_PAD = 24; // --mcap-pad（= 新半径 h/2 = 24）

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const near = (a, b, tol = 1) => Math.abs(Number(a) - Number(b)) <= tol;

// Console/pageerror 硬零（噪声过滤 = pop-footer 同表）。
const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script|ResizeObserver loop completed/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => {
    if (!NOISE.test(e.message)) consoleErrors.push(`pageerror: ${e.message}`);
  });
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`);
  });
};

/* ── 夹具：项目 12 行（projects 页滚动域）＋ 待办 24 行（todo 页滚动域；
   行数取 24 = WebKit 行高更矮时仍保证主滚轴余量 > 吸附阈值 56px，否则
   scrollTop 被钳在 40 以内、进度永远到不了 1（实测账本在批文档））── */
const PROJECTS = Array.from({ length: 12 }, (_, i) => ({
  name: `fixture-project-${String(i + 1).padStart(2, '0')}`,
  workspace: `/tmp/mpt/proj-${i + 1}`,
  agentFile: '',
  description: i % 2 === 0 ? '验收夹具项目（滚动域填充行）' : '',
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
const TODOS = Array.from({ length: 24 }, (_, i) => ({
  id: `mpt-td-${i + 1}`,
  kind: 'todo',
  content: `验收夹具待办 ${i + 1}：滚动收缩域填充行`,
  triggerAt: Date.now() + 3600_000,
}));

/* ── boot（route 静态 + WS 夹具 + /api 夹具；375×812 手机视口）────────── */
async function boot(scheme, engineName, opts = {}) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  const ctx = await engine.launch().then((b) => b.newContext({
    viewport: { width: VW, height: VH },
    colorScheme: scheme,
    deviceScaleFactor: 2,
    hasTouch: true,
    locale: 'zh-CN',
    reducedMotion: opts.reducedMotion ? 'reduce' : 'no-preference',
  }));
  const page = await ctx.newPage();
  watchErrors(page);
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
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') {
        const scope = m.scope === 'all' ? 'all' : 'session';
        ws.send(JSON.stringify({ type: 'scheduledTaskList', scope, tasks: TODOS.map((t) => ({ ...t })) }));
      }
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.waitForTimeout(600);
  return page;
}

/* ── 导航（真实 DOM 点击路径）────────────────────────────────────────── */
async function gotoWorkbench(page) {
  await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page.live');
    if (sec) sec.querySelectorAll('.mobile-back-btn').forEach((b) => b.click());
  });
  for (let i = 0; i < 6; i += 1) {
    const onHome = await page.evaluate(() => !document.querySelector('#mobile-pages .mobile-page.live[data-page]:not([data-page="messages"]):not([data-page="contacts"]):not([data-page="canvas"]):not([data-page="workbench"])'));
    if (onHome) break;
    await page.waitForTimeout(120);
  }
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="workbench"] .mf-hub-rows', { timeout: 10000 });
  await page.waitForTimeout(200);
}

async function openPage(page, hubPage, liveSelector) {
  await gotoWorkbench(page);
  await page.locator(`#mobile-pages .mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${hubPage}"]`).click();
  await page.waitForSelector(liveSelector, { timeout: 15000 });
  await page.waitForTimeout(450); // 面装配/图标渲染收敛
}

// ⛔ 【退役】入口收敛批 todo-unify-entry-collapse 2026-11-04：#todo-page 整面退役
// （DOM 保留、不可达）⇒ 原「nf:mobile-todo-entry → pushPage('todo')」下钻腿失效。
// 待办面的现役形态 = 顶带胶囊 + 展开面板（js/todoFace.js），它不是页面栈成员、
// 没有页面壳标题 ⇒ 本 spec（页面壳标题吸附几何）对该面无适用对象，腿随之退役；
// 函数体留档不删（恢复入口即可复用）：
// async function openTodo(page) {
//   await gotoWorkbench(page);
//   await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:mobile-todo-entry')));
//   await page.waitForSelector('#mobile-pages .mobile-page.live[data-page="todo"] #todo-page .reminder-row', { timeout: 15000 });
//   await page.waitForTimeout(450);
// }

/* ── 探针：读页面壳标题态 ───────────────────────────────────────────────
   标题单元素化批 2026-11-02 22:32（作者令「一直是同一个的形式」）：吸附态装饰
   副本 .mobile-page-title-mini **退役**，吸附胶囊 = .mobile-page-title 自身
   （壳层 = 它的 ::before）。故本探针的胶囊几何全部改探同一元素：
   静止态（p=0）几何 = 大标题档、吸附态（p=1）几何 = 胶囊档，全程同一个盒；
   「胶囊」前缀的字段名保留为兼容读者，语义 = 该元素在当前进度的盒几何。 */
async function probe(page, pageId) {
  return page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    if (!sec) return { found: false };
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const title = sec.querySelector('.mobile-page-title');
    const back = sec.querySelector('.mobile-back-btn');
    if (!title) return { found: false, why: 'title missing' };
    const tb = title.getBoundingClientRect();
    const bb = back ? back.getBoundingClientRect() : null;
    const tokenProbe = document.createElement('span');
    tokenProbe.style.color = 'var(--color-frame-text-bright)';
    document.body.appendChild(tokenProbe);
    const ink = getComputedStyle(tokenProbe).color;
    tokenProbe.remove();
    const t = cs(title);
    const shellCs = cs(title) && getComputedStyle(title, '::before');
    const bs = cs(back);
    const pv = parseFloat(t.getPropertyValue('--mtitle-p')) || 0;
    return {
      found: true,
      p: Math.round(pv * 1000) / 1000,
      rolled: sec.classList.contains('rolled'),
      paneScroll: sec.classList.contains('mpt-pane-scroll'),
      titleText: title.textContent || '',
      titlePos: t.position,
      titleIsBodyFirst: sec.querySelector('.mobile-page-body')?.firstElementChild === title,
      titleAlign: t.textAlign,
      titlePadL: t.paddingLeft,
      titlePadR: t.paddingRight,
      titleFontSize: t.fontSize,
      titleWeight: t.fontWeight,
      titleSpacing: parseFloat(t.letterSpacing),
      titleColor: t.color,
      ink,
      titleH: Math.round(tb.height),
      titleTop: Math.round(tb.top),
      titleBottom: Math.round(tb.bottom),
      // 流内占位（单元素化批 2026-11-02）：盒高 + 上下外边距 —— 旧两段式的
      // 「大标题收高 → 0」在本形态里由 margin 对承担，盒本身恒 48–52 不塌。
      titleMarginTop: Math.round(parseFloat(t.marginTop)),
      titleMarginBottom: Math.round(parseFloat(t.marginBottom)),
      titleFootprint: Math.round(tb.height + parseFloat(t.marginTop) + parseFloat(t.marginBottom)),
      // 文字行盒中心（盒顶 + 上内距 + 行高/2）= 应与返回钮中心同轴 40；
      // 盒几何中心则是 12 + 52/2 = 38（盒内上下内距不对称 10/6 的直接结果）。
      titleTextCenterY: Math.round(tb.top + parseFloat(t.paddingTop) + parseFloat(t.lineHeight) / 2),
      titleOpacity: Math.round(parseFloat(t.opacity) * 1000) / 1000,
      titleTransform: t.transform,
      titlePE: t.pointerEvents,
      // ── 以下「胶囊」面读数 = 同一元素（.mobile-page-title）在当前进度的盒几何
      //    与壳层（::before）读数（标题单元素化批 2026-11-02）。
      shellOpacity: Math.round(parseFloat(shellCs.opacity) * 1000) / 1000,
      shellRadius: shellCs.borderTopLeftRadius,
      shellInset: `${shellCs.top}/${shellCs.right}/${shellCs.bottom}/${shellCs.left}`,
      miniOpacity: Math.round(parseFloat(shellCs.opacity) * 1000) / 1000,
      miniH: Math.round(tb.height),
      miniHExact: Math.round(tb.height * 100) / 100,
      miniW: Math.round(tb.width * 100) / 100,
      miniPadL: t.paddingLeft,
      miniPadR: t.paddingRight,
      miniLineHeight: t.lineHeight,
      miniText: title.textContent || '',
      miniScrollW: title.scrollWidth,
      miniClientW: title.clientWidth,
      miniCenterY: Math.round(tb.top + tb.height / 2),
      miniCenterYExact: Math.round((tb.top + tb.height / 2) * 100) / 100,
      miniLeft: Math.round(tb.left),
      miniLeftExact: Math.round(tb.left * 100) / 100,
      miniRight: Math.round(tb.right),
      miniCenterX: Math.round(tb.left + tb.width / 2),
      miniPE: t.pointerEvents,
      miniZ: t.zIndex,
      miniFS: t.fontSize,
      // 壳层玻璃材质（伪元素 computed backdropFilter 不可读 ⇒ 取玻璃包 token 单源）
      miniBg: shellCs.backgroundColor,
      miniBlur: (shellCs.backdropFilter || shellCs.webkitBackdropFilter || '')
        || `blur(${getComputedStyle(document.body).getPropertyValue('--cv-blur').trim()})`,
      miniRadius: shellCs.borderTopLeftRadius || t.borderTopLeftRadius,
      miniIsSingleElement: !sec.querySelector('.mobile-page-title-mini'),
      // 文字实际渲染宽度（Range 量文本节点，不受 padding 影响）。
      miniTextW: (() => {
        const r = document.createRange();
        r.selectNodeContents(title);
        const rects = [...r.getClientRects()];
        if (!rects.length) return 0;
        return Math.round((Math.max(...rects.map((x) => x.right)) - Math.min(...rects.map((x) => x.left))) * 100) / 100;
      })(),
      backW: bb ? Math.round(bb.width * 100) / 100 : -1,
      backH: bb ? Math.round(bb.height * 100) / 100 : -1,
      backLeft: bb ? Math.round(bb.left * 100) / 100 : -1,
      backRight: bb ? Math.round(bb.right * 100) / 100 : -1,
      backTop: bb ? Math.round(bb.top * 100) / 100 : -1,
      backBottom: bb ? Math.round(bb.bottom) : -1,
      backCenterY: bb ? Math.round(bb.top + bb.height / 2) : -1,
      backCenterYExact: bb ? Math.round((bb.top + bb.height / 2) * 100) / 100 : -1,
      backZ: bs ? bs.zIndex : '',
      backPE: bs ? bs.pointerEvents : '',
      backVisible: !!back && bs.display !== 'none',
      bandCount: sec.querySelectorAll('.mobile-page-title-band').length,
      // ⑥ 内容仍从胶囊下方流过：吸附态下把页体内容件的渲染盒与胶囊盒求交。
      contentOverlapsCapsule: (() => {
        const bodyEl = sec.querySelector('.mobile-page-body');
        if (!bodyEl) return { any: false, n: 0, sample: '' };
        let n = 0; let sample = '';
        for (const el of bodyEl.querySelectorAll('*')) {
          if (el.classList.contains('mobile-page-title')) continue;
          if (el.closest('.mobile-page-title')) continue;
          const r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) continue;
          const inter = !(r.right <= tb.left || r.left >= tb.right || r.bottom <= tb.top || r.top >= tb.bottom);
          if (inter) { n += 1; if (!sample) sample = `${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]}`; }
        }
        return { any: n > 0, n, sample };
      })(),
      vw: window.innerWidth,
    };
  }, pageId);
}

/** 主滚轴（判定同 navController.refreshRoll：页体子树内**最外层**有余量的纵向
 *  滚动器——按距 section 的层级取最小者，把嵌套小滚区排除；页体自身可滚即它）。 */
const PRIMARY_SEL_JS = `
  (sec) => {
    const bodyEl = sec.querySelector('.mobile-page-body');
    const depthOf = (n) => { let d = 0; for (let p = n; p && p !== sec; p = p.parentElement) d += 1; return d; };
    let primary = null; let best = Infinity;
    for (const n of [bodyEl, ...bodyEl.querySelectorAll('*')]) {
      const oy = getComputedStyle(n).overflowY;
      if (oy !== 'auto' && oy !== 'scroll') continue;
      if (n.scrollHeight - n.clientHeight <= 4) continue;
      const d = depthOf(n);
      if (d < best) { primary = n; best = d; }
      if (d === 1) break; // 页体自身可滚 ⇒ 它必是最外层
    }
    return primary || bodyEl;
  }`;

/** 主滚轴 scrollTop 精调 + 滚动事件派发（阈值/插值腿）。 */
async function setScroll(page, pageId, value) {
  const res = await page.evaluate(({ pid, v, selSrc }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const target = eval(selSrc)(sec);
    target.scrollTop = v;
    target.dispatchEvent(new Event('scroll', { bubbles: false }));
    return { tag: target.className || target.id || target.tagName, top: target.scrollTop };
  }, { pid: pageId, v: value, selSrc: PRIMARY_SEL_JS });
  await page.waitForTimeout(120);
  return res;
}

/** 主滚轴读数（滚轮腿的前后态核对）。 */
async function readScrollTop(page, pageId) {
  return page.evaluate(({ pid, selSrc }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const target = eval(selSrc)(sec);
    return { tag: target.className || target.id || target.tagName, top: target.scrollTop };
  }, { pid: pageId, selSrc: PRIMARY_SEL_JS });
}

/** 期望进度（区间 8→56 线性钳死）。 */
const expectP = (st) => {
  const p = (st - ROLL_START) / (ROLL_SNAP - ROLL_START);
  return Math.round(Math.max(0, Math.min(1, p)) * 1000) / 1000;
};

/* ── 单面板全腿 ──────────────────────────────────────────────────────── */
async function panelLeg(page, tag, pageId, hoverSel) {
  const p0 = await probe(page, pageId);
  ok(`${tag} 页面壳在场`, p0.found);
  if (!p0.found) return;

  // ── P1 静止态 ──
  ok(`${tag} 静止态 .rolled 不在场`, !p0.rolled);
  ok(`${tag} 静止态进度 p = 0`, p0.p === 0, `p=${p0.p}`);
  ok(`${tag} 大标题文案 = 面板现名`, p0.titleText.length > 0, `text="${p0.titleText}"`);
  // 标题单元素化批 2026-11-02：载体不再是「流内 static 大标题 + 绝对副本」，而是
  // **同一个元素 sticky 原地收缩**（iOS 大标题语义：页体自滚型页面标题不随滚走）。
  ok(`${tag} 大标题钉在原位（position:sticky）、仍是页体首件`, p0.titlePos === 'sticky' && p0.titleIsBodyFirst, `${p0.titlePos}/${p0.titleIsBodyFirst}`);
  ok(`${tag} 大标题水平居中（text-align center）`, p0.titleAlign === 'center', p0.titleAlign);
  ok(`${tag} 大标题左右内缩各 72px（--mtitle-inset）`, p0.titlePadL === `${INSET}px` && p0.titlePadR === `${INSET}px`, `${p0.titlePadL}/${p0.titlePadR}`);
  ok(`${tag} 大标题 28px/600（作者令破档档位保留）`, p0.titleFontSize === '28px' && p0.titleWeight === '600', `${p0.titleFontSize}/${p0.titleWeight}`);
  ok(`${tag} 大标题字距负档（-0.01em）`, p0.titleSpacing < 0, `got ${p0.titleSpacing}px`);
  ok(`${tag} 大标题墨色 = --color-frame-text-bright`, p0.titleColor === p0.ink, `got ${p0.titleColor} / token ${p0.ink}`);
  ok(`${tag} 大标题可见（opacity 1）`, near(p0.titleOpacity, 1, 0.01), `got ${p0.titleOpacity}`);
  ok(`${tag} 大标题盒高 52（防裁字）`, p0.titleH === 52, `h=${p0.titleH}`);
  ok(`${tag} 大标题盒顶 12（页体顶呼吸缝）`, p0.titleTop === 12, `top=${p0.titleTop}`);
  ok(`${tag} 大标题盒底 64 = 返回钮底缘`, p0.titleBottom === 64 && p0.backBottom === 64, `title=${p0.titleBottom} back=${p0.backBottom}`);
  ok(`${tag} 大标题文字中心 = 返回钮中心（40，同轴）`, p0.titleTextCenterY === CAP_CENTER_Y && p0.backCenterY === CAP_CENTER_Y, `text=${p0.titleTextCenterY} back=${p0.backCenterY}`);
  // ── 单元素契约（标题单元素化批 2026-11-02 22:32 作者令「一直是同一个的形式」）：
  //    吸附胶囊 = 同一元素的 ::before 壳层，故静止态壳层透明、盒仍是**大标题档**。
  ok(`${tag} 静止态壳层（同一元素 ::before）opacity 0`, p0.shellOpacity === 0, `got ${p0.shellOpacity}`);
  ok(`${tag} 同一元素即胶囊载体（页内零 .mobile-page-title-mini 装饰副本）`, p0.miniIsSingleElement === true);
  ok(`${tag} 静止态盒高 52（大标题档，不提前塌成 48）`, p0.miniH === 52, `h=${p0.miniH}`);
  // 静止态盒内上下内距不对称（10/6）⇒ **盒几何中心 38**，而同轴契约钉的是
  // **文字行盒中心 40**（= 返回钮中心）；吸附态垫钉 1px 后盒中心也归 40。
  ok(`${tag} 静止态文字中心 40（= 返回钮中心；盒几何中心 38 是内距不对称的结果）`,
    p0.titleTextCenterY === CAP_CENTER_Y, `text=${p0.titleTextCenterY} box=${p0.miniCenterY}`);
  ok(`${tag} 静止态横向居中（页面中轴）`, near(p0.miniCenterX, p0.vw / 2, 1), `cx=${p0.miniCenterX} vw=${p0.vw}`);
  ok(`${tag} 胶囊右缘不越返回钮禁区（左缘 ≥ 72）`, p0.miniLeft >= INSET - 1, `left=${p0.miniLeft}`);
  ok(`${tag} 壳层全圆角 999 + 毛玻璃材质（玻璃包 --cv-blur 单源）`, p0.miniRadius === '999px' && /blur/.test(p0.miniBlur), `${p0.miniRadius} ${p0.miniBlur.slice(0, 30)}`);
  ok(`${tag} 静止态文字 28px（未缩）`, p0.miniFS === '28px', p0.miniFS);
  ok(`${tag} 静止态标题接事件（pointer-events auto，未 rolled）`, p0.miniPE === 'auto', p0.miniPE);
  ok(`${tag} 渐隐带节点退役（.mobile-page-title-band 计数 0）`, p0.bandCount === 0, `count=${p0.bandCount}`);
  ok(`${tag} 「<」返回钮在场且可点`, p0.backVisible && p0.backPE !== 'none', `pe=${p0.backPE}`);

  // 面内同名双标题退役（B 批配套，两段式批沿用）。
  const dups = await page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const found = [];
    ['.todo-page-title', '#usage-title', '#settings-modal-title', '#daemon-panel-title'].forEach((s) => {
      sec.querySelectorAll(s).forEach((el) => found.push({ sel: s, display: getComputedStyle(el).display }));
    });
    return found;
  }, pageId);
  for (const d of dups) ok(`${tag} 面内同名标题退役（${d.sel}）`, d.display === 'none', d.display);

  await page.screenshot({ path: join(SHOTS, `${tag}-rest.png`) });

  // ── P2 阈值 + 连续插值 ──
  const s40 = await setScroll(page, pageId, 40);
  const mid = await probe(page, pageId);
  // 期望进度按**实测**主滚轴 scrollTop 换算（WebKit 会把 scrollTop 吸附到整数/
  // 设备像素档，写 40 可能读 39 —— 断言口径以实测值为准，仍钉死插值公式）。
  const a40 = await readScrollTop(page, pageId);
  const p40 = expectP(a40.top);
  const range = await page.evaluate(({ pid, selSrc }) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const t = eval(selSrc)(sec);
    return { max: t.scrollHeight - t.clientHeight };
  }, { pid: pageId, selSrc: PRIMARY_SEL_JS });
  ok(`${tag} 主滚轴余量 > 吸附阈值（夹具充足，实测 max=${range.max}）`, range.max > ROLL_SNAP + 40, `max=${range.max}`);
  ok(`${tag} 区间内（scrollTop ${a40.top}）进度 = ${p40}`, near(mid.p, p40, 0.02), `got p=${mid.p} @${s40.tag}`);
  ok(`${tag} 区间内不切 .rolled（${a40.top} < 56）`, !mid.rolled);
  ok(`${tag} 区间内文字始终可见（opacity 恒 1，无淡出交接）`, near(mid.titleOpacity, 1, 0.01), `got ${mid.titleOpacity}`);
  ok(`${tag} 区间内壳层 opacity = p（轮廓连续显影）`, near(mid.miniOpacity, p40, 0.02), `got ${mid.miniOpacity}`);
  // 区间内几何在 52→48 / 28→15 的插值中（单元素连续变形，非两套几何交接）。
  ok(`${tag} 区间内盒高在 52→48 插值区间内（单元素变形，无第二套几何）`,
    mid.miniH >= CAP_H - 0.5 && mid.miniH <= 52 + 0.5, `h=${mid.miniH}`);
  ok(`${tag} 区间内字号在 28→15 插值区间内（同元素缩字）`,
    parseFloat(mid.miniFS) < 28 && parseFloat(mid.miniFS) > 15, mid.miniFS);
  ok(`${tag} 区间内文字中心恒 40（插值不漂）`, near(mid.miniCenterY, CAP_CENTER_Y, 1), `cy=${mid.miniCenterY}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-mid.png`) });

  const s300 = await setScroll(page, pageId, 300);
  const top = await probe(page, pageId);
  ok(`${tag} 阈值上（scrollTop 300）进度 = 1`, top.p === 1, `got p=${top.p} @${s300.tag}`);
  ok(`${tag} 阈值上切 .rolled`, top.rolled);
  ok(`${tag} 吸附态壳层 opacity 1（胶囊轮廓满显影）`, near(top.miniOpacity, 1, 0.01), `got ${top.miniOpacity}`);
  ok(`${tag} 吸附态文字仍可见（opacity 1，同元素未淡出）`, near(top.titleOpacity, 1, 0.01), `got ${top.titleOpacity}`);
  ok(`${tag} 吸附态不拦交互（pointer-events none，返回钮可点）`, top.titlePE === 'none', top.titlePE);
  ok(`${tag} 吸附态顶区零占位（流内 footprint → 0，盒本身仍 48）`,
    top.titleFootprint === 0 && top.titleH === CAP_H, `footprint=${top.titleFootprint} h=${top.titleH}`);
  ok(`${tag} 吸附态胶囊几何 = 48 高 / 中心 40 / 左缘 ≥72`, top.miniH === CAP_H && top.miniCenterY === CAP_CENTER_Y && top.miniLeft >= INSET - 1, `${top.miniH}/${top.miniCenterY}/${top.miniLeft}`);
  ok(`${tag} 吸附态字号 15px（缩字终点，≥ HIG 13px 下限）`, top.miniFS === '15px' && parseFloat(top.miniFS) >= 13, top.miniFS);
  ok(`${tag} 吸附态胶囊仍横向居中`, near(top.miniCenterX, top.vw / 2, 1), `cx=${top.miniCenterX}`);
  ok(`${tag} 吸附态返回钮仍在胶囊左侧且可点（z 6 > 5）`, top.backVisible && Number(top.backZ) > Number(top.miniZ), `backZ=${top.backZ} miniZ=${top.miniZ}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-snapped.png`) });

  // ── 胶囊几何七条机械断言（胶囊高度批 2026-11-02，吸附态实测面）──
  // ① 高差：胶囊渲染高 vs 返回钮渲染高 ≤2px。
  ok(`${tag} ① 胶囊高 ↔ 返回钮高 |差| ≤2px（实测 胶囊 ${top.miniHExact} / 钮 ${top.backH}）`,
    Math.abs(top.miniHExact - top.backH) <= 2, `cap=${top.miniHExact} back=${top.backH}`);
  // ② 垂直中心同轴：偏差 ≤1px（40 轴线不破）。
  ok(`${tag} ② 胶囊中心 ↔ 返回钮中心 |偏| ≤1px（实测 胶囊 ${top.miniCenterYExact} / 钮 ${top.backCenterYExact}）`,
    Math.abs(top.miniCenterYExact - top.backCenterYExact) <= 1, `cap=${top.miniCenterYExact} back=${top.backCenterYExact}`);
  // ③ 水平缝：胶囊左缘 − 返回钮右缘 = 8px（±1）。
  //    几何事实：胶囊**横向居中**（left:50% + translate −50%）、宽度 max-content
  //    随页名收缩，故 8px 是「胶囊撑到上限（max-width = 100% − 2×72）」时的缝；
  //    短页名胶囊更窄、缝更大。故本断言分两臂：
  //    ③a 真实页名：缝 ≥ 8（永不重叠，8 是最小保证）；
  //    ③b 上限饱和探针：把文本临时换成长串逼到 max-width ⇒ 缝必须落在 8±1。
  const realGap = Math.round((top.miniLeftExact - top.backRight) * 100) / 100;
  ok(`${tag} ③a 胶囊不压返回钮（缝 ≥ 8px：实测 ${realGap}，左 ${top.miniLeftExact} / 钮右 ${top.backRight}）`,
    realGap >= CAP_GAP - 1, `gap=${realGap}`);
  const sat = await page.evaluate((pid) => {
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    // 标题单元素化批 2026-11-02：上限饱和探针改探同一个 .mobile-page-title
    // （装饰副本 .mobile-page-title-mini 已退役），量的是它在吸附态的盒宽。
    const mini = sec.querySelector('.mobile-page-title');
    const back = sec.querySelector('.mobile-back-btn');
    const old = mini.textContent;
    mini.textContent = '胶囊上限饱和探针页名 W'.repeat(4); // 必然越过 max-width
    const mb = mini.getBoundingClientRect();
    const bb = back.getBoundingClientRect();
    mini.textContent = old; // 复原（本探针只量几何，不留痕）
    return {
      gap: Math.round((mb.left - bb.right) * 100) / 100,
      w: Math.round(mb.width * 100) / 100,
      maxW: getComputedStyle(mini).maxWidth,
      vw: window.innerWidth,
    };
  }, pageId);
  ok(`${tag} ③b 上限饱和时缝 = 8px±1（实测 ${sat.gap}；胶囊宽 ${sat.w} = max-width ${sat.maxW}，vw ${sat.vw}）`,
    Math.abs(sat.gap - CAP_GAP) <= 1 && Math.abs(sat.w - (sat.vw - 2 * INSET)) <= 1, `gap=${sat.gap} w=${sat.w}`);
  // ④ 宽度随内容收缩（< vw×0.9）+ 圆角 999 档。
  ok(`${tag} ④ 胶囊宽度随内容收缩（< vw×0.9 = ${Math.round(top.vw * 0.9)}）+ 圆角 999（实测 w=${top.miniW} r=${top.miniRadius}）`,
    top.miniW < top.vw * 0.9 && top.miniRadius === '999px', `w=${top.miniW} vw=${top.vw} r=${top.miniRadius}`);
  // ⑤ 文字不被圆角裁切：文字宽 + 左内距 + 右内距 ≤ 胶囊宽（内距 24 = 新半径，
  //    保证文字整段落在直段内）。
  const textFit = Math.round((top.miniTextW + parseFloat(top.miniPadL) + parseFloat(top.miniPadR)) * 100) / 100;
  ok(`${tag} ⑤ 文字不溢出/不被圆角裁（文字 ${top.miniTextW} + 内距 ${top.miniPadL}+${top.miniPadR} = ${textFit} ≤ 胶囊宽 ${top.miniW}）`,
    textFit <= top.miniW + 0.5, `${textFit} vs ${top.miniW}`);
  ok(`${tag} ⑤b 胶囊内距 = 24（= 半径 h/2，文字落直段内）`, top.miniPadL === `${CAP_PAD}px` && top.miniPadR === `${CAP_PAD}px`, `${top.miniPadL}/${top.miniPadR}`);
  // ⑥ 内容仍从胶囊下方流过（页体内容件与胶囊渲染盒相交）。
  ok(`${tag} ⑥ 内容仍从胶囊下方流过（相交件 ${top.contentOverlapsCapsule.n} 个，示例 ${top.contentOverlapsCapsule.sample}）`,
    top.contentOverlapsCapsule.any, `n=${top.contentOverlapsCapsule.n}`);


  // ── P3 真滚轮回顶（形态反向插值展开）──
  const box = await page.locator(hoverSel).first().boundingBox();
  if (box) {
    await page.mouse.move(Math.min(box.x + box.width / 2, VW / 2), Math.min(box.y + box.height / 2, 600));
    for (let i = 0; i < 6; i += 1) await page.mouse.wheel(0, -400);
    await page.waitForTimeout(320);
  }
  const back1 = await probe(page, pageId);
  const st = await readScrollTop(page, pageId);
  ok(`${tag} 滚轮回顶（真滚轮）文字展开复现`, back1.p === 0 && !back1.rolled && near(back1.titleOpacity, 1, 0.01), `p=${back1.p} top=${st.top}@${st.tag}`);
  ok(`${tag} 回顶后壳层退回 0（轮廓无残留）+ 盒复 52 / 字复 28`, back1.miniOpacity === 0 && back1.miniH === 52 && back1.miniFS === '28px', `op=${back1.miniOpacity} h=${back1.miniH} fs=${back1.miniFS}`);
  await page.screenshot({ path: join(SHOTS, `${tag}-backtop.png`) });
}

/* ── 主题腿（引擎 × scheme × 3 面板）────────────────────────────────── */
async function themeLeg(engineName, scheme) {
  const tag = `${engineName}-${scheme}`;
  const page = await boot(scheme, engineName);
  try {
    // ① 项目（pane 页：主滚轴 = .team-scroll，吸附后面内收高）。
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll .project-card, #mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    await panelLeg(page, `${tag}-projects`, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');

    // ② 设置（overlay 收编面：主滚轴 = #settings-content）。
    await openPage(page, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal');
    const setScrollable = await page.evaluate(() => {
      const sec = document.querySelector('#mobile-pages .mobile-page[data-page="settings"]');
      const c = sec.querySelector('#settings-content');
      return c ? c.scrollHeight - c.clientHeight : -1;
    });
    ok(`${tag}-settings 设置内容滚动域成立（夹具充足）`, setScrollable > 100, `surplus=${setScrollable}`);
    await panelLeg(page, `${tag}-settings`, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-content');

    // ③ 待办事项页 —— ⛔ 腿退役（入口收敛批 2026-11-04）：#todo-page 整面退役，
    //    不再有页面栈成员 ⇒ 页面壳标题吸附几何对本面无测点（见文件头退役注记）。
    // await openTodo(page);
    // await panelLeg(page, `${tag}-todo`, 'todo', '#mobile-pages .mobile-page.live[data-page="todo"] #todo-page');

    // ── P4 面板切换对账：吸附态离场 → 下一面板静止态（不继承）──
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll .project-card, #mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    await setScroll(page, 'projects', 300);
    const pScrolled = await probe(page, 'projects');
    ok(`${tag} 切换前 projects 处于吸附态`, pScrolled.p === 1 && pScrolled.rolled);
    await page.locator('#mobile-pages .mobile-page[data-page="projects"] .mobile-back-btn').click();
    await page.waitForTimeout(350);
    const pAfter = await probe(page, 'projects');
    ok(`${tag} 返回后 projects 退场（.live 摘除）`, await page.evaluate(() => !document.querySelector('#mobile-pages .mobile-page[data-page="projects"]').classList.contains('live')));
    ok(`${tag} 退场后进度保留但不影响他页（离场态即 p=${pAfter.p}）`, pAfter.p === 1, `p=${pAfter.p}`);
    await openPage(page, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal');
    const sFresh = await probe(page, 'settings');
    ok(`${tag} 新进面板静止态（进场对账 p=0，不继承他页吸附）`, sFresh.p === 0 && !sFresh.rolled && sFresh.titleH === 52, `p=${sFresh.p} h=${sFresh.titleH}`);
  } finally {
    await page.close();
  }
}

/* ── reduced-motion 腿（chromium-light 一次）────────────────────────── */
async function reducedMotionLeg() {
  const page = await boot('light', 'chromium', { reducedMotion: true });
  try {
    await openPage(page, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    const samples = [];
    for (const v of [20, 30, 40, 50]) {
      await setScroll(page, 'projects', v);
      samples.push((await probe(page, 'projects')).p);
    }
    ok('reduced-motion 进度二值化（取样只会是 0 或 1）', samples.every((x) => x === 0 || x === 1), JSON.stringify(samples));
    ok('reduced-motion 区间前段即 0（scrollTop 20 = p 0.25 侧）', samples[0] === 0, JSON.stringify(samples));
    await setScroll(page, 'projects', 120);
    const hi = await probe(page, 'projects');
    ok('reduced-motion 阈值上瞬时成立（p=1）', hi.p === 1, `p=${hi.p}`);
    ok('reduced-motion 大标题无位移量（transform none）', hi.titleTransform === 'none' || hi.titleTransform === 'matrix(1, 0, 0, 1, 0, 0)', hi.titleTransform);
  } finally {
    await page.close();
  }
}

/* ── ⑦ 全页壳一致腿（胶囊高度批 2026-11-02）────────────────────────────
   19+ 个手机页壳共用同一份 .mobile-page-title-mini 规则（吸附胶囊），但页名
   长短跨度很大（「设置」2 字 ↔ 「旧工具结果清理」6 字 ↔「Jev（插件分配）」）。
   高度档换到 48 后必须逐壳复验：胶囊高 = 48、中心 = 40、内距 = 24、圆角 999、
   左缘 ≥ 72（不压返回钮）、文字不被 ellipsis 裁（scrollWidth ≤ clientWidth+1）。
   面 = navController.PAGES 全表（dock 根页无返回钮/无胶囊，按设计排除），
   走 pushPage 导航 = 每壳的真实 shell 构建路径（非 DOM 手搭）。 */
async function shellLeg() {
  const page = await boot('light', 'chromium');
  try {
    const ids = await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      // 会话页（conversation）按「三种会话页头同构」批的契约**大标题退场**
      // （身份头取而代之，标题盒 h=0）—— 不在吸附胶囊口径内，显式排除，
      // 该页的零标题断言由 conversation-heads-unify.spec.mjs 覆盖。
      const EXCLUDED = new Set(['conversation']);
      return nav.PAGES.filter((p) => !p.dock && !EXCLUDED.has(p.id)).map((p) => p.id);
    });
    // 入口收敛批 todo-unify-entry-collapse 2026-11-04：'tasks'（任务页面板）与
    // 'todo'（待办页）两个导航页随入口收敛退役（PAGES 常数 20 → 18），页壳表
    // 收缩 2 ⇒ 在场下限由 19 下调为 17（仍能捕获整表崩掉，只是不再假设两页在场）。
    ok('⑦ 页壳表在场（PAGES 非根页 ≥ 17）', ids.length >= 17, `n=${ids.length}`);
    const rows = [];
    for (const id of ids) {
      const r = await page.evaluate(async (pid) => {
        const nav = await import('/js/mobile/navController.js');
        try { nav.pushPage(pid); } catch (e) { return { id: pid, err: String(e).slice(0, 80) }; }
        await new Promise((res) => setTimeout(res, 420));
        const sec = document.querySelector(`#mobile-pages .mobile-page.live[data-page="${pid}"]`);
        // 标题单元素化批 2026-11-02：19 页壳一致性腿改探同一个 .mobile-page-title，
        // 且必须取**吸附态**（p=1）读数 —— 静止态是 52 高 / 28px 的大标题档，
        // 胶囊档（48/40/24/999）只是同一元素在 p=1 的形态。
        const mini = sec && sec.querySelector('.mobile-page-title');
        const back = sec && sec.querySelector('.mobile-back-btn');
        if (!mini) return { id: pid, noMini: true };
        sec.style.setProperty('--mtitle-p', '1');
        await new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(res)));
        const mb = mini.getBoundingClientRect();
        const bb = back ? back.getBoundingClientRect() : null;
        const cs = getComputedStyle(mini);
        const shell = getComputedStyle(mini, '::before');
        const out = {
          id: pid,
          text: (mini.textContent || '').trim(),
          w: +mb.width.toFixed(1),
          h: +mb.height.toFixed(1),
          cy: +(mb.top + mb.height / 2).toFixed(1),
          left: +mb.left.toFixed(1),
          padL: cs.paddingLeft,
          padR: cs.paddingRight,
          radius: shell.borderTopLeftRadius || cs.borderTopLeftRadius,
          backH: bb ? +bb.height.toFixed(1) : null,
          clipped: mini.scrollWidth > mini.clientWidth + 1,
          clippedIsMasked: mini.classList.contains('mtitle-clip'),   // 2026-11-05 起 = 溢出标记类（不再画 mask）
          shellOpacity: +shell.opacity,
        };
        sec.style.removeProperty('--mtitle-p');
        return out;
      }, id);
      rows.push(r);
      await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); try { nav.popToRoot(); } catch { /* at root */ } });
      await page.waitForTimeout(140);
    }
    const miss = rows.filter((r) => r.noMini || r.err);
    ok('⑦ 每个页壳都建了页名节点（零缺失）', miss.length === 0, JSON.stringify(miss.slice(0, 4)));
    const live = rows.filter((r) => !r.noMini && !r.err);
    const badH = live.filter((r) => Math.abs(r.h - CAP_H) > 1);
    const badCy = live.filter((r) => Math.abs(r.cy - CAP_CENTER_Y) > 1);
    const badPad = live.filter((r) => r.padL !== `${CAP_PAD}px` || r.padR !== `${CAP_PAD}px`);
    const badR = live.filter((r) => r.radius !== '999px');
    const badLeft = live.filter((r) => r.left < INSET - 1);
    const badClip = live.filter((r) => r.clipped && !r.clippedIsMasked);
    const badBack = live.filter((r) => r.backH != null && Math.abs(r.backH - CAP_H) > 1);
    const badShell = live.filter((r) => Math.abs(r.shellOpacity - 1) > 0.01);
    ok(`⑦ 全部页壳吸附态胶囊高 = 48（${live.length} 壳）`, badH.length === 0, JSON.stringify(badH.map((r) => `${r.id}:${r.h}`)));
    ok('⑦ 全部页壳吸附态胶囊中心 = 40', badCy.length === 0, JSON.stringify(badCy.map((r) => `${r.id}:${r.cy}`)));
    ok('⑦ 全部页壳内距 = 24（= 半径 h/2）', badPad.length === 0, JSON.stringify(badPad.map((r) => `${r.id}:${r.padL}/${r.padR}`)));
    ok('⑦ 全部页壳圆角 999', badR.length === 0, JSON.stringify(badR.map((r) => `${r.id}:${r.radius}`)));
    ok('⑦ 全部页壳胶囊左缘 ≥ 72（不压返回钮）', badLeft.length === 0, JSON.stringify(badLeft.map((r) => `${r.id}:${r.left}`)));
    ok('⑦ 全部页壳壳层满显影（p=1 ⇒ ::before opacity 1）', badShell.length === 0, JSON.stringify(badShell.map((r) => `${r.id}:${r.shellOpacity}`)));
    // 端区分批 2026-11-05 09:28：作者令撤掉右缘渐隐 ⇒ 「溢出可见」的承载从
    // `.mtitle-clip` 的 mask 变成**可见省略号**（基座档 text-overflow:ellipsis）；
    // `.mtitle-clip` 退为纯溢出标记类。本判据仍是「钳宽态必须打标记」，
    // 省略号一侧由 tests/mobile-hig-assertions.spec.mjs 的 H4b 机械兜住。
    ok(`⑦ 全部页壳文字不被硬裁（钳宽态挂 .mtitle-clip 溢出标记，截断走可见省略号）`, badClip.length === 0, JSON.stringify(badClip.map((r) => `${r.id}:${r.text}`)));
    ok('⑦ 全部页壳返回钮高 = 48（胶囊高差基准同表）', badBack.length === 0, JSON.stringify(badBack.map((r) => `${r.id}:${r.backH}`)));
    const ws = live.map((r) => r.w).sort((a, b) => a - b);
    ok('⑦ 宽度随页名收缩（最短/最长极差 > 40px，非定宽）', ws.length > 2 && ws[ws.length - 1] - ws[0] > 40,
      `min=${ws[0]} max=${ws[ws.length - 1]} sample=${live.slice(0, 4).map((r) => `${r.text}@${r.w}`).join(' / ')}`);
  } finally {
    await page.close();
  }
}

/* ── 桌面零触碰腿 ─────────────────────────────────────────────────────── */
async function desktopLeg() {
  const engine = chromium;
  const page = await (await engine.launch()).newContext({
    viewport: { width: 1280, height: 800 }, colorScheme: 'light', locale: 'zh-CN',
  }).then((ctx) => ctx.newPage());
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto(`${ORIGIN}/`);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(800);
  const d = await page.evaluate(() => ({
    mobileLayer: document.body.classList.contains('mobile-layer'),
    pageShells: document.querySelectorAll('.mobile-page').length,
    titleNodes: document.querySelectorAll('.mobile-page-title').length,
    headerShown: !!document.querySelector('#header'),
  }));
  ok('桌面腿 body.mobile-layer 不激活（390 折叠点外）', !d.mobileLayer);
  ok('桌面腿零页面壳/零标题节点（页面世界不建）', d.pageShells === 0 && d.titleNodes === 0, `shells=${d.pageShells} titles=${d.titleNodes}`);
  ok('桌面腿 #header 在场（桌面壳零变化）', d.headerShown);
  await page.close();
}

/* ── 主流程：双引擎 × 双主题 + reduced-motion + 桌面腿 + 零 console error ── */
console.log(`evidence → ${OUT}`);
for (const engineName of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await themeLeg(engineName, scheme);
  }
}
await reducedMotionLeg();
await shellLeg();
await desktopLeg();

ok('零 console error（双引擎×双主题全腿 + reduced-motion + 桌面腿，噪声过滤后硬零）', consoleErrors.length === 0,
  consoleErrors.slice(0, 5).join(' | '));

console.log(failures === 0 ? '\nALL PASS' : `\nFAILED: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
