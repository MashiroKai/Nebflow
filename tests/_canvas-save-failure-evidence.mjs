// _canvas-save-failure-evidence.mjs — records the before/after GIF/MP4 evidence
// for the canvas "保存失败" notice batch (2026-11-18 P0).
//
//   node tests/_canvas-save-failure-evidence.mjs
//
// "before" = the WHOLE frontend exported from 41420bed8 (git archive → tmp dir),
// "after" = the fixed worktree. Swapping the whole web root (not just canvas.js)
// matters: the acceptance must exercise baseline modal.js + canvas.js together.
//
// For each viewport (desktop 1440x900 full page, phone 390x844 full-screen page):
//   before — three loads, the notice re-pops each time (the "老是");
//   after  — one notice on the first refusal, closable, then two reloads SILENT.
// Playwright records webm; ffmpeg converts to GIF (<=10MB) / MP4.

import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, existsSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadChromium, startSeatServer, attachWsMock, HARNESS_INIT, driveWrite, toasts } from './_canvas-save-failure-harness.mjs';

const ROOT = '/private/tmp/stand-canvas-save-20261118';
const OUT = '/Users/kaiyu/stand-evidence/canvas-save-failure-20261118';
const BASE = '41420bed8';
const FAIL = 'Canvas 保存失败';

const chromatic = loadChromium();
rmSync(join(ROOT, '.evidence-raw'), { recursive: true, force: true });
mkdirSync(join(ROOT, '.evidence-raw'), { recursive: true });
mkdirSync(OUT, { recursive: true });
// 本批落在**持久目录**（作者令：证据不留 /tmp）。命名空间批次一律带 manifest.json
// —— fail-closed 的 TTL 清扫靠它认领批次（check-evidence-manifests 契约）。
writeFileSync(
  join(OUT, 'manifest.json'),
  JSON.stringify({ createdAt: new Date().toISOString(), project: 'stand', batch: 'canvas-save-failure-20261118' }) + '\n',
);

// Export the baseline frontend tree (whole web root) into .evidence-raw/baseline.
const baselineHome = join(ROOT, '.evidence-raw', 'baseline');
mkdirSync(baselineHome, { recursive: true });
execFileSync('/bin/sh', ['-c', `cd "${ROOT}" && git archive ${BASE} src/main/resources/web | tar -x -C "${baselineHome}"`], { stdio: 'inherit' });
const BASELINE_WEB = join(baselineHome, 'src', 'main', 'resources', 'web');

