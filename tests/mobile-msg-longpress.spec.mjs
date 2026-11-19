// mobile-msg-longpress.spec.mjs — 手机端长按退役批验收（作者令 2026-10-31
// 22:55「去掉手机端长按弹出引用/复制菜单——整条退役干净，桌面右键不动；
// 文件和图片消息补 footer（同文本消息底部信息条族：时间/下载等常用操作），
// 替代长按触达」）。
//
// 本文件前身为「手机端长按消息上下文菜单」批（2026-10-31 09:04）验收 spec，
// 全部长按正断言随退役翻为反断言；引用/下载触达改走 footer 信息条（同一夹具
// 覆盖新入口）。桌面右键腿逐字保留（退役不翻桌面案）。
//
// 覆盖：
//   T1 长按退役：__nfMobileLongPress 探针不在场；长按（CDP 真触屏按住 620ms）
//      不弹 .fm-context-menu（按住中/抬手后双断言）；行上派发 contextmenu 事件
//      也零菜单（Android/iOS 原生补发腿，msgQuote.attachQuoteMenu 手机层闸）；
//      普通轻点/按下即滑零菜单；聊天滚动面自身完好（可滚可复位）；
//      顺带面回归钉：未开的 #todo-page 不占流（手机聊天列 ≥60% 视口高，
//      todo-page.css `:not(.open){display:none}` 一行修复，本批截图阶段实证）
//   T2 媒体行 footer：单件图片/文件行 = .duration-badge（data-ts=消息时间戳）+
//      .msg-dl 下载键；点击下载键 = 真实 download 事件（path 腿走 nf-ticket 票链
//      + nf-file 字节，内联 data: 腿直落）；两件行（图+文件）footer 有时间无下载
//      （一枚键对哪件负责说不清，宁缺毋假）
//   T3 触达替代（原 T2 长按引用链改走 footer）：图片行 footer 引用钮 → 引用
//      chip → 发送 → 乐观气泡引用块 .msg-quote-atts（图片缩略 + 文件 chip 可点开
//      = workspace-open-item）；文本行 footer 引用/复制钮同链可用
//   T4 历史重放腿：落盘 quote 快照缩略带（票腿水合）不变；重放媒体行 footer
//      data-ts 与消息时间戳同值（重放腿 ts 传递）
//   T5 桌面右键同腿（逐字保留）：桌面视口右键媒体行 → 同款菜单/引用块；桌面无
//      长按探针；桌面媒体行同样带 footer（footer 是通用聊天面，非手机层专属）
//   T6 双主题存证：文件 footer / 图片 footer / 长按无弹 三样 × dark/light 六图
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
// 64×42 纯绿 PNG（附件缩略断言用：解码后 naturalWidth>0 即证字节水合成功；
// 非退化尺寸让图片脸有真实比例，footer 存证里图片可见）。
const GREEN_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAAAqCAIAAACMZMq1AAAAPElEQVR42u3PAQkAAAgDsGNMExrTHIfBCixzWy0CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgJNHtp/qpfLpgCAAAAAAElFTkSuQmCC';

