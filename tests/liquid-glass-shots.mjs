// liquid-glass-shots.mjs — 液体玻璃 v2 批截图证据（作者令：移动/桌面截图证据，
// 折射边缘特写必须有）。自启动脚本（非 @playwright/test 收集形）：
//   node tests/liquid-glass-shots.mjs <输出目录>
// 数据面：全 mock（route 供真实 web 文件 + WS 三帧），零后端。
// 机位：
//   mobile-light / mobile-dark   聊天根全景（无顶栏 + 渐隐带 + 双悬浮圆钮 + 入流标题）
//   mobile-*-scrolled            滚动态：内容穿入渐隐带「先糊后没」
//   mobile-*-fab-closeup         折射边缘特写（彩色底上的圆钮，3× 缩放裁切）
//   desktop-light / desktop-dark 桌面按钮族（输入条发送药丸 + g2 药丸/圆钮对照带）
//   fallback-light               no-backdrop 降级态（实色圆钮 + 渐隐带纯 veil）
//   send-closeup                 发送键墨玻璃药丸特写

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = resolve(process.argv[2] || 'reports/liquid-glass-shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

mkdirSync(OUT, { recursive: true });

async function boot({ width, height, colorScheme = 'light' }) {
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 2 });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'shots-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
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
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: 'lg2-shot', name: 'liquid-glass-v2', agentName: 'Nebula' }],
      folders: [], activeId: 'lg2-shot',
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
  await page.emulateMedia({ colorScheme });
  await page.setViewportSize({ width, height });
  await page.goto('http://localhost:1/');
  if (width <= 768) {
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  }
  await page.waitForTimeout(700);
  return { browser, page };
}

/** 种一批彩色消息行（渐隐带/折射需要花底才能读出效果）。 */
async function seedColorfulRows(page) {
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const palette = ['#5b8def', '#4aa88c', '#c2784a', '#8a6fd6', '#c24a6f', '#6f9c4a'];
    for (let i = 0; i < 16; i++) {
      const row = document.createElement('div');
      row.className = `row ${i % 2 ? 'ai' : 'user'}`;
      const bubble = document.createElement('div');
      bubble.className = `bubble ${i % 2 ? 'ai' : 'user'}`;
      bubble.textContent = `液体玻璃 v2 效果行 ${i} —— 滚入顶缘 88px 渐隐带时应先模糊后渐没。`;
      if (i % 2) bubble.style.background = palette[i % palette.length];
      row.appendChild(bubble);
      chat.appendChild(row);
    }
  });
  await page.waitForTimeout(200);
}

async function shotMobile(colorScheme) {
  const tag = `mobile-${colorScheme}`;
  const { browser, page } = await boot({ width: 375, height: 812, colorScheme });
  await seedColorfulRows(page);

  await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${tag}-root.png`) });

  await page.evaluate(() => { document.getElementById('chat').scrollTop = 240; });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${tag}-scrolled.png`) });

  // 折射边缘特写：左圆钮 + 右圆钮区域 3× 裁切（彩色内容在带下 ⇒ 边缘光畸变可读）。
  const fab = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="tasks"]');
  const box = await fab.boundingBox();
  await page.screenshot({
    path: join(OUT, `${tag}-fab-closeup.png`),
    clip: { x: Math.max(0, box.x - 28), y: Math.max(0, box.y - 28), width: box.width + 56, height: box.height + 56 },
  });
  const search = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="search"]');
  const sbox = await search.boundingBox();
  await page.screenshot({
    path: join(OUT, `${tag}-fab-closeup-r.png`),
    clip: { x: Math.max(0, sbox.x - 28), y: Math.max(0, sbox.y - 28), width: sbox.width + 56, height: sbox.height + 56 },
  });
  await browser.close();
}

async function shotDesktop(colorScheme) {
  const tag = `desktop-${colorScheme}`;
  const { browser, page } = await boot({ width: 1280, height: 800, colorScheme });
  await seedColorfulRows(page);
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${tag}-shell.png`) });

  // 桌面按钮族对照带（同 mockup D 节机位）：g2 圆钮 40 / 玻璃药丸 36 / 墨玻璃药丸 36，
  // 压在花底上读折射与高光。
  await page.evaluate(() => {
    const strip = document.createElement('div');
    strip.id = 'g2-shot-strip';
    strip.style.cssText =
      'position:fixed;left:120px;top:140px;z-index:9999;display:flex;gap:14px;align-items:center;' +
      'padding:26px 30px;border-radius:18px;background:linear-gradient(135deg,#dfe5ee,#cdd6e4,#b8c6dd);';
    strip.innerHTML =
      '<button type="button" class="g2-fab" aria-label="圆钮特写"><i data-lucide="plus"></i></button>' +
      '<button type="button" class="g2-pill">玻璃药丸</button>' +
      '<button type="button" class="g2-pill g2-pill-primary">墨玻璃药丸</button>';
    document.body.appendChild(strip);
    if (typeof lucide !== 'undefined') lucide.createIcons({ attrs: {}, nameAttr: 'data-lucide' });
  });
  await page.waitForTimeout(300);
  const strip = await page.locator('#g2-shot-strip').boundingBox();
  await page.screenshot({ path: join(OUT, `${tag}-button-family.png`), clip: strip });

  // 发送键墨玻璃药丸特写。
  const send = await page.locator('#send-btn').boundingBox();
  const inputBar = await page.locator('#input-bar').boundingBox();
  await page.screenshot({
    path: join(OUT, `${tag}-send-closeup.png`),
    clip: { x: send.x - 30, y: inputBar.y - 8, width: send.width + 60, height: inputBar.height + 16 },
  });
  await browser.close();
}

async function shotFallback() {
  const { browser, page } = await boot({ width: 375, height: 812, colorScheme: 'light' });
  await page.evaluate(() => document.documentElement.classList.add('no-backdrop'));
  await seedColorfulRows(page);
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, 'fallback-light-root.png') });
  const fab = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="tasks"]');
  const box = await fab.boundingBox();
  await page.screenshot({
    path: join(OUT, 'fallback-light-fab-closeup.png'),
    clip: { x: Math.max(0, box.x - 28), y: Math.max(0, box.y - 28), width: box.width + 56, height: box.height + 56 },
  });
  await browser.close();
}

await shotMobile('light');
await shotMobile('dark');
await shotDesktop('light');
await shotDesktop('dark');
await shotFallback();
console.log(`shots written to ${OUT}`);
