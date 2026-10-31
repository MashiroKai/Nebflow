// mobile-chat-topclip.spec.mjs — 手机端 AskUser 卡白屏修复批回归钉
// （作者 10-31 15:38 截图令：ask 卡上方整段白、卡内容被 mask 裁没、只剩
// 取消/确认浮在半空）。
//
// 病根（取证 evidence/tmp-askuser-mobile-repro/askuser-mobile-garbage-clip-
// light.png）：#chat 顶部 mask 的锚点变量 --chat-top-clip 由 main.js 顶带
// 观察器按 `header.top − chat.top` 写入；手机层桌面 #header display:none
// （rect 全零），任何让 #chat 盒瞬态上移的读数（iOS 固定层橡皮筋/键盘位移，
// getBoundingClientRect 含 transform）都会把 clip 写成数百 px 垃圾值，而
// 观察器只挂在 header/overlays resize 上、转场结束后无重测 ⇒ 垃圾值持久化
// ⇒ 消息区顶部整段被 mask 成白屏，AskUser 卡题干区被裁没、按钮行露在裁切
// 线之下（作者实拍同款）。
//
// 修 = main.js syncTopReserve：body.mobile-layer 下 clip 恒 0（顶缘渐隐带
// #g2-edge-fade 自带「滚入渐隐」，chat 顶部不再挂 mask；桌面公式不变）+
// 渐隐带节点挂进 ResizeObserver（bandBottom 成员，层翻转后必有一拍重测）。
//
// 本 spec（自足：route 拦截静态资源 + mock WS，不进真实网关）锁三条：
//   C1 手机层初始 clip = '' 或 0px（不写垃圾值）
//   C2 模拟垃圾瞬态（强写 390px）后触发重测 ⇒ clip 回到 0px（mask 解除、
//      卡片题干恢复可见——白屏不复发）
//   C3 双主题同款（亮/暗各跑一遍 C1/C2）
// Run: npx playwright test tests/mobile-chat-topclip.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'topclip-session-1';

async function bootMobile(page, colorScheme) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'kaiyu' }], activeId: SID }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root 的会话', agentName: 'kaiyu' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'getProjects' || m.type === 'listProjects') ws.send(JSON.stringify({ type: 'projects', projects: [] }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  await page.waitForTimeout(900);
}

for (const scheme of ['light', 'dark']) {
  test.describe(`手机端 chat-top-clip 白屏修复 (${scheme})`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.emulateMedia({ colorScheme: scheme });
      await bootMobile(page, scheme);
    });

    test(`C1 [${scheme}] 手机层初始 clip 为零值（不写垃圾 mask 锚点）`, async ({ page }) => {
      const clip = await page.evaluate(() => document.getElementById('chat').style.getPropertyValue('--chat-top-clip').trim());
      expect(clip === '' || clip === '0px').toBe(true);
    });

    test(`C2 [${scheme}] 垃圾瞬态 clip 在重测后归零（白屏不复发）`, async ({ page }) => {
      // 模拟 iOS 固定层瞬态：观察器恰在 #chat 盒上移时读数 ⇒ 写入 390px 垃圾值
      await page.evaluate(() => {
        document.getElementById('chat').style.setProperty('--chat-top-clip', '390px');
      });
      const poisoned = await page.evaluate(() => getComputedStyle(document.getElementById('chat')).getPropertyValue('--chat-top-clip').trim());
      expect(poisoned).toBe('390px');
      // resize 事件 = 观察器兜底重算路径（main.js 顶带观察器挂 window resize）
      await page.evaluate(() => window.dispatchEvent(new Event('resize')));
      await page.waitForTimeout(200);
      const healed = await page.evaluate(() => document.getElementById('chat').style.getPropertyValue('--chat-top-clip').trim());
      expect(healed === '' || healed === '0px').toBe(true);
      // mask 消费端仍在场（机制不拆，只是锚点被正确维护）
      const mask = await page.evaluate(() => String(getComputedStyle(document.getElementById('chat')).maskImage));
      expect(mask).toContain('linear-gradient');
    });
  });
}
