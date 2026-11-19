#!/usr/bin/env node
// composer-input-inset-metrics.spec.mjs — composer 输入框**左右留白 / 文字可排布宽度**
// 实测取证（2026-11-18 作者令「两边留白边距应该差不多 + 文字离两边近一点、可用空间大一点」）。
//
// 判据全部取 **computed 值 + 布局盒**，不靠目测：
//   · shellPadding / fieldPadding：外壳与内件的左右 padding（成对读数）
//   · textStartFromShellLeft  = 文字起始位置 − 外壳左缘（= 外壳左 padding + 内件左边框 + 内件左 padding）
//   · textEndFromShellRight   = 外壳右缘 − 文字结束位置（对称量）
//   · usable                  = 文字**实际可排布宽度**（textarea content box 宽 = clientWidth − 左右 padding − 左右边框）
// 三面各取：主会话（#input-bar/#input）、消息面（.fm-input-bar/.fm-input，手机走**全屏页**
// `body.mobile-layer .mobile-page[data-page="conversation"]` 形态，不是弹窗/内嵌面板）。
//
// Run: LABEL=before|after node tests/composer-input-inset-metrics.spec.mjs
// 证据：EVID 目录（缺省 .stand/tmp/composer-unify/evidence/inset-<LABEL>）

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const LABEL = process.env.LABEL || 'before';
const EVID = process.env.EVID || join(ROOT, '.stand', 'tmp', 'composer-unify', 'evidence', `inset-${LABEL}`);
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };
const UID = 'u-inset';
const CID = 'c-inset';
const nowSec = () => Math.floor(Date.now() / 1000);
const HISTORY = [
  { type: 'user', text: '输入框内距实测用行', timestamp: nowSec() * 1000 - 5000 },
  { type: 'ai', text: '收到', timestamp: nowSec() * 1000 - 4000 },
];
const SEED = {
  self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
  users: [],
  friends: [{ userId: UID, neblinkId: 'buddy-inset', name: '好友内距', avatarUrl: '', since: new Date().toISOString() }],
  incoming: [],
  outgoing: [],
  conversations: [{ conversationId: CID, friend: { userId: UID, neblinkId: 'buddy-inset', name: '好友内距', avatarUrl: '' }, lastMessage: { id: 1, senderId: UID, kind: 'text', body: '早前消息', createdAt: nowSec() - 600 }, unreadCount: 0 }],
  messages: { [CID]: [{ id: 1, senderId: UID, kind: 'text', body: '早前消息', createdAt: nowSec() - 600 }] },
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function boot(page) {
  await page.addInitScript(([seedJson]) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_trusted', JSON.stringify([]));
  }, [JSON.stringify(SEED)]);
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/neblink/status', (r) => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: 's1', messages: HISTORY, hasMore: false, offset: 0 }));
  });
  await page.goto('http://localhost:1/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#input', { state: 'attached', timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await sleep(800);
}

/** 手机层全屏页 → 好友会话（真点击；形态 = 全屏页，不是弹窗/内嵌面板）。 */
async function openConversationFullScreen(page) {
  const dock = page.locator('#mobile-dock .dock-key[data-dock="messages"]');
  const mobile = (await dock.count()) > 0 && await dock.first().isVisible();
  if (mobile) {
    await dock.first().click({ timeout: 15000 });
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="messages"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 15000 }).catch(() => {});
  } else if (!(await page.locator('#panel-messages.active').count())) {
    await page.click('#messages-btn', { timeout: 15000 });
    await sleep(700);
  }
  const row = page.locator(`.fm-conv-row[data-conversation-id="${CID}"]`).first();
  const anyRow = page.locator('.fm-conv-row').first();
  if (await row.count()) await row.click({ timeout: 15000 });
  else if (await anyRow.count()) await anyRow.click({ timeout: 15000 });
  else return false;
  if (mobile) {
    await page.waitForFunction(() => {
      const el = document.querySelector('.mobile-page[data-page="conversation"]');
      return !!el && el.classList.contains('live');
    }, null, { timeout: 15000 }).catch(() => {});
  } else {
    await page.waitForSelector('#header .fm-main-head', { timeout: 15000 });
  }
  await sleep(700);
  return true;
}

