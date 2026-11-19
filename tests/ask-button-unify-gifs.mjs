#!/usr/bin/env node
// ask-button-unify-gifs.mjs — 把 shots.mjs 的实况录屏裁成「主会话 | Ask」并排 GIF/MP4。
// 裁切窗 = 录屏内的动作时间戳（readings.json videoClips.{main,ask}.{clip,t0,t1}），
// 不是按「末尾 N 秒」猜 ⇒ 手机层引导耗时不影响成片。
// Run: OUT=<证据目录> node tests/ask-button-unify-gifs.mjs
import { readFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(process.env.OUT || join(HERE, '..', 'evidence', 'ask-button-unify'));
const W = 176, H = 84, UP = 3, FPS = 12;
mkdirSync(join(OUT, 'gif'), { recursive: true });
mkdirSync(join(OUT, 'mp4'), { recursive: true });

const ff = (args) => execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...args], { stdio: 'inherit' });
const probe = (f, stream, entries) => execFileSync('ffprobe', ['-v', 'error', '-select_streams', stream, '-show_entries', entries, '-of', 'default=nw=1', f]).toString().trim();

for (const phase of ['before', 'after']) {
  const r = JSON.parse(readFileSync(join(OUT, 'shots', phase, 'readings.json'), 'utf8'));
  for (const screen of ['mobile', 'desktop']) {
    const c = r.screens[screen].videoClips;
    // 每腿各按**自己的动作窗**裁（两侧各自 rest→busy→idle 同步对齐），窗长取长者；
    // 短的那条由 hstack 的 eof repeat 补末帧。
    const dMain = Math.max(2.5, c.main.t1 - c.main.t0);
    const dAsk = Math.max(2.5, c.ask.t1 - c.ask.t0);
    const dur = Math.max(dMain, dAsk);
    const crop = (k) => `crop=${W}:${H}:${c[k].clip.x}:${c[k].clip.y},scale=iw*${UP}:ih*${UP}:flags=lanczos`;
    ff(['-ss', String(c.main.t0), '-t', String(dur), '-i', join(OUT, 'shots', phase, screen, 'main.webm'),
      '-ss', String(c.ask.t0), '-t', String(dur), '-i', join(OUT, 'shots', phase, screen, 'ask.webm'),
      '-filter_complex', `[0:v]${crop('main')},fps=${FPS}[m];[1:v]${crop('ask')},fps=${FPS}[a];[m][a]hstack=inputs=2[v]`,
      '-map', '[v]', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
      join(OUT, 'mp4', `${screen}-${phase}.mp4`)]);
    ff(['-i', join(OUT, 'mp4', `${screen}-${phase}.mp4`), '-filter_complex',
      `split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3`,
      '-loop', '0', join(OUT, 'gif', `${screen}-${phase}.gif`)]);
    console.log(`[${phase}] ${screen} windows main=${c.main.t0}→${(c.main.t0 + dMain).toFixed(2)}s ask=${c.ask.t0}→${(c.ask.t0 + dAsk).toFixed(2)}s dur=${dur.toFixed(2)}s`);
    console.log('   gif ' + probe(join(OUT, 'gif', `${screen}-${phase}.gif`), 'v:0',
      'stream=width,height,avg_frame_rate,nb_read_frames:format=duration,size').replace(/\n/g, ' '));
  }
}
