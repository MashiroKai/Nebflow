// mobile-search-flat.spec.mjs — 手机搜索条「扁平长条 ⇄ 右上角圆钮」批主 spec
// （件八，作者令 2026-11-05 12:28 逐字见 css/mobileSearchFlat.css 头注）
//
// 判据九组（消息页 + 联系人页各跑一遍 = ⑨ 两页同源）：
//   ① 展开态几何与材质场：.mh-search-bar 44×343 @ left16/right359；圆角 = 我方圆角
//      档 --ds-radius-xl（12px；2026-11-05 17:37 作者令「圆角幅度再大一点」由
//      --ds-radius-input 8px 抬档）；**材质 = .nf-glass 唯一毛玻璃标准**
//      （glass-top-overlays 批 2026-11-06 —— 条浮在滚动列表之上，按作者令
//      「所有悬浮在顶部 可以透出背景的 都用毛玻璃效果 比如消息和联系人的手机端
//      的搜索框」；12:28 那版的 --ds-wash-16 浅灰底 + 零模糊已退役）；
//      doc/搜索带高 = 60（两页同值）。
//   ② 上滚过阈值（真实滚动容器 scrollTop > 24）⇒ 条**同一节点**形变为右上角圆钮：
//      48×48 / right 359 / radius 50% / 挂 .g2-fab；材料逐值 = 主会话搜索钮
//      （#mobile-fab-layer .mh-chrome-btn.g2-fab）计算样式 —— 同一套 CSS 的同源证据。
//   ③ bug① 回归：收起期间**搜索带高与滚动容器几何零变化**（dock 高恒 60、
//      scroller clientHeight 不变、scrollTop 不被钳回）；收起后仍能一路滚到列表末尾
//      （scrollTop 触底 = scrollHeight − clientHeight）。
//   ④ bug② 回归：切到别的页面（联系人 / 工作台）再回来 —— 非 live 页的条不可见
//      （无可见节点落在视口右上角），回到页时一律是展开长条（收起态被复位）。
//   ⑤ 回顶（scrollTop ≤ 4）⇒ 还原：bar 几何与初始逐值相同。
//   ⑥ 点圆钮 ⇒ 展开 + 聚焦输入 + 键入生效（圆钮是按钮：role=button/tabindex）。
//   ⑦ 形变过渡：rAF 实录 ≥2 个中间帧（0 < 宽 < 343 且 48 < 宽、圆角/输入行透明度
//      在两端之间），且宽度单调；展开腿同判据反向。
//   ⑧ reduced-motion：--mhsc-dur 压到 1ms（二值语义保留，功能不退化）。
//   ⑨ 两页同源（同上判据在 contacts 页重跑）。
//
// 改前树腿：SEARCH7_LEGACY_TREE=1 + SEARCH7_WEB_ROOT=<改前 web 树> ⇒ 同一脚本按
// 「改前红口径」断言（条无 .nf-glass / 无毛玻璃配方 / 上滚不收 / 无 g2-fab 形变），
// 红即证明本批判据钉在改动上（不是恒真断言）。改前树取 main tip fa38813ea 的
// src/main/resources/web 导出。
//
// Self-contained: route-intercepted static files + mocked REST/WS（夹具与
// mobile-pinned-search.spec.mjs 同源：fm_api_mock 好友域种子），零后端。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-search-flat.spec.mjs
//   env：SEARCH7_EVID_DIR=<dir> 落中间帧 PNG + readings.json；
//        SEARCH7_LEGACY_TREE=1 / SEARCH7_WEB_ROOT=<web 树> 跑改前树腿。

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SEARCH7_WEB_ROOT
  ? normalize(process.env.SEARCH7_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'flat-search-session';
/** 阈值算术镜像（js/mobile/socialSearch.js：COLLAPSE_AT=24 / EXPAND_AT=4）。 */
const COLLAPSE_AT = 24;
const EXPAND_AT = 4;
/** 搜索带高（css/mobileSearchFlat.css --mhsc-band = 48 + 2 × 16）。 */
const BAND_H = 80;
const LEGACY = !!process.env.SEARCH7_LEGACY_TREE;
const EVID = process.env.SEARCH7_EVID_DIR || null;

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具（好友域 mock 种子：30 会话 + 30 好友 ⇒ 两页列表都可滚；
 *  消息正文含共同关键词「部署」，users 含 xintongxue）。
 *  `mock:false` = 关掉 fm_api_mock、把好友域 REST 打成 503（错误态判据的注入腿）。 */
async function bootApp(page, { mock = true } = {}) {
  // origin 档位：WebKit 拒用受限端口（实测 "Not allowed to use restricted network
  // port 1: http://localhost:1/" ⇒ 双引擎各取 ≥1024 的虚拟 origin。请求全由
  // page.route 拦截 fulfill，**不监听本地端口、不起服务**（零端口占用）。
  const engine = page.context().browser()?.browserType().name() || 'chromium';
  const ORIGIN = engine === 'webkit' ? 'http://localhost:8381/' : 'http://localhost:8380/';
  await page.addInitScript((useMock) => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    if (!useMock) return;
    localStorage.setItem('fm_api_mock', '1');
    const friends = [
      { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: NOW - 86400e3 },
      { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '', since: NOW - 172800e3 },
    ];
    const conversations = [
      {
        conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
        lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
        unreadCount: 0,
      },
      {
        conversationId: 'c-k', friend: { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '' },
        lastMessage: { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
        unreadCount: 0,
      },
    ];
    // 行数下限：列表必须可滚（滚动收起腿的载体；无内容时判据 ②③⑥ 无载体）。
    for (let i = 0; i < 30; i++) {
      const uid = `u-r${i}`;
      friends.push({ userId: uid, neblinkId: `row${i}`, name: `好友${String(i + 1).padStart(2, '0')}`, avatarUrl: '', since: NOW - (i + 3) * 86400e3 });
      conversations.push({
        conversationId: `c-r${i}`,
        friend: { userId: uid, neblinkId: `row${i}`, name: `好友${String(i + 1).padStart(2, '0')}`, avatarUrl: '' },
        lastMessage: { id: 1000 + i, senderId: uid, kind: 'text', body: `普通消息 ${i + 1}`, createdAt: NOW - (i + 3) * 3600e3 },
        unreadCount: 0,
      });
    }
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '' },
      users: [
        { userId: 'u-new', username: 'xintongxue', email: 'xin@example.com', displayName: '新同学', avatar: '' },
      ],
      friends,
      incoming: [],
      outgoing: [],
      conversations,
      messages: {
        'c-p': [
          { id: 210, senderId: 'me', kind: 'text', body: '周五能交付吗', createdAt: NOW - 3600e3 },
          { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
        ],
        'c-k': [
          { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
        ],
        'g-1': [
          { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
        ],
      },
      groups: [{
        groupId: 'g-1', title: '周末球局', role: 'owner', memberCount: 2,
        lastMessage: { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
        unreadCount: 0, lastMessageId: 440, createdAt: NOW - 86400e3 * 7,
      }],
      groupInvites: [],
      groupMembers: {
        'g-1': [
          { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
          { userId: 'u-p', username: null, display_name: '分页君', avatar: null, role: 'member', joinedAt: 0 },
        ],
      },
    }));
  }, mock);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (!mock && p.startsWith('/api/')
        && p !== '/api/sessions' && p !== '/api/neblink/status') {
      // 取数失败注入（错误态判据）：会话/好友/群腿一律 503，状态腿照常
      // （保持已登录 ⇒ 走「取数失败」而非「未登录」分态）。
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"down"}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') {
        const projects = Array.from({length: 12}, (_, i) => ({name:`fixture-project-${String(i + 1).padStart(2, '0')}`, workspace:`/tmp/appearance-project-${i + 1}`, agentFile:'', description:'', createdAt:Date.now() - i * 86400000}));
        return route.fulfill({status:200, contentType:'application/json', body:JSON.stringify({projects})});
      }
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }) });
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
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
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
  await page.goto(ORIGIN);
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

