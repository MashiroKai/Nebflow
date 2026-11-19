// device-provenance.spec.mjs — 入站消息来源设备标记 · 客户端声明面（E2E）
//
// 契约（device-provenance 批 2026-10-23；标注全自动批 2026-10-26 作者令
// 「设置里这里相关的内容去掉，消息来源识别应该是自动的」；设备角标本名批
// 2026-10-27 作者令「不要用local，本地的也显示本地的设备名」+ 07:05 补充裁决
// 「-iso 后缀只剥显示面，完整原值放 title」）：
//   1. 稳定身份：clientId localStorage 持久、跨刷新不变；deviceStamp() 形状 =
//      {clientId, platform, browser} + 自动判定的 {boundDeviceId}（花名册 self
//      命中才携带；缺席键省略）。手动命名键 name 已退役，永不携带。
//   2. 自动判定三分支：登录会话/配对设备 → 花名册 self ⇒ boundDeviceId 自动随帧；
//      匿名（花名册不可得）→ 无 boundDeviceId ⇒ 网关默认名兜底。
//   3. 设置面退役：「消息设备标记」区（命名输入/保存/绑定下拉/说明文案）与
//      locales 键（zh/en）零残留；手动存储键（device_name / device_bound_id）
//      存量遗留值不再被读。
//   4. 真人发送帧随帧带 `device` 声明：输入框直投（typeless 帧）端到端截获断言；
//      队列「立即发送」等其余发送点的随帧声明由 DeviceProvenanceInjectionGateSpec
//      （源契约门）钉死。
//   5. 工具卡设备角标本名：本机字面（local/self）解析为本机设备名（花名册 self →
//      configData.selfDevice 引擎身份；两源皆缺不渲染角标），**绝不直出「local」**；
//      隔离实例 `-iso-<hex8>` 后缀只剥显示读数（title/tooltip 保留完整原值），
//      身份面（boundDeviceId / currentAutoBinding / 缓存 full 原值）不受影响。
//
// 自包含：page.route 静态面 + page.routeWebSocket 假 WS（branding-migration
// 同法），无后端、无 sbt。花名册面 mock /api/neblink/status。
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/device-provenance.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

// 台账 #933 复验批补钉：platform/browser 断言依赖 UA 派生（deviceIdentity
// detectPlatform/detectBrowser），Playwright 默认 UA 随宿主机漂移（Mac 上 =
// mac/Chrome，Windows 宿主 = windows/…）—— 全文件钉一支 macOS Chrome UA，
// 断言机无关（#933 batchA 在 Windows KAI 宿主复验时发现并修正）。
test.use({
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
});

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'e2e-root-session';
// 本机身份名带隔离实例后缀（NeblinkModel.mintedDeviceName：非默认数据根 =
// `-iso-<8位指纹>`）——真实形态，供「剥后缀只打显示面」的断言用。
const SELF_DEVICE_NAME_RAW = 'Mashiros-MacBook-Pro-iso-a659864a';
const SELF_DEVICE_NAME_DISPLAY = 'Mashiros-MacBook-Pro';
const ROSTER = {
  device: { id: 'self-mac-1', name: SELF_DEVICE_NAME_RAW },
  peers: [
    { deviceId: 'peer-win-1', deviceName: 'DESKTOP-IJBBEOT', online: true },
    { deviceId: 'peer-phone-1', deviceName: 'Kaiyu-iPhone', online: false },
  ],
};

/**
 * Minimal boot（branding-migration 同法）：configData + sessionList + 假 WS。
 * 所有客户端 → 服务器帧被截进 `frames` 供逐帧断言。
 * roster = ROSTER（默认，花名册可达 ⇒ 自动绑定 self.id）| null（401 ⇒ 匿名分支）。
 * opts.selfDeviceName：configData.selfDevice.name（引擎自身身份加法字段，批⑤）——
 * 默认 = 本机身份原值（现行引擎形态）；null = 帧不带该键（老引擎形态）。
 */
async function bootApp(page, frames, roster = ROSTER, opts = {}) {
  const selfDeviceName = opts.selfDeviceName === undefined ? SELF_DEVICE_NAME_RAW : opts.selfDeviceName;
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/neblink/status') {
      if (roster === null) return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(roster) });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
      ...(selfDeviceName !== null ? { selfDevice: { name: selfDeviceName } } : {}),
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      frames.push(msg);
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(200);
}

