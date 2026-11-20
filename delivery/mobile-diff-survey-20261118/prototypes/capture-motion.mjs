// capture-motion.mjs — record a scroll-through of each candidate (delegate + ask
// side by side, 375x812 each) and assemble a GIF per candidate per theme.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, statSync, mkdirSync, rmSync } from 'node:fs';
import { join, normalize, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const EVID = process.env.EVID || '/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/motion';
mkdirSync(EVID, { recursive: true });
const FRAMES = join(EVID, '_frames');
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(FRAMES, { recursive: true });

const PORT = 8478;
const MONACO = '/private/tmp/stand-mobile-diff-survey-20261118/src/main/resources/web/vendor/monaco';
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png', '.ttf': 'font/ttf', '.woff': 'font/woff', '.woff2': 'font/woff2', '.map': 'application/json' };
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
  let base = join(HERE, 'dist');
  if (p.startsWith('/monaco/')) { base = MONACO; p = p.slice('/monaco'.length); }
  const file = normalize(join(base, p));
  if (!file.startsWith(base)) { res.writeHead(403); res.end(); return; }
  try { if (statSync(file).isDirectory()) throw 0;
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' }); res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const KEYS = { 'a-codemirror': 'codemirror', 'b-diff2html': 'diff2html', 'c-monaco': 'monaco' };
const NFRAMES = 30, FPS = 10;
const browser = await chromium.launch();
const made = [];
try {
  for (const [name, key] of Object.entries(KEYS)) {
    for (const theme of ['dark', 'light']) {
      const ctx = await browser.newContext({ viewport: { width: 820, height: 880 }, colorScheme: theme, deviceScaleFactor: 2 });
      const page = await ctx.newPage();
      await page.goto(`http://127.0.0.1:${PORT}/${name}.html?theme=${theme}`, { waitUntil: 'load' });
      await page.waitForFunction((k) => window.__RESULTS__ && window.__RESULTS__[k], key, { timeout: 30000 }).catch(() => {});
      await page.waitForTimeout(1500);
      // put the two phones side by side, trim the SBS screen
      await page.evaluate(() => {
        const wrap = document.querySelector('.wrap');
        wrap.style.flexDirection = 'row'; wrap.style.alignItems = 'flex-start';
        document.getElementById('s-sbs').remove();
        document.querySelector('.cand').style.display = 'none';
        // monaco roots need a height in row layout too
        for (const r of document.querySelectorAll('.root')) if (r.style.height) r.style.height = '772px';
      });
      await page.waitForTimeout(400);
      const wrapBox = await page.locator('.wrap').boundingBox();
      const frames = [];
      for (let i = 0; i < NFRAMES; i++) {
        const prog = i / (NFRAMES - 1);
        await page.evaluate((p) => {
          for (const id of ['s-delegate', 's-ask']) {
            const sc = document.getElementById(id);
            const max = Math.max(0, sc.scrollHeight - sc.clientHeight);
            sc.scrollTop = Math.round(max * p);
          }
        }, prog);
        await page.waitForTimeout(60);
        const f = join(FRAMES, `${name}-${theme}-${String(i).padStart(3, '0')}.png`);
        await page.screenshot({ path: f, clip: { x: wrapBox.x, y: wrapBox.y, width: Math.min(wrapBox.width, 820), height: Math.min(wrapBox.height, 880) } });
        frames.push(f);
      }
      const gif = join(EVID, `${name}-${theme}.gif`);
      execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(FRAMES, `${name}-${theme}-%03d.png`),
        '-vf', `fps=${FPS},scale=660:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer`,
        '-loop', '0', gif]);
      const probe = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,avg_frame_rate,nb_frames:format=duration,size', '-of', 'json', gif]).toString();
      made.push({ gif, name, theme, probe: JSON.parse(probe) });
      await ctx.close();
    }
  }
} finally { await browser.close(); server.close(); }
for (const m of made) {
  const st = m.probe.streams[0], fm = m.probe.format;
  console.log(`${m.name}-${m.theme}: ${st.width}x${st.height} @${st.avg_frame_rate} frames=${st.nb_frames} dur=${(+fm.duration).toFixed(2)}s size=${(fm.size / 1048576).toFixed(2)}MB`);
}
