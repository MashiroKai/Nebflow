// _notice-unify-evidence.mjs — captures the notice-unify batch evidence:
// the three declared notification types (toast / banner / inline) rendered with
// the REAL production stylesheets + REAL modal.js & notificationBanner.js, at
// two viewports (desktop 1280x800 @1x, mobile 390x844 @2x).
//
// Outputs (persistent dir):
//   <dir>/desktop-three-types.png   static: all three types visible
//   <dir>/mobile-three-types.png
//   <dir>/desktop-notice.mp4 / .gif
//   <dir>/mobile-notice.mp4  / .gif
// Every media file is probed with ffprobe and the true values are printed.
//
// Self-contained: spins up its own static server on an ephemeral port serving
// src/main/resources/web + tests (never touches the running instance).
// Run: node tests/_notice-unify-evidence.mjs [outDir]

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, rmSync, statSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/Users/kaiyu/stand-evidence/notice-unify-20261118';
mkdirSync(OUT, { recursive: true });
// Namespace-batch manifest (check-evidence-manifests.mjs A-face contract): a
// writer into the stand-evidence namespace must also write a manifest.json,
// else the fail-closed TTL sweep can never reclaim the batch.
writeFileSync(
  join(OUT, 'manifest.json'),
  JSON.stringify({ createdAt: new Date().toISOString(), project: 'stand', batch: `notice-unify-${process.pid}` }) + '\n',
);

const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.json': 'application/json' };

function startServer() {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/notice-unify-harness.html';
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = (p.startsWith('/js/') || p.startsWith('/css/')) ? join(WEB, p) : join(HERE, '..', p);
        const data = readFileSync(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

const ffprobe = (file) => {
  const r = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,r_frame_rate,nb_frames',
    '-show_entries', 'format=duration,size', '-of', 'default=noprint_wrappers=1', file], { encoding: 'utf8' });
  return (r.stdout || '').trim().split('\n').map((l) => l.trim()).filter(Boolean).join('  ');
};
const ff = (args) => { const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', ...args], { encoding: 'utf8' }); if (r.status !== 0) throw new Error(r.stderr); };

const stamp = Date.now();
const runViewport = async (browser, { label, viewport, dpr, step }) => {
  const vdir = join(OUT, `.vid-${label}-${stamp}`);
  mkdirSync(vdir, { recursive: true });
  const context = await browser.newContext({
    viewport, deviceScaleFactor: dpr,
    recordVideo: { dir: vdir, size: { width: viewport.width, height: viewport.height } },
  });
  const page = await context.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e.message)));
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__noticeTest.ready === true', null, { timeout: 15000 });

  // Static A: overview — banner (conn + notification) + inline + one rich toast.
  await page.evaluate(() => {
    const t = window.__noticeTest;
    t.showConnBanner();
    t.addNotification('reminder', '提醒：18:00 有一条待办到期');
    t.addNotification('skill', '技能「stand-recall」已更新');
    t.showToast('Canvas 保存失败，本地变更已保留。', 'error', {
      closable: true,
      hint: '请关闭其他控制台窗口后重试。',
      action: { label: '复制详情', onClick: () => {} },
    });
  });
  await page.waitForTimeout(900); // toast slide-in + banner animation settle
  const shot = join(OUT, `${label}-three-types.png`);
  await page.screenshot({ path: shot });
  console.log(`shot ${shot}`);

  // Static B: one toast per severity, alone (they share the same anchor, so a
  // single-toast frame reads cleanly; the overview above carries the stacking).
  const clearToasts = () => page.evaluate(() => {
    document.querySelectorAll('.nebflow-toast').forEach((e) => e.remove());
  });
  const toastShots = [];
  for (const [kind, msg] of [
    ['error', 'Canvas 保存失败，本地变更已保留。'],
    ['success', '设置已保存'],
    ['info', '已切换到暗色主题'],
  ]) {
    await clearToasts();
    await page.evaluate(({ msg, kind }) => {
      const opts = kind === 'error'
        ? { closable: true, hint: '请关闭其他控制台窗口后重试。', action: { label: '复制详情', onClick: () => {} } }
        : undefined;
      window.__noticeTest.showToast(msg, kind, opts);
    }, { msg, kind });
    await page.waitForTimeout(500);
    const f = join(OUT, `${label}-toast-${kind}.png`);
    await page.screenshot({ path: f, clip: { x: Math.max(0, viewport.width - 520), y: Math.max(0, viewport.height - 260), width: Math.min(520, viewport.width), height: Math.min(260, viewport.height) } });
    toastShots.push(f);
    console.log(`shot ${f}`);
  }
  await clearToasts();

  // Video take: the real lifecycle in motion.
  await page.waitForTimeout(300);
  await step(page);
  await page.waitForTimeout(600);

  const video = page.video();
  await context.close();
  const webm = await video.path();
  const mp4 = join(OUT, `${label}-notice.mp4`);
  const gif = join(OUT, `${label}-notice.gif`);
  ff(['-i', webm, '-movflags', '+faststart', '-pix_fmt', 'yuv420p', '-vf', `fps=20,scale=${viewport.width}:-2`, mp4]);
  // GIF at reduced scale/fps to stay small.
  ff(['-i', webm, '-vf', `fps=12,scale=${Math.round(viewport.width * 0.6)}:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse`, gif]);
  return { label, errs, shot, toastShots, mp4, gif };
};