/** 最近一次被截获的、带 content 的用户帧（typeless 或 immediateInput）。 */
function lastUserFrame(frames, pred = () => true) {
  for (let i = frames.length - 1; i >= 0; i--) {
    const f = frames[i];
    if (typeof f.content === 'string' && pred(f)) return f;
  }
  return null;
}

/** 显式刷新自动绑定读数（ws onopen 也会刷，这里显式等待保证断言确定性）。 */
async function refreshBinding(page) {
  return page.evaluate(async () => {
    const m = await import('/js/deviceIdentity.js');
    return m.refreshAutoBinding();
  });
}

test('device identity: stable clientId; auto-bound stamp shape from the fleet roster; legacy manual keys ignored', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);

  await refreshBinding(page);
  const stamp1 = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp1.clientId, 'clientId 必须在场（身份主键）').toBeTruthy();
  // Playwright 默认桌面 UA（本机 = macOS headless chrome）。
  expect(stamp1.platform).toBe('mac');
  expect(stamp1.browser).toBe('Chrome');
  // 自动判定：花名册 self 命中 ⇒ boundDeviceId 自动随帧（登录会话/配对设备分支）。
  expect(stamp1.boundDeviceId, '花名册 self 自动绑定').toBe('self-mac-1');
  expect(stamp1, '手动命名键已退役，永不携带').not.toHaveProperty('name');

  // 跨刷新稳定：reload 后同一 clientId。
  await page.reload();
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  const stamp2 = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp2.clientId).toBe(stamp1.clientId);

  // 手动命名/绑定的存量遗留值（旧版本写入）不再被读：stamp 仍走自动判定，
  // 绝不复活 name 键、绝不采信遗留绑定值。
  await page.evaluate(async () => {
    const { key } = await import('/js/branding.js');
    localStorage.setItem(key('device_name'), '客厅 iPad（遗留）');
    localStorage.setItem(key('device_bound_id'), 'peer-phone-1（遗留）');
  });
  await page.reload();
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await refreshBinding(page);
  const stamp3 = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp3, '遗留 name 值不复活').not.toHaveProperty('name');
  expect(stamp3.boundDeviceId, '遗留绑定值不采信，仍为花名册自动判定').toBe('self-mac-1');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('typeless send: the input-box frame carries the automatic device declaration', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);

  await refreshBinding(page);
  await page.fill('#input', '设备标记探针消息');
  await page.press('#input', 'Enter');
  await page.waitForTimeout(300);

  const frame = lastUserFrame(frames);
  expect(frame, '必须截获 typeless 用户帧').toBeTruthy();
  expect(frame.content).toBe('设备标记探针消息');
  expect(frame.device, 'typeless 帧必须带 device 声明').toBeTruthy();
  expect(frame.device.clientId).toBeTruthy();
  expect(frame.device.platform).toBe('mac');
  expect(frame.device.browser).toBe('Chrome');
  expect(frame.device.boundDeviceId, '自动绑定随帧上行').toBe('self-mac-1');
  expect(frame.device, '手动命名键已退役').not.toHaveProperty('name');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('anonymous branch: roster unavailable ⇒ no boundDeviceId (gateway default tag path)', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames, null);

  const bound = await refreshBinding(page);
  expect(bound, '花名册不可得 ⇒ 自动绑定读数为空').toBe('');
  const stamp = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp.clientId).toBeTruthy();
  expect(stamp, '匿名分支不得携带 boundDeviceId（网关默认名兜底）').not.toHaveProperty('boundDeviceId');

  await page.fill('#input', '匿名分支探针');
  await page.press('#input', 'Enter');
  await page.waitForTimeout(300);
  const frame = lastUserFrame(frames);
  expect(frame?.device, '匿名帧仍带身份声明').toBeTruthy();
  expect(frame?.device?.clientId).toBeTruthy();
  expect(frame?.device, '匿名帧不得携带 boundDeviceId').not.toHaveProperty('boundDeviceId');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('settings face retired: no device-stamp section, no locale keys, no manual binding face', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);

  await page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  await expect(page.locator('#device-stamp-section'), '插槽已拆除').toHaveCount(0);
  await expect(page.locator('#cfg-device-stamp-name')).toHaveCount(0);
  await expect(page.locator('#cfg-device-stamp-bind')).toHaveCount(0);
  await expect(page.locator('#btn-device-stamp-name-save')).toHaveCount(0);
  const bodyText = await page.locator('#settings-content, #settings-overlay').first().innerText();
  expect(bodyText, '设置页不再出现「消息设备标记」区').not.toContain('消息设备标记');
  expect(bodyText).not.toContain('本网页设备命名');
  expect(bodyText).not.toContain('绑定到设备列表');

  // 源面退役断言（与 DeviceStampAutoTaggingSpec 同判据的 E2E 侧镜像）。
  const readSrc = (rel) => readFileSync(join(WEB, rel), 'utf8');
  for (const rel of ['js/locales/zh-CN.js', 'js/locales/en.js']) {
    const src = readSrc(rel);
    expect(src.includes("'settings.deviceStamp'"), `${rel} 不得再有 settings.deviceStamp 键`).toBe(false);
    expect(src.includes("'settings.deviceStampHint'"), `${rel} 不得再有 settings.deviceStampHint 键`).toBe(false);
  }
  const identitySrc = readSrc('js/deviceIdentity.js');
  for (const sym of ['setDeviceName', 'setDeviceBinding', 'mountDeviceStampSettings', 'defaultDeviceLabel']) {
    expect(identitySrc.includes(sym), `deviceIdentity.js 不得再有 ${sym}`).toBe(false);
  }
  const sidebarSrc = readSrc('js/sidebar.js');
  expect(sidebarSrc.includes('device-stamp-section'), 'sidebar.js 插槽已拆除').toBe(false);
  expect(sidebarSrc.includes('mountDeviceStampSettings'), 'sidebar.js 挂载点已拆除').toBe(false);
  // 自动判定面在位（同位替换，不是裸拆）。
  expect(identitySrc.includes('refreshAutoBinding'), '自动刷新面在位').toBe(true);
  expect(identitySrc.includes('currentAutoBinding'), '自动绑定同步读数在位').toBe(true);

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

