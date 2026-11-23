// social-input-unify-shots.mjs — 「社交对话窗输入框 ⇄ 主会话输入框统一」批的真渲染
// 实拍 + 读数 harness（作者令：社交对话窗口的输入框完全与主会话输入框保持一致，
// 包括语音气泡与发送按钮）。
//
// 产物（FM_SHOTS 目录，缺省 .stand/tmp/social-input-unify/evidence）：
//   main-desktop.png / social-desktop.png   桌面档输入条实拍（主会话 #input-bar / 社交 .fm-input-bar）
//   main-mobile.png  / social-mobile.png    手机档（375×812）输入条实拍
//   voice-social.gif / voice-main.gif       语音光球按下后的状态动效（逐帧 GIF）
//   readings.json                           两面输入条几何 / 类名 / 键位读数
//
// 零实例依赖：静态资源 = 内置 http server；REST/WS 为 page.route 契约镜像（非生产网关）。
// 运行：node tests/social-input-unify-shots.mjs
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.FM_SHOTS || join(ROOT, '..', '..', 'tmp', 'social-input-unify', 'evidence');
mkdirSync(OUT, { recursive: true });

const FIXED = new Date(2026, 10, 4, 15, 30, 0).getTime();
const iso = ms => new Date(ms).toISOString();
const sleep = ms => new Promise(r => setTimeout(r, ms));
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(FIXED - 86400000) };
const row = (id, senderId, atMs, body) => ({ id, senderId, kind: 'text', body, createdAt: iso(atMs), attachments: [] });
const MSGS = { cF: [row(1, 'u-p', FIXED - 3600e3, 'hello')] };
const CONVS = [{ conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[0], unreadCount: 0 }];
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    if (p.startsWith('/agents/')) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
    const file = join(WEB, p === '/' ? 'index.html' : p);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
const readings = { web: WEB, out: OUT, runs: {} };
const errors = [];

async function boot(viewport) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, colorScheme: 'light' });
  await ctx.addInitScript(() => {
    for (const k of ['nebflow', 'neblink']) {
      localStorage.setItem(`${k}_token`, 't');
      localStorage.setItem(`${k}_locale`, 'zh-CN');
    }
  });
  const page = await ctx.newPage();
  page.on('pageerror', e => errors.push(`pageerror: ${String(e).slice(0, 200)}`));
  page.on('console', m => { if (m.type() === 'error') errors.push(`console: ${m.text().slice(0, 200)}`); });
  await page.clock.setFixedTime(new Date(FIXED));
  await page.route('**/api/**', async (route) => {
    const p = new URL(route.request().url()).pathname;
    const j = (b) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
    if (p === '/api/neblink/status') return j({ loggedIn: true,
      relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
      device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
      peers: [] });
    if (p === '/api/conversations') return j(CONVS);
    if (p === '/api/friends') return j({ friends: [FRIEND], incoming: [], outgoing: [] });
    if (p === '/api/groups' || p === '/api/groups/invites') return j(p === '/api/groups' ? [] : { incoming: [] });
    const mm = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (mm) return j(MSGS[decodeURIComponent(mm[1])] || []);
    return j({});
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: FIXED }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page };
}

// 输入条读数：同一套键位都量一遍（盒高、类名、光球壳、语音/附件/发送键）。
const readBar = (page, sel) => page.evaluate((s) => {
  const bar = document.querySelector(s);
  if (!bar) return null;
  const rect = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
  const q = (x) => bar.querySelector(x);
  const input = q('textarea, input');
  const send = q('.fm-send-btn, #send-btn');
  const attach = q('.fm-attach-btn, #attach-btn, .icon-btn:not(.fm-mic-btn)');
  const mic = q('.fm-mic-btn, #mic-btn, .micbubble');
  const wrap = q('.mic-orb-wrap');
  const cs = (el) => el ? getComputedStyle(el) : null;
  return {
    barClass: bar.className,
    barRect: rect(bar),
    barRadius: cs(bar)?.borderRadius,
    inputTag: input?.tagName, inputClass: input?.className, inputRect: rect(input),
    micClass: mic?.className, micRect: rect(mic),
    micWrapClass: wrap?.className || null, micWrapRect: rect(wrap),
    orbCanvas: !!q('canvas.orb-canvas'), cssOrb: !!q('.css-orb'),
    attachClass: attach?.className || null, attachRect: rect(attach),
    sendClass: send?.className, sendRect: rect(send), sendBg: cs(send)?.backgroundColor,
    sendRadius: cs(send)?.borderRadius,
  };
}, sel);