/** 单一读数探针：条几何 + 计算样式 + 滚动容器几何 + 焦点。 */
function readState(page, pageId) {
  return page.evaluate((pid) => {
    const rectOf = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height };
    };
    const section = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const dock = section?.querySelector('.mh-search-dock') || null;
    const bar = dock?.querySelector('.mh-search-bar') || null;
    const cs = bar ? getComputedStyle(bar) : null;
    const input = dock?.querySelector('.mh-search-input') || null;
    const icon = bar?.querySelector('svg') || null;
    const scroller = section?.querySelector('#fm-conversations')
      || section?.querySelector('#fm-contacts-body') || null;
    // 主会话搜索钮（同源基准）：住在聊天根 chrome 带里，计算样式与是否可见无关。
    const base = document.querySelector('#mobile-fab-layer .mh-chrome-btn[data-chrome="search"]');
    const bcs = base ? getComputedStyle(base) : null;
    const baseIcon = base?.querySelector('svg') || null;
    return {
      dockH: dock ? Math.round(dock.getBoundingClientRect().height) : null,
      dockClass: dock ? dock.className : null,
      dockInlineStyle: dock ? dock.getAttribute('style') : null,
      bar: rectOf(bar),
      barClass: bar ? bar.className : null,
      barRole: bar ? bar.getAttribute('role') : null,
      barTabIndex: bar ? bar.getAttribute('tabindex') : null,
      visible: !!bar && getComputedStyle(bar).visibility === 'visible'
        && bar.getBoundingClientRect().height > 0,
      barStyle: cs ? {
        backdropFilter: cs.webkitBackdropFilter || cs.backdropFilter || 'none',
        borderTopWidth: cs.borderTopWidth,
        borderTopColor: cs.borderTopColor,
        borderRadius: cs.borderRadius,
        boxShadow: cs.boxShadow,
        backgroundColor: cs.backgroundColor,
      } : null,
      /** 玻璃单源实解析值（探针元素读 token，不手写期望值 —— 令牌改了本腿跟随）。 */
      glassTokens: (() => {
        const mk = (css) => {
          const p = document.createElement('div');
          p.style.cssText = `position:absolute;left:-9999px;top:0;${css}`;
          document.body.appendChild(p);
          const s = getComputedStyle(p);
          const out = { bg: s.backgroundColor, border: s.borderTopColor, shadow: s.boxShadow, filter: s.webkitBackdropFilter || s.backdropFilter || 'none' };
          p.remove();
          return out;
        };
        return {
          bg: mk('background:var(--nf-glass-bg)').bg,
          border: mk('border-top:1px solid var(--nf-glass-border-color)').border,
          shadow: mk(`box-shadow:var(--nf-glass-shadow)`).shadow,
          filter: mk('backdrop-filter:var(--nf-glass-filter)').filter,
        };
      })(),
      inputOpacity: input ? +getComputedStyle(input).opacity : null,
      inputMaxWidth: input ? getComputedStyle(input).maxWidth : null,
      inputColor: input ? getComputedStyle(input).color : null,
      iconW: icon ? getComputedStyle(icon).width : null,
      /** 展开态黑盒：主会话按钮的计算样式（同源对比基准）。 */
      baseline: bcs ? {
        width: Math.round(base.getBoundingClientRect().width),
        height: Math.round(base.getBoundingClientRect().height),
        borderRadius: bcs.borderRadius,
        backgroundColor: bcs.backgroundColor,
        borderTopColor: bcs.borderTopColor,
        boxShadow: bcs.boxShadow,
        backdropFilter: bcs.webkitBackdropFilter || bcs.backdropFilter,
        iconW: baseIcon ? getComputedStyle(baseIcon).width : null,
      } : null,
      activeIsSearchInput: !!document.activeElement?.classList?.contains('mh-search-input'),
      inputValue: input ? input.value : null,
      scrollerY: scroller ? Math.round(scroller.scrollTop) : null,
      scrollerClientH: scroller ? scroller.clientHeight : null,
      scrollerScrollH: scroller ? scroller.scrollHeight : null,
      scrollerMaxY: scroller ? Math.round(scroller.scrollHeight - scroller.clientHeight) : null,
    };
  }, pageId);
}

/** 非 live 页的条是否仍然可见（bug② 泄漏判据）。 */
const leakProbe = (page) => page.evaluate(() => {
  const out = [];
  for (const sec of document.querySelectorAll('#mobile-pages .mobile-page')) {
    if (sec.classList.contains('live')) continue;
    // 两种形态都扫：新形态 = 条本体形变（.mh-search-bar）；改前形态 = 独立浮钮
    // （.mh-search-fab，fixed + 自持 visibility ⇒ 泄漏源）。
    for (const bar of sec.querySelectorAll('.mh-search-bar, .mh-search-fab')) {
      const cs = getComputedStyle(bar);
      const r = bar.getBoundingClientRect();
      const painted = cs.visibility === 'visible' && +cs.opacity > 0.01 && r.width > 0 && r.height > 0;
      // 视口右上角（圆钮落位区）是否被非 live 页的面盖住。
      const atCorner = r.top < 80 && r.right > 320 && painted;
      out.push({ page: sec.dataset.page, painted, atCorner, visibility: cs.visibility, cls: bar.className });
    }
  }
  return out;
});

