// mobile-download-media.mjs — mobile-download 批「手机全屏前后」动效存证。
//
// 产出：手机层 375×812 全屏（deviceScaleFactor=2 → 750×1624 实拍，GIF 内缩小到
// 逻辑像素 375×812）、12 fps、逐帧 PNG → ffmpeg GIF + MP4（H.264）。ffprobe 实测
// 像素/帧率/时长由本脚本收尾打印。
//
// 相位（MDS_PHASE）：
//   before —— 在「修前树」（无 js/downloadSave.js）跑：点附件下载键 → 屏幕**零反馈**
//             （文件被静默交给浏览器下载管理器，作者困惑的直接现象）。
//   after  —— 在修后树跑：点同一枚键 → (能力支持) 系统分享面板腿 + 落点提示；
//             再点一次（能力不支持档）→ 原下载 + 落点提示。
//
// env：MDS_PHASE=before|after、MDS_OUT=<dir>、MDS_ENGINE=chromium|webkit、HEADLESS=0
//
// Run: MDS_PHASE=after MDS_OUT=/tmp/mds node tests/mobile-download-media.mjs

import { chromium, webkit } from 'playwright';
import { readFileSync, mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const PHASE = process.env.MDS_PHASE === 'before' ? 'before' : 'after';
const ENGINE = process.env.MDS_ENGINE || 'chromium';
const HEADLESS = process.env.HEADLESS !== '0';
const OUT = process.env.MDS_OUT || join(HERE, '..', 'evidence', `mds-${PHASE}`);
const FRAMES = join(OUT, `frames-${PHASE}`);
const FPS = 12;
const VIEW = { width: 375, height: 812 };
const SID = 'mds-media-root';

const FFMPEG = ['/opt/homebrew/bin/ffmpeg', 'ffmpeg'].find((p) => p === 'ffmpeg' || existsSync(p));

// 真实附件卡（用户消息 + 图片附件）：走主会话媒体卡（popArtifacts）的下载键。
const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAEAAAAAqCAIAAACMZMq1AAAAPElEQVR42u3PAQkAAAgDsGNMExrTHIfBCixzWy0CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgJNHtp/qpfLpgCAAAAAAElFTkSuQmCC';
const IMG_PATH = '/tmp/mds/photo.png';
const HISTORY = [
  { type: 'ai', text: '自媒体归档存证：请把这张现场照片存下来。', timestamp: Date.now() - 120_000 },
  {
    type: 'user', text: '', timestamp: Date.now() - 60_000, injected: false,
    attachments: [{ name: 'site-photo.png', type: 'image', path: IMG_PATH, preview: 'data:image/png;base64,' + PNG_B64 }],
  },
  { type: 'ai', text: '收到。', timestamp: Date.now() - 30_000 },
];

const INIT = () => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_sw', '0');
  Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  // 分享能力**桩**：真机 = 系统分享面板（OS 行为，自动化只能验「调用了 + 参数对」）。
  window.__nfShare = { mode: 'files', calls: [] };
  Object.defineProperty(navigator, 'canShare', {
    configurable: true,
    value: (d) => !!(d && d.files && d.files.length) && window.__nfShare.mode === 'files',
  });
  Object.defineProperty(navigator, 'share', {
    configurable: true,
    value: async (d) => { window.__nfShare.calls.push({ n: d?.files?.[0]?.name, t: d?.files?.[0]?.type, s: d?.files?.[0]?.size }); },
  });
};

async function boot(page) {
  await page.addInitScript(INIT);
  await page.route('**/*', (route) => {
    const raw = route.request().url();
    if (!/^https?:/i.test(raw)) return route.continue();   // blob:/data: 交引擎原生处理（WebKit 会把 blob fetch 也暴露给 route）
    let p = decodeURIComponent(new URL(raw).pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p.startsWith('/api/nf-file')) return route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from(PNG_B64, 'base64') });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const ssn = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ssn();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ssn();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:8921/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
}

let frameNo = 0;
const frameStamps = [];
async function snap(page, n = 1, label = '') {
  for (let i = 0; i < n; i += 1) {
    frameNo += 1;
    frameStamps.push(Date.now());
    await page.screenshot({ path: join(FRAMES, `frame-${String(frameNo).padStart(4, '0')}.png`) });
    await page.waitForTimeout(1000 / FPS - 4);
  }
  if (label) console.log(`  frames → ${frameNo} (${label})`);
}

/** 实测采集节拍（每帧 screenshot 的固有耗时会让真实节拍慢于 FPS）——按**实测**节拍
 *  编码，GIF/MP4 的时长与真实时间一致（不虚报帧率）。 */
