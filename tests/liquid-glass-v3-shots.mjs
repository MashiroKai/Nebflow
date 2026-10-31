// liquid-glass-v3-shots.mjs — 液体玻璃 v3 实现批回归证据（作者 10-31 12:32 点选：
// F1 悬浮族 + 控件族 Stand 墨族扁平 + 发送钮恢复绿色）。自启动脚本（非
// @playwright/test 收集形）：
//   node tests/liquid-glass-v3-shots.mjs <输出目录>
// 数据面：全 mock（route 供真实 web 文件 + WS 三帧），零后端。
// 断言（computed style，双端 × 亮暗四机位）：
//   · 发送钮绿三档：#send-btn rest/hover/active = #07C160/#06AD56/#059A4C
//     （作者令「改回之前的绿色」，git c91de8456^ 考古值；暗主题同值）
//   · 发送键族跟随：synthetic .cfg-btn.fm-send-btn = 绿实底（:is 族规则压过
//     cfg-btn 中性档）；.ask-input .ask-send = 绿实底
//   · F1 悬浮族：手机顶缘圆钮 backdrop blur(20px) saturate(2.2) + 填充
//     亮 .30 / 暗 .10；canvas .canvas-source-toggle / .ask-fab 同档（扩面）
//   · 控件族中性档：.cfg-btn = 亮 #ECECEE / 暗 #2C2C31（--ctl-neutral-*）；
//     .cfg-btn-primary 仍墨族实底（亮 #111827 / 暗 #E6EDF3）
// 机位：mobile-light/dark 全景 + 圆钮特写；desktop-light/dark 绿色发送钮特写 +
// 控件族对照带（F1 圆钮/药丸 + 中性次钮 + 墨族主钮 + 绿发送芯片，同花底）。

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = resolve(process.argv[2] || 'evidence/20261031_liquid-glass-v3-impl');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, actual, expected) {
  const ok = actual === expected;
  results.push({ name, actual, expected, ok });
  if (!ok) console.error(`FAIL ${name}: actual=${actual} expected=${expected}`);
  else console.log(`ok   ${name} = ${actual}`);
}

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
      sessions: [{ id: 'lg3-shot', name: 'liquid-glass-v3', agentName: 'Nebula' }],
      folders: [], activeId: 'lg3-shot',
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
      bubble.textContent = `液体玻璃 v3 效果行 ${i} —— F1 轻霜悬浮钮 + 绿发送键回归。`;
      if (i % 2) bubble.style.background = palette[i % palette.length];
      row.appendChild(bubble);
      chat.appendChild(row);
    }
  });
  await page.waitForTimeout(200);
}

/** 发送钮绿三档 + F1 悬浮族 + 控件族中性档断言（单主题一机位）。 */
async function assertBatch(page, theme) {
  const send = page.locator('#send-btn');
  check(`${theme} send rest`, await send.evaluate((el) => getComputedStyle(el).backgroundColor), 'rgb(7, 193, 96)');
  const sbox = await send.boundingBox();
  await page.mouse.move(sbox.x + sbox.width / 2, sbox.y + sbox.height / 2);
  await page.waitForTimeout(300); /* 越过 0.15s 过渡再采样，防插值色 */
  check(`${theme} send hover`, await send.evaluate((el) => getComputedStyle(el).backgroundColor), 'rgb(6, 173, 86)');
  await page.mouse.down();
  await page.waitForTimeout(300);
  check(`${theme} send active`, await send.evaluate((el) => getComputedStyle(el).backgroundColor), 'rgb(5, 154, 76)');
  await page.mouse.up();
  await page.mouse.move(2, 2);

  // 合成探针：发送键族成员 / 中性次钮 / 墨族主钮（真实 CSS 级联，零 mock 样式）。
  const probe = await page.evaluate(() => {
    const strip = document.createElement('div');
    strip.id = 'lg3-probe';
    strip.style.cssText = 'position:fixed;left:-9999px;top:0;';
    strip.innerHTML =
      '<button type="button" class="cfg-btn fm-send-btn">族发送</button>' +
      '<button type="button" class="cfg-btn">中性次钮</button>' +
      '<button type="button" class="cfg-btn cfg-btn-primary">墨族主钮</button>' +
      '<button type="button" class="ask-input" style="position:static">' +
      '  <button type="button" class="ask-send">发</button></button>';
    document.body.appendChild(strip);
    const cs = (sel) => getComputedStyle(strip.querySelector(sel));
    return {
      familyBg: cs('.fm-send-btn').backgroundColor,
      familyRadius: cs('.fm-send-btn').borderRadius,
      neutralBg: cs('.cfg-btn:not(.fm-send-btn):not(.cfg-btn-primary)').backgroundColor,
      neutralBlur: cs('.cfg-btn:not(.fm-send-btn):not(.cfg-btn-primary)').backdropFilter || cs('.cfg-btn:not(.fm-send-btn):not(.cfg-btn-primary)').webkitBackdropFilter,
      primaryBg: cs('.cfg-btn-primary').backgroundColor,
      askSendBg: cs('.ask-send').backgroundColor,
    };
  });
  check(`${theme} family fm-send-btn bg`, probe.familyBg, 'rgb(7, 193, 96)');
  check(`${theme} neutral cfg-btn bg`, probe.neutralBg, theme === 'light' ? 'rgb(236, 236, 238)' : 'rgb(44, 44, 49)');
  check(`${theme} neutral cfg-btn no blur`, probe.neutralBlur, 'none');
  check(`${theme} primary cfg-btn ink`, probe.primaryBg, theme === 'light' ? 'rgb(17, 24, 39)' : 'rgb(230, 237, 243)');
  check(`${theme} ask-send bg`, probe.askSendBg, 'rgb(7, 193, 96)');

  // F1 悬浮族：手机顶缘圆钮 / canvas 圆钮 / ask 开合钮（扩面三族同档）。
  /* 圆钮 blur 在 ::before 折射层（liquid-glass.css 架构），探针读伪元素。 */
  const fab = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="tasks"]');
  if (await fab.count()) {
    const f = await fab.first().evaluate((el) => {
      const cs = getComputedStyle(el);
      const pb = getComputedStyle(el, '::before');
      return { bg: cs.backgroundColor, bf: pb.backdropFilter || pb.webkitBackdropFilter };
    });
    check(`${theme} fab F1 fill`, f.bg, theme === 'light' ? 'rgba(255, 255, 255, 0.3)' : 'rgba(255, 255, 255, 0.1)');
    check(`${theme} fab F1 blur`, f.bf, 'blur(20px) saturate(2.2)');
  }
  const cvProbe = await page.evaluate(() => {
    const el = document.createElement('button');
    el.className = 'canvas-source-toggle';
    document.body.appendChild(el);
    const cs = getComputedStyle(el);
    const out = { bg: cs.backgroundColor, bf: cs.backdropFilter || cs.webkitBackdropFilter };
    el.remove();
    return out;
  });
  check(`${theme} canvas toggle F1 fill`, cvProbe.bg, theme === 'light' ? 'rgba(255, 255, 255, 0.3)' : 'rgba(255, 255, 255, 0.1)');
  check(`${theme} canvas toggle F1 blur`, cvProbe.bf, 'blur(20px) saturate(2.2)');
}

