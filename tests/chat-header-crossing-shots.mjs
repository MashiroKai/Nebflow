// chat-header-crossing-shots.mjs — 聊天窗口顶部消失点批（2026-10-22 作者追加两症状）证据。
//
// 症状①：内容区渐隐未退役（气泡边缘渐变淡出）。症状②：消失点应锚定 Header 顶缘
// （文字从 Header 下穿过），Header 下缘硬切/极窄过渡 —— 滚动遮罩锚点 = Header 顶缘。
// 症状③（2026-10-22 作者回访裁定「不允许任何渐变蒙层盖在气泡内容上」）：穿顶首行
// 上的灰蒙渐变条 = #top-overlays 的 .scroll-under-glass::after 裙边（带空也画，
// 28px tint+blur 压在带下首行上；归因探针 tests/tmp-topclip-band-probe.mjs 四连拍
// 实证：仅隐藏该元素带区均色即回亮）—— 裙边随顶带裙边退役批整组摘除，本契约加
// 「无蒙层压字」双腿：DOM（不挂类/伪元素不在）+ 像素（隐藏该元素前后带区零差）。
//
// 用法：node tests/chat-header-crossing-shots.mjs <evidenceSubdir> [light|dark|both]
// 产物 = evidence/<subdir>/C-*.png + stdout 断言清单（真 web 树 + 真 WS 桩）。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SUB = process.argv[2] || '20261022_chatinput-two-defects';
const SCHEMES = (process.argv[3] || 'both').split(',').flatMap(s => (s === 'both' ? ['light', 'dark'] : [s]));
const OUT = join(HERE, '..', 'evidence', SUB);
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-header-cross';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

function wireUp(ws) {
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '顶部消失点E2E' }], activeId: SID, folders: [] }));
}

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();