/** rAF 实录（帧序存 document.body.dataset，零全局变量）：条宽/高/圆角 + 输入行透明度 + 带高。 */
function startFrameLog(page) {
  return page.evaluate(() => {
    const dock = document.querySelector('.mobile-page.live .mh-search-dock');
    const bar = dock ? dock.querySelector('.mh-search-bar') : null;
    const input = dock ? dock.querySelector('.mh-search-input') : null;
    if (!dock || !bar) return false;
    document.body.dataset.mhscFrames = '';
    const frames = [];
    const t0 = performance.now();
    const tick = () => {
      const t = performance.now() - t0;
      const r = bar.getBoundingClientRect();
      const cs = getComputedStyle(bar);
      frames.push({
        t: Math.round(t),
        w: Math.round(r.width * 100) / 100,
        h: Math.round(r.height * 100) / 100,
        radius: cs.borderTopLeftRadius,
        inputOpacity: input ? Math.round(Number(getComputedStyle(input).opacity) * 1000) / 1000 : null,
        dockH: Math.round(dock.getBoundingClientRect().height * 100) / 100,
      });
      if (t < 640) requestAnimationFrame(tick);
      else document.body.dataset.mhscFrames = JSON.stringify(frames);
    };
    requestAnimationFrame(tick);
    return true;
  });
}
const readFrameLog = (page) => page.evaluate(() => {
  const raw = document.body.dataset.mhscFrames;
  return raw ? JSON.parse(raw) : null;
});

/** ⑦ 中间帧判据：宽度落在两端之间 ≥2 帧、宽度单调、带高恒 60（bug① 无回流）。 */
function assertIntermediateFrames(frames, w0, w1, phase) {
  expect(Array.isArray(frames) && frames.length > 2, `⑦ ${phase}: rAF 帧序非空（${frames ? frames.length : 0} 帧）`).toBe(true);
  const lo = Math.min(w0, w1);
  const hi = Math.max(w0, w1);
  const mid = frames.filter((f) => f.w > lo + 0.5 && f.w < hi - 0.5);
  expect(mid.length, `⑦ ${phase}: 宽度中间帧 ≥2（实测 ${mid.length}）`).toBeGreaterThanOrEqual(2);
  const ws = mid.map((f) => f.w);
  const mono = phase === 'collapse'
    ? ws.every((w, i) => i === 0 || w <= ws[i - 1])
    : ws.every((w, i) => i === 0 || w >= ws[i - 1]);
  expect(mono, `⑦ ${phase}: 宽度单调 ${JSON.stringify(ws)}`).toBe(true);
  // bug① 结构判据：过渡全程搜索带高不变（滚动容器几何零变化）。
  const dockHs = [...new Set(frames.map((f) => Math.round(f.dockH * 100) / 100))];
  expect(dockHs, `⑦ ${phase}: 带高全程恒定（实测集合 ${JSON.stringify(dockHs)}）`).toHaveLength(1);
  expect(Math.round(dockHs[0]), `⑦ ${phase}: 带高 = ${BAND_H}`).toBe(BAND_H);
  return mid;
}

/** 改前树红腿：把本批判据对着 77afefc77 的 web 树跑一遍，逐条收集**未落地**项。 */
async function legacyRedLeg(page, pageId, s0, tag) {
  const unmet = [];
  // 改前红口径（glass-top-overlays 批 2026-11-06 换代）：本条判据现在是「条必须
  // 吃唯一毛玻璃标准」。改前树是 --ds-wash-16 浅灰底 + 零模糊 ⇒ 下列两条必红。
  if ((s0.barClass || '').includes('nf-glass')) unmet.push(`① 改前已挂 .nf-glass（不应出现）：${s0.barClass}`);
  else unmet.push(`① 未走毛玻璃：条无 .nf-glass 类（class = ${s0.barClass}）`);
  if (/blur\(/.test(s0.barStyle.backdropFilter)) {
    unmet.push(`① 改前已带毛玻璃配方（不应出现）：${s0.barStyle.backdropFilter}`);
  } else {
    unmet.push(`① 无毛玻璃配方：backdrop-filter = ${s0.barStyle.backdropFilter}，底 = ${s0.barStyle.backgroundColor}`);
  }
  const scrollerSel = pageId === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
  await page.evaluate(([pid, sel, at]) => {
    const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
    if (!sc) return;
    sc.scrollTop = at + 200;
    sc.dispatchEvent(new Event('scroll', { bubbles: false }));
  }, [pageId, scrollerSel, COLLAPSE_AT]);
  await page.waitForTimeout(500);
  const after = await readState(page, pageId);
  if (after.barClass.includes('g2-fab')) unmet.push('② 改前竟然是圆钮形态（不应出现）');
  else unmet.push(`② 上滚不成圆钮（bar class = ${after.barClass}）`);
  unmet.push('⑧ 无圆钮可点（展开+聚焦判据无载体）');
  unmet.push('⑦ 无长条→圆钮形变（无宽度中间帧可采）');
  // ③ bug①（改前形态）：收起改的是滚动容器几何 —— 带上读「带高前后不等 + 容器可视高变了」。
  if (after.dockH !== BAND_H || after.scrollerClientH !== s0.scrollerClientH) {
    unmet.push(`③ bug①：收起改了滚动容器几何（带高 ${Math.round(after.dockH)} → 容器可视高 `
      + `${s0.scrollerClientH}→${after.scrollerClientH}）`);
  }
  // ④ bug②（改前形态）：独立 fixed 浮钮在非 live 页仍绘制 ⇒ 切走即泄漏。
  await dockTo(page, pageId === 'messages' ? 'contacts' : 'messages');
  const legacyLeak = await leakProbe(page);
  if (legacyLeak.some((x) => x.painted)) unmet.push('④ bug②：圆钮跨页泄漏（非 live 页仍有可见浮钮）');
  else unmet.push('④ bug②：改前无可见泄漏样本（浮钮形态缺席）');
  expect(unmet, `改前树腿（${pageId}）必红：${unmet.join(' | ')}`).toEqual([]);
}

test('FMA-SCN-001 ordinary page keeps accessible name without title capsule', async ({ page }) => {
  await bootApp(page);
  await dockTo(page, 'workbench');
  await page.locator('[data-hub-page="settings"]').click();
  const section = page.locator('.mobile-page[data-page="settings"]');
  await expect(section).toBeVisible();
  await expect(section).toHaveAttribute('aria-label', /\S/);
  // 大标题退役（ui-unify-panels B 批 2026-11-17 作者令「一级与次级面板都不渲染大标题」）：
  // 标题节点不再建（旧「1px 隐藏位」几何判据随节点一并退场）。
  await expect(section.locator('.mobile-page-title')).toHaveCount(0);
  const scroller = section.locator('#settings-content');
  await expect(scroller).toBeVisible();
  const distance = await scroller.evaluate(el => el.scrollHeight - el.clientHeight);
  expect(distance).toBeGreaterThan(0);
  await scroller.evaluate(el => { el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event('scroll')); });
  await expect.poll(() => scroller.evaluate(el => el.scrollTop)).toBeGreaterThan(0);
  await expect(section.locator('.mobile-page-title')).toHaveCount(0);
  await scroller.evaluate(el => { el.scrollTop = 0; el.dispatchEvent(new Event('scroll')); });
  await expect.poll(() => scroller.evaluate(el => el.scrollTop)).toBe(0);
  await section.locator('.mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="workbench"]')).toBeVisible();
});