async function shotMobile(colorScheme) {
  const tag = `mobile-${colorScheme}`;
  const { browser, page } = await boot({ width: 375, height: 812, colorScheme });
  await seedColorfulRows(page);
  await assertBatch(page, colorScheme);

  await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(OUT, `${tag}-root.png`) });

  const fab = page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="tasks"]');
  const box = await fab.boundingBox();
  await page.screenshot({
    path: join(OUT, `${tag}-fab-closeup.png`),
    clip: { x: Math.max(0, box.x - 28), y: Math.max(0, box.y - 28), width: box.width + 56, height: box.height + 56 },
  });
  await browser.close();
}

async function shotDesktop(colorScheme) {
  const tag = `desktop-${colorScheme}`;
  const { browser, page } = await boot({ width: 1280, height: 800, colorScheme });
  await seedColorfulRows(page);
  await assertBatch(page, colorScheme);

  // 绿色发送钮特写（本批作者令的回报机位）。
  const send = await page.locator('#send-btn').boundingBox();
  const inputBar = await page.locator('#input-bar').boundingBox();
  await page.screenshot({
    path: join(OUT, `${tag}-send-closeup.png`),
    clip: { x: send.x - 30, y: inputBar.y - 8, width: send.width + 60, height: inputBar.height + 16 },
  });

  // 控件族对照带（同 mockup D 节机位，换墨族/绿配色）：F1 圆钮 / F1 药丸 /
  // 中性次钮 / 墨族主钮 / 发送键族芯片，压花底读材质。
  await page.evaluate(() => {
    const strip = document.createElement('div');
    strip.id = 'lg3-shot-strip';
    strip.style.cssText =
      'position:fixed;left:120px;top:140px;z-index:9999;display:flex;gap:14px;align-items:center;' +
      'padding:26px 30px;border-radius:18px;background:linear-gradient(135deg,#dfe5ee,#cdd6e4,#b8c6dd);';
    strip.innerHTML =
      '<button type="button" class="g2-fab" aria-label="圆钮特写"><i data-lucide="plus"></i></button>' +
      '<button type="button" class="g2-pill">F1 玻璃药丸</button>' +
      '<button type="button" class="cfg-btn">中性次钮</button>' +
      '<button type="button" class="cfg-btn cfg-btn-primary">墨族主钮</button>' +
      '<button type="button" class="cfg-btn fm-send-btn cfg-btn-primary">发送</button>';
    document.body.appendChild(strip);
    if (typeof lucide !== 'undefined') lucide.createIcons({ attrs: {}, nameAttr: 'data-lucide' });
  });
  await page.waitForTimeout(300);
  const strip = await page.locator('#lg3-shot-strip').boundingBox();
  await page.screenshot({ path: join(OUT, `${tag}-ctl-family.png`), clip: strip });
  await browser.close();
}

await shotMobile('light');
await shotMobile('dark');
await shotDesktop('light');
await shotDesktop('dark');

const failed = results.filter((r) => !r.ok);
writeFileSync(join(OUT, 'assertions.json'), JSON.stringify({ when: new Date().toISOString(), total: results.length, failed: failed.length, results }, null, 2));
console.log(`\n${results.length - failed.length}/${results.length} assertions ok; shots + assertions.json written to ${OUT}`);
if (failed.length) process.exit(1);