const { server, port } = await startServer();
const base = `http://127.0.0.1:${port}`;
const browser = await chromium.launch();

// Scripted take: banner slides in, toasts come and go, one is closed by hand.
const desktopStep = async (page) => {
  await page.evaluate(() => window.__noticeTest.showConnBanner());
  await page.waitForTimeout(1000);
  await page.evaluate(() => {
    window.__noticeTest.addNotification('conn', '设备「MacBook」已离线');
  });
  await page.waitForTimeout(800);
  await page.evaluate(() => window.__noticeTest.showToast('设置已保存', 'success'));
  await page.waitForTimeout(1400);
  await page.evaluate(() => window.__noticeTest.showToast('Canvas 保存失败，本地变更已保留。', 'error', {
    closable: true, hint: '请关闭其他控制台窗口后重试。',
  }));
  await page.waitForTimeout(1600);
  await page.evaluate(() => document.querySelector('.nebflow-toast-close')?.click());
  await page.waitForTimeout(1200);
  await page.evaluate(() => window.__noticeTest.hideConnBanner());
  await page.waitForTimeout(600);
};

const mobileStep = async (page) => {
  await page.evaluate(() => window.__noticeTest.showToast('已加入输入框', 'info'));
  await page.waitForTimeout(1200);
  await page.evaluate(() => window.__noticeTest.addNotification('task', '后台任务 #12 已完成'));
  await page.waitForTimeout(900);
  await page.evaluate(() => window.__noticeTest.showToast('语音模式绘制失败', 'error', {
    closable: true, hint: '请检查麦克风权限后重试。',
  }));
  await page.waitForTimeout(1800);
  await page.waitForTimeout(400);
};

const results = [];
results.push(await runViewport(browser, { label: 'desktop', viewport: { width: 1280, height: 800 }, dpr: 1, step: desktopStep }));
results.push(await runViewport(browser, { label: 'mobile', viewport: { width: 390, height: 844 }, dpr: 2, step: mobileStep }));

await browser.close();
server.close();

// Clean the temp recording dirs; print the truth.
for (const r of results) {
  console.log(`\n== ${r.label} (pageErrors: ${r.errs.length ? r.errs.join(' | ') : 'none'}) ==`);
  for (const f of [r.shot, ...r.toastShots, r.mp4, r.gif]) {
    const size = existsSync(f) ? statSync(f).size : 0;
    console.log(`${f}  (${size} bytes)`);
    if (f.endsWith('.mp4') || f.endsWith('.gif')) console.log(`   ffprobe: ${ffprobe(f)}`);
  }
}
for (const d of readdirSync(OUT)) if (d.startsWith('.vid-')) {
  const p = join(OUT, d);
  for (const f of readdirSync(p)) rmSync(join(p, f));
  rmSync(p, { recursive: true, force: true });
}
// Drop any stray raw recordings leaked by the video-dir rename.
for (const f of readdirSync(OUT)) if (f.endsWith('.webm')) rmSync(join(OUT, f));
console.log('\ndone');
