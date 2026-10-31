// mobile-device-chat-windows.spec.mjs — 设备平级 + 手机端设备对话窗口修复批
// （2026-10-31 作者令 + 截图两张实锤）自验 harness。
//
// 覆盖：
//   M1 联系人页设备分组：本机以普通设备条目入列（首行：名/在线/系统），
//      与其它设备同条目规格；「本机 版本·通道·commit」更新卡整块退役
//      （#fm-local-update-card 零命中、页内无版本串）；本机行不挂远程更新键。
//   M2 375 视口一进一出：进 KAI 对话 → 单窗固定 → 返回 → 进 DESKTOP-IJBBEOT
//      → 仍只有一个对话窗（不串窗——修前旧收编窗残留页体，两窗同屏各带输入条）。
//   M3 禁横向滚动：注入无空格长串消息后，消息流与页面体 scrollWidth
//      均 ≤ clientWidth（修前 overflow-y 单边声明使另一轴计算为 auto，整页可左右拖）。
//   D1 桌面端不回退：宽视口设备段同 M1 行规格；点设备行进 #fm-chat-overlay
//      单窗，关闭再进第二台不串窗。
//
// 形态沿 devnamefix-window-header.spec.mjs / mobile-v3.spec.mjs 既有路线：
// 静态 route 引真实 js/css/locale，API/WS 全 stub —— 零后端、零真实端口。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-device-chat-windows.spec.mjs --workers=1 --reporter=line

import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { settlePage, dockTo } from './_mobile-dock-nav.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = join(ROOT, '.nebflow', 'evidence', '20261031_device-chat-windows-fix');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'devchat-root-session';
const SELF = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '' };
const PEERS = [
  { deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true },
  { deviceId: 'dev-desk', deviceName: 'DESKTOP-IJBBEOT', platform: 'windows', online: true, directOnline: false, relayAvailable: true },
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
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: SELF, peers: PEERS }),
        });
      }
      if (p === '/api/friends') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [], incoming: [], outgoing: [] }) });
      }
      if (p === '/api/conversations') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
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
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
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

/** 展开联系人页设备分组并等行渲染。 */
async function expandDevices(page) {
  await page.locator('[data-devices-entry]').click();
  await expect(page.locator('[data-device-section="1"]')).toHaveCount(1, { timeout: 5000 });
}

const deviceRows = (page) => page.locator('[data-device-section="1"] .fm-device-row');
const convModals = (page) => page.locator('.mobile-page[data-page="conversation"] .mobile-page-body > .fm-modal');

async function shot(page, name) {
  try {
    mkdirSync(EVID, { recursive: true });
    await page.screenshot({ path: join(EVID, name + '.png'), fullPage: false });
  } catch { /* 证据截图不是判据 */ }
}

// ── M 系：375 视口（手机层激活口径 = bootstrap.js 同源 ≤768px）──────────
test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

