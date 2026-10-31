// mobile-msg-longpress.spec.mjs — 手机端长按消息上下文菜单 + 引用支持文件/图片批
// 验收（作者令 2026-10-31 09:04「手机端 长按消息要像电脑端右键一样 弹出引用
// 然后引用要支持你发的文件 图片等」）。
//
// 覆盖：
//   T1 长按手势：touchstart 按住 480ms ⇒ .fm-context-menu 弹出（与桌面右键
//      同一单点出口 openQuoteMenuForRow：文本行 = 复制+引用，媒体行 = 仅引用）；
//      探针 __nfMobileLongPress.active；手指抬起（合成 click 被吞窗吃掉）菜单不丢
//   T2 长按媒体行 → 引用 → 发送：乐观气泡引用块渲染 .msg-quote-atts
//      （图片缩略 48px 实图 + 文件 chip），点击文件 chip/图片缩略 =
//      workspace-open-item（点击可开，零死点击）
//   T3 手势不冲突：touchstart+touchMove(>10px) 滚动 ⇒ 无菜单 + 滚动位移真实；
//      普通轻点 ⇒ 无菜单
//   T4 历史重放腿：落盘 quote 快照（attachments 白名单 type/name/path，无 preview）
//      渲染同一引用块缩略带（字节走 nf-ticket 票腿，installTicketMock + nf-file
//       fulfill PNG）
//   T5 桌面右键同腿：桌面视口右键媒体行 → 同款引用块（同一渲染腿，桌面受益）
//
// 数据面 = 自包含 harness（route 静态文件 + WS getHistory 夹具 + nf-ticket mock），
// 与 mobile-v3.spec.mjs 同族；零后端、零 sbt。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-msg-longpress.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'mlp-root-session';
// 手机层激活口径 = bootstrap.js 同源（≤768px 折叠点）：默认手机触屏视口
// （T5 桌面腿在测试体内自行改视口 + 重载）。
test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });
// 2×1 绿/蓝 PNG（附件缩略断言用：解码后 naturalWidth>0 即证字节水合成功）。
const GREEN_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const SHOTS_DIR = process.env.MLP_SHOTS_DIR ||
  resolve(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', `${TS}_mobile-msg-longpress-quote`, 'shots');
mkdirSync(SHOTS_DIR, { recursive: true });

/** 历史夹具：18 条填充消息撑出滚动（T3 滚动共存断言的前提）+ 一条「文本+图片+
 *  文件」消息、一条纯文本消息、一条带落盘 quote 快照的引用回复消息
 * （attachments 为后端白名单形状：type/name/path，无 preview）。 */
const FILLERS = Array.from({ length: 18 }, (_, i) => ({
  type: 'ai', text: `填充消息 ${i + 1}：滚动共存断言需要聊天列可滚。`, timestamp: Date.now() - 300_000 + i * 10_000,
}));
const HISTORY = [
  ...FILLERS,
  {
    type: 'user', text: '带附件的消息正文，长按我应该能引用到文件和图片。',
    timestamp: Date.now() - 60_000, injected: false,
    attachments: [
      { name: 'pic.png', type: 'image', path: '/tmp/mlp/pic.png', preview: GREEN_PNG },
      { name: '报告.pdf', type: 'file', path: '/tmp/mlp/report.pdf' },
    ],
  },
  { type: 'user', text: '纯文本消息，长按我弹出复制和引用。', timestamp: Date.now() - 30_000, injected: false },
  {
    type: 'user', text: '这是引用回复的消息（历史重放腿，缩略带走票腿水合）。',
    timestamp: Date.now() - 10_000, injected: false,
    quote: {
      refMessageId: 'h0-deadbeef', excerpt: '[图片] pic.png', senderRole: 'user', senderName: '我',
      ts: Date.now() - 60_000,
      attachments: [
        { type: 'image', name: 'pic.png', path: '/tmp/mlp/pic.png' },
        { type: 'file', name: '报告.pdf', path: '/tmp/mlp/report.pdf' },
      ],
    },
  },
];

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p.startsWith('/api/nf-file')) {
        // 票腿：无票 401（机制本身在测），有票回 PNG 字节。
        return ticketGuard(route).then((guarded) => {
          if (guarded) return;
          const body = Buffer.from(GREEN_PNG.split(',')[1], 'base64');
          return route.fulfill({ status: 200, contentType: 'image/png', body });
        });
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
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
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
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
}

/** CDP 真触屏事件（与 canvas-stack.spec.mjs 同手法）：长按/滑动的唯一可信源。 */
async function cdpTouch(page) {
  const cdp = await page.context().newCDPSession(page);
  return {
    start: (x, y) => cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] }),
    move: (x, y) => cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y, id: 1 }] }),
    end: () => cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }),
  };
}

