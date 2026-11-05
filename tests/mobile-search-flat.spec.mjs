// mobile-search-flat.spec.mjs — 手机搜索扁平化 + 滚动收起批主 spec（件七）
// 作者令 2026-11-04 13:07 逐字：「手机端 把联系人和消息的顶部的搜索款 改为扁平化
// 设计 同时默认展开 向上滚动内容的时候则收为悬浮在顶部右上角的一个搜索按钮
// 样式和主会话的一致」
//
// 判据六组（消息页 + 联系人页各跑一遍 = ⑥ 两页同源）：
//   ① 初始展开态几何：.mh-search-bar 44×343 @ left16/right359、可见；计算样式
//      扁平场（backdrop-filter none / border-top 0 / radius 0px / box-shadow
//      none / 底 = --color-surface 解析值 / 字色与 placeholder 档位）—— 逐值给出。
//   ② 内容上滚过阈值（真实滚动容器 scrollTop > 24）⇒ 常驻条连续收起、右上角
//      浮钮出现（28×28，rect.right = 359 / rect.top = 16 ⇒ 距视口右上角各 16px）。
//   ③ 回滚到顶（scrollTop ≤ 4）⇒ 常驻条复原（bar rect 与初始逐值相同）。
//   ④ 点浮钮 ⇒ 展开且 document.activeElement 命中 .mh-search-input、键入生效。
//   ⑤ 连续过渡：收起 / 展开各 ≥2 个中间帧（rAF 实录 0 < h < h0 且 0 < opacity < 1，
//      高度单调）+ 实拍 PNG（evidence/…/search7/）。
//   ⑥ 消息页 + 联系人页两页同源。
//
// 改前树腿：SEARCH7_LEGACY_TREE=1 + SEARCH7_WEB_ROOT=<77afefc77 导出的 web 树>
// ⇒ 同一脚本按「改前红口径」断言（玻璃胶囊在位 / 滚动不收 / 无浮钮），红即证明
// 本批判据真的钉在改动上（不是恒真断言）。
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
const LEGACY = !!process.env.SEARCH7_LEGACY_TREE;
const EVID = process.env.SEARCH7_EVID_DIR || null;

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具（好友域 mock 种子：30 会话 + 30 好友 ⇒ 两页列表都可滚；
 *  消息正文含共同关键词「部署」，users 含 xintongxue）。 */
async function bootApp(page) {
  // origin 档位：WebKit 拒用受限端口（实测 "Not allowed to use restricted network
  // port 1: http://localhost:1/" ⇒ 双引擎各取 ≥1024 的虚拟 origin。请求全由
  // page.route 拦截 fulfill，**不监听本地端口、不起服务**（零端口占用；8380/8381
  // = 本批分配档，仅作 origin 标识）。
  const engine = page.context().browser()?.browserType().name() || 'chromium';
  const ORIGIN = engine === 'webkit' ? 'http://localhost:8381/' : 'http://localhost:8380/';
  await page.addInitScript(() => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
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
    // 行数下限：列表必须可滚（滚动收起腿的载体；无内容时判据 ②③④ 无载体）。
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
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
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

/** 单一读数探针：几何（rect）+ 计算样式 + 焦点。 */
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
    const fab = section?.querySelector('.mh-search-fab') || null;
    const fabCs = fab ? getComputedStyle(fab) : null;
    const input = dock?.querySelector('.mh-search-input') || null;
    const icon = fab?.querySelector('svg') || null;
    const scroller = section?.querySelector('#fm-conversations')
      || section?.querySelector('#fm-contacts-body') || null;
    return {
      dockH: dock ? dock.getBoundingClientRect().height : null,
      dockClass: dock ? dock.className : null,
      dockInlineH: dock ? dock.style.height : null,
      bar: rectOf(bar),
      visible: !!bar && getComputedStyle(bar).visibility === 'visible'
        && bar.getBoundingClientRect().height > 0,
      barStyle: cs ? {
        backdropFilter: cs.webkitBackdropFilter || cs.backdropFilter || 'none',
        borderTopWidth: cs.borderTopWidth,
        borderBottomWidth: cs.borderBottomWidth,
        borderRadius: cs.borderRadius,
        boxShadow: cs.boxShadow,
        backgroundColor: cs.backgroundColor,
        color: cs.color,
      } : null,
      inputColor: input ? getComputedStyle(input).color : null,
      inputPlaceholderColor: input
        ? getComputedStyle(input, '::placeholder').color : null,
      fab: rectOf(fab),
      fabOpacity: fabCs ? +fabCs.opacity : null,
      fabVisibility: fabCs ? fabCs.visibility : null,
      // 触屏热区外扩（::before，mobileLayer.css .mf-strip-chip::before 同款惯例）。
      fabHitW: fab ? getComputedStyle(fab, '::before').width : null,
      fabHitH: fab ? getComputedStyle(fab, '::before').height : null,
      fabStyle: fabCs ? {
        borderRadius: fabCs.borderRadius,
        background: fabCs.backgroundColor,
        borderTopWidth: fabCs.borderTopWidth,
        borderTopColor: fabCs.borderTopColor,
        color: fabCs.color,
        iconW: icon ? getComputedStyle(icon).width : null,
      } : null,
      activeEl: document.activeElement
        ? `${document.activeElement.tagName.toLowerCase()}.${(document.activeElement.className || '').split(' ')[0]}`
        : null,
      activeIsSearchInput: !!document.activeElement?.classList?.contains('mh-search-input'),
      inputValue: input ? input.value : null,
      scrollerY: scroller ? scroller.scrollTop : null,
      scrollerScrollable: scroller ? scroller.scrollHeight - scroller.clientHeight : null,
    };
  }, pageId);
}

