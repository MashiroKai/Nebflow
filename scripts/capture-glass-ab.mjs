#!/usr/bin/env node
// capture-glass-ab.mjs — 毛玻璃「A/B 背景承接」证据采集器
// （glass-recalibrate 批 2026-11-05；作者原话 18:13：「我觉得毛玻璃效果还是不是
//   我想要的那种，我要的是主会话的输入框的这种，不透明，但是能反映背景内容颜色的。」）
//
// 为什么要有这支脚本：本批的判据不是「看起来像磨砂」，而是两条可读数的性质——
//   ① 背景**颜色**必须透上来（同一块玻璃在灰/红/蓝/图像四种底上，平均 RGB 要明显不同）；
//   ② 背景**内容**必须看不到（同一块玻璃压在正文墙上，亮度标准差要贴住平底基线）。
// 这两条只有真渲染 + 真像素读数能证。手绘对照图不算证据。
//
// 装置两条腿（同一支脚本、同一套场景、同一组背景，才能「改前 / 改后 / 参照物」三方对照）：
//   --root <dir>   把磁盘上的静态根（src/main/resources/web 或其快照）喂给
//                  scripts/lib/visual-harness.mjs（零端口、MockWS、真 Chromium）
//   --url  <url>   **参照物腿**：直接打本机隔离/宿主实例的静态资源（只取 GET 静态文件，
//                  /api/** 一律 404，WebSocket 全部走 MockWS —— 不写库、不建会话、不碰运行态）
//
// 用法（改前/改后/参照物三方）：
//   node scripts/capture-glass-ab.mjs --url http://127.0.0.1:6138/index.html --tag ref   --out /tmp/ab
//   node scripts/capture-glass-ab.mjs --root /tmp/glass-before/src/main/resources/web \
//        --tag before --out /tmp/ab
//   node scripts/capture-glass-ab.mjs --root src/main/resources/web --tag after --out /tmp/ab
//
// 产出：<out>/<tag>-<variant>-<theme>.png（整屏）+ <out>/<tag>-<variant>-<theme>-glass.png
//       （玻璃区域原始像素）+ <out>/<tag>-measure.json（计算样式 + 平均 RGB + 细节对比度）

import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };

const URL_ = valOf('--url');
const ROOT_DIR = valOf('--root', 'src/main/resources/web');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', '/tmp/glass-ab');
const THEMES = (valOf('--themes', 'light,dark') || '').split(',').filter(Boolean);
const ASSETS = valOf('--assets', '/tmp/glass-ab-assets');
const VARIANTS = (valOf('--variants', 'gray,red,blue,nebula,detail') || '').split(',').filter(Boolean);

mkdirSync(OUT, { recursive: true });

const require = createRequire(import.meta.url);
async function resolvePW() {
  // 与 visual-harness 同序：NEBFLOW_PW_PATH → playwright → playwright-core → 全局安装
  const cands = [];
  if (process.env.NEBFLOW_PW_PATH) cands.push(process.env.NEBFLOW_PW_PATH);
  cands.push('playwright', 'playwright-core', '/opt/homebrew/lib/node_modules/playwright');
  const tried = [];
  for (const c of cands) { try { return { mod: require(c), from: c }; } catch (e) { tried.push(c); } }
  throw new Error('playwright 不可解析：' + tried.join(', '));
}

// ── 背景变体 ──────────────────────────────────────────────────────────────
const IMG_CACHE = {};
function dataUrl(name) {
  if (!IMG_CACHE[name]) {
    IMG_CACHE[name] = 'data:image/png;base64,' + readFileSync(join(ASSETS, `${name}.png`)).toString('base64');
  }
  return IMG_CACHE[name];
}
const VARIANTS_DEF = {
  gray: { paint: 'background:#808080;', why: '纯灰底（无彩）' },
  red: { paint: 'background:#d92b2b;', why: '纯红底' },
  blue: { paint: 'background:#2b5cd9;', why: '纯蓝底' },
  nebula: { paint: () => `background:#0b0d14;background-image:url('${dataUrl('nebula')}');background-size:cover;background-position:center;`, why: '星云彩图（低频色 + 高频细节）' },
  detail: { paint: () => `background:#808080;background-image:url('${dataUrl('detail')}');background-size:cover;background-position:center;`, why: '正文墙（黑字黑块 = 内容幽灵探测）' },
};

const STAGE_HTML = (variant) => `
  <div id="nf-ab-panel" class="nf-glass"></div>
`;