function effectiveFps() {
  if (frameStamps.length < 2) return FPS;
  const ms = (frameStamps[frameStamps.length - 1] - frameStamps[0]) / (frameStamps.length - 1);
  return Math.round((1000 / ms) * 100) / 100;
}

function encode(outDir, stem, fps) {
  const gif = join(outDir, `${stem}.gif`);
  const mp4 = join(outDir, `${stem}.mp4`);
  const palette = join(outDir, `palette-${stem}.png`);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', join(FRAMES, 'frame-%04d.png'), '-vf', 'palettegen', palette]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', join(FRAMES, 'frame-%04d.png'), '-i', palette, '-lavfi', 'paletteuse', '-loop', '0', gif]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', join(FRAMES, 'frame-%04d.png'),
    '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-r', String(fps), mp4]);
  rmSync(palette, { force: true });
  return { gif, mp4 };
}

function probe(file) {
  const out = execFileSync('/opt/homebrew/bin/ffprobe', ['-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,avg_frame_rate,nb_frames', '-show_entries', 'format=duration,size',
    '-of', 'json', file], { encoding: 'utf8' });
  return JSON.parse(out);
}

const engine = ENGINE === 'webkit' ? webkit : chromium;
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(FRAMES, { recursive: true });

const browser = await engine.launch({ headless: HEADLESS });
const ctx = await browser.newContext({ viewport: VIEW, hasTouch: true, colorScheme: 'dark', acceptDownloads: true, locale: 'zh-CN', deviceScaleFactor: 2 });
const page = await ctx.newPage();
console.log(`── phase=${PHASE} engine=${ENGINE} 375×812 (dsf=2) ${FPS}fps ──`);
await boot(page);
await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
await page.waitForFunction(() => !!document.querySelector('#chat .row.user-media .duration-badge .msg-dl'), null, { timeout: 15000 });
await page.evaluate(() => { document.querySelector('#chat').scrollTop = document.querySelector('#chat').scrollHeight; });
await page.waitForTimeout(400);

// 1) 静置（整幅手机屏）
await snap(page, 10, 'idle');

// 2) 第 1 拍：点附件下载键（分享能力档）——先清掉启动期的无关通知
//    （夹具下 canvas 保存失败会弹一条 toast，与本批落点提示无关）。
await page.evaluate(() => { document.querySelectorAll('.nebflow-toast').forEach((n) => n.remove()); });
await page.waitForTimeout(200);
const dlBtn = page.locator('#chat .row.user-media .duration-badge .msg-dl').first();
await dlBtn.click();
await page.waitForTimeout(400);                    // 通知栈淡入 ~250ms
const toast1 = await page.evaluate(() => document.querySelector('.nebflow-toast .nebflow-toast-msg')?.textContent || '');
await snap(page, 40, 'tap1');

// 3) 第 2 拍：能力不支持档再点一次（原下载腿 + 落点提示）
await page.evaluate(() => { window.__nfShare.mode = 'none'; document.querySelectorAll('.nebflow-toast').forEach((n) => n.remove()); });
await page.waitForTimeout(200);
await dlBtn.click();
await page.waitForTimeout(400);
const toast2 = await page.evaluate(() => document.querySelector('.nebflow-toast .nebflow-toast-msg')?.textContent || '');
await snap(page, 40, 'tap2');

const effFps = effectiveFps();
const { gif, mp4 } = encode(OUT, `mobile-download-${PHASE}`, effFps);
const shareCalls = await page.evaluate(() => window.__nfShare.calls);
const realMs = frameStamps.length > 1 ? frameStamps[frameStamps.length - 1] - frameStamps[0] : 0;
writeFileSync(join(OUT, `readings-${PHASE}.json`), JSON.stringify({
  phase: PHASE, engine: ENGINE, viewport: VIEW, deviceScaleFactor: 2,
  nominalFps: FPS, measuredFps: effFps, frames: frameNo, captureWallMs: realMs, shareCalls,
  toastAfterTap1: toast1, toastAfterTap2: toast2,
}, null, 2));
console.log(`share calls = ${JSON.stringify(shareCalls)}`);
console.log(`toast: tap1="${toast1}" tap2="${toast2}"`);
console.log(`capture: ${frameNo} frames, wall ${realMs}ms, measured ${effFps} fps (nominal ${FPS})`);
console.log(`GIF  ${gif}\n  ${JSON.stringify(probe(gif).streams?.[0])} ${JSON.stringify(probe(gif).format)}`);
console.log(`MP4  ${mp4}\n  ${JSON.stringify(probe(mp4).streams?.[0])} ${JSON.stringify(probe(mp4).format)}`);
await browser.close();