async function shotEl(page, sel, file) {
  const loc = page.locator(sel).first();
  await loc.scrollIntoViewIfNeeded();
  await sleep(250);
  const buf = await loc.screenshot();
  writeFileSync(join(OUT, file), buf);
  return file;
}

// 语音按下动效：逐帧截图 → PIL 合成 GIF（动效必须 GIF）。
async function voiceGif(page, sel, name) {
  const loc = page.locator(sel).first();
  const frameDir = join(OUT, `_frames-${name}`);
  rmSync(frameDir, { recursive: true, force: true });
  mkdirSync(frameDir, { recursive: true });
  await loc.scrollIntoViewIfNeeded();
  await sleep(250);
  const mic = loc.locator('.fm-mic-btn, #mic-btn, .micbubble').first();
  const frames = [];
  const grab = async () => {
    const f = join(frameDir, `f${String(frames.length).padStart(3, '0')}.png`);
    writeFileSync(f, await loc.screenshot());
    frames.push(f);
  };
  for (let i = 0; i < 3; i++) { await grab(); await sleep(120); }
  await mic.click({ force: true }).catch(e => errors.push(`mic-click ${name}: ${String(e).slice(0, 120)}`));
  for (let i = 0; i < 12; i++) { await sleep(140); await grab(); }
  await mic.click({ force: true }).catch(() => {});
  for (let i = 0; i < 4; i++) { await sleep(140); await grab(); }
  const gif = join(OUT, `voice-${name}.gif`);
  execFileSync('python3', ['-c', `
import sys
from PIL import Image
files = sys.argv[2:]
imgs = [Image.open(f).convert('RGB') for f in files]
imgs[0].save(sys.argv[1], save_all=True, append_images=imgs[1:], duration=140, loop=0, optimize=False)
`, gif, ...frames]);
  return { gif, frames: frames.length };
}

// ── 桌面档 ──
{
  const { ctx, page } = await boot({ width: 1280, height: 900 });
  // 主会话输入条（默认主视图）
  readings.runs.desktopMain = await readBar(page, '#input-bar');
  await shotEl(page, '#input-bar', 'main-desktop.png');
  readings.runs.voiceMain = await voiceGif(page, '#input-bar', 'main');
  // 打开社交好友会话窗
  await page.click('#messages-btn'); await sleep(600);
  await page.click('#fm-conversations .fm-conv-row[data-conversation-id="cF"]'); await sleep(900);
  readings.runs.desktopSocial = await readBar(page, '#fm-chat-overlay .fm-input-bar');
  await shotEl(page, '#fm-chat-overlay .fm-input-bar', 'social-desktop.png');
  readings.runs.voiceSocial = await voiceGif(page, '#fm-chat-overlay .fm-input-bar', 'social');
  await ctx.close();
}

// ── 手机档（375×812）──
{
  const { ctx, page } = await boot({ width: 375, height: 812 });
  readings.runs.mobileMain = await readBar(page, '#input-bar');
  await shotEl(page, '#input-bar', 'main-mobile.png');
  // 移动端社交窗 = .mobile-page[data-page="conversation"]（.live），不是 #fm-chat-overlay
  // （composer-input-inset-metrics.spec.mjs 同一打开路径）。
  // 手机档进消息面 = 底部 dock 的消息键（composer-input-inset-metrics.spec.mjs 同路径）。
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').first().click({ timeout: 15000 })
    .catch(e => errors.push(`mobile dock: ${String(e).slice(0, 120)}`));
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="messages"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 15000 }).catch(() => errors.push('mobile messages page never went live'));
  await sleep(500);
  const row = page.locator('.fm-conv-row[data-conversation-id="cF"]').first();
  await row.click({ timeout: 15000 }).catch(e => errors.push(`mobile row: ${String(e).slice(0, 120)}`));
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="conversation"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 15000 }).catch(() => errors.push('mobile conversation never went live'));
  await sleep(700);
  const MSEL = '.mobile-page[data-page="conversation"] .fm-input-bar';
  readings.runs.mobileSocial = await readBar(page, MSEL);
  await shotEl(page, MSEL, 'social-mobile.png').catch(e => errors.push(`mobile shot: ${String(e).slice(0, 120)}`));
  await ctx.close();
}

readings.errors = errors;
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
await browser.close();
server.close();
console.log(JSON.stringify({ out: OUT, desktopMain: readings.runs.desktopMain, desktopSocial: readings.runs.desktopSocial, errors: errors.length }, null, 2));
