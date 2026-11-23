#!/usr/bin/env node
// chat-input-autosize-shots.mjs — 输入框四态 × 两端实拍 + 动效 GIF（输入框增高）。
//
// 产物（默认 OUT=.stand/tmp/chat-input-perm-card/evidence/autosize-shots）：
//   <vp>-<label>-<state>.png   四态（empty / oneLine / multiLine / veryLong）× 两端
//   <vp>-<label>-grow.gif      动效：空态 → 逐行打字增高到封顶 → 内部滚动 → 收回空态
//   montage-<label>.png        两端四态拼图（一图看全）
//   shots.json                 每张图的盒高/光标读数（图与读数同源）
//
// 用法：LABEL=before|after node tests/chat-input-autosize-shots.mjs
// 改前腿（LABEL=before）需把 web 树指到基线：CHATFACE_WEB_ROOT / WEB_ROOT 指到
// 基线导出树，或直接用 `git stash`-free 的基线 worktree 跑同一脚本。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(HERE);
const WEB = process.env.WEB_ROOT ? normalize(process.env.WEB_ROOT) : join(REPO, 'src', 'main', 'resources', 'web');
const LABEL = process.env.LABEL || 'after';
const OUT = process.env.OUT || join(REPO, '.stand', 'tmp', 'chat-input-perm-card', 'evidence', 'autosize-shots');
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'chat-input-shots';
const TEXTS = {
  empty: '',
  oneLine: '一行文本',
  multiLine: ['第一行：多行输入', '第二行', '第三行', '第四行'].join('\n'),
  veryLong: Array.from({ length: 20 }, (_, i) => `第 ${i + 1} 行：超长文本复现，用于验证上限后的内部滚动与光标锚定`).join('\n'),
};
const VIEWPORTS = [
  { name: 'desktop-1440x900', width: 1440, height: 900 },
  { name: 'mobile-375x812', width: 375, height: 812 },
];

async function boot(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_input_history', '[]');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.route('**/api/neblink/status', (r) => r.fulfill({
    json: { loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '' }, peers: [] },
  }));
  const now = Date.now();
  const history = [
    { type: 'user', text: '输入框四态取证用行', timestamp: now - 5000 },
    { type: 'ai', text: '收到', timestamp: now - 4000 },
  ];
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: history, hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.send(JSON.stringify({ type: 'historyPage', sessionId: SID, messages: history, hasMore: false, offset: 0 }));
  });
  await page.goto('http://localhost:1/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await page.waitForTimeout(600);
}

/** 写值走产品入口（与草稿恢复/召回同腿）；基线树无该入口时退回「写值+mec」等价写法。 */
const setText = async (page, text) => page.evaluate(async (t) => {
  const ta = document.querySelector('#input');
  try {
    const mod = await import('/js/input/composer.js');
    if (typeof mod.setComposerText === 'function') { mod.setComposerText(ta, t); return 'setComposerText'; }
    if (typeof mod.growComposerInput === 'function') {
      ta.value = t; ta.style.height = 'auto'; mod.growComposerInput(ta);
      ta.setSelectionRange(t.length, t.length); return 'growComposerInput';
    }
  } catch { /* 基线无模块面 */ }
  ta.value = t; ta.style.height = 'auto';
  ta.setSelectionRange(t.length, t.length);
  return 'raw';
}, text);

const READ = () => {
  const ta = document.querySelector('#input');
  const cs = getComputedStyle(ta);
  const r = ta.getBoundingClientRect();
  const lh = parseFloat(cs.lineHeight) || 0;
  const mirror = document.createElement('div');
  mirror.style.cssText = `position:absolute;left:-99999px;top:0;width:${ta.clientWidth}px;font:${cs.font};`
    + `line-height:${cs.lineHeight};padding:${cs.padding};box-sizing:${cs.boxSizing};`
    + 'white-space:pre-wrap;overflow-wrap:break-word;';
  mirror.textContent = ta.value.slice(0, ta.selectionStart ?? ta.value.length);
  const mark = document.createElement('span'); mark.textContent = '\u200b'; mirror.appendChild(mark);
  document.body.appendChild(mirror);
  const caretY = r.top + (mark.getBoundingClientRect().top - mirror.getBoundingClientRect().top) - ta.scrollTop;
  document.body.removeChild(mirror);
  return {
    lines: ta.value === '' ? 0 : ta.value.split('\n').length,
    styleH: ta.style.height, clientH: ta.clientHeight, scrollH: ta.scrollHeight,
    scrollTop: Math.round(ta.scrollTop),
    caretVisiblePx: Math.round((caretY - r.top) * 10) / 10,
    caretVisible: caretY >= r.top - 1 && caretY + lh <= r.bottom + 1,
    barH: Math.round(document.querySelector('#input-bar').getBoundingClientRect().height),
  };
};