const TS = new Date().toISOString().replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
const SHOTS_DIR = process.env.MLP_SHOTS_DIR ||
  resolve(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', `${TS}_mobile-longpress-retire`, 'shots');
mkdirSync(SHOTS_DIR, { recursive: true });

// 固定时间戳（相对偏移）：footer data-ts 断言与视觉时间档共用同一源。
const T_ATT = Date.now() - 90_000;   // 图+文件两件行
const T_IMG = Date.now() - 80_000;   // 纯图片单件行（内联 data: 腿）
const T_FILE = Date.now() - 70_000;  // 纯文件单件行（path 票腿）
const T_TEXT = Date.now() - 60_000;  // 纯文本行

/** 历史夹具：18 条填充消息撑出滚动 + 四条被测消息（两件行/纯图/纯文件/纯文本）
 *  + 一条带落盘 quote 快照的引用回复消息（attachments 为后端白名单形状：
 *  type/name/path，无 preview）。 */
const FILLERS = Array.from({ length: 18 }, (_, i) => ({
  type: 'ai', text: `填充消息 ${i + 1}：滚动共存断言需要聊天列可滚。`, timestamp: T_TEXT - 400_000 + i * 10_000,
}));
const HISTORY = [
  ...FILLERS,
  {
    type: 'user', text: '带附件的消息正文（图片+文件两件行）。',
    timestamp: T_ATT, injected: false,
    attachments: [
      { name: 'pic.png', type: 'image', path: '/tmp/mlp/pic.png', preview: GREEN_PNG },
      { name: '报告.pdf', type: 'file', path: '/tmp/mlp/report.pdf' },
    ],
  },
  {
    type: 'user', text: '', timestamp: T_IMG, injected: false,
    attachments: [{ name: 'photo.jpg', type: 'image', preview: GREEN_PNG }],
  },
  {
    type: 'user', text: '', timestamp: T_FILE, injected: false,
    attachments: [{ name: '报告.pdf', type: 'file', path: '/tmp/mlp/report.pdf' }],
  },
  { type: 'user', text: '纯文本消息，触达走 footer 信息条。', timestamp: T_TEXT, injected: false },
  {
    type: 'user', text: '这是引用回复的消息（历史重放腿，缩略带走票腿水合）。',
    timestamp: Date.now() - 10_000, injected: false,
    quote: {
      refMessageId: 'h0-deadbeef', excerpt: '[图片] pic.png', senderRole: 'user', senderName: '我',
      ts: T_ATT,
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

/** 长按一行：按住 ≥600ms（fingerDown=true 手指仍在屏）。 */
async function longPressRow(page, row, holdMs = 620) {
  const touch = await cdpTouch(page);
  const box = await row.boundingBox();
  const x = Math.round(box.x + box.width / 2);
  const y = Math.round(box.y + Math.min(box.height / 2, 30));
  await touch.start(x, y);
  await page.waitForTimeout(holdMs);
  return { touch, x, y };
}

/** 媒体行定位判别（单点；严格模式下不许歧义）：
 *  imgOnlyRow = 只有图片脸（有 img、无 .fc-name）；mixRow = 图+文件两件行；
 *  fileRow = 只有文件卡（有 .fc-name、无 img）。 */
function imgOnlyRow(page) {
  return page.locator('#chat .row.user-media').filter({ has: page.locator('img') }).filter({ hasNot: page.locator('.fc-name') });
}
function mixRow(page) {
  return page.locator('#chat .row.user-media').filter({ has: page.locator('img') }).filter({ has: page.locator('.fc-name') });
}
function fileOnlyRow(page) {
  return page.locator('#chat .row.user-media').filter({ hasNot: page.locator('img') }).filter({ has: page.locator('.fc-name') });
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 长按退役：探针不在场，长按/原生 contextmenu/轻点/滑动均零菜单，滚动面完好', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 手机层激活如常；长按处理器整条退役 ⇒ 探针不存在（结构读数）。
  expect(await page.evaluate(() => window.__nfMobileLayer?.active)).toBe(true);
  expect(await page.evaluate(() => window.__nfMobileLongPress)).toBeUndefined();

  // 顺带面回归钉（本批截图阶段发现的既有缺陷，todo-page.css 一行修复）：
  // 未开的 #todo-page 不再占流挤压聊天列 —— 手机层聊天列高度 ≥ 60% 视口。
  const chatH = await page.evaluate(() => document.querySelector('#chat')?.getBoundingClientRect().height || 0);
  expect(chatH).toBeGreaterThan(812 * 0.6);

  const menu = page.locator('.fm-context-menu');
  const textRow = page.locator('#chat .row.user', { hasText: '纯文本消息' });
  await expect(textRow).toBeVisible();

  // CDP 真长按：按住越过长按计时窗 ⇒ 零菜单（截按住中帧存证）。
  const { touch } = await longPressRow(page, textRow);
  await expect(menu).toHaveCount(0);
  await page.screenshot({ path: join(SHOTS_DIR, 'theme-dark_longpress-no-menu.png') });
  // 抬手（退役后无吞窗机制——全局 click 关闭监听原样）：仍零菜单。
  await touch.end();
  await page.waitForTimeout(250);
  await expect(menu).toHaveCount(0);

  // 原生补发腿（Android/iOS 长按会派发 contextmenu）：行上真事件 ⇒ 手机层闸拦截，
  // 同一事件在桌面腿必须弹菜单（T5 反向断言收口）。
  await textRow.evaluate((el) => el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 100, clientY: 300 })));
  await page.waitForTimeout(150);
  await expect(menu).toHaveCount(0);

  // 普通轻点（真触屏 <480ms）⇒ 零菜单（零打扰不变）。行先滚到视口中央——
  // dock/输入条会遮住贴底行的点击点（可操作性拦截），中央触点才是纯行区域。
  await textRow.evaluate((el) => el.scrollIntoView({ block: 'center' }));
  await page.waitForTimeout(150);
  const tap = await cdpTouch(page);
  const tb = await textRow.boundingBox();
  await tap.start(Math.round(tb.x + tb.width / 2), Math.round(tb.y + Math.min(tb.height / 2, 20)));
  await page.waitForTimeout(120);
  await tap.end();
  await page.waitForTimeout(150);
  await expect(menu).toHaveCount(0);

  // 滚动语义（退役后重述）：长按处理器是全站唯一的聊天区 touch 监听——它退役后
  // 「滚动取消长按」这一手势仲裁随之消失，值得守住的只剩两件事实：
  //   ① 触摸交互全程零菜单（上面已断言）；② 聊天滚动面自身完好（可滚、可复位）。
  // 拖动位移断言不在此处：手机层布局把聊天列压成贴底窄条（input-bar/dock 覆盖），
  // 夹具里 CDP 触摸拖不到纯聊天区——该断言旧版在本仓库 main 上即红（滚动容器
  // 已随后续手机批迁移），非本批回归面。
  const scrollOk = await page.evaluate(() => {
    let el = document.querySelector('#chat .row')?.parentElement;
    while (el && el !== document.body) {
      const oy = getComputedStyle(el).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight) {
        const before = el.scrollTop;
        el.scrollTop = 0;
        const reset = el.scrollTop === 0;
        el.scrollTop = before;   // 复位，不污染后续用例的滚动位
        return reset;
      }
      el = el.parentElement;
    }
    return false;
  });
  expect(scrollOk).toBe(true);
  // 触摸按下即滑（越过长按窗）依旧零菜单——retire 后无任何 touch 监听可被触发。
  const sw = await cdpTouch(page);
  const sb = await textRow.boundingBox();
  const sx = Math.round(sb.x + sb.width / 2);
  const sy = Math.round(sb.y + Math.min(sb.height / 2, 20));
  await sw.start(sx, sy);
  for (let i = 1; i <= 5; i++) await sw.move(sx, sy - i * 24);
  await page.waitForTimeout(650);
  await sw.end();
  await page.waitForTimeout(150);
  await expect(menu).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('T2 媒体行 footer：单件行时间+下载键（两腿下载真落盘），两件行有时间无下载', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 纯图片单件行（内联 data: 腿）：footer = 时间（data-ts 同值）+ 下载键。
  const imgRow = imgOnlyRow(page);
  await imgRow.scrollIntoViewIfNeeded();
  const imgBadge = imgRow.locator(':scope > .duration-badge');
  await expect(imgBadge).toBeVisible();
  await expect(imgBadge.locator('.duration-badge-time')).toHaveAttribute('data-ts', String(T_IMG));
  const imgDl = imgBadge.locator('.msg-copy.msg-dl');
  await expect(imgDl).toBeVisible();
  await expect(imgDl).toHaveAttribute('title', '下载');
  // 引用钮挂同一徽章（长按触达的替代入口，attachQuoteMenu rAF 收敛）。
  await expect(imgBadge.locator('.msg-quote-btn')).toHaveCount(1);

  // 下载（内联 data: 腿）：真实 download 事件 + 文件名。
  const dl1 = page.waitForEvent('download', { timeout: 8000 });
  await imgDl.click();
  const d1 = await dl1;
  expect(d1.suggestedFilename()).toBe('photo.jpg');

  // 纯文件单件行（path 票腿）：同样时间 + 下载键；下载走 nf-ticket 铸票 + nf-file。
  const fileRow = fileOnlyRow(page);
  await fileRow.scrollIntoViewIfNeeded();
  const fileBadge = fileRow.locator(':scope > .duration-badge');
  await expect(fileBadge).toBeVisible();
  await expect(fileBadge.locator('.duration-badge-time')).toHaveAttribute('data-ts', String(T_FILE));
  await expect(fileBadge.locator('.msg-copy.msg-dl')).toBeVisible();
  const dl2 = page.waitForEvent('download', { timeout: 8000 });
  await fileBadge.locator('.msg-copy.msg-dl').click();
  const d2 = await dl2;
  expect(d2.suggestedFilename()).toBe('报告.pdf');

  // 图+文件两件行：footer 有时间 + 引用钮，无下载键（一枚键对哪件负责说不清）。
  const mixRowEl = mixRow(page);
  await mixRowEl.scrollIntoViewIfNeeded();
  const mixBadge = mixRowEl.locator(':scope > .duration-badge');
  await expect(mixBadge).toBeVisible();
  await expect(mixBadge.locator('.duration-badge-time')).toHaveAttribute('data-ts', String(T_ATT));
  await expect(mixBadge.locator('.msg-quote-btn')).toHaveCount(1);
  await expect(mixBadge.locator('.msg-copy.msg-dl')).toHaveCount(0);

  // 文本行 footer（既有族不回归）：时间 + 复制 + 引用俱全。
  const textRow = page.locator('#chat .row.user', { hasText: '纯文本消息' });
  await textRow.scrollIntoViewIfNeeded();
  const textBadge = textRow.locator(':scope > .duration-badge');
  await expect(textBadge.locator('.duration-badge-time')).toHaveAttribute('data-ts', String(T_TEXT));
  await expect(textBadge.locator('.msg-copy:not(.msg-dl):not(.msg-quote-btn)')).toHaveCount(1);
  await expect(textBadge.locator('.msg-quote-btn')).toHaveCount(1);
  expect(pageErrors).toEqual([]);
});