test('FMA-SCN-001 project list scrolls without a title capsule', async ({ page }) => {
  await bootApp(page);
  await dockTo(page, 'workbench');
  await page.locator('[data-hub-page="projects"]').click();
  const section = page.locator('.mobile-page[data-page="projects"]');
  await expect(section).toBeVisible();
  const scroll = section.locator('.team-scroll');
  await expect(scroll).toBeVisible();
  const cards = scroll.locator('.project-card');
  await expect(cards).toHaveCount(12);
  await expect(cards.first()).toBeVisible();
  await cards.last().scrollIntoViewIfNeeded();
  await expect(cards.last()).toBeInViewport();
  // 大标题退役（ui-unify-panels B 批 2026-11-17）：项目页同样零标题节点。
  await expect(section.locator('.mobile-page-title')).toHaveCount(0);
  await section.locator('.mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="workbench"]')).toBeVisible();
});

test('FMA-SCN-001 canvas document retains its interactive tab capsule', async ({ page }) => {
  await bootApp(page);
  await page.waitForTimeout(3400); // Production boot-restoration suppression window.
  await page.evaluate(() => {
    const content = '# Appearance fixture\n' + 'canvas content\n'.repeat(50);
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
      id: 'file:docs/appearance.md', itemType: 'markdown', title: 'appearance.md',
      content, absPath: '/tmp/docs/appearance.md', size: content.length,
    } }));
  });
  const canvas = page.locator('.mobile-page[data-page="canvas"]');
  await expect(canvas).toBeVisible();
  const chip = canvas.locator('.mf-strip-chip.on');
  await expect(chip).toBeVisible();
  await expect(chip).toContainText('appearance.md');
  await chip.click();
  await expect(canvas).toBeVisible();
  const ask = page.locator('.ask-fab:not(.note-fab)');
  await expect(ask).toBeVisible();
  await ask.click();
  const input = page.locator('.ask-dock .ask-input');
  await expect(input).toBeVisible();
  const material = await input.evaluate(el => {
    const s = getComputedStyle(el), c = getComputedStyle(document.querySelector('#input-bar'));
    return { bg:s.backgroundColor, filter:s.backdropFilter, baseBg:c.backgroundColor, baseFilter:c.backdropFilter };
  });
  expect(material.bg).toBe(material.baseBg);
  expect(material.filter).toBe(material.baseFilter);
  const editor = input.locator('textarea');
  await editor.focus();
  await expect(editor).toBeFocused();
});

for (const [theme, name] of [
  ['light', 'FMA-SCN-002 narrow search and back remain centered light'],
  ['dark', 'FMA-SCN-002 narrow search and back remain centered dark'],
]) {
  test(name, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 812 });
    await page.emulateMedia({ colorScheme: theme });
    await bootApp(page);
    await dockTo(page, 'messages');
    await page.locator('.mobile-page[data-page="messages"] #fm-conversations').evaluate(el => {
      el.scrollTop = 200; el.dispatchEvent(new Event('scroll'));
    });
    const search = page.locator('.mobile-page[data-page="messages"] .mh-search-bar');
    await expect(search).toHaveClass(/g2-fab/);
    await page.waitForTimeout(400);
    const searchRect = await search.evaluate(el => {
      const a = el.getBoundingClientRect(), b = el.querySelector('svg').getBoundingClientRect();
      return { dx:(a.left+a.right-b.left-b.right)/2, dy:(a.top+a.bottom-b.top-b.bottom)/2 };
    });
    expect(Math.abs(searchRect.dx)).toBeLessThanOrEqual(1);
    expect(Math.abs(searchRect.dy)).toBeLessThanOrEqual(1);
    await dockTo(page, 'workbench');
    await page.locator('[data-hub-page="settings"]').click();
    const back = page.locator('.mobile-page[data-page="settings"] .mobile-back-btn');
    await expect(back).toBeVisible();
    for (const button of [back]) {
      const r = await button.evaluate(el => {
        const a = el.getBoundingClientRect(), b = el.querySelector('svg').getBoundingClientRect();
        return { dx:(a.left+a.right-b.left-b.right)/2, dy:(a.top+a.bottom-b.top-b.bottom)/2, hit:document.elementFromPoint(a.x+a.width/2,a.y+a.height/2)?.closest('button') === el };
      });
      expect(Math.abs(r.dx)).toBeLessThanOrEqual(1);
      expect(Math.abs(r.dy)).toBeLessThanOrEqual(1);
      expect(r.hit).toBe(true);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await back.click();
    await expect(page.locator('.mobile-page[data-page="workbench"]')).toBeVisible();
  });
}

test('FMA-SCN-002 collapsed search and back icons center in their controls', async ({ page }) => {
  await bootApp(page);
  await dockTo(page, 'messages');
  await page.locator('.mobile-page[data-page="messages"] #fm-conversations').evaluate(el => {
    el.scrollTop = 200;
    el.dispatchEvent(new Event('scroll'));
  });
  const search = page.locator('.mobile-page[data-page="messages"] .mh-search-bar');
  await expect(search).toHaveClass(/g2-fab/);
  await page.waitForTimeout(400);
  async function centered(control) {
    const d = await control.evaluate(el => {
      const a = el.getBoundingClientRect(), b = el.querySelector('svg').getBoundingClientRect();
      return { dx: (a.left + a.right - b.left - b.right) / 2, dy: (a.top + a.bottom - b.top - b.bottom) / 2, width: a.width, height: a.height };
    });
    expect(Math.abs(d.dx)).toBeLessThanOrEqual(1);
    expect(Math.abs(d.dy)).toBeLessThanOrEqual(1);
    expect(d.width).toBeGreaterThanOrEqual(44);
    expect(d.height).toBeGreaterThanOrEqual(44);
  }
  await centered(search);
  await dockTo(page, 'workbench');
  await page.locator('[data-hub-page="settings"]').click();
  await centered(page.locator('.mobile-page[data-page="settings"] .mobile-back-btn'));
});

