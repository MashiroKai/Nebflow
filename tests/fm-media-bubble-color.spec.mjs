// fm-media-bubble-color.spec.mjs — 社交窗口「媒体气泡 vs 文字气泡 同色」验收钉
// （作者令 2026-11-04 16:39「社交窗口对话 气泡颜色不统一 应该和文字的气泡一个颜色」）。
//
// 🔴 本文件由实现节点代建（2026-11-04）：派工单指定「上游 probe 节点已建
//    tests/fm-media-bubble-color.spec.mjs，改前读数在
//    ~/.stand/evidence/bubble-color-unify/before/readings.md」——实测该文件与
//    readings.md **在分支与所有 worktree 上均不存在**（git rev-list --all 零命中，
//    见代建申报）。故本节点按派工单口径**自行确立刻度**：①以「容器 computed 底色
//    与**本向文字气泡** computed 底色逐字相等」为唯一判据（作者令原话「和文字的气泡
//    一个颜色」的字面化）；②先跑红取证（before）再改 CSS 跑绿（after），两份读数
//    落 ~/.stand/evidence/bubble-color-unify/{before,after}/readings.md。
//
// 肇事批次 = a3342618c（fm-att-component-lib 2026-11-02）：社交通道出向附件卡区
// 从 `.row.user-media` 族**逐字复制**了一条「底色 = var(--glass-bub-user-bg)」的
// 规则进 `.fm-` 面，并在注释里声称「identical to the user text bubble by construction」
// —— 该断言在 fm 面**不成立**：主会话用户文字气泡才是绿的（--glass-bub-user-bg =
// --color-bubble-user #95ec69 90%），fm 文字气泡是 sapphire 族（出向
// --sapphire-tint-15 / 入向 --glass-etched-bg，sapphire.css:779-788）。于是社交通道
// 出现「绿色媒体卡 + 蓝灰文字气泡」的双色对话。本批改判：fm 出/入向媒体面各自镜像
// **本向**文字气泡 token；主会话 `.row.user-media` 的绿语义原样不动。
//
// 判据（每条二值）：
//   C1 出向同色  `.fm-msg.out .fm-att-list` 下媒体面容器（平面 `.pop-media-flat` /
//       叠卡 `.pop-stack-view .media-layer`）computed backgroundColor **逐字等于**
//       `.fm-msg.out .fm-msg-bubble` 的 computed backgroundColor（= --nf-bub-ours-bg）。
//   C2 入向同色  同法镜像到 `.fm-msg.in`（= --nf-bub-theirs-bg，须压过 .pop-media-flat
//       基座 #17191f 与暗档 #101318）。
//   C3 媒体元素透明  `.pop-img` / `.pop-video` 自身底色 = transparent（字节未到 / 失败态
//       直接透出容器底色，不叠一道留黑）。
//   C4 归属方色族 出向容器必须落**我方绿族**（g≥150 且双通道差 ≥55），入向容器
//       **零品牌绿**（对方永远不绿）；媒体元素自身零色相。
//       ⚠ bubble-side-color 批（2026-11-06）改判：本条此前是「fm 面一律零品牌绿」——
//       作者新令「我们这方发的……一律绿」把 fm 出向也归入我方，旧口径作废。
//   C5 几何不动   出向媒体面仍是气泡剪影 16px + border-bottom-right-radius 4px +
//       box-shadow none + 零边框；**入向不加边框**（作者令「圆角内裁、不额外加框」，
//       媒体面不吃入向文字气泡那 1px etched 边）。
//   C6 两面同族   `.row.user-media .pop-media-flat`（主会话）与 fm 出向底色**逐字相等**
//       —— 同一枚 --nf-bub-ours-bg（旧口径「两面必须不同值」随 2026-11-06 判据作废）。
//   C7 文字气泡  出向 = 我方绿（#95ec69 97%）、入向 = 对方白（亮 #fff / 暗 #1c1f27，97%）
//       —— 与 js/bubbleSide.js + css/glass.css 单点块同源。
//   C8 下载角标  `.pop-media-canvas` 保留中性半透明墨（rgba(0,0,0,0.45)），非饱和品牌色。
//
// 覆盖矩阵 = 出入双向（out / in）× 四形态（平面图 / 平面视频 / 叠卡前卡 / 叠卡中背层）
// × 四态（正常亮档 / 正常暗档 / 占位未加载 / 加载失败）.nf-img-failed。
//
// 驱动方式：**自足静态夹具**——自带 http 服务只服务 src/main/resources/web/ 的真实
// CSS（按 index.html 的载入次序：base → chat → friends → social → stand-tokens →
// design-system → sapphire → glass），页面标记逐字照 messages.js / popArtifacts.js
// 的渲染出口结构手抄（`.fm-msg > .fm-msg-bubble` + 兄弟 `.fm-att-list > .fm-att >
// .pop-media-flat|.pop-stack-area`）。零真实实例、永不触 :8080。
//
// Run: node tests/fm-media-bubble-color.spec.mjs        （退出码 0 = 全绿）
//      加 --json 打印机器可读读数（取证用）。