// ── 每页一次：注入 stage（在 #main 内、#input-area 之下）────────────────────
async function installStage(page, variant) {
  await page.evaluate(({ style, html }) => {
    let st = document.getElementById('nf-ab-stage');
    if (!st) {
      st = document.createElement('div');
      st.id = 'nf-ab-stage';
      st.style.cssText = 'position:absolute;inset:0;z-index:15;pointer-events:none;';
      const host = document.querySelector('#main') || document.body;
      host.appendChild(st);
      st.innerHTML = html;
      const css = document.createElement('style');
      css.textContent = `
        #nf-ab-panel{position:absolute;left:48px;top:300px;width:380px;height:180px;border-radius:20px;}
        body.nf-ab-blank #input-bar > *:not(.nf-ab-keep){visibility:hidden !important;}`;
      document.head.appendChild(css);
    }
    st.setAttribute('style', 'position:absolute;inset:0;z-index:15;pointer-events:none;' + style);
  }, { style: typeof VARIANTS_DEF[variant].paint === 'function' ? VARIANTS_DEF[variant].paint() : VARIANTS_DEF[variant].paint, html: STAGE_HTML(variant) });
  await page.waitForTimeout(120);
}

// ── 读数 ──────────────────────────────────────────────────────────────────
const STYLE_PROPS = ['backgroundColor', 'backgroundImage', 'backdropFilter', 'webkitBackdropFilter', 'borderTopWidth', 'borderTopStyle', 'borderTopColor', 'borderRadius', 'boxShadow', 'opacity'];
const TOKENS = ['--nf-glass-bg', '--nf-glass-blur', '--nf-glass-saturate', '--nf-glass-filter', '--nf-glass-border-color', '--glass-bg', '--cv-bg', '--cv-bg-opaque', '--cv-blur', '--cv-saturate', '--color-bg'];

async function styleOf(page, sel) {
  return page.evaluate(({ sel, props, tokens }) => {
    const el = document.querySelector(sel);
    if (!el) return { sel, missing: true };
    const cs = getComputedStyle(el);
    const out = { sel, rect: null, tokens: {} };
    const r = el.getBoundingClientRect();
    out.rect = { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) };
    for (const p of props) out[p] = cs[p];
    for (const t of tokens) out.tokens[t] = cs.getPropertyValue(t).trim();
    return out;
  }, { sel, props: STYLE_PROPS, tokens: TOKENS });
}

async function cropTo(page, sel, insetX, insetY, path) {
  const box = await (await page.$(sel)).boundingBox();
  const clip = {
    x: Math.round(box.x + insetX), y: Math.round(box.y + insetY),
    width: Math.round(box.width - insetX * 2), height: Math.round(box.height - insetY * 2),
  };
  await page.screenshot({ path, clip, animations: 'disabled', caret: 'hide' });
  return clip;
}

// 用 PIL 读平均 RGB / 亮度标准差（零新依赖，环境自带）
const PY_MEAN = `
import json,sys
from PIL import Image, ImageStat
files=json.loads(sys.argv[1])
out={}
for k,p in files.items():
    im=Image.open(p).convert('RGB')
    st=ImageStat.Stat(im)
    r,g,b=[round(v,1) for v in st.mean]
    lum=[0.2126*p[0]+0.7152*p[1]+0.0722*p[2] for p in im.getdata()]
    m=sum(lum)/len(lum)
    sd=(sum((v-m)**2 for v in lum)/len(lum))**0.5
    out[k]={'r':r,'g':g,'b':b,'lum':round(m,1),'lumSd':round(sd,2),
            'chroma':round(max(r,g,b)-min(r,g,b),1),
            'warmth':round(r-b,1)}
print(json.dumps(out))
`;

function means(files) {
  return JSON.parse(execFileSync('python3', ['-c', PY_MEAN, JSON.stringify(files)], { encoding: 'utf8' }));
}