// ══ 设备角标本名批（2026-10-27 作者令「不要用local，本地的也显示本地的设备名」
//    + 07:05 补充裁决「-iso 后缀只剥显示面，完整原值放 title」）══════════════

/** 经生产渲染路径（chat.js renderTool，live 面与 WS 管线同一函数）追加一张终态工具卡。 */
async function renderToolCard(page, input) {
  return page.evaluate((inp) => (async () => {
    const m = await import('/js/chat.js');
    m.renderTool('Bash\n  (cat /tmp/kaiti_archive_path.txt)', '3 lines of output', null, false, JSON.stringify(inp), 'e2e-root-session');
    const rows = document.querySelectorAll('.row.tool');
    const row = rows[rows.length - 1];
    return row ? row.querySelector('.tool-card').innerHTML : null;
  })(), input);
}

test('device tag: local-source tool capsule shows the machine device name, never the literal `local`', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);
  await refreshBinding(page);

  // 本机字面（local）→ 花名册 self 显示名（剥 -iso 后缀），绝不直出「local」。
  const localHtml = await renderToolCard(page, { command: 'cat /tmp/kaiti_archive_path.txt', device: 'local' });
  expect(localHtml, '本机来源必须渲染设备角标').toContain('tool-device-tag');
  expect(localHtml, '绝不显示字面 local').not.toContain('>local<');
  expect(localHtml, '显示 = 本机设备名（剥后缀）').toContain('>' + SELF_DEVICE_NAME_DISPLAY + '<');
  // 补充裁决：完整原值（含 -iso 后缀）放 title/tooltip。
  expect(localHtml, 'tooltip = 完整原始名').toContain(`title="${SELF_DEVICE_NAME_RAW}"`);

  // 「self」字面同走本机解析（ProjectOrigin.Self 同族）。
  const selfLitHtml = await renderToolCard(page, { command: 'echo hi', device: 'self' });
  expect(selfLitHtml).toContain('>' + SELF_DEVICE_NAME_DISPLAY + '<');
  expect(selfLitHtml).not.toContain('>local<');

  // 对端名原样直出（现状不变），无 tooltip。
  const remoteHtml = await renderToolCard(page, { command: 'echo hi', device: 'DESKTOP-IJBBEOT' });
  expect(remoteHtml, '对端名原样直出').toContain('>DESKTOP-IJBBEOT<');
  expect(remoteHtml, '对端名无 tooltip').not.toContain('title=');

  // 无 device 键 ⇒ 无角标（现状不变）。
  const noDevHtml = await renderToolCard(page, { command: 'echo hi' });
  expect(noDevHtml).not.toContain('tool-device-tag');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('device tag: -iso suffix stripped on the display face only; identity faces keep the raw name', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);
  await refreshBinding(page);

  // 纯函数钉：剥后缀生效；无后缀透传；空串安全。
  const strip = await page.evaluate(async () => {
    const m = await import('/js/deviceRoster.js');
    return {
      iso: m.stripIsoNameSuffix('Mashiros-MacBook-Pro-iso-a659864a'),
      plain: m.stripIsoNameSuffix('DESKTOP-IJBBEOT'),
      empty: m.stripIsoNameSuffix(''),
    };
  });
  expect(strip.iso, '剥 -iso-<hex8> 后缀生效').toBe('Mashiros-MacBook-Pro');
  expect(strip.plain, '无后缀名透传').toBe('DESKTOP-IJBBEOT');
  expect(strip.empty, '空值安全').toBe('');

  // 身份面不受影响：自动绑定读数 / 随帧 stamp 仍是原值 id（剥后缀只打显示读数，
  // 不碰 boundDeviceId / currentAutoBinding / 花名册 id↔名绑定）。
  const bound = await refreshBinding(page);
  expect(bound, '自动绑定读数 = 原值 id').toBe('self-mac-1');
  const stamp = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp.boundDeviceId, '随帧身份声明 = 原值 id').toBe('self-mac-1');

  // 缓存读数：full 保留原值（tooltip 真源），display 剥后缀（显示真源）。
  const selfName = await page.evaluate(async () => (await import('/js/deviceRoster.js')).currentSelfDeviceName());
  expect(selfName.full, 'full = 原值（身份面保真）').toBe(SELF_DEVICE_NAME_RAW);
  expect(selfName.display, 'display = 剥后缀').toBe(SELF_DEVICE_NAME_DISPLAY);

  const html = await renderToolCard(page, { command: 'cat x', device: 'local' });
  expect(html).toContain('>' + SELF_DEVICE_NAME_DISPLAY + '<');
  expect(html).toContain(`title="${SELF_DEVICE_NAME_RAW}"`);

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('device tag: roster unavailable ⇒ engine selfDevice fallback (configData), still never `local`', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  // 花名册 401（匿名腿）+ configData.selfDevice 在场（现行引擎形态）⇒ 引擎自身
  // 身份读数兜底（无身份文件时引擎侧已落主机名，恒非「local」）。
  await bootApp(page, frames, null);

  const html = await renderToolCard(page, { command: 'cat x', device: 'local' });
  expect(html, '引擎身份兜底仍渲染本机名').toContain('>' + SELF_DEVICE_NAME_DISPLAY + '<');
  expect(html, '绝不显示字面 local').not.toContain('>local<');
  expect(html, 'tooltip = 完整原始名').toContain(`title="${SELF_DEVICE_NAME_RAW}"`);

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('device tag: neither roster nor engine selfDevice ⇒ tag dropped, never the literal `local`', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  // 花名册 401 + 老引擎帧（configData 不带 selfDevice 键）⇒ 两源皆缺：不渲染角标
  // ——绝不回退「local」字样（作者令原判据）。
  await bootApp(page, frames, null, { selfDeviceName: null });

  const html = await renderToolCard(page, { command: 'cat x', device: 'local' });
  expect(html, '两源皆缺不渲染角标').not.toContain('tool-device-tag');
  expect(html || '', '绝不显示字面 local').not.toContain('>local<');

  // 对端名照常渲染（本分支只影响本机字面的解析失败面）。
  const remoteHtml = await renderToolCard(page, { command: 'echo hi', device: 'DESKTOP-IJBBEOT' });
  expect(remoteHtml).toContain('>DESKTOP-IJBBEOT<');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});