import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
// 载入次序 = index.html 的真实次序（同特异性时后者胜，顺序错会假绿/假红）。
const CSS_FILES = [
  'css/base.css', 'css/chat.css', 'css/friends.css', 'css/social.css',
  'css/stand-tokens.css', 'css/design-system.css', 'css/sapphire.css', 'css/glass.css',
];
const HTML_TYPE = 'text/html; charset=utf-8';

const SIDES = ['out', 'in'];
// 四形态：平面图 / 平面视频 / 叠卡前卡 / 叠卡中背层
const FORMS = ['flatImg', 'flatVideo', 'stackFront', 'stackBack'];
// 四态（亮/暗各一趟 ⇒ 见 THEMES）
const STATES = ['loaded', 'placeholder', 'failed'];

// C7 钉死值。bubble-side-color 批（2026-11-06）改判后的口径：
//   出向 = 我方绿（--nf-bub-ours-bg = --color-bubble-user #95ec69 97%）
//   入向 = 对方白（--nf-bub-theirs-bg = --color-bubble-ai；亮 #fff / 暗 #1c1f27，各 97%）
// 比对走容差（见 closeColor：Chromium 会把 α 量化，逐字比会被量化器误伤）。
const BUBBLE_LIGHT = { out: [149, 236, 105, 0.97], in: [255, 255, 255, 0.97] };
const BUBBLE_DARK = { out: [149, 236, 105, 0.97], in: [28, 31, 39, 0.97] };
const CANVAS_CHIP_BG = 'rgba(0, 0, 0, 0.45)';
const TRANSPARENT = 'rgba(0, 0, 0, 0)';

const IMG_OK = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='8' height='8'><rect width='8' height='8' fill='%235b7fbf'/></svg>";

/** 媒体元素（.pop-img / .pop-video）——三态差异只在这里。 */
function mediaEl(kind, state, id) {
  if (kind === 'img') {
    if (state === 'placeholder') return `<img class="pop-img" id="${id}" alt="">`;
    if (state === 'failed') return `<img class="pop-img nf-img-failed" id="${id}" alt="加载失败">`;
    return `<img class="pop-img" id="${id}" src="${IMG_OK}" alt="">`;
  }
  if (state === 'placeholder') return `<video class="pop-video" id="${id}" preload="none" playsinline></video>`;
  if (state === 'failed') return `<video class="pop-video nf-img-failed" id="${id}"></video>`;
  return `<video class="pop-video" id="${id}" preload="none" playsinline></video>`;
}

/** 一条消息行：文字气泡 + 兄弟卡区（messages.js 的真实出口结构）。 */
function messageRow(side, state) {
  const p = `${side}-${state}`;
  const flatImg = `
    <div class="fm-att fm-att-media" data-att-state="ready">
      <div class="pop-media-flat" id="c-${p}-flatImg">
        <div class="pop-media">${mediaEl('img', state, `m-${p}-flatImg`)}</div>
        <button class="pop-media-canvas" id="chip-${p}" type="button"></button>
      </div>
    </div>`;
  const flatVideo = `
    <div class="fm-att fm-att-media" data-att-state="ready">
      <div class="pop-media-flat" id="c-${p}-flatVideo">
        <div class="pop-media">${mediaEl('video', state, `m-${p}-flatVideo`)}</div>
      </div>
    </div>`;
  const stack = `
    <div class="fm-att fm-att-stack">
      <div class="pop-stack-area ds-media-stack">
        <button class="pill ds-media-stack__pill" type="button">展开 3</button>
        <div class="pop-stack-view ds-media-stack__view">
          <div class="media-layer ds-media-card layer-front" id="c-${p}-stackFront">
            <div class="pop-media">${mediaEl('img', state, `m-${p}-stackFront`)}</div>
          </div>
          <div class="media-layer ds-media-card layer-mid" id="c-${p}-stackMid">
            <div class="pop-media">${mediaEl('img', state, `m-${p}-stackMid`)}</div>
          </div>
          <div class="media-layer ds-media-card layer-back" id="c-${p}-stackBack">
            <div class="pop-media">${mediaEl('img', state, `m-${p}-stackBack`)}</div>
          </div>
        </div>
        <div class="pop-expand-view ds-media-stack__expand"></div>
      </div>
    </div>`;
  return `
  <div class="fm-msg ${side}" id="msg-${p}">
    <div class="fm-msg-bubble bubble ${side === 'out' ? 'user ours' : 'peer theirs'}" id="bub-${p}" data-bubble-side="${side === 'out' ? 'ours' : 'theirs'}">测试文字气泡</div>
    <div class="fm-att-list">${flatImg}${flatVideo}${stack}</div>
  </div>`;
}