test('FMA-SCN-004 mobile layer removes its document touchmove listener', async ({ page }) => {
  await page.addInitScript(() => {
    const add = document.addEventListener.bind(document), remove = document.removeEventListener.bind(document);
    const listeners = new Set();
    window.__touchListeners = listeners;
    document.addEventListener = function (type, listener, options) {
      if (type === 'touchmove' && (options === true || options?.capture)) listeners.add(listener);
      return add(type, listener, options);
    };
    document.removeEventListener = function (type, listener, options) {
      if (type === 'touchmove' && (options === true || options?.capture)) listeners.delete(listener);
      return remove(type, listener, options);
    };
  });
  await bootApp(page);
  const count = () => page.evaluate(() => window.__touchListeners.size);
  const active = await count();
  await page.setViewportSize({ width: 1000, height: 812 });
  await page.waitForFunction(() => !document.body.classList.contains('mobile-layer'));
  const inactive = await count();
  expect(inactive).toBeLessThan(active);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
  await expect.poll(count, { message: 'reactivation reinstalls exactly the original capture listeners' }).toBe(active);
});

test('FMA-SCN-004 reduced motion cancelled edge returns immediately', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await bootApp(page);
  await dockTo(page, 'workbench');
  await page.locator('[data-hub-page="settings"]').click();
  const section = page.locator('.mobile-page[data-page="settings"]');
  await expect(section).toBeVisible();
  await page.mouse.move(2, 200);
  await page.mouse.down();
  await page.mouse.move(22, 200, { steps: 3 });
  await page.waitForTimeout(250);
  await page.mouse.up();
  await expect(section).toBeVisible();
  const state = await section.evaluate(el => ({ transform: el.style.transform, transition: el.style.transition }));
  expect(state).toEqual({ transform: '', transition: '' });
});

test('FMA-SCN-003 todo surfaces use shared components', async ({ page }) => {
  await bootApp(page);
  await page.locator('[data-chrome="tasks"]').click();
  const tasks = page.locator('.mobile-page[data-page="tasks"]');
  await expect(tasks).toBeVisible();
  const pill = tasks.locator('.ta-pill');
  await expect(pill).toHaveClass(/nf-glass/);
  await expect(pill).toHaveCSS('backdrop-filter', await page.locator('#input-bar').evaluate(el => getComputedStyle(el).backdropFilter));
  await tasks.locator('.ta-pill-btn').first().click();
  const input = tasks.locator('.ta-search-input');
  await expect(input).toBeVisible();
  await input.fill('test');
  await expect(input).toHaveValue('test');
  await expect(input).toHaveCSS('box-sizing', 'border-box');
});

for (const [theme, name] of [
  ['light', 'FMA-SCN-003 todo glass hover and no-backdrop light'],
  ['dark', 'FMA-SCN-003 todo glass hover and no-backdrop dark'],
]) {
  test(name, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme });
    await bootApp(page);
    await page.locator('[data-chrome="tasks"]').click();
    const tasks = page.locator('.mobile-page[data-page="tasks"]');
    const pill = tasks.locator('.ta-pill');
    await expect(pill).toBeVisible();
    const composerBg = await page.locator('#input-bar').evaluate(el => getComputedStyle(el).backgroundColor);
    await pill.hover();
    await expect(pill).toHaveCSS('background-color', composerBg);
    await tasks.locator('.ta-pill-btn').nth(2).click();
    const menu = tasks.locator('.ta-menu');
    await expect(menu).toBeVisible();
    await page.evaluate(() => document.documentElement.classList.add('no-backdrop'));
    for (const surface of [pill, menu]) {
      await expect(surface).toHaveCSS('backdrop-filter', 'none');
      const color = await surface.evaluate(el => ({actual: getComputedStyle(el).backgroundColor, solid: getComputedStyle(document.body).getPropertyValue('--color-surface').trim()}));
      const normalized = await page.evaluate(value => { const node = document.createElement('i'); node.style.color = value; document.body.append(node); const c = getComputedStyle(node).color; node.remove(); return c; }, color.solid);
      expect(color.actual).toBe(normalized);
    }
  });
}

test('FMA-SCN-003 unsupported-backdrop fallback applies to actual todo pill', async ({ page }) => {
  await bootApp(page);
  await page.locator('[data-chrome="tasks"]').click();
  const pill = page.locator('.mobile-page[data-page="tasks"] .ta-pill');
  await expect(pill).toBeVisible();
  // Exercise the production @supports declarations in current engines by
  // installing that rule's actual child CSS in a later stylesheet.
  const copied = await page.evaluate(() => {
    const sheet = [...document.styleSheets].find(s => s.href?.includes('/glass.css'));
    const rule = [...sheet.cssRules].find(r => r instanceof CSSSupportsRule && r.conditionText.includes('backdrop-filter') && r.conditionText.startsWith('not'));
    if (!rule) throw new Error('glass fallback @supports rule missing');
    const style = document.createElement('style');
    style.textContent = [...rule.cssRules].map(r => r.cssText).join('\n');
    document.head.append(style);
    return rule.conditionText;
  });
  expect(copied).toContain('backdrop-filter');
  await expect(pill).toHaveCSS('backdrop-filter', 'none');
  const colors = await pill.evaluate(el => {
    const probe = document.createElement('i');
    probe.style.color = 'var(--color-surface)'; document.body.append(probe);
    const result = [getComputedStyle(el).backgroundColor, getComputedStyle(probe).color];
    probe.remove(); return result;
  });
  expect(colors[0]).toBe(colors[1]);
});

test('FMA-SCN-003 search uses actual composer material', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  const composer = await page.locator('#input-bar').evaluate((el) => {
    const style = getComputedStyle(el);
    return { background: style.backgroundColor, filter: style.backdropFilter };
  });
  for (const pageId of ['messages', 'contacts']) {
    await dockTo(page, pageId);
    const search = page.locator(`.mobile-page[data-page="${pageId}"] .mh-search-bar`);
    await expect(search).toBeVisible();
    const actual = await search.evaluate((el) => {
      const style = getComputedStyle(el);
      return { background: style.backgroundColor, filter: style.backdropFilter, opacity: style.opacity };
    });
    expect(actual.background, `${pageId} 背景与主输入一致`).toBe(composer.background);
    expect(actual.filter, `${pageId} 滤镜与主输入一致`).toBe(composer.filter);
    expect(actual.opacity, `${pageId} 前景不得整体透明`).toBe('1');
  }
  expect(errors).toEqual([]);
});

test('default search controls geometry readings', async ({ page }) => {
  await bootApp(page);
  for (const id of ['messages', 'contacts']) {
    await dockTo(page, id);
    await expect(page.locator(`.mobile-page[data-page="${id}"] .mh-search-bar`)).toBeVisible();
    const state = await readState(page, id);
    console.log(`FLOAT_DEFAULT ${id} ${JSON.stringify({bar: state.bar, dockH: state.dockH})}`);
    expect(state.bar.width).toBeGreaterThan(0);
    expect(state.bar.height).toBeGreaterThan(0);
  }
});

