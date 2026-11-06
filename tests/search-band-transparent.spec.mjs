// search-band-transparent.spec.mjs — 手机搜索带「透明顶带 / 内容穿行」批主 spec
// （作者令 2026-11-05 17:34 逐字：「联系人和消息的这搜索 缩小成一个按钮之后，应该
//  顶部那部分的透明的，能显示内容，现在是直接被截断了。」＋ 17:37「搜索框，没收缩
//  之前的圆角幅度再大一点」；样式单源 css/mobileSearchFlat.css「透明顶带」块）
//
// 判据（消息页 + 联系人页各跑一遍；浅色 / 深色各一遍）：
//   ① 顶带透明：.mh-search-dock 计算样式 position:absolute / background 全透明 /
//      backdrop-filter none / pointer-events none / z-index ≥1；
//   ② 截断消失（结构判据）：收编滚区顶边 = 页顶（0），不再被带高顶到 y=60；
//      内容让位内距住在滚区**内部**（padding-top = 带高 + 4），可随内容滚走；
//   ③ 穿行实证（本批核心）：把一行滚到「跨带底沿」的位置（该行 top < 带底 < bottom）
//      ⇒ 行顶必须 **早于** 带底（改前：行顶被滚区顶边钳在带底，读数 top == 带底）；
//      同时 elementFromPoint(带内一点) 必须命中列表内节点（内容拥有该条区域，
//      不是被一块不透明件盖住）；
//   ④ 圆角加大：展开态 bar border-radius = 12px（--ds-radius-xl）+ 图标左内距 16；
//   ⑤ 桌面宽屏零命中（1440×900）：body 不带 mobile-layer、#mobile-pages 缺席、
//      桌面面板 #fm-conversations / #fm-contacts-body 计算几何与改前逐值一致。
//
// 改前树腿：SB_LEGACY=1 + SB_WEB_ROOT=<改前 web 树导出目录> ⇒ 同一脚本按「改前红
// 口径」断言（滚区顶边 = 60 / 行顶被钳在带底 / 圆角 8 / 带内 elementFromPoint 命中
// 非列表节点），红即证明本批判据钉在改动上（不是恒真断言）。
//
// Self-contained: route-intercepted static files + mocked REST/WS（夹具与
// mobile-search-flat.spec.mjs 同源），零后端、零端口占用。
// Run: node node_modules/@playwright/test/cli.js test tests/search-band-transparent.spec.mjs
//   env：SB_EVID_DIR=<dir> 落截图 + readings.json；SB_LEGACY=1 跑改前树腿。