function fixture() {
  const rows = SIDES.flatMap((s) => STATES.map((st) => messageRow(s, st))).join('\n');
  return `<!doctype html><html><head><meta charset="utf-8">
${CSS_FILES.map((f) => `<link rel="stylesheet" href="/${f}">`).join('\n')}
</head><body>
<!-- 对照组：主会话用户媒体面（.row.user-media）——本批**不许**动 -->
<div class="row user-media" id="main-row">
  <div class="pop-group">
    <div class="pop-media-flat" id="ctl-main-flat">
      <div class="pop-media">${mediaEl('img', 'loaded', 'ctl-main-img')}</div>
    </div>
  </div>
</div>
${rows}
</body></html>`;
}

const MIME_BY_EXT = { '.css': 'text/css; charset=utf-8' };

function startServer() {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      const url = decodeURIComponent((req.url || '/').split('?')[0]);
      if (url === '/' ) {
        res.writeHead(200, { 'content-type': HTML_TYPE });
        res.end(fixture());
        return;
      }
      if (!url.startsWith('/css/')) { res.writeHead(404); res.end('nf'); return; }
      try {
        const body = readFileSync(join(WEB, url));
        const ext = url.slice(url.lastIndexOf('.'));
        res.writeHead(200, { 'content-type': MIME_BY_EXT[ext] || 'text/css; charset=utf-8' });
        res.end(body);
      } catch {
        res.writeHead(404); res.end('nf');
      }
    });
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

/** 页面侧读数：computed style 原始字符串一律不加工（逐字相等判据要的就是原文）。 */
function collect() {
  const cs = (id) => {
    const el = document.getElementById(id);
    if (!el) return null;
    const s = getComputedStyle(el);
    return {
      bg: s.backgroundColor,
      radiusTL: s.borderTopLeftRadius,
      radiusTR: s.borderTopRightRadius,
      radiusBR: s.borderBottomRightRadius,
      radiusBL: s.borderBottomLeftRadius,
      shadow: s.boxShadow,
      borderW: s.borderTopWidth,
      borderS: s.borderTopStyle,
    };
  };
  const out = { bubbles: {}, faces: [], chips: {}, controls: {} };
  for (const side of ['out', 'in']) {
    for (const st of ['loaded', 'placeholder', 'failed']) {
      out.bubbles[`${side}-${st}`] = cs(`bub-${side}-${st}`);
    }
  }
  for (const side of ['out', 'in']) {
    for (const st of ['loaded', 'placeholder', 'failed']) {
      for (const form of ['flatImg', 'flatVideo', 'stackFront', 'stackMid', 'stackBack']) {
        out.faces.push({ side, state: st, form, container: cs(`c-${side}-${st}-${form}`), media: cs(`m-${side}-${st}-${form}`) });
      }
    }
  }
  for (const side of ['out', 'in']) out.chips[side] = cs(`chip-${side}-loaded`);
  out.controls.mainFlat = cs('ctl-main-flat');
  out.controls.mainMedia = cs('ctl-main-img');
  return out;
}

