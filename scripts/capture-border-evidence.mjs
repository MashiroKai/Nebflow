#!/usr/bin/env node
// capture-border-evidence.mjs — 暗档边框权重的**前后对照证据**采集 + 合成。
//
// 两种模式：
//   --tag before|after   拍图（同一支脚本、同一份场景、同一机位；两腿只差
//                        「被验的那一个变量」= 工作树里的 CSS 静态根）
//   --compose            把 <out>/before-*.png 与 <out>/after-*.png 合成 GIF
//                        （2 帧交替 + 保持）与并排对照图（同裁切，ffmpeg crop）
//
// 为什么 crop 放在合成阶段：裁切矩形只写一次、对 before/after 逐像素同一份 ——
// 「同机位、同底图、同裁切」是天然成立的，不靠两次手感对齐。
//
// 用法：
//   node scripts/capture-border-evidence.mjs --tag before --out <dir> [--scenes a,b] [--themes dark,light]
//   node scripts/capture-border-evidence.mjs --compose --out <dir>
//
// 产出（<dir> 下）：<tag>-<scene>-<WxH>-<theme>.png / <scene>-<theme>-ab.gif /
//                   <scene>-<theme>-sbs.png（side-by-side，带 BEFORE/AFTER 字条）

import { mkdirSync, existsSync, readdirSync, writeFileSync } from 'node:fs';
import { join, basename } from 'node:path';
import { execFileSync } from 'node:child_process';
import { loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from './lib/visual-harness.mjs';
import { BORDER_SCENES, assertMobileForm } from './lib/border-scenes.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const COMPOSE = argv.includes('--compose');
const TAG = valOf('--tag', 'before');
const OUT = valOf('--out', null);
const ROOT_OVERRIDE = valOf('--root');
const SCENES_FILTER = valOf('--scenes');
const THEMES = (valOf('--themes', 'dark,light') || '').split(',').filter(Boolean);
// 并排对照的裁切矩形（像素，左/上/宽/高），每个场景可有多块。选的是作者报障的
// 两处：① 玻璃面板/输入条上缘那一带（暗档最扎眼的白线在此）；② AI 气泡里被写死
// 成 #eee 的 hr 分隔线那一带。两处各生成 GIF 与并排静帧。
const CROPS = {
  'shell-chat': [
    { name: 'panel-edge', x: 240, y: 0, w: 720, h: 220 },
    { name: 'bubble-hr', x: 300, y: 230, w: 460, h: 200 },
  ],
  settings: [{ name: 'frame', x: 240, y: 0, w: 720, h: 220 }],
  modal: [{ name: 'frame', x: 360, y: 300, w: 720, h: 240 }],
  canvas: [{ name: 'frame', x: 300, y: 0, w: 720, h: 220 }],
  dropdown: [{ name: 'frame', x: 900, y: 0, w: 540, h: 260 }],
  explorer: [{ name: 'frame', x: 0, y: 0, w: 400, h: 260 }],
  'mobile-chat': [{ name: 'frame', x: 0, y: 0, w: 390, h: 240 }],
};

if (!OUT) { console.error('--out <dir> required'); process.exit(2); }
mkdirSync(OUT, { recursive: true });

if (ROOT_OVERRIDE) { const r = setWebRoot(ROOT_OVERRIDE); console.log(`# static root = ${r}`); }

function ff(args) { return execFileSync('ffmpeg', ['-y', '-hide_banner', '-loglevel', 'error', ...args], { stdio: ['ignore', 'inherit', 'inherit'] }); }
function ffprobe(args) { return execFileSync('ffprobe', ['-hide_banner', ...args], { encoding: 'utf8' }); }
/** 标注字条用的字体：ImageMagick 不自带默认字体（实测 unable to read font ''）。 */
const LABEL_FONT = existsSync('/System/Library/Fonts/Hiragino Sans GB.ttc')
  ? '/System/Library/Fonts/Hiragino Sans GB.ttc'
  : existsSync('/System/Library/Fonts/Helvetica.ttc') ? '/System/Library/Fonts/Helvetica.ttc' : null;

const scenes = BORDER_SCENES.filter((s) => !SCENES_FILTER || SCENES_FILTER.split(',').includes(s.name));

async function shoot() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`# browser = ${label}\n`);
  const made = [];
  const mobileForms = [];
  for (const scene of scenes) {
    for (const vp of scene.viewports) {
      for (const theme of THEMES) {
        const s = await createSession(browser, { colorScheme: theme, width: vp.width, height: vp.height, clock: scene.clock });
        try {
          await scene.setup(s);
          await settle(s.page, 250);
          // 手机档形态自证（作者真实看到的全屏页）：手机视口下必须先证
          // `body.mobile-layer` 在场 —— 否则这张图是「窄桌面窗」而不是手机形态。
          const form = await assertMobileForm(s.page, vp, scene.name, (m) => { throw new Error(m); });
          if (form) { mobileForms.push({ theme, ...form }); console.log(`  [mobile-layer ${form.ok ? '✓' : '✗'}] ${scene.name}/${theme}  viewport=${form.viewport} dpr=${form.dpr} probe=${JSON.stringify(form.probe)}`); }
          const name = `${TAG}-${scene.name}-${vp.width}x${vp.height}-${theme}.png`;
          await s.page.screenshot({ path: join(OUT, name), fullPage: false, animations: 'disabled', caret: 'hide', scale: 'css' });
          made.push(name);
          console.log(`  [shot] ${name}`);
        } catch (e) {
          console.log(`  [ERR] ${scene.name} ${theme}: ${String(e.message).split('\n')[0]}`);
        } finally {
          await s.close();
        }
      }
    }
  }
  await browser.close();
  if (mobileForms.length) {
    const p = join(OUT, `mobile-form-${TAG}.json`);
    writeFileSync(p, JSON.stringify(mobileForms, null, 2) + '\n');
    console.log(`  [json] ${basename(p)}（手机档形态自证）`);
  }
  console.log(`\n# ${made.length} shots → ${OUT}`);
}