/** rAF 实录（帧序存 document.body.dataset，零全局变量）：dock 高度/透明度 + 浮钮透明度。 */
function startFrameLog(page) {
  return page.evaluate(() => {
    const dock = document.querySelector('.mobile-page.live .mh-search-dock');
    const fab = document.querySelector('.mobile-page.live .mh-search-fab');
    if (!dock) return false;
    document.body.dataset.mhscFrames = '';
    const frames = [];
    const t0 = performance.now();
    const tick = () => {
      const t = performance.now() - t0;
      frames.push({
        t: Math.round(t),
        dockH: Math.round(dock.getBoundingClientRect().height * 100) / 100,
        dockOpacity: Math.round(Number(getComputedStyle(dock).opacity) * 1000) / 1000,
        fabOpacity: fab ? Math.round(Number(getComputedStyle(fab).opacity) * 1000) / 1000 : null,
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

/** ⑤ 中间帧判据：0 < h < h0 且 0 < opacity < 1 的帧 ≥2，且高度单调。 */
function assertIntermediateFrames(frames, h0, phase) {
  expect(Array.isArray(frames) && frames.length > 2, `⑤ ${phase}: rAF 帧序非空（${frames ? frames.length : 0} 帧）`).toBe(true);
  const mid = frames.filter((f) => f.dockH > 0.5 && f.dockH < h0 - 0.5 && f.dockOpacity > 0.02 && f.dockOpacity < 0.98);
  expect(mid.length, `⑤ ${phase}: 中间帧 ≥2（实测 ${mid.length}）`).toBeGreaterThanOrEqual(2);
  const hs = mid.map((f) => f.dockH);
  const mono = phase === 'collapse'
    ? hs.every((h, i) => i === 0 || h <= hs[i - 1])
    : hs.every((h, i) => i === 0 || h >= hs[i - 1]);
  expect(mono, `⑤ ${phase}: 中间帧高度单调 ${JSON.stringify(hs)}`).toBe(true);
  return mid;
}

/** 改前树红腿：把本批六组判据对着 77afefc77 的 web 树跑一遍，逐条收集**未落地**
 *  的判据（= 改前红清单）。红证明判据钉在改动上，不是恒真断言。 */
async function legacyRedLeg(page, pageId, s0, readings, tag) {
  const unmet = [];
  if (s0.barStyle.backdropFilter !== 'none') unmet.push(`① 未扁平：backdrop-filter=${s0.barStyle.backdropFilter}`);
  if (s0.barStyle.borderRadius !== '0px') unmet.push(`① 未扁平：圆角 ${s0.barStyle.borderRadius}（胶囊）`);
  if (s0.barStyle.boxShadow !== 'none') unmet.push(`① 未扁平：投影 ${s0.barStyle.boxShadow.slice(0, 40)}…`);
  const scrollerSel = pageId === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
  await page.evaluate(([pid, sel, at]) => {
    const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
    if (!sc) return;
    sc.scrollTop = at + 200;
    sc.dispatchEvent(new Event('scroll', { bubbles: false }));
  }, [pageId, scrollerSel, COLLAPSE_AT]);
  await page.waitForTimeout(500);
  const after = await readState(page, pageId);
  readings[`${tag}.legacyAfterScroll`] = after;
  if (after.dockClass.includes('mh-search-collapsed') || Math.round(after.dockH) === 0) {
    unmet.push('② 改前竟然收了（不应出现收起态）');
  } else {
    unmet.push(`② 上滚不收：dock 高仍 ${Math.round(after.dockH)}`);
  }
  if (after.fab) unmet.push('② 改前竟然有收起浮钮（不应出现）');
  else unmet.push('② 无收起浮钮（.mh-search-fab 不在）');
  unmet.push('④ 无浮钮可点（展开+聚焦判据无载体）');
  unmet.push('⑤ 无收起/展开过渡（无中间帧可采）');
  expect(unmet, `改前树腿（${pageId}）必红：${unmet.join(' | ')}`).toEqual([]);
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('mobile-search-flat: messages + contacts（六组判据，双页同源）', async ({ page }) => {
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

    // ── ① 初始展开态几何 + 扁平场计算样式 ────────────────────────────────
    const s0 = await readState(page, pageId);
    readings[`${tag}.initial`] = s0;
    if (LEGACY) {
      if (EVID) {
        mkdirSync(EVID, { recursive: true });
        await page.screenshot({ path: join(EVID, `${tag}.0-legacy-initial.png`) });
      }
      await legacyRedLeg(page, pageId, s0, readings, tag);
      continue;
    }
    expect(s0.visible, '① 初始展开：条可见').toBe(true);
    expect(Math.round(s0.bar.height), '① bar 高 44').toBe(44);
    expect(Math.round(s0.bar.width), '① bar 宽 343').toBe(343);
    expect(Math.round(s0.bar.left), '① bar 左缘 16').toBe(16);
    expect(Math.round(s0.bar.right), '① bar 右缘 359').toBe(359);
    expect(s0.bar.top, '① bar 顶 ≤ 20（贴页面顶：dock 8 + 页面底 0）').toBeLessThanOrEqual(20);
    // 扁平场（撤胶囊：毛玻璃/描边/投影/全圆角全无）。
    expect(s0.barStyle.backdropFilter, '① backdrop-filter none').toBe('none');
    expect(s0.barStyle.borderRadius, '① 圆角 0px（999 胶囊退役）').toBe('0px');
    expect(s0.barStyle.borderTopWidth, '① 无上描边').toBe('0px');
    expect(s0.barStyle.borderBottomWidth, '① 扁平分隔线 1px').toBe('1px');
    expect(s0.barStyle.boxShadow, '① 无投影').toBe('none');
    // 输入行仍可辨：底 = --color-surface 暗档（≠ 页面底 --color-bg #13161c）；
    // 字色/placeholder 仍走既有 --color-frame-text / --color-frame-text-muted。
    expect(s0.barStyle.backgroundColor, '① 扁平场底 = --color-surface 暗档').toBe('rgb(28, 31, 39)');
    expect(s0.inputColor, '① 输入行字色 = --color-frame-text 暗档').toBe('rgb(200, 202, 208)');
    expect(['rgb(129, 131, 138)', 'rgb(200, 202, 208)'], '① placeholder 仍在既有 muted 族').toContain(s0.inputPlaceholderColor);
    // 浮钮 DOM 在位但隐性（默认展开 = 缺省态）。
    expect(s0.fab, '① 浮钮 DOM 在位').not.toBeNull();
    expect(s0.fabOpacity, '① 展开态浮钮 opacity 0').toBe(0);
    expect(Math.round(s0.dockH), `① dock 初始高 ≥52（8 padding + 44 bar + 结果区留白；实测 ${Math.round(s0.dockH)}）`).toBeGreaterThanOrEqual(52);
    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `${tag}.0-expanded.png`) });
    }

    // ── ②⑤ 上滚过阈值 ⇒ 连续收起 + 浮钮右上角显影 ───────────────────────
    expect(s0.scrollerScrollable, `② ${scrollerSel} 可滚（余量 >300px）`).toBeGreaterThan(300);
    expect(await startFrameLog(page), '⑤ rAF 实录挂上').toBe(true);
    await page.evaluate(([pid, sel, at]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = at + 200;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel, COLLAPSE_AT]);
    if (EVID) {
      await page.screenshot({ path: join(EVID, `${tag}.2-collapse-mid1.png`) });
      await page.screenshot({ path: join(EVID, `${tag}.2-collapse-mid2.png`) });
    }
    await page.waitForFunction(() => {
      const dock = document.querySelector('.mobile-page.live .mh-search-dock');
      return !!dock && dock.classList.contains('mh-search-collapsed')
        && Math.round(dock.getBoundingClientRect().height) === 0;
    }, null, { timeout: 3000 });
    await page.waitForTimeout(700);   // rAF 帧序收尾（实录 640ms）
    const collapseFrames = await readFrameLog(page);
    readings[`${tag}.collapseFrames`] = collapseFrames;
    assertIntermediateFrames(collapseFrames, Math.round(s0.dockH), 'collapse');

    const s2 = await readState(page, pageId);
    readings[`${tag}.collapsed`] = s2;
    expect(Math.round(s2.dockH), '② dock 收起终态高 0').toBe(0);
    expect(s2.dockClass, '② 收起判据类在').toContain('mh-search-collapsed');
    expect(s2.fab, '② 浮钮在位').not.toBeNull();
    expect({ w: Math.round(s2.fab.width), h: Math.round(s2.fab.height) }, '② 浮钮 28×28').toEqual({ w: 28, h: 28 });
    expect(s2.fabOpacity, '② 浮钮显影 opacity 1').toBe(1);
    expect(s2.fabVisibility, '② 浮钮 visibility visible').toBe('visible');
    // 右上角：375 − 359 = 16（距右缘）/ top 16（距顶缘 + safe=0）。
    readings[`${tag}.fabCornerGap`] = { right: Math.round(375 - s2.fab.right), top: Math.round(s2.fab.top) };
    expect(readings[`${tag}.fabCornerGap`], '② 浮钮距视口右上角 = (16,16)').toEqual({ right: 16, top: 16 });
    // 浮钮 = 主会话 #search-btn 同族档位（css/chat.css:146-174 逐项）。
    expect(s2.fabStyle.borderRadius, '④族 圆角 6px').toBe('6px');
    expect(s2.fabStyle.background, '④族 transparent 底').toBe('rgba(0, 0, 0, 0)');
    expect(s2.fabStyle.borderTopWidth, '④族 1px 描边档').toBe('1px');
    expect(s2.fabStyle.borderTopColor, '④族 描边 transparent').toBe('rgba(0, 0, 0, 0)');
    expect(s2.fabStyle.color, '④族 muted 字色').toBe('rgb(129, 131, 138)');
    expect(s2.fabStyle.iconW, '④族 16px 图标档').toBe('16px');
    // 触屏热区 ≥44（视觉本体 28 不变；::before 外扩到 --mhit 44）。
    expect(s2.fabHitW, '④ 热区宽 44（::before 外扩）').toBe('44px');
    expect(s2.fabHitH, '④ 热区高 44（::before 外扩）').toBe('44px');
    if (EVID) await page.screenshot({ path: join(EVID, `${tag}.2-collapsed.png`) });

    // ── ③⑤ 回滚到顶 ⇒ 再展开（连续过渡 + 中间帧）─────────────────────────
    expect(await startFrameLog(page), '⑤ 展开腿实录挂上').toBe(true);
    await page.evaluate(([pid, sel]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = 0;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel]);
    if (EVID) {
      await page.screenshot({ path: join(EVID, `${tag}.3-expand-mid1.png`) });
      await page.screenshot({ path: join(EVID, `${tag}.3-expand-mid2.png`) });
    }
    await page.waitForFunction(() => {
      const dock = document.querySelector('.mobile-page.live .mh-search-dock');
      const bar = dock ? dock.querySelector('.mh-search-bar') : null;
      return !!bar && !dock.classList.contains('mh-search-collapsed')
        && Math.round(bar.getBoundingClientRect().height) === 44;
    }, null, { timeout: 3000 });
    await page.waitForTimeout(700);
    const expandFrames = await readFrameLog(page);
    readings[`${tag}.expandFrames`] = expandFrames;
    assertIntermediateFrames(expandFrames, Math.round(s0.dockH), 'expand');

    const s3 = await readState(page, pageId);
    readings[`${tag}.reExpanded`] = s3;
    for (const k of ['top', 'left', 'right', 'width', 'height']) {
      expect(Math.abs(s3.bar[k] - s0.bar[k]), `③ 复原 ${k} = 初始 ${Math.round(s0.bar[k] * 100) / 100}`).toBeLessThanOrEqual(0.5);
    }
    expect(s3.fabOpacity, '③ 展开态浮钮退影 opacity 0').toBe(0);
    if (EVID) await page.screenshot({ path: join(EVID, `${tag}.3-re-expanded.png`) });

    // ── ④ 点浮钮 ⇒ 展开 + 聚焦 + 键入 ────────────────────────────────────
    await page.evaluate(([pid, sel, at]) => {
      const sc = document.querySelector(`.mobile-page[data-page="${pid}"] ${sel}`);
      sc.scrollTop = at + 200;
      sc.dispatchEvent(new Event('scroll', { bubbles: false }));
    }, [pageId, scrollerSel, COLLAPSE_AT]);
    await expect(page.locator(`${secSel} .mh-search-dock`)).toHaveClass(/mh-search-collapsed/, { timeout: 3000 });
    await page.waitForTimeout(420);    // 过渡锁窗（360ms）走完再点
    await page.locator(`${secSel} .mh-search-fab`).click();
    await expect.poll(async () => (await readState(page, pageId)).activeIsSearchInput, { timeout: 3000 })
      .toBe(true);
    await page.keyboard.type('部署');
    // 展开过渡走完（--mhsc-dur 280ms）再读终态：条高 44、浮钮退影 opacity 0。
    await page.waitForFunction((pid) => {
      const dock = document.querySelector(`.mobile-page[data-page="${pid}"] .mh-search-dock`);
      const bar = dock ? dock.querySelector('.mh-search-bar') : null;
      const fab = document.querySelector(`.mobile-page[data-page="${pid}"] .mh-search-fab`);
      return !!bar && Math.round(bar.getBoundingClientRect().height) === 44
        && !dock.classList.contains('mh-search-collapsed')
        && (!fab || Number(getComputedStyle(fab).opacity) === 0);
    }, pageId, { timeout: 3000 });
    const typed = await readState(page, pageId);
    readings[`${tag}.fabClick`] = {
      activeEl: typed.activeEl, activeIsSearchInput: typed.activeIsSearchInput,
      inputValue: typed.inputValue, dockH: typed.dockH,
    };
    expect(typed.activeIsSearchInput, '④ activeElement = .mh-search-input').toBe(true);
    expect(typed.inputValue, '④ 键入生效').toBe('部署');
    expect(Math.round(typed.dockH), '④ 展开态 dock 高 > 0').toBeGreaterThan(0);
    expect(typed.fabOpacity, '④ 展开后浮钮退影 opacity 0').toBe(0);
    if (pageId === 'messages') {
      // 既有行为不破坏：输入即搜（结果组出现）+ 清空回列表。
      await expect(page.locator(`${secSel} .mh-sr-group`).first()).toBeVisible({ timeout: 8000 });
      await page.locator(`${secSel} .mh-search-clear`).click();
      await expect(page.locator(`${secSel} .mh-sr-row`)).toHaveCount(0);
    } else {
      // 联系人页：查询串写回 contacts.js 单一源（searchQ ⇒ 桌面搜索块输入行；
      // 450ms 去抖后同一条提交流水，故轮询等落位）。
      const readSearchQ = () => page.evaluate(() => {
        const el = document.querySelector('.mobile-page[data-page="contacts"] #fm-contacts-body .fm-search-input');
        return el ? el.value : null;
      });
      await expect.poll(readSearchQ, { timeout: 5000 }).toBe('部署');
      const sq = await readSearchQ();
      readings[`${tag}.searchQ`] = sq;
      expect(sq, '④ 联系人页 searchQ 单源写回').toBe('部署');
    }
  }

  if (EVID) {
    mkdirSync(EVID, { recursive: true });
    writeFileSync(join(EVID, 'readings.json'), JSON.stringify(readings, null, 2));
  }
  expect(errors, 'console/pageerror 干净').toEqual([]);
});