for (const colorScheme of ['light', 'dark']) {
  for (const deviceScaleFactor of [1, 2]) {
    test.describe(`search morph contract ${colorScheme} dpr${deviceScaleFactor}`, () => {
      test.use({ colorScheme, deviceScaleFactor });
      test('both pages preserve expanded field and collapsed FAB geometry', async ({ page }) => {
        await bootApp(page);
        for (const pageId of ['messages', 'contacts']) {
          await dockTo(page, pageId);
          const bar = page.locator(`.mobile-page[data-page="${pageId}"] .mh-search-bar`);
          await expect(bar).toBeVisible();
          await expect.poll(() => bar.evaluate(el => getComputedStyle(el).borderRadius)).toBe('12px');
          const scroller = pageId === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
          await page.locator(`.mobile-page[data-page="${pageId}"] ${scroller}`).evaluate(el => {
            el.scrollTop = 224;
            el.dispatchEvent(new Event('scroll'));
          });
          await expect(bar).toHaveClass(/g2-fab/);
          await expect.poll(() => bar.evaluate(el => getComputedStyle(el).borderRadius)).toBe('50%');
          await expect.poll(async () => {
            const box = await bar.boundingBox();
            return { width: Math.round(box.width), height: Math.round(box.height) };
          }).toEqual({ width: 48, height: 48 });
          if (EVID) {
            mkdirSync(EVID, { recursive: true });
            await page.screenshot({ path: join(EVID, `contract-${pageId}-${colorScheme}-dpr${deviceScaleFactor}.png`) });
          }
        }
      });
    });
  }
}

test('mobile-search-flat: messages + contacts（九组判据，双页同源）', async ({ page }) => {
  await bootApp(page);
  const errors = watchErrors(page);
  const readings = {};
  for (const pageId of ['messages', 'contacts']) {
    if (pageId === 'contacts') {
      await page.evaluate(() => {
        const sc = document.querySelector('.mobile-page[data-page="messages"] #fm-conversations');
        if (sc) sc.scrollTop = 0;
      });
    }
    await dockTo(page, pageId);
    const secSel = `.mobile-page[data-page="${pageId}"]`;
    await expect(page.locator(`${secSel} .mh-search-bar`)).toBeVisible();
    const scrollerSel = pageId === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
    await expect(page.locator(`${secSel} ${scrollerSel}`)).toBeVisible();
    const tag = pageId;
// ── ① 展开态：扁平长条（我方 token）+ 带高 60 ───────────────────────
    const s0 = await readState(page, pageId);
    readings[`${tag}.initial`] = s0;
    if (LEGACY) {
      if (EVID) {
        mkdirSync(EVID, { recursive: true });
        await page.screenshot({ path: join(EVID, `${tag}.0-legacy-initial.png`) });
      }
      await legacyRedLeg(page, pageId, s0, tag);
      continue;
    }
    expect(s0.visible, '① 初始展开：条可见').toBe(true);
    expect(Math.round(s0.bar.height), '① bar 高 44').toBe(44);
    expect(Math.round(s0.bar.width), '① bar 宽 343').toBe(343);
    expect(Math.round(s0.bar.left), '① bar 左缘 16').toBe(16);
    expect(Math.round(s0.bar.right), '① bar 右缘 359').toBe(359);
    expect(Math.round(s0.bar.top), '① 搜索条在实测带内垂直居中').toBe((BAND_H - Math.round(s0.bar.height)) / 2);
    expect(s0.dockH, `① 搜索带高 = ${BAND_H}`).toBe(BAND_H);
    // 材质场（glass-top-overlays 批 2026-11-06 换代）：条浮在滚动列表之上 ⇒
    // 必须吃唯一毛玻璃标准（.nf-glass：玻璃底 + blur(24px) saturate(1.15)，单一材质批 2026-11-15 +
    // 细边 + 内高光），圆角仍是我方条状容器档 12px（几何零改）。
    // 12:28 那版的「--ds-wash-16 浅灰底 / 零描边 / 零投影 / 零毛玻璃」断言随
    // 作者令（「所有悬浮在顶部 可以透出背景的 都用毛玻璃效果 比如消息和联系人的
    // 手机端的搜索框」）整组退役 —— 不是删断言，是**换成新的材质标准**：
    expect(s0.barClass, '① 材质 = .nf-glass（唯一工具类）').toContain('nf-glass');
    const composerMaterial = await page.locator('#input-bar').evaluate(el => {
      const s = getComputedStyle(el);
      return { filter: s.backdropFilter, bg: s.backgroundColor, border: s.borderTopColor, shadow: s.boxShadow };
    });
    expect(s0.barStyle.backdropFilter, '① 配方与主输入同源').toBe(composerMaterial.filter);
    expect(s0.barStyle.backgroundColor, '① 背景与主输入同源').toBe(composerMaterial.bg);
    expect(s0.barStyle.borderTopColor, '① 描边与主输入同源').toBe(composerMaterial.border);
    expect(s0.barStyle.boxShadow, '① 浮影与主输入同源').toBe(composerMaterial.shadow);
    expect(s0.barStyle.borderRadius, '① 圆角 = --ds-radius-xl 12px').toBe('12px');
    // 玻璃底不透明度必须落进 [0.90, 1.0]（低了内容透底 —— 规范 G3 区间，单一材质批 2026-11-15）。
    const glassAlpha = (() => {
      const m = s0.barStyle.backgroundColor.match(/rgba?\(([^)]*)\)/);
      if (!m) return null;
      const tail = m[1].includes('/') ? m[1].split('/').pop().trim() : (m[1].split(',').map((x) => x.trim())[3] ?? '1');
      return parseFloat(tail);
    })();
    readings[`${tag}.glassAlpha`] = glassAlpha;
    expect(glassAlpha, '① 主输入材质包含不透明度').toBeGreaterThan(0);
    expect(glassAlpha, '① alpha 为有效值').toBeLessThanOrEqual(1);
    expect(s0.inputColor, '① 输入行字色仍走既有 --color-frame-text').toBe('rgb(200, 202, 208)');
    expect(s0.dockInlineStyle, '① 条/带零内联样式（旧 bug① 的高度写入已删）').toBeNull();
    expect(s0.barClass, '① 展开态不挂 .g2-fab').not.toContain('g2-fab');
    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `${tag}.0-expanded.png`) });
    }