import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SB_WEB_ROOT
  ? normalize(process.env.SB_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'band-session';
/** 搜索带高（css/mobileSearchFlat.css --mhsc-band = 48 + 12）。 */
const BAND_H = 60;
const LEGACY = process.env.SB_LEGACY === '1';
const EVID = process.env.SB_EVID_DIR || null;
/** 改前口径（透明顶带批之前的树）：带在流内、滚区顶边 = 带底、圆角 8。 */
const PRE_BAND_H = 60;

/** 夹具 + 静态路由 + WS（与 mobile-search-flat.spec.mjs 同源，只留两页列表要用的段）。 */
async function bootApp(page) {
  const engine = page.context().browser()?.browserType().name() || 'chromium';
  const ORIGIN = engine === 'webkit' ? 'http://localhost:8381/' : 'http://localhost:8380/';
  await page.addInitScript(() => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    const friends = [];
    const conversations = [];
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
      users: [{ userId: 'u-new', username: 'xintongxue', email: 'xin@example.com', displayName: '新同学', avatar: '' }],
      friends, incoming: [], outgoing: [], conversations, messages: {}, groups: [],
      groupInvites: [], groupMembers: {},
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

/** 单一读数探针：带 / 条 / 滚区 / 首行 / 跨带行 / 带内命中节点。 */
function probe(page, pageId) {
  return page.evaluate((pid) => {
    const r = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2), left: +b.left.toFixed(2), right: +b.right.toFixed(2), width: +b.width.toFixed(2), height: +b.height.toFixed(2) };
    };
    const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const body = sec?.querySelector('.mobile-page-body');
    const dock = body?.querySelector(':scope > .mh-search-dock') || null;
    const bar = dock?.querySelector('.mh-search-bar') || null;
    const scroller = pid === 'messages'
      ? body?.querySelector(':scope > #fm-conversations') || null
      : body?.querySelector(':scope > #fm-contacts-body') || null;
    const rowSel = pid === 'messages' ? '.fm-conv-row' : '.fm-friend-row';
    const rows = scroller ? [...scroller.querySelectorAll(rowSel)] : [];
    const dcs = dock ? getComputedStyle(dock) : null;
    const bcs = bar ? getComputedStyle(bar) : null;
    const scs = scroller ? getComputedStyle(scroller) : null;
    const bandRect = r(dock);
    const icon = bar?.querySelector('svg') || null;
    const rects = rows.map((row) => r(row));
    // 跨带底沿那一行：top < 带底 < bottom（改后应能出现；改前被滚区顶边钳死）。
    const straddle = rects.find((x) => x && bandRect && x.top < bandRect.bottom - 1 && x.bottom > bandRect.bottom + 1) || null;
    const hit = bandRect ? document.elementFromPoint(
      Math.round(bandRect.left + 120), Math.round(bandRect.bottom - 12)) : null;
    return {
      pageTop: sec ? +sec.getBoundingClientRect().top.toFixed(2) : null,
      band: bandRect,
      dock: dcs ? {
        position: dcs.position,
        background: dcs.backgroundColor,
        backgroundImage: dcs.backgroundImage,
        backdropFilter: dcs.webkitBackdropFilter || dcs.backdropFilter,
        opacity: dcs.opacity,
        pointerEvents: dcs.pointerEvents,
        zIndex: dcs.zIndex,
        dockClass: dock.className,
      } : null,
      bar: bcs ? {
        rect: r(bar),
        borderRadius: bcs.borderLeftTopRadius ?? bcs.borderTopLeftRadius,
        borderRadiusValue: bcs.borderRadius,
        background: bcs.backgroundColor,
        backdropFilter: bcs.webkitBackdropFilter || bcs.backdropFilter,
        collapsed: bar.classList.contains('g2-fab'),
        paddingLeft: bcs.paddingLeft,
      } : null,
      icon: icon ? r(icon) : null,
      scroller: scs ? {
        rect: r(scroller),
        clientHeight: scroller.clientHeight,
        scrollHeight: scroller.scrollHeight,
        scrollTop: Math.round(scroller.scrollTop),
        paddingTop: scs.paddingTop,
        overflowY: scs.overflowY,
        position: scs.position,
        zIndex: scs.zIndex,
      } : null,
      rowCount: rows.length,
      firstRow: rects[0] || null,
      straddle,
      hitInBand: hit ? {
        tag: hit.tagName,
        cls: typeof hit.className === 'string' ? hit.className : '',
        inScroller: !!scroller && scroller.contains(hit),
        inDock: !!dock && dock.contains(hit),
      } : null,
    };
  }, pageId);
}

/** 把第 n 行的行顶滚到 targetY（页面坐标）——用于精确制造「跨带底沿」。 */
async function scrollRowTo(page, pageId, rowIndex, targetY) {
  await page.evaluate(([pid, idx, ty]) => {
    const sel = pid === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
    const rowSel = pid === 'messages' ? '.fm-conv-row' : '.fm-friend-row';
    const sc = document.querySelector(`.mobile-page[data-page="${pid}"] .mobile-page-body > ${sel}`);
    if (!sc) return;
    const rows = sc.querySelectorAll(rowSel);
    const row = rows[Math.min(idx, rows.length - 1)];
    if (!row) return;
    sc.scrollTop += row.getBoundingClientRect().top - ty;
    sc.dispatchEvent(new Event('scroll', { bubbles: false }));
  }, [pageId, rowIndex, targetY]);
  await page.waitForTimeout(520); // 收起/展开过渡 280ms + 稳定
}

const shot = async (page, dir, name) => {
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: join(dir, `${name}.png`) });
};
/** 带区特写（页顶 0..band+20）：内容是否穿进带内一眼可见。 */
const shotBand = async (page, dir, name) => {
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: join(dir, `${name}.png`), clip: { x: 0, y: 0, width: 375, height: 92 } });
};