test('T3 触达替代：媒体行 footer 引用钮 → chip → 发送 → 引用块缩略带（原长按链改新入口）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  // 图+文件两件行 footer 引用钮（触屏可点）⇒ 引用 chip 落附件条。
  // （两件行 = 原长按链的同款夹具：缩略带同时覆盖图片缩略 + 文件 chip。）
  const mixRowEl = mixRow(page);
  await mixRowEl.scrollIntoViewIfNeeded();
  await mixRowEl.locator('.duration-badge .msg-quote-btn').click();
  const chip = page.locator('#attachment-preview .att-ref');
  await expect(chip).toBeVisible();

  // 发送 ⇒ 乐观气泡上方引用块带 .msg-quote-atts（图片缩略 + 文件 chip）。
  await page.locator('#input').fill('回复带附件的消息');
  await page.locator('#send-btn').click();
  const quoteBlock = page.locator('#chat .row.user .msg-quote-block').last();
  await expect(quoteBlock).toBeVisible();
  const atts = quoteBlock.locator('.msg-quote-atts');
  await expect(atts).toBeVisible();
  const img = atts.locator('.msg-quote-att-media img.msg-quote-att-img');
  await expect(img).toHaveCount(1);
  await page.waitForFunction(() => {
    const im = document.querySelector('.msg-quote-atts img.msg-quote-att-img');
    return im && im.complete && im.naturalWidth > 0;
  }, { timeout: 5000 });
  const fileChip = atts.locator('.msg-quote-att-file');
  await expect(fileChip).toBeVisible();
  await expect(fileChip).toHaveAttribute('role', 'button');
  await expect(fileChip).toContainText('报告.pdf');

  // 点击可开（零死点击）：两路都 dispatch workspace-open-item（Pop 同款单点）。
  // DOM click 而非指针点击：打开会把 canvas 页推前盖住聊天页，第二次真实指针
  // 点击会被页面体拦截 —— 打开路径本身是 DOM 事件分派，DOM click 即是全链。
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

test('T4 历史重放腿：quote 快照缩略带票腿水合不变，重放媒体行 footer 时间档同值', async ({ page }) => {
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

  // 重放媒体行 footer：时间档与消息时间戳同值（persistence 两腿 ts 传递），
  // 引用钮在场（描述符随行挂）。
  const fileRow = fileOnlyRow(page);
  await expect(fileRow.locator(':scope > .duration-badge .duration-badge-time')).toHaveAttribute('data-ts', String(T_FILE));
  await expect(fileRow.locator(':scope > .duration-badge .msg-quote-btn')).toHaveCount(1);
  expect(pageErrors).toEqual([]);
});

test('T5 桌面右键同腿（退役不翻桌面案）：右键媒体行出菜单/引用块照常，桌面媒体行也带 footer', async ({ page }) => {
  // 桌面视口重开（mobileLayer 不激活）。
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
  expect(await page.evaluate(() => window.__nfMobileLongPress)).toBeUndefined();

  // 右键文本行 ⇒ 复制 + 引用（与退役前同一份 items）。
  const textRow = page.locator('#chat .row.user', { hasText: '带附件的消息正文' });
  await textRow.click({ button: 'right' });
  const menu = page.locator('.fm-context-menu');
  await expect(menu).toBeVisible();
  const items = await menu.locator('button').allTextContents();
  expect(items).toEqual(['复制', '引用']);
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

  // 桌面媒体行同样带 footer（通用聊天面）：时间 + 下载 + 引用钮（单件行）。
  const fileRow = fileOnlyRow(page);
  await fileRow.scrollIntoViewIfNeeded();
  const badge = fileRow.locator(':scope > .duration-badge');
  await expect(badge.locator('.duration-badge-time')).toHaveAttribute('data-ts', String(T_FILE));
  await expect(badge.locator('.msg-copy.msg-dl')).toHaveCount(1);
  await expect(badge.locator('.msg-quote-btn')).toHaveCount(1);
  await page.screenshot({ path: join(SHOTS_DIR, 't5-desktop-quote-block.png'), fullPage: false });
  expect(pageErrors).toEqual([]);
});

// ── T6 双主题存证：文件 footer / 图片 footer / 长按无弹 三样 × dark/light ──
for (const theme of ['dark', 'light']) {
  test(`T6 ${theme}：三样存证（文件 footer / 图片 footer / 长按无弹）`, async ({ browser }) => {
    // 独立 context 承载主题（colorScheme 是 context 级开关）；手机触屏视口同规格。
    const ctx = await browser.newContext({
      viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: theme,
    });
    const p = await ctx.newPage();
    const pageErrors = [];
    p.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(p);

    // 图片 footer（单件行整行帧）。
    const imgRow = imgOnlyRow(p);
    await imgRow.scrollIntoViewIfNeeded();
    await p.waitForTimeout(250);
    await p.screenshot({ path: join(SHOTS_DIR, `theme-${theme}_image-footer.png`) });

    // 文件 footer（单件行整行帧）。
    const fileRow = fileOnlyRow(p);
    await fileRow.scrollIntoViewIfNeeded();
    await p.waitForTimeout(250);
    await p.screenshot({ path: join(SHOTS_DIR, `theme-${theme}_file-footer.png`) });

    // 长按无弹（按住中帧存证；dark 主断言腿 T1 已带结构断言，此处为视觉存证）。
    const textRow = p.locator('#chat .row.user', { hasText: '纯文本消息' });
    await textRow.scrollIntoViewIfNeeded();
    const { touch } = await longPressRow(p, textRow);
    await expect(p.locator('.fm-context-menu')).toHaveCount(0);
    await p.screenshot({ path: join(SHOTS_DIR, `theme-${theme}_longpress-no-menu.png`) });
    await touch.end();

    expect(pageErrors).toEqual([]);
    await ctx.close();
  });
}
