#!/usr/bin/env node
// _capture-diff-review-evidence.mjs — 手机形态证据取证（非闸；tests/_ 前缀不进
// playwright 收集面）。
//
// 输出（全部 375×812 真全屏页；证据落 evidence dir）：
//   · read / review / multi 三面的亮暗 PNG（真全屏，非弹窗/内嵌面板）
//   · review 交互（全部接受 + 全部拒绝 + 折行开关）GIF、multi 交互（切文件 +
//     第 2 文件逐块接受）GIF（≤10MB，超标转 MP4）
//   · ffprobe 实测像素/帧率/时长清单 evidence-manifest.json
//
// 用法：node tests/_capture-diff-review-evidence.mjs <outDir>

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const HARNESS_SRC = resolve(HERE, 'fixtures/diff-review-ui/harness.html');
const OUT = resolve(process.argv[2] || '/Users/kaiyu/stand-evidence/diff-review-ui-20261119');
const PORT = Number(process.env.PORT || 8355);
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

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, colorScheme: 'light' });
  const page = await ctx.newPage();
  await page.addInitScript(() => { localStorage.setItem('nebflow_locale', 'zh-CN'); });
  await page.goto(BASE + '/__harness.html');
  await page.waitForFunction('window.__ready === true', null, { timeout: 20000 });
  await page.waitForTimeout(400);

  // ── 静帧（亮暗 × 三面）──────────────────────────────────────────────────
  for (const [name, note] of [['read', 'read 面（delegate 阅读面，纯只读）'], ['review', 'review 面（ask 审阅面：逐块 + 全部接受/拒绝）'], ['multi', '多文件面（文件列表 + 单文件折叠 + 计数 + 逐块归属）']]) {
    await page.evaluate((n) => window.__showPage(n), name);
    await page.waitForTimeout(220);
    let f = join(OUT, `mobile-${name}-light.png`);
    await page.screenshot({ path: f }); manifest.files.push({ path: f, note: `${note}（亮）375×812 全屏` });
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForTimeout(220);
    f = join(OUT, `mobile-${name}-dark.png`);
    await page.screenshot({ path: f }); manifest.files.push({ path: f, note: `${note}（暗）375×812 全屏` });
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForTimeout(160);
  }

  // ── 动效①：review 面动作区交互（全部接受 → 全部拒绝 → 折行开关 → 逐块接受）──
  await page.evaluate(() => { window.__showPage('review'); window.__resetChunkActions(); });
  await page.waitForTimeout(250);
  const rDir = join(TMP, 'review'); rmSync(rDir, { recursive: true, force: true }); mkdirSync(rDir, { recursive: true });
  const script = [
    { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-review .nf-dv-accept-all' }, { k: 'wait' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-review .nf-dv-reject-all' }, { k: 'wait' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-review .nf-dv-wrapbtn' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-review .nf-dv-wrapbtn' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-review .nf-dv-chunkbtn-accept' }, { k: 'wait' }, { k: 'wait' },
  ];
  let si = 0;
  for (let i = 0; i < script.length; i++) {
    const step = script[i];
    if (step.k === 'click') await page.locator(step.sel).first().click().catch(() => {});
    await page.waitForTimeout(70);
    await page.screenshot({ path: join(rDir, `f${String(i).padStart(3, '0')}.png`) });
  }
  const rg = toGif(rDir, 'motion-review-actionbar');
  manifest.files.push({ path: rg, note: 'review 动作区交互（全部接受 → 全部拒绝 → 折行开关 → 逐块接受），10fps' });
  manifest.reviewActions = await page.evaluate(() => window.__chunkActions());

  // ── 动效②：多文件面（切到第 2 文件 → 第 2 文件内逐块接受）───────────────
  await page.evaluate(() => { window.__showPage('multi'); window.__resetChunkActions(); });
  await page.waitForTimeout(250);
  const mDir = join(TMP, 'multi'); rmSync(mDir, { recursive: true, force: true }); mkdirSync(mDir, { recursive: true });
  const script2 = [
    { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-multi .nf-dv-file[data-file-index="1"]' }, { k: 'wait' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-multi .nf-dv-chunkbtn-accept' }, { k: 'wait' }, { k: 'wait' },
    { k: 'click', sel: '#page-multi .nf-dv-file[data-file-index="0"]' }, { k: 'wait' }, { k: 'wait' },
  ];
  for (let i = 0; i < script2.length; i++) {
    const step = script2[i];
    if (step.k === 'click') await page.locator(step.sel).first().click().catch(() => {});
    await page.waitForTimeout(70);
    await page.screenshot({ path: join(mDir, `f${String(i).padStart(3, '0')}.png`) });
  }
  const mg = toGif(mDir, 'motion-multifile-switch');
  manifest.files.push({ path: mg, note: '多文件面交互（切到第 2 文件 → 该文件逐块接受 → 切回），10fps' });
  manifest.multiActions = await page.evaluate(() => window.__chunkActions());

  for (const file of [rg, mg]) {
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