for (const scheme of SCHEMES) {
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 860 }, colorScheme: scheme, deviceScaleFactor: 2,
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);

  // 灌一屏半的消息（绿泡为主，可辨识穿顶形态），滚到中部让内容穿过 header 带。
  await page.evaluate(() => (async () => {
    const chat = await import('/js/chat.js');
    for (let i = 1; i <= 26; i++) {
      chat.renderUserBubble(`穿顶验证消息 ${i} —— 用于滚动遮罩锚点与渐隐退役的截图证据。`, []);
      if (i % 3 === 0) {
        const row = document.createElement('div');
        row.className = 'row ai';
        const b = document.createElement('div');
        b.className = 'bubble ai';
        b.textContent = `助手回复 ${i}。`;
        row.appendChild(b);
        document.getElementById('chat').appendChild(row);
      }
    }
  })());
  await sleep(400);
  // 滚到中部：让若干条消息已穿过 header 带（上方内容被遮罩/裁剪）。
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    chat.scrollTop = chat.scrollHeight * 0.45;
  });
  await sleep(500);

  const geo = await page.evaluate(() => {
    const header = document.getElementById('header');
    const chat = document.getElementById('chat');
    const cs = getComputedStyle(chat);
    const hcs = getComputedStyle(header);
    const hRect = header.getBoundingClientRect();
    const cRect = chat.getBoundingClientRect();
    return {
      clipVar: cs.getPropertyValue('--chat-top-clip').trim() || '(unset)',
      maskImage: cs.maskImage || cs.webkitMaskImage || '',
      marginTop: cs.marginTop,
      headerTopRel: Math.round(hRect.top - cRect.top),
      headerBottomRel: Math.round(hRect.bottom - cRect.top),
      chatTop: Math.round(cRect.top),
      scrolled: chat.scrollTop,
      mobile: document.body.classList.contains('mobile-layer'),
      mobileHeaderSkirt: (() => {
        const af = getComputedStyle(header, '::after');
        return { content: af.content, height: af.height, mask: (af.maskImage || af.webkitMaskImage || '').slice(0, 60) };
      })(),
      // Header 底缘零边界批：外投影退役 + 底边框透明化（作者令 2026-10-22）。
      headerBoxShadow: hcs.boxShadow,
      borderBottom: { color: hcs.borderBottomColor, width: hcs.borderBottomWidth },
    };
  });
  const tag = `${scheme}${geo.mobile ? '-mobile' : ''}`;
  console.log(`[${tag}]`, JSON.stringify(geo));
  // 桌面新契约断言（手机层跑桌面分支会标 mobile，仅断渐隐退役腿）：
  ok(`[${tag}] #chat 顶部裁剪变量在场（--chat-top-clip）`, geo.clipVar !== '(unset)', geo.clipVar);
  ok(`[${tag}] #chat 挂 mask（滚动遮罩）`, /linear-gradient/.test(geo.maskImage), geo.maskImage.slice(0, 80));
  ok(`[${tag}] 裁剪锚 ≈ Header 顶缘（±3px）`, Math.abs(parseFloat(geo.clipVar) - geo.headerTopRel) <= 3,
    `var=${geo.clipVar} headerTop=${geo.headerTopRel}`);
  ok(`[${tag}] 内容区渐隐退役：#header 无 ::after 裙边`, geo.mobileHeaderSkirt.content === 'none' || geo.mobileHeaderSkirt.content === 'normal',
    JSON.stringify(geo.mobileHeaderSkirt));
  ok(`[${tag}] 滚动态（内容确已越过 header 带）`, geo.scrolled > 200, `scrollTop=${geo.scrolled}`);
  // Header 底缘零边界批（2026-10-22 作者追加令「Header 下面有一个边缘…要移除」）：
  // 穿顶气泡上的横向边界线 = #header 外投影（暗档 .20/.35 深色带）+ 底缘 1px
  // border。契约：box-shadow 只剩 inset 层（无外投影）+ 底边框不可见
  // （transparent / 零 alpha）——底缘只许剩毛玻璃 tint/blur 自身边界。
  const shadowLayers = geo.headerBoxShadow && geo.headerBoxShadow !== 'none' ? geo.headerBoxShadow.split(/,(?![^(]*\))/).map(s => s.trim()) : [];
  ok(`[${tag}] 底缘零边界①：#header 外投影退役（box-shadow 仅 inset 层）`,
    shadowLayers.length > 0 && shadowLayers.every(s => /\binset\b/.test(s)), geo.headerBoxShadow);
  const bm = /rgba?\(([^)]*)\)/.exec(geo.borderBottom.color);
  const bAlpha = bm ? Math.max(...bm[1].split(',').map(Number)) : 1;
  ok(`[${tag}] 底缘零边界②：底边框不可见（transparent 或 1px 透明占位）`,
    geo.borderBottom.width === '0px' || bAlpha === 0, JSON.stringify(geo.borderBottom));

  // 症状③（2026-10-22 作者回访裁定）：无蒙层压字 —— DOM 腿：顶带不挂裙边类、
  // ::after 伪元素不存在（带空时 28px tint+blur 裙边恰压穿顶首行 = 作者所指渐变条）。
  const bandSkirt = await page.evaluate(() => {
    const to = document.getElementById('top-overlays');
    const af = getComputedStyle(to, '::after');
    return { hasClass: to.classList.contains('scroll-under-glass'), content: af.content, mask: af.maskImage || af.webkitMaskImage || '' };
  });
  ok(`[${tag}] 无蒙层压字①：#top-overlays 裙边退役（不挂类 + ::after 不存在）`,
    !bandSkirt.hasClass && /none|normal/.test(bandSkirt.content) && !/linear-gradient/.test(bandSkirt.mask),
    JSON.stringify(bandSkirt));

  // 症状③像素腿：header 底缘下首行区（裙边带原位）截图 → 隐藏 #top-overlays →
  // 再截 —— 蒙层若在，两拍必异；退役后两拍逐通道均色差 ≤2（零贡献实证）。
  const avgOf = async (buf) => {
    const b64 = buf.toString('base64');
    return page.evaluate(async (b64) => {
      const img = new Image();
      img.src = 'data:image/png;base64,' + b64;
      await img.decode();
      const cv = document.createElement('canvas');
      cv.width = img.width; cv.height = img.height;
      const c2 = cv.getContext('2d');
      c2.drawImage(img, 0, 0);
      const d = c2.getImageData(0, 0, cv.width, cv.height).data;
      let r = 0, g = 0, b = 0; const n = d.length / 4;
      for (let i = 0; i < n; i++) { r += d[i * 4]; g += d[i * 4 + 1]; b += d[i * 4 + 2]; }
      return [r / n, g / n, b / n];
    }, b64);
  };
  const bandClip = { x: 470, y: Math.round(geo.headerBottomRel + 2), width: 360, height: 26 }; // header 底缘下 2..28px（原裙边带位）
  const withOverlay = await avgOf(await page.screenshot({ clip: bandClip }));
  await page.evaluate(() => { document.getElementById('top-overlays').style.visibility = 'hidden'; });
  const withoutOverlay = await avgOf(await page.screenshot({ clip: bandClip }));
  await page.evaluate(() => { document.getElementById('top-overlays').style.visibility = ''; });
  const drift = Math.max(...withOverlay.map((v, i) => Math.abs(v - withoutOverlay[i])));
  ok(`[${tag}] 无蒙层压字②：顶带对穿顶首行零像素贡献（隐藏前后均色差 ≤2）`,
    drift <= 2, `drift=${drift.toFixed(2)} with=[${withOverlay.map(v => v.toFixed(0))}] without=[${withoutOverlay.map(v => v.toFixed(0))}]`);

  await page.screenshot({ path: join(OUT, `C-header-crossing-${tag}.png`) });
  // 顶部特写：header 带 ± 60px。
  const hRect = await page.evaluate(() => document.getElementById('header').getBoundingClientRect());
  await page.screenshot({
    path: join(OUT, `C-header-crossing-${tag}-closeup.png`),
    clip: { x: 0, y: 0, width: 1280, height: Math.min(hRect.bottom + 60, 860) },
  });
  ok(`[${tag}] 零页面错误`, pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));
  await ctx.close();
}

await browser.close();
await new Promise(r => server.close(r));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