function compose() {
  const files = readdirSync(OUT);
  const before = files.filter((f) => f.startsWith('before-') && f.endsWith('.png')).sort();
  const gifs = [];
  for (const b of before) {
    const rest = b.slice('before-'.length);
    const a = `after-${rest}`;
    if (!existsSync(join(OUT, a))) { console.log(`  [skip] ${rest}: no after`); continue; }
    const stem = rest.replace(/\.png$/, '');            // <scene>-<WxH>-<theme>
    const scene = rest.replace(/-\d+x\d+-/, '|').split('|')[0];
    const crop = CROPS[scene];

    // ① 全页 GIF：两帧交替（BEFORE 1.6s → AFTER 2.2s），缩到宽 720 控体积。
    //    统一走 palettegen/paletteuse（GIF 编码的标准路径；直接 concat→gif 会因
    //    变帧率被 gif 编码器拒收 —— 实测 exit -22）。
    const gifFull = join(OUT, `${stem}-full-ab.gif`);
    ff(['-loop', '1', '-framerate', '10', '-t', '1.6', '-i', join(OUT, b),
      '-loop', '1', '-framerate', '10', '-t', '2.2', '-i', join(OUT, a),
      '-filter_complex', '[0:v]scale=720:-2:flags=lanczos,setsar=1,fps=10[v0];[1:v]scale=720:-2:flags=lanczos,setsar=1,fps=10[v1];'
        + '[v0][v1]concat=n=2:v=1:a=0,split[a][b];[a]palettegen[p];[b][p]paletteuse',
      '-loop', '0', gifFull]);
    gifs.push(gifFull);
    console.log(`  [gif] ${basename(gifFull)}`);

    // ② 1:1 裁切 GIF + 并排静帧：每个场景的每块裁切共用一个矩形、逐像素一致
    //    （几何只写一次），因此「同机位、同底图、同裁切」是结构成立的。
    for (const crop of (CROPS[scene] || [])) {
      const cf = `${crop.w}:${crop.h}:${crop.x}:${crop.y}`;
      const gifCrop = join(OUT, `${stem}-crop-${crop.name}-ab.gif`);
      ff(['-loop', '1', '-framerate', '10', '-t', '1.6', '-i', join(OUT, b),
        '-loop', '1', '-framerate', '10', '-t', '2.2', '-i', join(OUT, a),
        '-filter_complex', `[0:v]crop=${cf},setsar=1,fps=10[v0];[1:v]crop=${cf},setsar=1,fps=10[v1];`
          + '[v0][v1]concat=n=2:v=1:a=0,split[a][b];[a]palettegen[p];[b][p]paletteuse',
        '-loop', '0', gifCrop]);
      gifs.push(gifCrop);
      console.log(`  [gif] ${basename(gifCrop)}`);

      // ③ 并排静帧（同裁切）：左 BEFORE / 右 AFTER，顶部字条标注。
      //    字条用 ImageMagick（本机 ffmpeg 无 drawtext 滤镜 —— 实测 "No such filter"）。
      const sbs = join(OUT, `${stem}-crop-${crop.name}-sbs.png`);
      const cropArgs = ['-crop', `${crop.w}x${crop.h}+${crop.x}+${crop.y}`, '+repage'];
      const label = (txt) => ['-font', LABEL_FONT, '-fill', 'white', '-undercolor', '#000000b8', '-pointsize', '22', '-annotate', '+10+30', txt];
      execFileSync('magick', [
        '(', join(OUT, b), ...cropArgs, ...label('BEFORE 改前'), ')',
        '(', join(OUT, a), ...cropArgs, ...label('AFTER 改后'), ')',
        '+append', sbs,
      ], { stdio: ['ignore', 'inherit', 'inherit'] });
      console.log(`  [png] ${basename(sbs)}`);
    }
  }

  // ffprobe 实测（尺寸/帧率/帧数/时长/体积）
  console.log('\n## ffprobe 实测\n');
  for (const g of gifs) {
    const out = ffprobe(['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,avg_frame_rate,nb_frames', '-show_entries', 'format=duration,size', '-of', 'default=nw=1', g]);
    const m = Object.fromEntries(out.trim().split('\n').map((l) => l.split('=')));
    const mb = (Number(m.size) / 1048576).toFixed(2);
    console.log(`${basename(g)}   ${m.width}x${m.height}  ${m.avg_frame_rate}fps  ${m.nb_frames}帧  ${Number(m.duration).toFixed(2)}s  ${mb}MB`);
  }
}

if (COMPOSE) compose(); else shoot();