test('M1-M3 手机层：本机入设备列 + 单窗一进一出 + 禁横向滚动', async ({ page }) => {
  await bootApp(page);
  await expect(page.locator('body.mobile-layer')).toHaveCount(1);
  await dockTo(page, 'contacts');
  await expandDevices(page);

  // ── M1 设备分组行规格 ──
  const rows = deviceRows(page);
  await expect(rows).toHaveCount(3); // 本机 + 2 对端（设备平级）
  await expect(page.locator('[data-device-section="1"]')).toHaveAttribute('data-device-count', '3');
  // 本机首行：名 / 在线 / 系统
  await expect(rows.nth(0).locator('.neblink-peer-name')).toHaveText('Mashiro-Mac');
  await expect(rows.nth(0).locator('.neblink-presence')).toHaveText('在线');
  await expect(rows.nth(0)).toContainText('macOS');
  // 对端行不变（名 + 在线 + Windows + 远程更新键）
  await expect(rows.nth(1).locator('.neblink-peer-name')).toHaveText('KAI');
  await expect(rows.nth(2).locator('.neblink-peer-name')).toHaveText('DESKTOP-IJBBEOT');
  await expect(rows.nth(1).locator('[data-device-update-btn]')).toHaveCount(1);
  await expect(rows.nth(2).locator('[data-device-update-btn]')).toHaveCount(1);
  // 本机行不挂远程更新键（remoteUpdate 是远控对端语义）
  await expect(rows.nth(0).locator('[data-device-update-btn]')).toHaveCount(0);
  // 更新卡整块退役 + 版本/commit 串不进联系人页
  await expect(page.locator('#fm-local-update-card')).toHaveCount(0);
  const contactsText = await page.locator('.mobile-page[data-page="contacts"]').innerText();
  expect(contactsText).not.toMatch(/20\d\d\.\d{2}\.\d{2}/); // 版本串
  expect(contactsText).not.toMatch(/4e339ad64/); // commit 串
  await shot(page, 'm1-contacts-devices-self-row');

  // ── M2 一进一出不串窗（本机 → KAI → 返回 → DESKTOP）──
  await rows.nth(0).click(); // 本机也可点进对话
  await settlePage(page, 'conversation');
  await expect(convModals(page)).toHaveCount(1);
  await expect(convModals(page).nth(0).locator('.fm-modal-name')).toHaveText('Mashiro-Mac');
  await shot(page, 'm2-self-chat-single-window');

  const back = page.locator('.mobile-page[data-page="conversation"] .mobile-back-btn');
  await back.click();
  await expect(page.locator('.mobile-page[data-page="conversation"]')).not.toHaveClass(/live/);

  await rows.nth(1).click(); // KAI
  await settlePage(page, 'conversation');
  await expect(convModals(page)).toHaveCount(1);
  await expect(convModals(page).nth(0).locator('.fm-modal-name')).toHaveText('KAI');
  await shot(page, 'm2-kai-chat-single-window');
  await back.click();
  await expect(page.locator('.mobile-page[data-page="conversation"]')).not.toHaveClass(/live/);

  await rows.nth(2).click(); // DESKTOP-IJBBEOT——修前此处页体残留 KAI 旧窗 ⇒ 两窗同屏
  await settlePage(page, 'conversation');
  await expect(convModals(page)).toHaveCount(1);
  await expect(convModals(page).nth(0).locator('.fm-modal-name')).toHaveText('DESKTOP-IJBBEOT');
  // 全页也只有一个 fm-modal（含 overlay 壳内）：禁任何第二窗残留
  await expect(page.locator('.fm-modal')).toHaveCount(1);
  await shot(page, 'm2-desktop-chat-no-bleed');

  // ── M3 禁横向滚动：双探针 —— ① 300 无空格长串（断词折行承载，零内容丢失）；
  //    ② 固定 800px 宽块（真实溢出源形态：宽卡/pre——修前消息流/页体另一轴
  //    计算为 auto，整窗可左右拖）。clamp（hidden）语义 = 内容可裁、**容器不可
  //    拖**（scrollWidth 仍含溢出宽，故不变量钉在机制 + 窗口级）。 ──
  const m3 = await page.evaluate(() => {
    const body = document.querySelector('.mobile-page[data-page="conversation"] .mobile-page-body');
    const flow = body.querySelector('.fm-flow');
    const msg = document.createElement('div');
    msg.className = 'fm-msg';
    const bubble = document.createElement('div');
    bubble.className = 'fm-msg-bubble';
    bubble.textContent = 'a'.repeat(300); // 300 个无空格字符 ≈ 1800+px，远超 375 视口
    const wide = document.createElement('div');
    wide.style.width = '800px'; // 固定宽块：唯一靠容器 clamp 兜住
    wide.textContent = 'wide';
    msg.append(bubble, wide);
    flow.appendChild(msg);
    const cs = (el) => getComputedStyle(el).overflowX;
    return {
      flowOx: cs(flow),
      bodyOx: cs(body),
      // 长串探针必须被**折行**（scrollWidth 顶着容器宽 = 折行成立；超出 = 裁掉/撑宽）。
      bubbleContained: bubble.scrollWidth <= bubble.clientWidth + 1,
      // 窗口级：页面本身不得长出横向滚动条。
      docContained: document.scrollingElement.scrollWidth <= window.innerWidth + 1,
    };
  });
  expect(m3.flowOx).toBe('hidden'); // 消息流横向钉死（无此行 = 修前 auto 可拖）
  expect(m3.bodyOx).toBe('hidden'); // 页体横向钉死（一进一出固定窗的兜底）
  expect(m3.bubbleContained).toBe(true);
  expect(m3.docContained).toBe(true);
  await shot(page, 'm3-no-horizontal-scroll');
});

// ── D1：桌面端不回退（宽视口 = 手机层不激活）──────────────────────────
test('D1 桌面端：设备段行规格 + 会话窗单窗一进一出', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await bootApp(page);
  await expect(page.locator('body.mobile-layer')).toHaveCount(0);
  await page.click('#contacts-btn');
  await expect(page.locator('#panel-contacts.active')).toBeVisible({ timeout: 5000 });
  await page.waitForTimeout(300);
  await expandDevices(page);

  const rows = deviceRows(page);
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0).locator('.neblink-peer-name')).toHaveText('Mashiro-Mac');
  await expect(rows.nth(0).locator('.neblink-presence')).toHaveText('在线');
  await expect(rows.nth(0)).toContainText('macOS');
  await expect(rows.nth(0).locator('[data-device-update-btn]')).toHaveCount(0);
  await expect(rows.nth(1).locator('[data-device-update-btn]')).toHaveCount(1);
  await expect(page.locator('#fm-local-update-card')).toHaveCount(0);
  const panelText = await page.locator('#panel-contacts').innerText();
  expect(panelText).not.toMatch(/20\d\d\.\d{2}\.\d{2}/);
  await shot(page, 'd1-desktop-contacts-devices');

  // 桌面单窗：进 KAI → 关 → 进 DESKTOP ⇒ 每次 overlay 内恰一窗
  await rows.nth(1).click();
  await expect(page.locator('#fm-chat-overlay .fm-modal')).toHaveCount(1, { timeout: 5000 });
  await expect(page.locator('#fm-chat-overlay .fm-modal .fm-modal-name')).toHaveText('KAI');
  await page.locator('#fm-chat-overlay .fm-modal-close').click();
  await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);

  await rows.nth(2).click();
  await expect(page.locator('#fm-chat-overlay .fm-modal')).toHaveCount(1, { timeout: 5000 });
  await expect(page.locator('#fm-chat-overlay .fm-modal .fm-modal-name')).toHaveText('DESKTOP-IJBBEOT');
  await page.locator('#fm-chat-overlay .fm-modal-close').click();
  await expect(page.locator('#fm-chat-overlay')).toHaveCount(0);
  await shot(page, 'd1-desktop-chat-closed-clean');
});