test.describe('手机搜索透明顶带', () => {
  for (const theme of ['light', 'dark']) {
    test(`顶带透明 + 内容穿行（${theme}）`, async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 812 });
      await page.emulateMedia({ colorScheme: theme });
      await bootApp(page);
      const errors = watchErrors(page);
      const readings = {};
      const dir = EVID ? join(EVID, LEGACY ? 'before' : 'after', theme) : null;

      for (const pageId of ['messages', 'contacts']) {
        await dockTo(page, pageId);
        await expect(page.locator(`.mobile-page[data-page="${pageId}"] .mh-search-bar`)).toBeVisible();
        await page.waitForTimeout(200);

        // ① 静止态（展开长条）
        const rest = await probe(page, pageId);
        readings[`${theme}.${pageId}.rest`] = rest;
        await shot(page, dir, `${pageId}.0-rest`);
        await shotBand(page, dir, `${pageId}.0-rest-band`);

        // ② 上滚过阈值 ⇒ 收起成圆钮
        await scrollRowTo(page, pageId, 3, 40);
        const mid = await probe(page, pageId);
        readings[`${theme}.${pageId}.scrolled`] = mid;
        await shot(page, dir, `${pageId}.1-scrolled`);
        await shotBand(page, dir, `${pageId}.1-scrolled-band`);

        // ③ 精确制造「跨带底沿」：把一行顶压到带底上方 ~20px（穿行实证主镜头）
        await page.evaluate((pid) => {
          const sel = pid === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
          const sc = document.querySelector(`.mobile-page[data-page="${pid}"] .mobile-page-body > ${sel}`);
          if (sc) sc.scrollTop += 0;
        }, pageId);
        const straddleY = MID_BAND_Y();
        await scrollRowTo(page, pageId, 2, straddleY);
        const str = await probe(page, pageId);
        readings[`${theme}.${pageId}.straddle`] = str;
        await shot(page, dir, `${pageId}.2-straddle`);
        await shotBand(page, dir, `${pageId}.2-straddle-band`);

        if (LEGACY) {
          // 改前红口径：滚区顶边被带顶到 y=带底（内容根本无法进入带区）。
          expect(Math.round(rest.scroller.rect.top), `${pageId} 改前：滚动容器顶边 = 带底（静止态）`).toBe(PRE_BAND_H);
          expect(Math.round(str.scroller.rect.top), `${pageId} 改前：滚区顶边 = 带底（被顶下去）`).toBe(PRE_BAND_H);
          expect(str.rowCount, `${pageId} 改前：列表有行`).toBeGreaterThan(0);
          expect(Math.round(rest.firstRow.top), `${pageId} 改前：静止首行顶 ≥ 带底（流内让位）`)
            .toBeGreaterThanOrEqual(PRE_BAND_H);
          // 判别性判据：带内一点归属谁（改前 = 页面/带自己，内容一个像素也进不来）。
          // （注：行 rect 是布局几何、不受 overflow 裁切影响，故「跨带行是否存在」不能
          //  区分改前/改后 —— 真正的裁切面读数 = 滚动容器顶边与带内命中节点。）
          expect(str.hitInBand?.inScroller, `${pageId} 改前：带内不归属列表内容`).toBe(false);
          expect(rest.bar.borderRadiusValue, `${pageId} 改前：展开态圆角 = 8px`).toBe('8px');
          expect(Math.round(rest.icon.left - rest.bar.rect.left), `${pageId} 改前：图标距条左缘 13（内距 12 + 描边）`).toBe(13);
          continue;
        }

        // ── ① 顶带透明 ────────────────────────────────────────────────
        expect(rest.dock.position, `① ${pageId} 带脱离文档流`).toBe('absolute');
        expect(rest.dock.background, `① ${pageId} 带底色全透明`).toBe('rgba(0, 0, 0, 0)');
        expect(rest.dock.backgroundImage, `① ${pageId} 带无渐变/无图`).toBe('none');
        expect(rest.dock.backdropFilter, `① ${pageId} 带无毛玻璃`).toBe('none');
        expect(rest.dock.opacity, `① ${pageId} 带 opacity 1（非靠透明度藏）`).toBe('1');
        expect(rest.dock.pointerEvents, `① ${pageId} 带手势穿透`).toBe('none');
        expect(Number(rest.dock.zIndex), `① ${pageId} 带浮在内容之上`).toBeGreaterThan(0);
        expect(Math.round(rest.band.top), `① ${pageId} 带贴页顶`).toBe(0);
        expect(Math.round(rest.band.height), `① ${pageId} 带高 = ${BAND_H}`).toBe(BAND_H);

        // ── ② 结构：滚区顶边 = 页顶；让位内距在滚区内部（可滚走）──────────
        expect(Math.round(rest.scroller.rect.top), `② ${pageId} 滚区顶边 = 页顶 0`).toBe(0);
        expect(rest.scroller.paddingTop, `② ${pageId} 让位内距 = 带高 + 4（住在滚区内部）`).toBe(`${BAND_H + 4}px`);
        expect(rest.scroller.overflowY, `② ${pageId} 滚区仍是自滚容器`).toBe('auto');
        expect(Math.round(rest.firstRow.top), `② ${pageId} 静止时首行仍在带下（不让内容顶到圆钮脸上）`)
          .toBeGreaterThanOrEqual(Math.round(rest.band.bottom) + 4 - 1);

        // ── ③ 穿行：收起态下存在跨带底沿的行 + 带内命中列表内容 ──────────
        // 判别性判据 = 滚区顶边（0 ⇒ 内容画得到带区）+ 带内 elementFromPoint；
        // straddle 是「此刻确有行跨在带底线上」的描述性读数（配套截图主镜头）。
        expect(str.bar.collapsed, `③ ${pageId} 上滚后已收成圆钮`).toBe(true);
        expect(Math.round(str.scroller.rect.top), `③ ${pageId} 收起态滚区顶边仍 = 0`).toBe(0);
        expect(str.straddle, `③ ${pageId} 存在「跨带底沿」的行（内容穿进带内）`).not.toBeNull();
        expect(str.straddle.top, `③ ${pageId} 跨带行顶 < 带底（不被切平）`).toBeLessThan(str.band.bottom);
        expect(Math.round(str.straddle.bottom), `③ ${pageId} 跨带行底 > 带底（行体确实跨过裁切线）`)
          .toBeGreaterThan(Math.round(str.band.bottom) + 1);
        expect(str.hitInBand?.inScroller, `③ ${pageId} 带内一点命中列表内容（不是被不透明块盖住）`).toBe(true);
        expect(str.hitInBand?.inDock, `③ ${pageId} 带内一点不归带本体（带不吃点击）`).toBe(false);

        // ── ④ 圆角加大 + 图标不贴边 ────────────────────────────────────
        expect(rest.bar.borderRadiusValue, `④ ${pageId} 展开态圆角 = --ds-radius-xl 12px`).toBe('12px');
        expect(rest.bar.paddingLeft, `④ ${pageId} 左内距 = --ds-space-4 16px`).toBe('16px');
        expect(Math.round(rest.icon.left - rest.bar.rect.left), `④ ${pageId} 图标距条左缘 17（内距 16 + 1px 描边，不贴弧线）`).toBe(17);
        // 材质换代（glass-top-overlays 2026-11-06）：带仍透明（本条判据不变 ——
        // 内容从带内穿行是作者 17:34 的原令），但**条本体必须走唯一毛玻璃标准**
        // （作者本批原令「所有悬浮在顶部 可以透出背景的 都用毛玻璃效果 比如消息和
        // 联系人的手机端的搜索框」）。旧的「长条仍无毛玻璃」断言随之换代。
        expect(rest.bar.backdropFilter, `④ ${pageId} 长条吃毛玻璃配方（-nf-glass-filter）`)
          .toMatch(/blur\(40px\)\s+saturate\(1\.4\)/);

        // ── ③′ 滚回顶部 ⇒ 还原长条（既有语义不受扰）────────────────────
        await page.evaluate((pid) => {
          const sel = pid === 'messages' ? '#fm-conversations' : '#fm-contacts-body';
          const sc = document.querySelector(`.mobile-page[data-page="${pid}"] .mobile-page-body > ${sel}`);
          if (sc) { sc.scrollTop = 0; sc.dispatchEvent(new Event('scroll', { bubbles: false })); }
        }, pageId);
        await page.waitForTimeout(520);
        const back = await probe(page, pageId);
        readings[`${theme}.${pageId}.back`] = back;
        expect(back.bar.collapsed, `⑤ ${pageId} 回顶后还原长条`).toBe(false);
        expect(back.scroller.scrollTop, `⑤ ${pageId} 回顶后 scrollTop = 0`).toBe(0);
      }
      expect(errors, '两页零 pageerror/console.error').toEqual([]);
      if (EVID) {
        mkdirSync(EVID, { recursive: true });
        writeFileSync(join(EVID, LEGACY ? `readings-before-${theme}.json` : `readings-after-${theme}.json`),
          JSON.stringify(readings, null, 2));
      }
    });
  }
});