const browser = await chromium.launch();
const out = { label: LABEL, webRoot: WEB, at: new Date().toISOString(), viewports: {} };
const videos = [];
for (const vp of VIEWPORTS) {
  const vidDir = join(OUT, `video-${vp.name}`);
  mkdirSync(vidDir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: vp.width, height: vp.height }, locale: 'zh-CN',
    recordVideo: { dir: vidDir, size: { width: vp.width, height: vp.height } },
  });
  const page = await ctx.newPage();
  await boot(page);
  const rec = {};
  for (const [state, text] of Object.entries(TEXTS)) {
    await page.click('#input');
    const how = await setText(page, text);
    await page.waitForTimeout(220);
    rec[state] = { ...(await page.evaluate(READ)), how };
    await page.screenshot({ path: join(OUT, `${vp.name}-${LABEL}-${state}.png`) });
  }
  // 动效：空 → 逐行打字增高（封顶）→ 内部滚动 → 收回空态
  await page.click('#input');
  await setText(page, '');
  await page.waitForTimeout(300);
  for (let i = 0; i < 12; i++) {
    await page.keyboard.insertText(`第 ${i + 1} 行：输入框增高动效`);
    if (i < 11) await page.keyboard.press('Shift+Enter');
    await page.waitForTimeout(150);
  }
  await page.waitForTimeout(400);
  rec.typingGrow = await page.evaluate(() => {
    const ta = document.querySelector('#input');
    return { clientH: ta.clientHeight, scrollH: ta.scrollHeight };
  });
  await page.evaluate(async () => {
    const { setComposerText } = await import('/js/input/composer.js').catch(() => ({}));
    const ta = document.querySelector('#input');
    if (setComposerText) setComposerText(ta, '');
  });
  await page.waitForTimeout(400);
  rec.grownBack = await page.evaluate(() => {
    const ta = document.querySelector('#input');
    return { clientH: ta.clientHeight };
  });
  out.viewports[vp.name] = rec;
  const v = await page.video()?.path().catch(() => null);
  await ctx.close();
  if (v) videos.push({ vp: vp.name, path: v });
}
await browser.close();

// GIF：录屏 → 调色板 GIF（16fps、宽不超过 720px，体积可控）
const gifs = [];
for (const { vp, path } of videos) {
  const gif = join(OUT, `${vp}-${LABEL}-grow.gif`);
  const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', path,
    '-vf', 'fps=16,scale=720:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer',
    gif], { encoding: 'utf8' });
  if (r.status === 0) gifs.push(gif);
  else console.error(`ffmpeg failed for ${vp}: ${r.stderr}`);
}

writeFileSync(join(OUT, `shots-${LABEL}.json`), JSON.stringify({ ...out, gifs }, null, 2));

// 拼图：桌面 + 手机各一行四态（PIL 不可用则跳过，png 仍在）
const py = `
import json, os
from PIL import Image
p = ${JSON.stringify(join(OUT, `shots-${LABEL}.json`))}
d = json.load(open(p))
labels = ['empty','oneLine','multiLine','veryLong']
rows = []
for vp in ['desktop-1440x900','mobile-375x812']:
    imgs = [Image.open(os.path.join(${JSON.stringify(OUT)}, f'{vp}-${LABEL}-{s}.png')) for s in labels]
    if vp.startswith('mobile'):
        imgs = [im.crop((0, max(0, im.height-420), im.width, im.height)) for im in imgs]
    else:
        imgs = [im.crop((int(im.width*0.18), int(im.height*0.7), int(im.width*0.63), im.height)) for im in imgs]
    h = max(i.height for i in imgs); w = sum(i.width for i in imgs)
    row = Image.new('RGB', (w, h), 'white'); x = 0
    for im in imgs: row.paste(im, (x, 0)); x += im.width
    rows.append(row)
W = max(r.width for r in rows); H = sum(r.height for r in rows)
m = Image.new('RGB', (W, H), 'white'); y = 0
for r in rows: m.paste(r, (0, y)); y += r.height
m.save(os.path.join(${JSON.stringify(OUT)}, 'montage-${LABEL}.png'))
print('montage ok')
`;
const mont = spawnSync('python3', ['-c', py], { encoding: 'utf8' });
if (mont.status !== 0) console.error('montage skipped:', mont.stderr?.slice(0, 300));

console.log(`shots(${LABEL}) → ${OUT}`);
for (const [vp, rec] of Object.entries(out.viewports)) {
  for (const s of ['empty', 'oneLine', 'multiLine', 'veryLong']) {
    const m = rec[s];
    console.log(`  ${vp} ${s.padEnd(10)} how=${String(m.how).padEnd(18)} clientH=${String(m.clientH).padStart(4)} scrollH=${String(m.scrollH).padStart(4)} caretVisible=${m.caretVisible}`);
  }
}
console.log(`gifs: ${gifs.join(', ') || '(none)'}`);
