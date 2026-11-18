#!/usr/bin/env node
// tests/message-item-unify-shots.mjs — 动态证据（消息条目统一批 2026-11-18）。
// 为两个视口（375×812 手机 / 1280×800 桌面）录制一段演示：
//   气泡 → 附件区 → 叠卡（合起：整组 footer）→ 展开（每卡独立 footer + 下载键）
//   → footer 两格式（A：时间+复制；B：徽标+时间+动作）同基座。
// 录制方式 = Playwright recordVideo（Chromium 原生 WebM，无需外部编码器）；随后用
// 系统 ffmpeg 转 GIF（≤10MB）否则转 MP4。用法：node tests/message-item-unify-shots.mjs <outDir>

import { chromium } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, mkdir, readdir, stat } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const run = promisify(execFile);
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(ROOT, '.stand', 'tmp', 'composer-unify', 'evidence', 'shots');
const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const HARNESS = `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/stand-tokens.css">
<link rel="stylesheet" href="/css/design-system.css">
<link rel="stylesheet" href="/css/glass.css">
<link rel="stylesheet" href="/css/chat.css">
<link rel="stylesheet" href="/css/friends.css">
<style>body{background:#12141a;color:#e8e8ea;font:14px system-ui;padding:12px}
#stage{display:flex;flex-direction:column;gap:10px;align-items:flex-start}
.msg-cap{font:11px/1.6 ui-monospace,monospace;color:#8ea}</style></head><body>
<div id="stage"></div>
<script type="module">
import { messageBubbleNode, messageFooter, attachmentArea } from '/js/chatItem.js';
import { buildUserMediaRow } from '/js/popArtifacts.js';
const stage = document.getElementById('stage');
const PNG = ${JSON.stringify(PNG)};
const cap = (t) => { const d = document.createElement('div'); d.className='msg-cap'; d.textContent=t; stage.appendChild(d); };
window.__step = (n) => {
  if (n === 0) {
    stage.innerHTML=''; cap('1. bubble + attachment area');
    const bubble = messageBubbleNode('fm-msg-bubble bubble user ours'); bubble.textContent='Private message';
    stage.append(bubble, attachmentArea('pop-group'));
  } else if (n === 1) {
    cap('2. stacked attachments (collapsed: group footer)');
    const atts=[0,1,2].map(i=>({type:'image',name:'img'+i+'.png',path:'/tmp/img'+i+'.png',preview:PNG}));
    stage.appendChild(buildUserMediaRow(atts,{ts:1731900000000}));
  } else if (n === 2) {
    cap('3. expanded: per-card footer + download');
  } else if (n === 3) {
    cap('4. footer base: two formats');
    const a = messageFooter({ timestamp:1731900000000, copyText:'hi', timeFormat:'day' });
    const actions=document.createElement('span'); actions.className='fm-msg-actions';
    const b = messageFooter({ extraClass:'fm-msg-meta', badges:[{className:'fm-msg-agent-badge',text:'AGENT'}],
      timestamp:1731900000000, alwaysTime:true, timeText:'12:00', timeFormat:'clock', actions });
    stage.append(a,b);
  }
  return true;
};
</script></body></html>`;

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/' || url.pathname === '/harness.html') {
      res.writeHead(200, { 'content-type': 'text/html' }); res.end(HARNESS); return;
    }
    const rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '');
    const body = await readFile(join(WEB, rel));
    res.writeHead(200, { 'content-type': MIME[extname(rel)] || 'application/octet-stream' }); res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}/harness.html`;
await mkdir(OUT, { recursive: true });

const VIEWPORTS = [
  { name: 'mobile-375x812', width: 375, height: 812 },
  { name: 'desktop-1280x800', width: 1280, height: 800 },
];

const browser = await chromium.launch();
const report = [];
const FRAMES = 10; // 目标 GIF 帧率（fps）
for (const vp of VIEWPORTS) {
  const vdir = join(OUT, 'video-' + vp.name);
  await mkdir(vdir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, recordVideo: { dir: vdir, size: { width: vp.width, height: Math.min(vp.height, 900) } } });
  const page = await ctx.newPage();
  await page.goto(base);
  const wait = (ms) => page.waitForTimeout(ms);
  await page.evaluate(() => window.__step(0)); await wait(1400);
  await page.evaluate(() => window.__step(1)); await wait(1600);
  await page.locator('.ds-media-stack__pill, .pop-stack-area .pill').first().click(); await wait(1600);
  await page.evaluate(() => window.__step(2)); await wait(400);
  // 画面已展示展开态；再切回折叠演示分态往返
  await page.locator('.ds-media-stack__pill, .pop-stack-area .pill').first().click(); await wait(1200);
  await page.evaluate(() => window.__step(3)); await wait(1800);
  await ctx.close(); // flush video

  const files = (await readdir(vdir)).filter(f => f.endsWith('.webm'));
  const webm = join(vdir, files[0]);
  const gif = join(OUT, `message-item-unify-${vp.name}.gif`);
  const mp4 = join(OUT, `message-item-unify-${vp.name}.mp4`);
  // GIF 优先（≤10MB），否则 MP4。
  await run('ffmpeg', ['-y', '-i', webm, '-vf', `fps=${FRAMES},scale=${Math.min(vp.width, 640)}:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse`, '-loop', '0', gif]);
  let info = { viewport: vp.name, gif };
  const gs = await stat(gif);
  if (gs.size > 10 * 1024 * 1024) {
    await run('ffmpeg', ['-y', '-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', mp4]);
    info.mp4 = mp4; info.mp4Bytes = (await stat(mp4)).size;
  }
  info.gifBytes = gs.size;
  const probe = await run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', webm]);
  info.durationSec = Number(probe.stdout.trim()).toFixed(2);
  info.fps = FRAMES;
  info.method = 'Playwright recordVideo (Chromium WebM) -> ffmpeg palettegen GIF (fallback MP4/H.264)';
  report.push(info);
}
await browser.close();
await new Promise(r => server.close(r));
console.log(JSON.stringify(report, null, 2));