/** 内距读数（computed + 布局盒；textarea 的「文字可排布宽度」= clientWidth − 左右 padding − 左右边框）。 */
const METRICS = (arg) => {
  const { shellSel, fieldSel } = arg;
  const shell = document.querySelector(shellSel);
  const field = document.querySelector(fieldSel);
  if (!shell || !field) return { ok: false, shell: !!shell, field: !!field };
  const cs = getComputedStyle(shell);
  const cf = getComputedStyle(field);
  const sr = shell.getBoundingClientRect();
  const fr = field.getBoundingClientRect();
  const px = (v) => Math.round(parseFloat(v) * 100) / 100;
  const fPL = px(cf.paddingLeft); const fPR = px(cf.paddingRight);
  const fBL = px(cf.borderLeftWidth); const fBR = px(cf.borderRightWidth);
  const textStartX = fr.left + fBL + fPL;
  const textEndX = fr.right - fBR - fPR;
  return {
    ok: true,
    shellSel, fieldSel,
    shellPadding: [px(cs.paddingLeft), px(cs.paddingRight)],
    shellBorder: [px(cs.borderLeftWidth), px(cs.borderRightWidth)],
    fieldPadding: [fPL, fPR],
    fieldBorder: [fBL, fBR],
    shellBox: [Math.round(sr.width * 100) / 100, Math.round(sr.height * 100) / 100],
    fieldBox: [Math.round(fr.width * 100) / 100, Math.round(fr.height * 100) / 100],
    fieldClientWidth: field.clientWidth,
    // 文字可排布宽度（textarea content box 宽；两法互证）
    usableFromRect: Math.round((textEndX - textStartX) * 100) / 100,
    usableFromClient: field.clientWidth - fPL - fPR - fBL - fBR,
    // 文字起始/结束到外壳两缘的距离（对称量）
    textStartFromShellLeft: Math.round((textStartX - sr.left) * 100) / 100,
    textEndFromShellRight: Math.round((sr.right - textEndX) * 100) / 100,
    // 外壳内兄弟节点（图标位预留宽：文字是否被图标位挤走）
    siblings: [...shell.children].map((el) => {
      const r = el.getBoundingClientRect();
      const c2 = getComputedStyle(el);
      return { tag: el.tagName.toLowerCase(), cls: String(el.className).slice(0, 40),
        x: Math.round(r.left * 100) / 100, w: Math.round(r.width * 100) / 100,
        display: c2.display, position: c2.position,
        m: [Math.round(parseFloat(c2.marginLeft) * 100) / 100, Math.round(parseFloat(c2.marginRight) * 100) / 100],
        pad: [Math.round(parseFloat(c2.paddingLeft) * 100) / 100, Math.round(parseFloat(c2.paddingRight) * 100) / 100] };
    }),
    shellGap: px(cs.gap === 'normal' ? 0 : cs.gap),
    // 尾部余量：最后一个可见子项右缘 → 外壳内容盒右缘（左右不对称的隐藏来源）
    trailingSlack: (() => {
      const vis = [...shell.children].filter((el) => el.getBoundingClientRect().width > 0 && getComputedStyle(el).display !== 'none');
      if (!vis.length) return null;
      const last = vis[vis.length - 1].getBoundingClientRect();
      const contentRight = sr.right - parseFloat(cs.borderRightWidth) - parseFloat(cs.paddingRight);
      return Math.round((contentRight - last.right) * 100) / 100;
    })(),
    leadingSlack: (() => {
      const vis = [...shell.children].filter((el) => el.getBoundingClientRect().width > 0 && getComputedStyle(el).display !== 'none');
      if (!vis.length) return null;
      const first = vis[0].getBoundingClientRect();
      const contentLeft = sr.left + parseFloat(cs.borderLeftWidth) + parseFloat(cs.paddingLeft);
      return Math.round((first.left - contentLeft) * 100) / 100;
    })(),
    mobileFullScreen: (() => {
      const pg = document.querySelector('.mobile-page[data-page="conversation"]');
      return !!pg && pg.classList.contains('live');
    })(),
    fieldPlaceholder: field.getAttribute('placeholder'),
  };
};

const VIEWPORTS = [
  { name: 'mobile-375x812', width: 375, height: 812 },
  { name: 'desktop-1280x800', width: 1280, height: 800 },
];

const browser = await chromium.launch();
rmSync(EVID, { recursive: true, force: true });
mkdirSync(EVID, { recursive: true });
const out = { label: LABEL, at: new Date().toISOString(), viewports: {} };