const CAPTION = (text) => {
  let el = document.getElementById('__evidence_caption');
  if (!el) {
    el = document.createElement('div');
    el.id = '__evidence_caption';
    el.style.cssText = 'position:fixed;left:0;top:0;z-index:2147483647;max-width:100%;'
      + 'padding:6px 10px;font:600 13px/1.3 -apple-system,system-ui,sans-serif;'
      + 'color:#fff;background:rgba(20,20,24,.86);border-radius:0 0 8px 0;pointer-events:none';
    document.body.appendChild(el);
  }
  el.textContent = text;
  return text;
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function recordPhase({ browser, base, viewport, isMobile, label, writes, closeOnce, stillPath }) {
  const context = await browser.newContext({
    viewport, isMobile, hasTouch: isMobile, deviceScaleFactor: 1, locale: 'zh-CN',
    recordVideo: { dir: join(ROOT, '.evidence-raw'), size: viewport },
  });
  await context.addInitScript(HARNESS_INIT);
  const page = await context.newPage();
  await attachWsMock(page);
  const perLoad = [];
  await page.goto(base, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { state: 'attached' });
  for (let i = 0; i < writes; i++) {
    if (i > 0) {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForSelector('#activity-bar', { state: 'attached' });
    }
    await page.evaluate(CAPTION, `${label} — 第 ${i + 1} 次加载`);
    await page.waitForTimeout(500);
    await driveWrite(page, `ev-${label}-${i}`);
    const n = (await toasts(page)).filter((x) => x.includes(FAIL)).length;
    perLoad.push(n);
    await page.evaluate(CAPTION, `${label} — 第 ${i + 1} 次加载：失败通知 ${n} 条`);
    // 先留证（通知在场），再演示关闭 —— 顺序反了会拍到已关掉的通知。
    if (stillPath && i === 0 && n > 0) await page.screenshot({ path: stillPath });
    if (closeOnce && i === 0 && n > 0) {
      const close = page.locator('.nebflow-toast-close').first();
      if (await close.count()) {
        await page.evaluate(CAPTION, `${label} — 通知带可执行的下一步，可亲手关闭（×）`);
        await sleep(900);
        await close.click();
        await page.waitForTimeout(700);
      }
    }
    await page.waitForTimeout(1300);   // let the toast be visible on video
  }
  await page.evaluate(CAPTION, `${label} — 失败通知合计 ${perLoad.reduce((a, b) => a + b, 0)} 条（每次加载：${perLoad.join('/')}）`);
  await page.waitForTimeout(1200);
  const video = page.video();
  await context.close();
  const webm = await video.path();
  return { webm, perLoad, total: perLoad.reduce((a, b) => a + b, 0) };
}

function toGif(webm, outBase, fps = 8) {
  const gif = outBase + '.gif';
  const palette = outBase + '.palette.png';
  execFileSync('ffmpeg', ['-y', '-i', webm, '-vf', `fps=${fps},scale=iw:ih:flags=lanczos,palettegen=stats_mode=diff`, palette], { stdio: 'ignore' });
  execFileSync('ffmpeg', ['-y', '-i', webm, '-i', palette, '-lavfi', `fps=${fps},scale=iw:ih:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer`, gif], { stdio: 'ignore' });
  if (existsSync(palette)) rmSync(palette);
  if (statSync(gif).size > 10 * 1024 * 1024) {          // >10MB ⇒ MP4
    const mp4 = outBase + '.mp4';
    execFileSync('ffmpeg', ['-y', '-i', webm, '-pix_fmt', 'yuv420p', '-crf', '26', mp4], { stdio: 'ignore' });
    rmSync(gif);
    return mp4;
  }
  return gif;
}

function probe(file) {
  const out = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
    'stream=width,height,avg_frame_rate,nb_frames:format=duration', '-of', 'json', file], { encoding: 'utf8' });
  const j = JSON.parse(out);
  const s = j.streams[0];
  return `${s.width}x${s.height} fps=${s.avg_frame_rate} frames=${s.nb_frames} dur=${Number(j.format.duration).toFixed(2)}s`;
}

const VIEWPORTS = [
  { tag: 'desktop', label: '桌面 1440x900 全页', viewport: { width: 1440, height: 900 }, isMobile: false },
  { tag: 'mobile', label: '手机 390x844 全屏页', viewport: { width: 390, height: 844 }, isMobile: true },
];

(async () => {
  const fixed = await startSeatServer(0, { webRoot: join(ROOT, 'src', 'main', 'resources', 'web') });
  const base = await startSeatServer(0, { webRoot: BASELINE_WEB });
  const browser = await chromatic.launch({ headless: true });
  const summary = [];
  try {
    for (const vp of VIEWPORTS) {
      base.clearSeat(); await base.seedGhostSeat();
      const before = await recordPhase({
        browser, base: base.base, viewport: vp.viewport, isMobile: vp.isMobile,
        label: `${vp.label} · 修复前(41420bed8)`, writes: 3, closeOnce: false, stillPath: null,
      });
      const bFile = toGif(before.webm, join(OUT, `before-${vp.tag}`));
      fixed.clearSeat(); await fixed.seedGhostSeat();
      const after = await recordPhase({
        browser, base: fixed.base, viewport: vp.viewport, isMobile: vp.isMobile,
        label: `${vp.label} · 修复后`, writes: 3, closeOnce: true, stillPath: join(OUT, `after-${vp.tag}.png`),
      });
      const aFile = toGif(after.webm, join(OUT, `after-${vp.tag}`));
      summary.push({
        viewport: vp.tag,
        before: { file: bFile, perLoad: before.perLoad, total: before.total, probe: probe(bFile) },
        after: { file: aFile, perLoad: after.perLoad, total: after.total, probe: probe(aFile) },
      });
    }
    console.log('EVIDENCE ' + JSON.stringify(summary, null, 2));
  } finally {
    await browser.close();
    await fixed.close();
    await base.close();
  }
})().catch((e) => { console.error('EVIDENCE_ERROR', e); process.exit(2); });