/** 长按一行：按住 ≥600ms 后截图（fingerDown=true 时手指仍在屏）。 */
async function longPressRow(page, row, tag) {
  const touch = await cdpTouch(page);
  const box = await row.boundingBox();
  const x = Math.round(box.x + box.width / 2);
  const y = Math.round(box.y + Math.min(box.height / 2, 30));
  await touch.start(x, y);
  await page.waitForTimeout(620);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-held.png`) });
  return { touch, x, y };
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 long-press opens the desktop-same context menu; menu survives finger lift (swallowed synthetic click)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 手机层 + 长按探针同源（bootstrap.js 激活谱系）。
  expect(await page.evaluate(() => window.__nfMobileLayer?.active)).toBe(true);
  expect(await page.evaluate(() => window.__nfMobileLongPress?.active)).toBe(true);

  // 纯文本行长按 ⇒ 复制 + 引用（与桌面右键同一份 items）。
  const textRow = page.locator('#chat .row.user', { hasText: '纯文本消息' });
  await expect(textRow).toBeVisible();
  const { touch } = await longPressRow(page, textRow, 't1-longpress-text-menu');
  const menu = page.locator('.fm-context-menu');
  await expect(menu).toBeVisible();
  // 动作项（排除弹窗退场批②注入的左上 .mfs-back 返回钮）。
  const items = await menu.locator('button:not(.mfs-back)').allTextContents();
  expect(items).toEqual(['复制', '引用']);

  // 手指抬起：合成 click 被吞窗吃掉（contextMenu.js swallowTouchClick），
  // 菜单不得闪没 —— 这是手机端右键菜单此前不可用的根因修复点。
  await touch.end();
  await page.waitForTimeout(250);
  await expect(menu).toBeVisible();
  await page.screenshot({ path: join(SHOTS_DIR, 't1-menu-after-lift.png') });

  // 菜单外点按 ⇒ 关闭（既有全局关闭语义不回归）。
  await page.mouse.click(200, 40);
  await expect(menu).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('T2 long-press media row → quote → send: quote block renders attachment thumbs (image preview + file card), clicks open', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 附件消息正文行长按 ⇒ 兄弟媒体行附件并入（微信整消息语义）。
  const textRow = page.locator('#chat .row.user', { hasText: '带附件的消息正文' });
  const { touch } = await longPressRow(page, textRow, 't2-longpress-media-menu');
  const menu = page.locator('.fm-context-menu');
  await expect(menu).toBeVisible();
  await touch.end();

  // 等吞窗过期（500ms）再点菜单项 —— 模拟真实用户读菜单的节奏。
  await page.waitForTimeout(600);
  await menu.locator('button', { hasText: '引用' }).click();
  await expect(menu).toHaveCount(0);
  // 引用 chip 落附件条（与附件 chip 同条带；renderRefBlock input 形态 .att-ref）。
  const chip = page.locator('#attachment-preview .att-ref');
  await expect(chip).toBeVisible();
  await page.screenshot({ path: join(SHOTS_DIR, 't2-quote-chip-mobile.png') });

  // 发送 ⇒ 乐观气泡上方引用块带 .msg-quote-atts（图片缩略 + 文件 chip）。
  await page.locator('#input').fill('回复带附件的消息');
  await page.locator('#send-btn').click();
  const quoteBlock = page.locator('#chat .row.user .msg-quote-block').last();
  await expect(quoteBlock).toBeVisible();
  const atts = quoteBlock.locator('.msg-quote-atts');
  await expect(atts).toBeVisible();
  // 图片缩略：内联 preview 直渲，解码成功（naturalWidth>0）。
  const img = atts.locator('.msg-quote-att-media img.msg-quote-att-img');
  await expect(img).toHaveCount(1);
  await page.waitForFunction(() => {
    const im = document.querySelector('.msg-quote-atts img.msg-quote-att-img');
    return im && im.complete && im.naturalWidth > 0;
  }, { timeout: 5000 });
  // 文件 chip：图标 + 文件名，有 path ⇒ 可点（role=button）。
  const fileChip = atts.locator('.msg-quote-att-file');
  await expect(fileChip).toBeVisible();
  await expect(fileChip).toHaveAttribute('role', 'button');
  await expect(fileChip).toContainText('报告.pdf');
  await page.screenshot({ path: join(SHOTS_DIR, 't2-quote-block-image-file-mobile.png') });

  // 点击可开（零死点击）：两路都 dispatch workspace-open-item（Pop 同款单点）。
  // DOM click 而非指针点击：文件 chip 的打开会把 canvas 页推前盖住聊天页
  // （手机层 workspace-open-item 收编语义），第二次真实指针点击会被页面体
  // 拦截 —— 打开路径本身是 DOM 事件分派，DOM click 即是全链。
  const opened = [];
  await page.exposeFunction('__mlpOpened', (p) => opened.push(p));
  await page.evaluate(() => {
    window.addEventListener('workspace-open-item', (e) => {
      window.__mlpOpened(e.detail && e.detail.absPath);
    }, { once: false });
  });
  await fileChip.evaluate((el) => el.click());
  await expect.poll(() => opened.length).toBe(1);
  expect(opened[0]).toBe('/tmp/mlp/report.pdf');
  await atts.locator('.msg-quote-att-media').evaluate((el) => el.click());
  await expect.poll(() => opened.length).toBe(2);
  expect(opened[1]).toBe('/tmp/mlp/pic.png');
  expect(pageErrors).toEqual([]);
});

test('T3 gesture coexistence: scroll cancels long-press, tap does not open menu', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 按住即滑（>10px）⇒ 长按取消，聊天列原生滚动不被劫持。夹具加载后聊天列
  // 自动停在底部 ⇒ 先回顶（首条填充行在视口内），再上滑（手指上移 = 内容下滚）
  // 验证真实位移。
  const touch = await cdpTouch(page);
  await page.evaluate(() => { const c = document.querySelector('#chat'); if (c) c.scrollTop = 0; });
  await page.waitForTimeout(150);
  const topRow = page.locator('#chat .row').first();
  const box = await topRow.boundingBox();
  const x = Math.round(box.x + box.width / 2);
  const y = Math.round(box.y + Math.min(box.height / 2, 30));
  const scrollBefore = await page.evaluate(() => document.querySelector('#chat')?.scrollTop || 0);
  await touch.start(x, y);
  for (let i = 1; i <= 5; i++) await touch.move(x, y - i * 24);
  await page.waitForTimeout(650); // 越过长按计时窗
  await touch.end();
  await expect(page.locator('.fm-context-menu')).toHaveCount(0);
  const scrollAfter = await page.evaluate(() => document.querySelector('#chat')?.scrollTop || 0);
  expect(scrollAfter).toBeGreaterThan(scrollBefore);

  // 普通轻点（<480ms）⇒ 无菜单。
  await touch.start(x, y);
  await page.waitForTimeout(120);
  await touch.end();
  await page.waitForTimeout(120);
  await expect(page.locator('.fm-context-menu')).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('T4 history replay leg: persisted quote snapshot (backend-whitelisted attachments) renders the same thumb strip via ticket leg', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  const quoteBlock = page.locator('#chat .row.user .msg-quote-block').first();
  await expect(quoteBlock).toBeVisible();
  const atts = quoteBlock.locator('.msg-quote-atts');
  await expect(atts).toBeVisible();
  await expect(atts.locator('.msg-quote-att-media')).toHaveCount(1);
  await expect(atts.locator('.msg-quote-att-file')).toHaveCount(1);
  // 落盘形状无 preview ⇒ 字节走 nf-ticket 票腿（mock 铸票 + nf-file fulfill）。
  await page.waitForFunction(() => {
    const im = document.querySelector('.msg-quote-atts img.msg-quote-att-img');
    return im && im.complete && im.naturalWidth > 0;
  }, { timeout: 8000 });
  await page.screenshot({ path: join(SHOTS_DIR, 't4-quote-block-history-replay.png') });
  expect(pageErrors).toEqual([]);
});

test('T5 desktop right-click quote renders the same attachment strip (one renderer leg)', async ({ page }) => {
  // 桌面视口重开（mobileLayer 不激活）：右键 = 既有入口，引用块渲染同一腿。
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);

  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  // 桌面壳：长按探针不在场（mobileLayer 口径），右键菜单可用。
  expect(await page.evaluate(() => !window.__nfMobileLayer?.active)).toBe(true);

  const textRow = page.locator('#chat .row.user', { hasText: '带附件的消息正文' });
  await textRow.click({ button: 'right' });
  const menu = page.locator('.fm-context-menu');
  await expect(menu).toBeVisible();
  await menu.locator('button', { hasText: '引用' }).click();
  await page.locator('#input').fill('桌面引用附件消息');
  await page.locator('#send-btn').click();
  const quoteBlock = page.locator('#chat .row.user .msg-quote-block').last();
  await expect(quoteBlock).toBeVisible();
  const atts = quoteBlock.locator('.msg-quote-atts');
  await expect(atts).toBeVisible();
  await expect(atts.locator('.msg-quote-att-media img')).toHaveCount(1);
  await expect(atts.locator('.msg-quote-att-file')).toContainText('报告.pdf');
  await page.waitForFunction(() => {
    const im = document.querySelector('.msg-quote-atts img.msg-quote-att-img');
    return im && im.complete && im.naturalWidth > 0;
  }, { timeout: 5000 });
  await page.screenshot({ path: join(SHOTS_DIR, 't5-desktop-quote-block.png'), fullPage: false });
  expect(pageErrors).toEqual([]);
});