for (const vp of VIEWPORTS) {
  const vidDir = join(EVID, `video-${vp.name}`);
  mkdirSync(vidDir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN', reducedMotion: 'reduce',
    recordVideo: { dir: vidDir, size: { width: vp.width, height: vp.height } },
  });
  const page = await ctx.newPage();
  const rec = {};
  try {
    await boot(page);
    // 主会话面（手机默认落全屏 chat 页）
    rec.main = await page.evaluate(METRICS, { shellSel: '#input-bar', fieldSel: '#input' });
    await page.screenshot({ path: join(EVID, `main-${vp.name}.png`) });
    const opened = await openConversationFullScreen(page);
    rec.opened = opened;
    if (opened) {
      rec.fm = await page.evaluate(METRICS, { shellSel: '.fm-input-bar', fieldSel: '.fm-input' });
      const isMobile = vp.width < 600;
      if (isMobile) {
        // 手机端形态核验：会话面必须是**全屏页**（不是弹窗/内嵌面板）
        rec.mobileForm = await page.evaluate(() => {
          const pg = document.querySelector('.mobile-page[data-page="conversation"]');
          const pgRect = pg ? pg.getBoundingClientRect() : null;
          const bar = document.querySelector('.fm-input-bar');
          // 形态证明：会话面必须是**全屏页**（页容器占满视口 + 祖先链最高层就是它，
          // 不是 dialog/modal 中间层）。类名里出现 modal 字样不算证据 —— 看盒与层级。
          const chain = []; let n = bar;
          while (n && n !== document.documentElement) {
            const r = n.getBoundingClientRect();
            const c = getComputedStyle(n);
            chain.push({ tag: n.tagName.toLowerCase(), cls: String(n.className || '').slice(0, 44),
              box: [Math.round(r.width), Math.round(r.height)], pos: c.position, z: c.zIndex,
              vis: c.visibility, isPage: n === pg });
            n = n.parentElement;
          }
          const overlays = [...document.querySelectorAll('[role="dialog"], .nf-modal, .nf-sheet')]
            .filter((el) => { const c = getComputedStyle(el); const r = el.getBoundingClientRect();
              return c.display !== 'none' && c.visibility !== 'hidden' && r.width > 0 && r.height > 0; })
            .map((el) => String(el.className || '').slice(0, 40));
          return { pageLive: !!pg && pg.classList.contains('live'),
            pageBox: pgRect ? [Math.round(pgRect.width), Math.round(pgRect.height)] : null,
            viewport: [window.innerWidth, window.innerHeight],
            barInPage: !!(pg && bar && pg.contains(bar)),
            ancestorChain: chain, visibleDialogOverlays: overlays };
        });
      }
      await page.screenshot({ path: join(EVID, `fm-${vp.name}.png`) });
    }
  } catch (e) {
    rec.error = String(e && e.message ? e.message : e);
  } finally {
    const v = await page.video()?.path().catch(() => null);
    await page.close();
    if (v) writeFileSync(join(EVID, `video-path-${vp.name}.txt`), v);
    await ctx.close();
  }
  out.viewports[vp.name] = rec;
}

await browser.close();
writeFileSync(join(EVID, `inset-${LABEL}.json`), JSON.stringify(out, null, 2));
const line = (n, m) => (m && m.ok
  ? `${n}: shell[L/R]=${m.shellPadding.join('/')} field[L/R]=${m.fieldPadding.join('/')} `
    + `usable=${m.usableFromClient}px(rect ${m.usableFromRect}) textStart(shell L)=${m.textStartFromShellLeft}px `
    + `textEnd(shell R)=${m.textEndFromShellRight}px gap=${m.shellGap} shellW=${m.shellBox[0]}`
  : `${n}: MISSING ${JSON.stringify(m)}`);
for (const [vp, rec] of Object.entries(out.viewports)) {
  console.log(`[${LABEL}] ${vp}`);
  if (rec.error) console.log(`  ERROR ${rec.error}`);
  console.log('  ' + line('main', rec.main));
  console.log('  ' + line('fm  ', rec.fm));
  if (rec.mobileForm) console.log(`  mobileForm=${JSON.stringify({ pageLive: rec.mobileForm.pageLive, pageBox: rec.mobileForm.pageBox, viewport: rec.mobileForm.viewport, barInPage: rec.mobileForm.barInPage, visibleDialogOverlays: rec.mobileForm.visibleDialogOverlays })}`);
}
console.log(`\ninset-metrics(${LABEL}) written: ${join(EVID, `inset-${LABEL}.json`)}`);