// ── 主体 ──────────────────────────────────────────────────────────────────
async function main() {
  const { mod: pwMod } = await resolvePW();
  const pw = pwMod.playwright || pwMod;
  const { chromium } = pw;
  const browser = await chromium.launch({ headless: true });
  const FIXED = 1757000000000;
  const results = { tag: TAG, source: URL_ || ROOT_DIR, themes: {}, generatedAt: new Date().toISOString() };
  const cropsFor = [];

  let harness = null;
  if (!URL_) {
    harness = await import('./lib/visual-harness.mjs');
    const served = harness.setWebRoot(ROOT_DIR);
    console.log(`# static root = ${served}`);
  } else {
    console.log(`# live static assets = ${URL_}`);
  }

  for (const theme of THEMES) {
    results.themes[theme] = { variants: {} };
    for (const variant of VARIANTS) {
      let page;
      if (harness) {
        const s = await harness.createSession(browser, { colorScheme: theme, width: 1280, height: 860, clock: FIXED });
        await s.boot({});
        page = s.page;
        const n = s.errors.length;
        results.themes[theme].pageErrors = (results.themes[theme].pageErrors || 0) + n;
        if (n) results.themes[theme].pageErrorSample = s.errors.slice(0, 3);
        page.__close = () => s.close();
      } else {
        page = await openLive(browser, theme, FIXED);
        page.__close = () => page.close();
      }
      await installStage(page, variant);
      await page.addStyleTag({ content: 'body.nf-ab-blank #input-bar > *{visibility:hidden !important;}' });
      await page.evaluate(() => document.body.classList.add('nf-ab-blank'));
      if (harness) await harness.settle(page, 200); else await page.waitForTimeout(250);

      const base = `${TAG}-${variant}-${theme}`;
      await page.screenshot({ path: join(OUT, `${base}.png`), animations: 'disabled', caret: 'hide' });
      // 读数区必须**避开圆角与描边**：圆角处会直接露出原始背景，把「结构」读数抬高。
      const barClip = await cropTo(page, '#input-bar', 24, 20, join(OUT, `${base}-glass.png`));
      const hasPanel = await page.$('#nf-ab-panel');
      let panelClip = null;
      if (hasPanel) panelClip = await cropTo(page, '#nf-ab-panel', 30, 30, join(OUT, `${base}-panel.png`));
      cropsFor.push({ key: `${theme}/${variant}/bar`, p: join(OUT, `${base}-glass.png`) });
      if (panelClip) cropsFor.push({ key: `${theme}/${variant}/panel`, p: join(OUT, `${base}-panel.png`) });

      results.themes[theme].variants[variant] = {
        why: VARIANTS_DEF[variant].why,
        barClip,
        panelClip,
        bar: await styleOf(page, '#input-bar'),
        panel: hasPanel ? await styleOf(page, '#nf-ab-panel') : null,
      };
      await page.evaluate(() => document.body.classList.remove('nf-ab-blank'));
      await page.__close();
    }
  }

  await browser.close();

  const files = {};
  for (const c of cropsFor) files[c.key] = c.p;
  const m = means(files);
  for (const theme of Object.keys(results.themes)) {
    for (const variant of Object.keys(results.themes[theme].variants)) {
      const v = results.themes[theme].variants[variant];
      v.barRGB = m[`${theme}/${variant}/bar`] || null;
      v.panelRGB = m[`${theme}/${variant}/panel`] || null;
    }
  }
  writeFileSync(join(OUT, `${TAG}-measure.json`), JSON.stringify(results, null, 2));
  console.log(`✓ ${TAG} 采集完成 → ${OUT}`);
  // 终端速览
  for (const theme of Object.keys(results.themes)) {
    for (const variant of Object.keys(results.themes[theme].variants)) {
      const v = results.themes[theme].variants[variant];
      const b = v.barRGB;
      console.log(`  ${theme.padEnd(5)} ${variant.padEnd(7)} bar rgb(${b.r},${b.g},${b.b}) chroma=${b.chroma} warmth=${b.warmth} lumSd=${b.lumSd}` +
        (v.panelRGB ? `  | panel rgb(${v.panelRGB.r},${v.panelRGB.g},${v.panelRGB.b}) chroma=${v.panelRGB.chroma} warmth=${v.panelRGB.warmth} lumSd=${v.panelRGB.lumSd}` : ''));
    }
  }
}

// ── 参照物腿：直接吃实例静态资源（只 GET 静态文件；WS 全 MockWS；/api 全 404）──
async function openLive(browser, theme, clock) {
  const page = await browser.newPage({
    viewport: { width: 1280, height: 860 }, deviceScaleFactor: 1, colorScheme: theme,
    locale: 'zh-CN', timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce',
  });
  page.on('pageerror', () => {});
  await page.addInitScript((fixed) => {
    try { localStorage.setItem('nebflow_token', 'ab-probe'); localStorage.setItem('nebflow_locale', 'zh-CN'); } catch { }
    const RealDate = Date;
    class FakeDate extends RealDate {
      constructor(...a) { if (a.length === 0) super(fixed); else super(...a); }
      static now() { return fixed; }
    }
    window.Date = FakeDate;
    const ENTRIES = [{ name: 'docs', type: 'dir' }, { name: 'README.md', type: 'file', size: 2048, mtime: fixed }];
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) { this.url = u; this.readyState = 0; window.__wsMock = this; window.__wsSent = []; }
      send(d) {
        let f = null; try { f = JSON.parse(d); } catch { }
        if (f) window.__wsSent.push(f);
        if (f && f.type === 'listDir') {
          const r = { type: 'dirListing', path: f.path, resolvedPath: f.path, entries: ENTRIES };
          setTimeout(() => this.onmessage && this.onmessage({ data: JSON.stringify(r) }), 0);
        }
      }
      close() { this.readyState = 3; }
      onopen = null; onmessage = null; onclose = null; onerror = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  }, clock);
  await page.route('**/api/**', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
  await page.goto(URL_, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 30000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(300);
  const frame = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await frame({ type: 'sessionList', sessionId: 'ab-root', activeId: 'ab-root', folders: [], sessions: [{ id: 'ab-root', name: 'Nebula', agentName: 'Nebula' }] });
  await frame({ type: 'configData', config: JSON.stringify({ llm: { providers: {} }, features: { friends: false } }), configured: true, onboarding: 'done' });
  await frame({ type: 'historyPage', sessionId: 'ab-root', messages: [], hasMore: false, offset: 0 });
  await page.evaluate(async () => { if (document.fonts && document.fonts.ready) await document.fonts.ready; });
  await page.waitForTimeout(600);
  return page;
}

main().catch((e) => { console.error(e); process.exit(2); });