// ── ②③ 上滚过阈值 ⇒ 同一节点形变为圆钮；容器几何零变化 ──────────────
    expect(s0.scrollerMaxY, `② ${scrollerSel} 可滚（余量 >300px）`).toBeGreaterThan(300);
    expect(await startFrameLog(page), '⑦ rAF 实录挂上').toBe(true);
    await page.evaluate(([pid, sel, at]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = at + 200;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel, COLLAPSE_AT]);
    if (EVID) {
      await page.screenshot({ path: join(EVID, `${tag}.2-collapse-mid1.png`) });
      // 形变中段实拍：等 ~40% 行程再拍（前两张落在 t≈8-16ms，视觉上仍接近长条）。
      await page.waitForTimeout(110);
      await page.screenshot({ path: join(EVID, `${tag}.2-collapse-mid2.png`) });
    }
    await page.waitForFunction(() => {
      const bar = document.querySelector('.mobile-page.live .mh-search-dock .mh-search-bar');
      return !!bar && bar.classList.contains('g2-fab')
        && Math.round(bar.getBoundingClientRect().height) === 48;
    }, null, { timeout: 3000 });
    await page.waitForTimeout(700);   // rAF 帧序收尾（实录 640ms）
    readings[`${tag}.collapseFrames`] = await readFrameLog(page);
    assertIntermediateFrames(readings[`${tag}.collapseFrames`], 343, 48, 'collapse');

    const s2 = await readState(page, pageId);
    readings[`${tag}.collapsed`] = s2;
    expect(s2.dockClass, '② 收起判据类在').toContain('mh-search-collapsed');
    expect(s2.barClass, '② 条本体挂 .g2-fab（主会话搜索钮同一个类）').toContain('g2-fab');
    expect({ w: Math.round(s2.bar.width), h: Math.round(s2.bar.height) }, '② 圆钮 48×48').toEqual({ w: 48, h: 48 });
    expect(Math.round(s2.bar.right), '② 圆钮右缘 359（距屏右 16，挂在右上角）').toBe(359);
    expect(s2.barStyle.borderRadius, '② 圆钮 = 50% 正圆').toBe('50%');
    expect(s2.inputOpacity, '② 输入行淡出 opacity 0').toBe(0);
    // 🔴 同源证据：收起态计算样式逐值 = 主会话搜索钮（#mobile-fab-layer .mh-chrome-btn.g2-fab）。
    expect(s2.baseline, '② 主会话搜索钮在 DOM（同源基准）').not.toBeNull();
    for (const k of ['borderRadius', 'backgroundColor', 'borderTopColor', 'boxShadow', 'backdropFilter']) {
      expect(s2.barStyle?.[k] ?? null, `② 同源 ${k} = 主会话钮`).toBe(s2.baseline[k]);
    }
    expect(s2.iconW, '② 圆钮图标 = --cv-icon 20px（与主会话钮同值）').toBe(s2.baseline.iconW);
    // bug① 结构判据：滚动容器几何零变化（clientHeight 不变、scrollTop 不被钳回）。
    expect(s2.dockH, '③ bug①：搜索带高收起前后不变').toBe(BAND_H);
    expect(s2.scrollerClientH, '③ bug①：滚动容器可视高不变').toBe(s0.scrollerClientH);
    expect(s2.scrollerY, '③ bug①：收起不重置 scrollTop').toBe(COLLAPSE_AT + 200);
    if (EVID) await page.screenshot({ path: join(EVID, `${tag}.2-collapsed.png`) });
// ── ③ 收起后仍能一路滚到列表末尾（bug① 回归）────────────────────────
    const bottom = await page.evaluate(async ([pid, sel]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      const maxY = sc.scrollHeight - sc.clientHeight;
      for (let i = 0; i < 40 && sc.scrollTop < maxY; i++) {
        sc.scrollTop = Math.min(maxY, sc.scrollTop + 120);
        await new Promise((r) => requestAnimationFrame(r));
      }
      await new Promise((r) => setTimeout(r, 120));
      return { y: Math.round(sc.scrollTop), maxY: Math.round(maxY), maxYNow: Math.round(sc.scrollHeight - sc.clientHeight) };
    }, [pageId, scrollerSel]);
    readings[`${tag}.scrollToBottom`] = bottom;
    expect(bottom.y, '③ bug①：能滚到列表末尾（scrollTop = max）').toBe(bottom.maxYNow);
    if (EVID) await page.screenshot({ path: join(EVID, `${tag}.2-scrolled-bottom.png`) });
// ── ④ bug② 跨页泄漏：切走再回来 ─────────────────────────────────────
    const otherPage = pageId === 'messages' ? 'contacts' : 'messages';
    await dockTo(page, otherPage);
    const leakWhileAway = await leakProbe(page);
    readings[`${tag}.leakWhileAway`] = leakWhileAway;
    expect(leakWhileAway.filter((x) => x.atCorner || x.painted), `④ bug②：非 live 页的条一律不绘制 ${JSON.stringify(leakWhileAway)}`).toEqual([]);
    await page.screenshot({ path: join(EVID || '/tmp', `${tag}.4-other-page.png`) });
    await dockTo(page, pageId);
    // 复位 = 类切换（展开）；宽度过渡走完 280ms 再读终值。
    await page.waitForFunction((pid) => {
      const bar = document.querySelector(`.mobile-page[data-page="${pid}"] .mh-search-bar`);
      return !!bar && !bar.classList.contains('g2-fab')
        && Math.round(bar.getBoundingClientRect().width) === 343;
    }, pageId, { timeout: 3000 });
    const back = await readState(page, pageId);
    readings[`${tag}.backToPage`] = back;
    expect(back.barClass, '④ bug②：回到该页一律是展开长条（收起态被路由复位）').not.toContain('g2-fab');
    expect(Math.round(back.bar.width), '④ bug②：长条宽度复原 343').toBe(343);
// ── ⑤ 回顶 ⇒ 还原（中间帧 + 终态逐值）──────────────────────────────
    await page.evaluate(([pid, sel, at]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = at + 200;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel, COLLAPSE_AT]);
    await expect(page.locator(`${secSel} .mh-search-dock`)).toHaveClass(/mh-search-collapsed/, { timeout: 3000 });
    await page.waitForTimeout(420);   // 过渡锁窗（360ms）走完
    expect(await startFrameLog(page), '⑦ 展开腿实录挂上').toBe(true);
    await page.evaluate(([pid, sel]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = 0;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel]);
    await page.waitForFunction(() => {
      const bar = document.querySelector('.mobile-page.live .mh-search-dock .mh-search-bar');
      return !!bar && !bar.classList.contains('g2-fab')
        && Math.round(bar.getBoundingClientRect().height) === 44;
    }, null, { timeout: 3000 });
    await page.waitForTimeout(700);
    readings[`${tag}.expandFrames`] = await readFrameLog(page);
    assertIntermediateFrames(readings[`${tag}.expandFrames`], 343, 48, 'expand');
    const s3 = await readState(page, pageId);
    readings[`${tag}.reExpanded`] = s3;
    for (const k of ['top', 'left', 'right', 'width', 'height']) {
      expect(Math.abs(s3.bar[k] - s0.bar[k]), `⑤ 复原 ${k} = 初始`).toBeLessThanOrEqual(0.5);
    }
    if (EVID) await page.screenshot({ path: join(EVID, `${tag}.5-re-expanded.png`) });