/** 主镜头用的行顶目标位（页面坐标）：带底 − 20 ⇒ 行顶落在带内 20px 处。 */
function MID_BAND_Y() { return BAND_H - 20; }

test('桌面宽屏零命中（1440×900：手机层不激活 + 桌面面板几何与改前逐值一致）', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await bootApp(page);
  const errors = watchErrors(page);
  const st = await page.evaluate(() => {
    const pick = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      return {
        paddingTop: cs.paddingTop, paddingBottom: cs.paddingBottom,
        overflowY: cs.overflowY, position: cs.position,
        top: +r.top.toFixed(2), height: +r.height.toFixed(2),
      };
    };
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      mobilePages: document.querySelectorAll('#mobile-pages').length,
      dockCount: document.querySelectorAll('.mh-search-dock').length,
      conversations: pick('#fm-conversations'),
      contacts: pick('#fm-contacts-body'),
    };
  });
  const dir = EVID ? join(EVID, LEGACY ? 'before' : 'after', 'desktop-1440') : null;
  await shot(page, dir, 'desktop-1440');
  if (EVID) {
    mkdirSync(EVID, { recursive: true });
    writeFileSync(join(EVID, LEGACY ? 'readings-before-desktop.json' : 'readings-after-desktop.json'),
      JSON.stringify(st, null, 2));
  }
  expect(st.mobileLayer, '⑤ 1440 宽屏：手机层不激活（改动全在 body.mobile-layer 下）').toBe(false);
  expect(st.mobilePages, '⑤ 1440 宽屏：#mobile-pages 不建').toBe(0);
  expect(st.dockCount, '⑤ 1440 宽屏：手机搜索带不建').toBe(0);
  // 桌面面板几何：*不许*吃到透明顶带的让位内距（改前后应逐值相同）。
  expect(st.conversations?.paddingTop, '⑤ 桌面 #fm-conversations 内距未被手机规则污染').toBe('4px');
  expect(st.contacts?.paddingTop, '⑤ 桌面 #fm-contacts-body 内距未被手机规则污染').toBe('4px');
  // 桌面消息面板真开一张（面板 = 侧栏 .panel，入口 = 活动栏 #messages-btn）：
  // 证明宽屏下这两页仍是桌面形态，与手机顶带无关。
  await page.locator('#messages-btn').click();
  await page.waitForTimeout(300);
  const panel = await page.evaluate(() => {
    const p = document.querySelector('#panel-messages');
    const sc = document.querySelector('#fm-conversations');
    const r = sc?.getBoundingClientRect();
    const pr = p?.getBoundingClientRect();
    return {
      panelActive: !!p?.classList.contains('active'),
      panelRect: pr ? { top: +pr.top.toFixed(1), height: +pr.height.toFixed(1), width: +pr.width.toFixed(1) } : null,
      scrollerRect: r ? { top: +r.top.toFixed(1), height: +r.height.toFixed(1) } : null,
      paddingTop: sc ? getComputedStyle(sc).paddingTop : null,
      rowCount: sc ? sc.children.length : 0,
    };
  });
  await shot(page, dir, 'desktop-1440-messages-panel');
  if (EVID) {
    mkdirSync(EVID, { recursive: true });
    writeFileSync(join(EVID, LEGACY ? 'readings-before-desktop-panel.json' : 'readings-after-desktop-panel.json'),
      JSON.stringify(panel, null, 2));
  }
  expect(panel.panelActive, '⑤ 1440：桌面消息面板可开（桌面形态仍在）').toBe(true);
  expect(panel.paddingTop, '⑤ 1440：桌面消息列表内距 = 4px（未吃手机顶带让位）').toBe('4px');
  expect(errors).toEqual([]);
});