/** 品牌绿族判据（方言无关：rgb()/rgba() 与 color(srgb …) 两种 computed 形态都读）。 */
function rgbOf(str) {
  const c = rgbaOf(str);
  return c ? [c.r, c.g, c.b] : null;
}
/** computed 颜色 → {r,g,b,a}（覆盖 rgb()/rgba()/color(srgb …)/transparent）。 */
function rgbaOf(str) {
  if (!str) return null;
  if (str === 'transparent') return { r: 0, g: 0, b: 0, a: 0 };
  let m = str.match(/rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?/);
  if (m) return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] };
  m = str.match(/color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?/);
  if (m) return { r: Math.round(+m[1] * 255), g: Math.round(+m[2] * 255), b: Math.round(+m[3] * 255), a: m[4] === undefined ? 1 : +m[4] };
  return null;
}
/** 容差比色（通道 ±1.5、alpha ±0.004）——只用于「钉死 token 原值」一类断言。 */
function closeColor(str, want) {
  const c = rgbaOf(str);
  if (!c) return false;
  return Math.abs(c.r - want[0]) <= 1.5 && Math.abs(c.g - want[1]) <= 1.5
    && Math.abs(c.b - want[2]) <= 1.5 && Math.abs(c.a - want[3]) <= 0.004;
}
function isBrandGreen(str) {
  const c = rgbOf(str);
  if (!c) return false;                        // transparent / none 等：不是绿
  const [r, g, b] = c;
  // 高饱和绿通道 = 微信绿族通性（#95ec69 / #07c160 及其 color-mix 降透明变体）。
  return g >= 150 && g - r >= 55 && g - b >= 55;
}

const failures = [];
function check(cond, label, detail) {
  if (cond) return;
  failures.push(`${label} :: ${detail}`);
}