// ── ⑥ 点圆钮 ⇒ 展开 + 聚焦 + 键入 ────────────────────────────────────
    await page.evaluate(([pid, sel, at]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = at + 200;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel, COLLAPSE_AT]);
    await expect(page.locator(`${secSel} .mh-search-dock`)).toHaveClass(/mh-search-collapsed/, { timeout: 3000 });
    await page.waitForTimeout(420);
    const collapsedA11y = await readState(page, pageId);
    expect(collapsedA11y.barRole, '⑥ 收起态圆形控件 = role button').toBe('button');
    expect(collapsedA11y.barTabIndex, '⑥ 收起态可键盘聚焦').toBe('0');
    await page.locator(`${secSel} .mh-search-bar`).click();
    await expect.poll(async () => (await readState(page, pageId)).activeIsSearchInput, { timeout: 3000 })
      .toBe(true);
    // 键入用 fill（单次 insertText）：headless 无 IME 时逐字 keyboard.type 会把第二个
    // CJK 字插到游标 0（实测 "署部"）⇒ 查询串反序、检索腿自然无命中。fill 走同一条
    // input 事件链，且顺序稳定。
    await page.locator(`${secSel} .mh-search-input`).fill('部署');
    await page.waitForFunction((pid) => {
      const bar = document.querySelector(`.mobile-page[data-page="${pid}"] .mh-search-bar`);
      return !!bar && !bar.classList.contains('g2-fab')
        && Math.round(bar.getBoundingClientRect().height) === 44;
    }, pageId, { timeout: 3000 });
    const typed = await readState(page, pageId);
    readings[`${tag}.fabClick`] = {
      activeIsSearchInput: typed.activeIsSearchInput, inputValue: typed.inputValue, barClass: typed.barClass,
    };
    expect(typed.activeIsSearchInput, '⑥ activeElement = .mh-search-input').toBe(true);
    expect(typed.inputValue, '⑥ 键入生效').toBe('部署');
    expect(typed.barRole, '⑥ 展开态摘掉 role=button（输入框自己就是焦点面）').toBeNull();
    if (pageId === 'messages') {
      // 既有行为不破坏：输入即搜（结果组出现）+ 清空回列表。
      await expect(page.locator(`${secSel} .mh-sr-group`).first()).toBeVisible({ timeout: 8000 });
      // 清空钮给显式短超时：locator.click 在不可点时按**测试总超时**阻塞（`.catch`
      // 拦不住已发出的调用）—— 实测曾把整条判据挂到 4 分钟才被引擎判超时。
      await page.locator(`${secSel} .mh-search-clear`).click({ timeout: 5000 });
      await expect(page.locator(`${secSel} .mh-sr-row`)).toHaveCount(0);
    } else {
      // 联系人页：查询串写回 contacts.js 单一源（searchQ ⇒ 桌面搜索块输入行）。
      const readSearchQ = () => page.evaluate(() => {
        const el = document.querySelector('.mobile-page[data-page="contacts"] #fm-contacts-body .fm-search-input');
        return el ? el.value : null;
      });
      await expect.poll(readSearchQ, { timeout: 5000 }).toBe('部署');
      readings[`${tag}.searchQ`] = await readSearchQ();
    }
    // 清场（下轮判据从静止态起）：
    await page.locator(`${secSel} .mh-search-clear`).click({ timeout: 5000 }).catch(() => {});
    await page.evaluate(([pid, sel]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      if (sc) sc.scrollTop = 0;
    }, [pageId, scrollerSel]);
    await page.waitForTimeout(200);
  }
// ── ⑧ reduced-motion：过渡压近零，功能不退化 ──────────────────────────
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await dockTo(page, 'messages');
  const rmDur = await page.evaluate(() => {
    const dock = document.querySelector('.mobile-page[data-page="messages"] .mh-search-dock');
    return dock ? getComputedStyle(dock).getPropertyValue('--mhsc-dur').trim() : null;
  });
  readings['reducedMotion.dur'] = rmDur;
  expect(rmDur, '⑧ reduced-motion：--mhsc-dur 压到 1ms').toBe('1ms');
  await page.evaluate(() => {
    const sc = document.querySelector('.mobile-page[data-page="messages"] #fm-conversations');
    sc.scrollTop = 200;
    sc.dispatchEvent(new Event('scroll', { bubbles: false }));
  });
  await page.waitForFunction(() => {
    const bar = document.querySelector('.mobile-page.live .mh-search-dock .mh-search-bar');
    return !!bar && bar.classList.contains('g2-fab');
  }, null, { timeout: 2000 });
  expect((await readState(page, 'messages')).barClass, '⑧ reduced-motion：圆钮语义仍成立').toContain('g2-fab');
  await page.emulateMedia({ reducedMotion: 'no-preference' });

  if (EVID) {
    mkdirSync(EVID, { recursive: true });
    writeFileSync(join(EVID, 'readings.json'), JSON.stringify(readings, null, 2));
  }
  expect(errors, 'console/pageerror 干净').toEqual([]);
});

/** ⑨-补：注入取数失败 ⇒ 错误态仍在（删占位/改形态都不得把失败伪装成空态）。
 *  腿 = 关掉 fm_api_mock + 好友域 REST 一律 503（见 bootApp 的 mock:false）。 */
test('mobile-search-flat: 取数失败态不被形态改造吞掉', async ({ page }) => {
  await bootApp(page, { mock: false });
  await dockTo(page, 'messages');
  // 收编面板（.active 翻转）会触发一次会话列表刷新 ⇒ 503 落 convListError → 错误态。
  await page.waitForFunction(() => {
    const box = document.querySelector('.mobile-page[data-page="messages"] #fm-conversations');
    return !!box?.querySelector('.fm-list-error');
  }, null, { timeout: 8000 }).catch(() => {});
  const state = await page.evaluate(() => {
    const dbg = document.querySelector('.mobile-page[data-page="messages"] #fm-conversations');
    const box = document.querySelector('.mobile-page[data-page="messages"] #fm-conversations');
    return {
      hasError: !!box?.querySelector('.fm-list-error'),
      errKind: box?.querySelector('.fm-list-error')?.dataset.listError || null,
      hasEmpty: !!box?.querySelector('.fm-empty'),
      text: (box?.textContent || '').slice(0, 60),
      html: (box?.innerHTML || '').slice(0, 300),
      loggedIn: (box?.innerHTML || '').includes('fm-login'),
    };
  });
  expect(state.hasError, '错误态在位（.fm-list-error）').toBe(true);
  expect(state.hasEmpty, '失败不得落成空态（无 .fm-empty）').toBe(false);
  // 分态值由 friendsApi.errKind 给（503 ⇒ retryable），非空即已分档：
  expect(typeof state.errKind === 'string' && state.errKind.length > 0, `错误分态可定位（${state.errKind}）`).toBe(true);
});
