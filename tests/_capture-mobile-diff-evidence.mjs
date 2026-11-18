#!/usr/bin/env node
// _capture-mobile-diff-evidence.mjs — 手机形态证据取证（非闸；tests/_ 前缀不进
// playwright 收集面）。
//
// 输出（全部 375×812 真全屏页；证据落 evidence dir）：
//   · delegate / review 面的亮暗 PNG
//   · delegate 滚屏 GIF、review 交互（折行开关 + 逐块接受）GIF（≤10MB，超标转 MP4）
//   · ffprobe 实测像素/帧率/时长清单 ffprobe.json
//
// 用法：node tests/_capture-mobile-diff-evidence.mjs <outDir>

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const HARNESS_SRC = resolve(HERE, 'fixtures/mobile-diff-baseline/harness.html');
const OUT = resolve(process.argv[2] || join(REPO, 'evidence/mobile-diff-migrate'));
const PORT = Number(process.env.PORT || 8315);
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

const manifest = { context: { viewport: '375×812', dsf: 3, isMobile: true, hasTouch: true, fullscreenPage: true }, files: [], ffprobe: [] };

/** Assemble N PNG frames into a GIF (palette two-pass); fall back to MP4 if >10MB. */
function toGif(frameDir, frameCount, name) {
  const gif = join(OUT, name + '.gif');
  const pal = join(TMP, name + '-pal.png');
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', '10', '-i', join(frameDir, 'f%03d.png'), '-vf', 'palettegen=stats_mode=diff', pal]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', '10', '-i', join(frameDir, 'f%03d.png'), '-i', pal, '-lavfi', 'paletteuse=dither=bayer:bayer_scale=3', '-loop', '0', gif]);
  let out = gif;
  // eslint-disable-next-line no-constant-condition
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

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, colorScheme: 'light' });
  const page = await ctx.newPage();
  await page.goto(BASE + '/__harness.html');
  await page.waitForFunction('window.__ready === true', null, { timeout: 20000 });
  await page.waitForTimeout(400);

  // ── 静帧（亮暗 × 两面）──────────────────────────────────────────────────
  await page.evaluate(() => window.__showPage('delegate'));
  await page.waitForTimeout(150);
  let f = join(OUT, 'mobile-delegate-light.png');
  await page.screenshot({ path: f }); manifest.files.push({ path: f, note: 'delegate read 面（亮）375×812 全屏' });
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(150);
  f = join(OUT, 'mobile-delegate-dark.png');
  await page.screenshot({ path: f }); manifest.files.push({ path: f, note: 'delegate read 面（暗）' });
  await page.evaluate(() => window.__showPage('review'));
  await page.waitForTimeout(200);
  f = join(OUT, 'mobile-review-dark.png');
  await page.screenshot({ path: f }); manifest.files.push({ path: f, note: 'ask review 面（暗）逐块接受/拒绝 + 折叠 + 让位带' });
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(200);
  f = join(OUT, 'mobile-review-light.png');
  await page.screenshot({ path: f }); manifest.files.push({ path: f, note: 'ask review 面（亮）' });

  // ── 动效：delegate 滚屏（行号随滚 + 虚拟化）──────────────────────────────
  await page.evaluate(() => window.__showPage('delegate'));
  await page.waitForTimeout(200);
  const dDir = join(TMP, 'delegate'); rmSync(dDir, { recursive: true, force: true }); mkdirSync(dDir, { recursive: true });
  const N = 24;
  for (let i = 0; i < N; i++) {
    await page.evaluate((frac) => {
      const sc = document.querySelector('#page-delegate .cm-scroller');
      sc.scrollTop = sc.scrollHeight * frac;
    }, i / (N - 1));
    await page.waitForTimeout(45);
    await page.screenshot({ path: join(dDir, `f${String(i).padStart(3, '0')}.png`) });
  }
  const dg = toGif(dDir, N, 'motion-delegate-scroll');
  manifest.files.push({ path: dg, note: 'delegate 阅读面滚屏（行号常驻 + 视口虚拟化），10fps' });

  // ── 动效：review 交互（折行开关切换 + 逐块接受）──────────────────────────
  await page.evaluate(() => { window.__showPage('review'); window.__resetChunkActions(); });
  await page.waitForTimeout(250);
  const rDir = join(TMP, 'review'); rmSync(rDir, { recursive: true, force: true }); mkdirSync(rDir, { recursive: true });
  const M = 26;
  const script = [
    { kind: 'wait' }, { kind: 'wait' },
    { kind: 'click', sel: '#page-review .nf-dv-wrapbtn' }, { kind: 'wait' }, { kind: 'wait' },
    { kind: 'click', sel: '#page-review .nf-dv-wrapbtn' }, { kind: 'wait' }, { kind: 'wait' },
    { kind: 'click', sel: '#page-review .nf-dv-chunkbtn-accept' }, { kind: 'wait' }, { kind: 'wait' },
  ];
  let si = 0;
  for (let i = 0; i < M; i++) {
    const step = script[Math.min(si, script.length - 1)];
    if (step.kind === 'click') {
      await page.locator(step.sel).first().click().catch(() => {});
      si++;
    } else { si++; }
    await page.waitForTimeout(70);
    await page.screenshot({ path: join(rDir, `f${String(i).padStart(3, '0')}.png`) });
  }
  const rg = toGif(rDir, M, 'motion-review-interact');
  manifest.files.push({ path: rg, note: 'ask 审阅面交互（折行开关切换 → 逐块接受），10fps' });
  manifest.chunkActions = await page.evaluate(() => window.__chunkActions());

  for (const file of [dg, rg]) {
    const p = probe(file);
    const st = p.streams[0];
    const num = st.r_frame_rate.split('/');
    manifest.ffprobe.push({
      file,
      bytes: Number(p.format.size),
      MB: +(Number(p.format.size) / 1048576).toFixed(2),
      codec: st.codec_name,
      width: st.width, height: st.height,
      fps: +(Number(num[0]) / Number(num[1])).toFixed(2),
      frames: Number(st.nb_frames),
      durationSec: +(Number(st.duration || p.format.duration)).toFixed(2),
    });
  }
} finally {
  await browser.close();
  server.close();
}
rmSync(TMP, { recursive: true, force: true });
writeFileSync(join(OUT, 'evidence-manifest.json'), JSON.stringify(manifest, null, 2));
console.log(JSON.stringify(manifest, null, 2));