async function main() {
  const wantJson = process.argv.includes('--json');
  const srv = await startServer();
  const port = srv.address().port;
  const browser = await chromium.launch({ headless: true });
  const readings = {};

  try {
    for (const theme of ['light', 'dark']) {
      const ctx = await browser.newContext({ colorScheme: theme, viewport: { width: 900, height: 1600 } });
      const page = await ctx.newPage();
      await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'load' });
      await page.waitForFunction(() => document.styleSheets.length >= 1);
      // 样式表就位闸：确认最后载入的 glass.css 已生效（--glass-bub-user-bg 可解析）。
      await page.waitForFunction(() => {
        const el = document.getElementById('ctl-main-flat');
        return el && getComputedStyle(el).backgroundColor !== 'rgba(0, 0, 0, 0)';
      });
      readings[theme] = await page.evaluate(collect);
      await ctx.close();
    }
  } finally {
    await browser.close();
    srv.close();
  }

  // ── C7 文字气泡本体零改动（先验：不成立则下面的「同色」判据没有意义）──
  for (const st of STATES) {
    const L = readings.light.bubbles[`out-${st}`];
    const Li = readings.light.bubbles[`in-${st}`];
    const D = readings.dark.bubbles[`out-${st}`];
    const Di = readings.dark.bubbles[`in-${st}`];
    check(L && closeColor(L.bg, BUBBLE_LIGHT.out), 'C7 出向文字气泡（亮）', `got ${L && L.bg}`);
    check(Li && closeColor(Li.bg, BUBBLE_LIGHT.in), 'C7 入向文字气泡（亮）', `got ${Li && Li.bg}`);
    check(D && closeColor(D.bg, BUBBLE_DARK.out), 'C7 出向文字气泡（暗）', `got ${D && D.bg}`);
    check(Di && closeColor(Di.bg, BUBBLE_DARK.in), 'C7 入向文字气泡（暗）', `got ${Di && Di.bg}`);
  }

  // ── C1/C2 容器底色 = 本向文字气泡底色（逐字相等），全矩阵 ──
  for (const theme of ['light', 'dark']) {
    const bubble = { out: readings[theme].bubbles[`out-loaded`].bg, in: readings[theme].bubbles[`in-loaded`].bg };
    for (const f of readings[theme].faces) {
      const label = `${f.side}/${f.form}/${f.state}/${theme}`;
      const want = bubble[f.side];
      check(f.container && f.container.bg === want, `C${f.side === 'out' ? 1 : 2} 媒体面底色=本向文字气泡（${label}）`,
        `container=${f.container && f.container.bg} bubble=${want}`);
      // C3 媒体元素自身透明（字节未到/失败态直接透出容器底色）
      check(f.media && f.media.bg === TRANSPARENT, `C3 媒体元素透明（${label}）`, `media=${f.media && f.media.bg}`);
      // C4 归属方色族（bubble-side-color 批 2026-11-06 改判）：
      //   出向 = 我方绿族（#95ec69 族，g≥150 且双通道差 ≥55）；
      //   入向 = 对方白/暗白（零品牌绿 —— 对方永远不许绿）。
      if (f.side === 'out') {
        check(isBrandGreen(f.container && f.container.bg), `C4 出向容器 = 我方绿族（${label}）`, `container=${f.container && f.container.bg}`);
      } else {
        check(!isBrandGreen(f.container && f.container.bg), `C4 入向容器零品牌绿（${label}）`, `container=${f.container && f.container.bg}`);
      }
      check(!isBrandGreen(f.media && f.media.bg), `C4 媒体元素零色相（${label}）`, `media=${f.media && f.media.bg}`);
      // C5 几何：入向不加边框（两向都不许长框）
      check(f.container && f.container.borderW === '0px', `C5 零边框（${label}）`,
        `border=${f.container && `${f.container.borderW} ${f.container.borderS}`}`);
      if (f.side === 'out') {
        check(f.container && f.container.radiusTL === '16px', `C5 出向圆角 16px（${label}）`, `tl=${f.container && f.container.radiusTL}`);
        check(f.container && f.container.radiusBR === '4px', `C5 出向尾角 4px（${label}）`, `br=${f.container && f.container.radiusBR}`);
        check(f.container && f.container.shadow === 'none', `C5 出向无阴影（${label}）`, `shadow=${f.container && f.container.shadow}`);
      }
    }
  }

  // ── C6 主会话与 fm 出向**同族同值**（bubble-side-color 批 2026-11-06 改判：
  //    "我们这方发的一律绿" 不分面；主会话用户媒体面与 fm 出向媒体面现在共吃
  //    同一枚 --nf-bub-ours-bg）──
  for (const theme of ['light', 'dark']) {
    const main = readings[theme].controls.mainFlat;
    check(!!main, `C6 主会话媒体面对照组存在（${theme}）`, 'missing #ctl-main-flat');
    check(!!main && !closeColor(main.bg, [91, 127, 191, 0.15]), `C6 主会话媒体面不再走旧 sapphire tint（${theme}）`, `bg=${main && main.bg}`);
    check(!!main && main.bg === readings[theme].bubbles['out-loaded'].bg, `C6 主会话媒体面 ≡ fm 出向底色（${theme}，同一枚我方令牌）`,
      `main=${main && main.bg} fmOut=${readings[theme].bubbles['out-loaded'].bg}`);
    check(!!main && isBrandGreen(main.bg), `C6 主会话媒体面仍是我方气泡绿族（${theme}）`, `bg=${main && main.bg}`);
    // 主会话媒体元素透明腿（2026-10-20 批既有）不得因本次选择器改写而丢失。
    const mm = readings[theme].controls.mainMedia;
    check(!!mm && mm.bg === TRANSPARENT, `C6 主会话媒体元素仍透明（${theme}）`, `media=${mm && mm.bg}`);
  }

  // ── C8 下载角标：中性墨，非品牌色 ──
  for (const theme of ['light', 'dark']) {
    for (const side of SIDES) {
      const chip = readings[theme].chips[side];
      check(!!chip && chip.bg === CANVAS_CHIP_BG, `C8 下载角标中性墨（${side}/${theme}）`, `bg=${chip && chip.bg}`);
      check(!!chip && !isBrandGreen(chip.bg), `C8 下载角标零品牌绿（${side}/${theme}）`, `bg=${chip && chip.bg}`);
    }
  }

  // ── 读数表（原始值；证用）──
  const line = (label, val) => console.log(`  ${label.padEnd(46)} ${val}`);
  if (wantJson) console.log(JSON.stringify(readings, null, 2));
  console.log('\n=== 读数字典（computed backgroundColor 原文） ===');
  for (const theme of ['light', 'dark']) {
    console.log(`\n[${theme}]`);
    line('文字气泡 .fm-msg.out .fm-msg-bubble', readings[theme].bubbles['out-loaded'].bg);
    line('文字气泡 .fm-msg.in  .fm-msg-bubble', readings[theme].bubbles['in-loaded'].bg);
    line('对照 主会话 .row.user-media .pop-media-flat', readings[theme].controls.mainFlat.bg);
    for (const f of readings[theme].faces) {
      line(`  ${f.side}/${f.form}/${f.state}`, `container=${f.container.bg}  media=${f.media.bg}  rTL=${f.container.radiusTL} rBR=${f.container.radiusBR} shadow=${f.container.shadow} border=${f.container.borderW}`);
    }
    line(`角标 .pop-media-canvas (out/in)`, `${readings[theme].chips.out.bg} / ${readings[theme].chips.in.bg}`);
  }

  console.log(`\n${failures.length ? 'FAIL' : 'PASS'}: ${failures.length} 项不合（矩阵 = 出/入 × 平面图/平面视频/叠卡前/叠卡背 × 正常亮/正常暗/占位/失败）`);
  if (failures.length) {
    failures.forEach((f) => console.log(`  ✗ ${f}`));
    process.exit(1);
  }
}

main().catch((e) => { console.error('ERROR', e && e.stack || e); process.exit(2); });
