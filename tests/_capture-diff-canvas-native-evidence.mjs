#!/usr/bin/env node
// _capture-diff-canvas-native-evidence.mjs — 「diff 面接入 canvas 特质」证据取证
// （非闸；tests/_ 前缀不进 playwright 收集面）。
//
// 输出（证据落 <outDir>）：
//   · 375×812 真全屏页静帧：推荐形态 / 备选形态 / canvas 真实容器 × 亮暗
//   · 1440 桌面一档：推荐形态
//   · 动效 GIF（≤10MB，超标转 MP4）：两形态切换 + 全部接受 + 逐块接受
//   · ffprobe 实测像素/帧率/时长清单 evidence-manifest.json
//   · 两形态几何读数 form-geometry.json（对照证据）
//
// 用法：node tests/_capture-diff-canvas-native-evidence.mjs <outDir>

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync, copyFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const HARNESS_SRC = resolve(HERE, 'fixtures/diff-canvas-native/compare.html');
const OUT = resolve(process.argv[2] || '/Users/kaiyu/stand-evidence/diff-canvas-native-20261119');
const PORT = Number(process.env.PORT || 8365);
const BASE = `http://127.0.0.1:${PORT}`;
const TMP = join(OUT, '.frames');
const FFMPEG = process.env.FFMPEG || '/opt/homebrew/bin/ffmpeg';
const FFPROBE = process.env.FFPROBE || '/opt/homebrew/bin/ffprobe';
mkdirSync(TMP, { recursive: true });

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = decodeURIComponent(url.pathname);
  if (p === '/__harness.html') { res.writeHead(200, { 'Content-Type': MIME['.html'] }); res.end(readFileSync(HARNESS_SRC)); return; }
  const f = join(WEB_DIR, p);
  if (!f.startsWith(WEB_DIR) || !existsSync(f)) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(f)] || 'application/octet-stream' });
  res.end(readFileSync(f));
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const manifest = { context: { viewport: '375×812 desktop 1440', dsf: 3, isMobile: true, hasTouch: true, fullscreenPage: true }, files: [], ffprobe: [] };

function toGif(frameDir, name) {
  const gif = join(OUT, name + '.gif');
  const pal = join(TMP, name + '-pal.png');
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', '10', '-i', join(frameDir, 'f%03d.png'), '-vf', 'palettegen=stats_mode=diff', pal]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', '10', '-i', join(frameDir, 'f%03d.png'), '-i', pal, '-lavfi', 'paletteuse=dither=bayer:bayer_scale=3', '-loop', '0', gif]);
  let out = gif;
  if (statSync(gif).size > 10 * 1024 * 1024) {
    out = join(OUT, name + '.mp4');
    execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', '10', '-i', join(frameDir, 'f%03d.png'), '-pix_fmt', 'yuv420p', '-vf', 'scale=trunc(iw/2)*2:trunc(ih/2)*2', out]);
    rmSync(gif, { force: true });
  }
  return out;
}

function probe(file) {
  const raw = execFileSync(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,r_frame_rate,nb_frames,codec_name,duration', '-show_entries', 'format=size,duration', '-of', 'json', file], { encoding: 'utf8' });
  return JSON.parse(raw);
}

async function shoot(page, name, note) {
  const f = join(OUT, name + '.png');
  await page.screenshot({ path: f });
  manifest.files.push({ path: f, note });
  return f;
}

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, colorScheme: 'light' });
  const page = await ctx.newPage();
  await page.addInitScript(() => { localStorage.setItem('nebflow_locale', 'zh-CN'); });
  await page.goto(BASE + '/__harness.html');
  await page.waitForFunction('window.__ready === true', null, { timeout: 25000 });
  await page.waitForTimeout(400);

  // ── 静帧（亮暗 × 推荐/备选/canvas）──────────────────────────────────────
  await page.evaluate(() => window.__hideCmp(true));
  const forms = [
    ['recommended', '推荐形态（顶部标题 + 流内右对齐玻璃控制带，零定位层）'],
    ['alternate', '备选形态（同一 DOM/几何族，canvas 右下角悬浮控件族；错列避让底部控件行）'],
    ['canvas', '推荐形态落在真实 canvas 容器链（.fcv-view > .fcv-dvhost）'],
  ];
  for (const [name, note] of forms) {
    await page.evaluate((n) => window.__showPage(n), name);
    await page.waitForTimeout(240);
    await shoot(page, `mobile-${name}-light`, `${note}（亮）375×812 真全屏`);
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForTimeout(240);
    await shoot(page, `mobile-${name}-dark`, `${note}（暗）`);
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForTimeout(160);
  }

  // ── 两形态几何读数（对照证据）───────────────────────────────────────────
  const geom = { recommended: await page.evaluate(() => window.__formGeom('recommended')), alternate: await page.evaluate(() => window.__formGeom('alternate')) };
  writeFileSync(join(OUT, 'form-geometry.json'), JSON.stringify(geom, null, 2));

  // ── 动效 GIF：推荐→备选切换 + 全部接受 + 逐块接受（亮档全屏页）───────────
  await page.evaluate(() => { window.__showPage('recommended'); window.__resetChunkActions(); });
  await page.waitForTimeout(250);
  const dir = join(TMP, 'motion'); rmSync(dir, { recursive: true, force: true }); mkdirSync(dir, { recursive: true });
  const script = [
    { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '.nf-dv-allbtn.nf-dv-accept-all' }, { k: 'wait' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '.nf-dv-allbtn.nf-dv-reject-all' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '.nf-dv-chunkbtn-accept' }, { k: 'wait' }, { k: 'wait' },
    { k: 'toggle' }, { k: 'wait' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '.nf-dv-allbtn.nf-dv-accept-all' }, { k: 'wait' }, { k: 'wait' },
  ];
  let on = 'recommended';
  for (let i = 0; i < script.length; i++) {
    const step = script[i];
    if (step.k === 'click') await page.locator(step.sel).first().click().catch(() => {});
    if (step.k === 'toggle') {
      on = on === 'recommended' ? 'alternate' : 'recommended';
      await page.evaluate((n) => window.__showPage(n), on);
    }
    await page.waitForTimeout(70);
    await page.screenshot({ path: join(dir, `f${String(i).padStart(3, '0')}.png`) });
  }
  const gif = toGif(dir, 'motion-canvas-native-compare');
  manifest.files.push({ path: gif, note: '两形态切换 + 全部接受 + 逐块接受（360→暗亮不切；10fps）' });
  manifest.chunkActions = await page.evaluate(() => window.__chunkActions());
  const p = probe(gif); const st = p.streams[0]; const num = st.r_frame_rate.split('/');
  manifest.ffprobe.push({ file: gif, bytes: Number(p.format.size), MB: +(Number(p.format.size) / 1048576).toFixed(2), codec: st.codec_name, width: st.width, height: st.height, fps: +(Number(num[0]) / Number(num[1])).toFixed(2), frames: Number(st.nb_frames), durationSec: +(Number(st.duration || p.format.duration)).toFixed(2) });

  // ── 桌面 1440 一档（推荐形态）───────────────────────────────────────────
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'light' });
  await page.evaluate(() => window.__showPage('recommended'));
  await page.waitForTimeout(240);
  await shoot(page, 'desktop-recommended-light', '桌面 1440 一档（推荐形态，亮）');
} finally {
  await browser.close();
  server.close();
}
rmSync(TMP, { recursive: true, force: true });
writeFileSync(join(OUT, 'evidence-manifest.json'), JSON.stringify(manifest, null, 2));
console.log(JSON.stringify(manifest, null, 2));
