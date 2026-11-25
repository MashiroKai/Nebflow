// _term-face-harness.mjs — 词条引用的视觉面离线夹具（term-system-v2 词条视觉面批
// 2026-11-05，作者令：「词条被使用的时候，应该是一个蓝色的文字，然后不需要显示
// 文档名」「引用了词条之后，引擎自动在下方建立索引，就是引用的词条所在的文档。
// 也可以点击跳转。」）
//
// 用途（离线、零后端、零 sbt、不碰 :6138）：
//   ① 视觉证据：1440/375 × 亮/暗 四张截图 → 项目 vault 的 attachments/；
//   ② 可达性硬判据：词条蓝与正文阅读面背景的 WCAG 对比度，亮/暗各一个数字，
//      必须 ≥ 4.5:1（正文文本门槛）；
//   ③ 结构/行为断言：词条链接字色 = 令牌（不是绿）、裸词同色、索引块 summary
//      文案 = 「词条索引（N）」、默认收起、幂等（连调两次不叠块）、0 引用不渲染、
//      共享卡的元信息元素与内容。
//
// 🔴 诚实申报（哪里是真、哪里是夹具）：
//   · 真：CSS 是**磁盘上的真文件**（page.route 从 src/main/resources/web 注入，
//     链接的就是 split.css / stand-tokens.css / design-system.css / sapphire.css）；
//     索引块的生成逻辑是**真函数**（动态 import js/viewers/markdown.js 后调它，
//     不是复刻实现）。
//   · 夹具：DOM 外壳（#canvas-panel / .canvas-md-scroll / .canvas-md-viewer）与
//     词条链接的 data-wiki-resolved 载荷是手搭的 —— 它们由真实渲染 pass 在
//     viewMarkdown 里产出，那条链需要整套应用外壳（WS/graph），本夹具不拖进来。
//     悬停气泡同法手搭（结构 = termTip.js paintTermTip 的实际产出形态）：
//     气泡本体需要 attachTermFace 挂 mouseenter 才有数据，夹具直接摆最终形态。
//   · 词条蓝的对比度用「实际计算色」算：字色取 getComputedStyle(链接).color
//     （浏览器解析令牌后的真值），背景取阅读面合成色（#canvas-panel 的
//     --glass-bg 半透明层叠在 body 的 --color-bg 上，按 alpha 合成为不透明色）。
//
// 跑法：node tests/_term-face-harness.mjs
//   EXIT 0 = 全部断言绿；1 = 有断言红；2 = 环境错误（playwright 缺失等）。

import { readFileSync, mkdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.TERMFACE_WEB_ROOT
  ? normalize(process.env.TERMFACE_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT_DIR = process.env.TERMFACE_OUT_DIR
  || '/Users/kaiyu/.stand/projects/stand/.stand/docs/attachments';
const PLAYWRIGHT = process.env.PLAYWRIGHT_ENTRY
  || '/opt/homebrew/lib/node_modules/playwright/index.mjs';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};

/** 面（viewer 实际产出的结构）：正文 + 两种词条链接 + 索引块 + 悬停气泡。 */
const FIXTURE = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>词条面夹具</title>
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/stand-tokens.css">
<link rel="stylesheet" href="/css/design-system.css">
<link rel="stylesheet" href="/css/sapphire.css">
<link rel="stylesheet" href="/css/split.css">
<style>
  /* 夹具脚手架（不是产品样式）：真 app 里 #canvas-panel 是 body.canvas-open 下
     才显形（基态 flex:0 0 0 + opacity:0），且被 #main 的 flex row 撑开 —— 夹具
     给同一上下文，否则面板宽 0 / 全透明（量不到溢出也拍不出东西）。 */
  html, body { height: 100%; margin: 0; }
  body { display: flex; align-items: stretch; }
  #canvas-panel { flex: 1 1 auto; width: 100%; }
  body.canvas-open #canvas-panel { flex-basis: auto; opacity: 1; }
</style>
</head>
<body class="canvas-open">
<div id="canvas-panel">
  <div id="canvas-content">
    <div class="canvas-tab-pane active">
      <div class="canvas-md-scroll">
        <div class="canvas-md-viewer" id="doc">
          <h1>词条系统 v2 · 渲染面</h1>
          <p>词条被引用时只显示词条名：<a class="nf-wikilink nf-wikilink-resolved nf-wikilink-term" id="t1" href="#"
            data-wiki-target="词条注册表"
            data-wiki-anchor="^term-registry"
            data-wiki-term-kind="term"
            data-wiki-resolved='{"absPath":"/Users/kaiyu/.stand/projects/stand/.stand/docs/glossary.md","anchor":{"kind":"block","id":"term-registry"},"term":{"name":"词条注册表","rel":"glossary.md","doc":"术语表","definition":"由正文块锚段落派生的显式词条集合。","source":"glossary"}}'
          >词条注册表</a>，它由正文块锚段落派生。</p>
          <p>正文裸词自动链接同色但底纹更轻：<a class="nf-term nf-wikilink-term" id="t2" href="#"
            data-wiki-target="三腿消解"
            data-wiki-term-kind="term"
            data-term="三腿消解"
            data-wiki-resolved='{"absPath":"/Users/kaiyu/.stand/projects/stand/.stand/docs/glossary.md","anchor":{"kind":"block","id":"term-three-leg"},"term":{"name":"三腿消解","rel":"glossary.md","doc":"术语表","definition":"精确路径 → 唯一 basename → 词条注册表 的引用消解优先级。","source":"glossary"}}'
          >三腿消解</a> 按优先级落地。</p>
          <p>载荷未收录的短形同样取蓝（hover 出泡走兜底腿）：<a class="nf-wikilink nf-wikilink-unresolved nf-wikilink-term" id="t3" href="#"
            data-wiki-term-kind="fallback" data-wiki-term-fallback="待入册词条"
          >待入册词条</a>。</p>
        </div>
      </div>
    </div>
  </div>
</div>
<div class="document-term-card" id="tip" style="display:block; left:24px; bottom:24px">
  <strong>词条注册表</strong>
  <div class="document-term-summary">由正文块锚段落派生的显式词条集合，「[[名]]」无锚引用在文档腿全失败后落到它上面。</div>
  <div class="document-term-meta">类型：词条　出处：glossary.md</div>
</div>
</body>
</html>`;

const results = [];
function assert(name, ok, detail) {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
}

/** WCAG 2.x 相对亮度 / 对比度（sRGB）。 */
function srgbLinear(c) {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}
function luminance([r, g, b]) {
  return 0.2126 * srgbLinear(r) + 0.7152 * srgbLinear(g) + 0.0722 * srgbLinear(b);
}
function contrast(a, b) {
  const la = luminance(a);
  const lb = luminance(b);
  const hi = Math.max(la, lb);
  const lo = Math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}
/** "rgb(70, 99, 156)" / "rgba(0,0,0,0.5)" → [r,g,b,a] */
function parseColor(str) {
  const m = String(str).match(/rgba?\(([^)]+)\)/);
  if (!m) return null;
  const parts = m[1].split(/[,/\s]+/).filter(Boolean).map(Number);
  return [parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : 1];
}
/** 半透明色叠在不透明底上（阅读面合成色 = --glass-bg 叠 --color-bg）。 */
function over(fg, bg) {
  const a = fg[3];
  return [0, 1, 2].map((i) => fg[i] * a + bg[i] * (1 - a));
}

async function main() {
  let chromium;
  try {
    ({ chromium } = await import(PLAYWRIGHT));
  } catch (e) {
    console.error(`环境错误：加载 playwright 失败（${PLAYWRIGHT}）：${e && e.message}`);
    process.exit(2);
  }
  if (!existsSync(WEB)) { console.error(`环境错误：web 树不存在 ${WEB}`); process.exit(2); }
  mkdirSync(OUT_DIR, { recursive: true });

  const browser = await chromium.launch();
  const contrastNumbers = {};

  for (const scheme of ['light', 'dark']) {
    for (const width of [1440, 375]) {
      const height = width === 375 ? 812 : 900;
      const ctx = await browser.newContext({
        viewport: { width, height },
        colorScheme: scheme,
        deviceScaleFactor: 2,
      });
      const page = await ctx.newPage();
      await page.route('**/*', (route) => {
        const url = new URL(route.request().url());
        let p = decodeURIComponent(url.pathname);
        if (p === '/' || p === '/__fixture.html') {
          return route.fulfill({ status: 200, contentType: 'text/html', body: FIXTURE });
        }
        if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        const file = normalize(join(WEB, p));
        if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
        try {
          return route.fulfill({
            status: 200,
            contentType: MIME[extname(file)] || 'application/octet-stream',
            body: readFileSync(file),
          });
        } catch {
          return route.fulfill({ status: 404, body: 'not found' });
        }
      });
      await page.goto('http://localhost:1/__fixture.html');
      // 主题双通道都钉死：html[data-theme]（--term-link-ink 等新族）+ 上下文
      // colorScheme（split.css / base.css 的 prefers-color-scheme 历史族）。
      await page.evaluate((s) => { document.documentElement.dataset.theme = s; }, scheme);
      await page.waitForLoadState('load');

      // 索引块 = 真函数产出（不是复刻）：import 页面里的 markdown.js，连调两次
      // 验幂等，再在空 viewer 上验「0 引用整块不渲染」。
      const gen = await page.evaluate(async () => {
        const mod = await import('/js/viewers/markdown.js');
        const viewer = document.getElementById('doc');
        const n1 = mod.renderTermIndex(viewer);
        const blocks1 = viewer.querySelectorAll('.nf-term-index').length;
        const n2 = mod.renderTermIndex(viewer);
        const blocks2 = viewer.querySelectorAll('.nf-term-index').length;
        const emptyViewer = document.createElement('div');
        const n0 = mod.renderTermIndex(emptyViewer);
        const blocks0 = emptyViewer.querySelectorAll('.nf-term-index').length;
        const el = viewer.querySelector('.nf-term-index');
        const summary = el ? el.querySelector('summary').textContent : '';
        const openByDefault = el ? el.hasAttribute('open') : null;
        const rows = el ? [...el.querySelectorAll('li')].map((li) => ({
          term: li.querySelector('.nf-term-index-term').textContent,
          doc: (li.querySelector('.nf-term-index-docname') || {}).textContent || '',
          click: {
            wikiTarget: li.querySelector('.nf-term-index-term').dataset.wikiTarget,
            hasResolved: !!li.querySelector('.nf-term-index-term').dataset.wikiResolved,
          },
        })) : [];
        if (el) el.setAttribute('open', '');   // 截图腿：展开（默认收起已单独断言）
        return { n1, blocks1, n2, blocks2, n0, blocks0, summary, openByDefault, rows };
      });

      const read = await page.evaluate(() => {
        const cs = (sel) => {
          const el = document.querySelector(sel);
          if (!el) return null;
          const s = getComputedStyle(el);
          return { color: s.color, borderBottom: s.borderBottom, textDecoration: s.textDecorationLine,
            display: s.display, fontSize: s.fontSize };
        };
        const panel = document.querySelector('#canvas-panel');
        return {
          htmlClass: document.documentElement.getAttribute('data-theme'),
          termLink: cs('#t1'),
          bareTerm: cs('#t2'),
          fallbackTerm: cs('#t3'),
          tipMetaText: document.querySelector('#tip .document-term-meta')?.textContent,
          tipBox: (() => { const e = document.querySelector('#tip'); const r = e.getBoundingClientRect();
            return { w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; })(),
          indexSummary: cs('.nf-term-index summary'),
          indexRow: (() => {
            const e = document.querySelector('.nf-term-index .nf-term-index-term');
            if (!e) return null;
            const r = e.getBoundingClientRect();
            const li = e.closest('li').getBoundingClientRect();
            return { color: getComputedStyle(e).color, rowW: +li.width.toFixed(1), rowH: +li.height.toFixed(1),
              overflows: r.right > li.right + 1 };
          })(),
          tokens: {
            termInk: getComputedStyle(document.documentElement).getPropertyValue('--term-link-ink').trim(),
            dsLinkTerm: getComputedStyle(document.documentElement).getPropertyValue('--ds-link-term').trim(),
            glassBg: getComputedStyle(panel).backgroundColor,
            bodyBg: getComputedStyle(document.body).backgroundColor,
            text: getComputedStyle(document.querySelector('#doc p')).color,
          },
        };
      });

      // ── 断言：索引块结构与行为 ──────────────────────────────────
      assert(`[${scheme}/${width}] 索引块生成 2 条（真函数返回 2）`, gen.n1 === 2, `n1=${gen.n1}`);
      assert(`[${scheme}/${width}] 幂等：连调两次仍只有 1 块`, gen.blocks1 === 1 && gen.blocks2 === 1,
        `blocks1=${gen.blocks1} blocks2=${gen.blocks2}`);
      assert(`[${scheme}/${width}] 0 引用整块不渲染`, gen.n0 === 0 && gen.blocks0 === 0,
        `n0=${gen.n0} blocks0=${gen.blocks0}`);
      assert(`[${scheme}/${width}] summary = 词条索引（2）`, gen.summary === '词条索引（2）', gen.summary);
      assert(`[${scheme}/${width}] 默认收起（无 open 属性）`, gen.openByDefault === false);
      assert(`[${scheme}/${width}] 条目 = 词条名 + 所在文档`,
        gen.rows.length === 2 && gen.rows.every((r) => r.term && r.doc === '术语表'),
        JSON.stringify(gen.rows));
      assert(`[${scheme}/${width}] 条目点击走既有 wikilink 委派（data-wiki-resolved + 非空 target）`,
        gen.rows.every((r) => r.click.hasResolved && r.click.wikiTarget === r.term));

      // ── 断言：字色 = 令牌（蓝，不是绿 / 不是继承正文色） ─────────
      // 令牌读出来是 `120 160 220` 形态、computed color 是 `rgb(120, 160, 220)`，
      // 两侧都归一成数值三元组再比（比较字面量格式 = 假红）。
      const inkTriple = parseColor(read.termLink.color).slice(0, 3).join(',');
      const token = String(read.tokens.dsLinkTerm || '');
      const hex = /^#([0-9a-f]{6})$/i.exec(token);
      const tokenTriple = hex ? [0, 2, 4].map(i => parseInt(hex[1].slice(i, i + 2), 16)).join(',') : (token.match(/[\d.]+/g) || []).slice(0, 3).map(Number).join(',');
      assert(`[${scheme}/${width}] 词条链接字色 = --ds-link-term`,
        !!tokenTriple && inkTriple === tokenTriple,
        `link=${read.termLink.color} token=${read.tokens.dsLinkTerm}`);
      assert(`[${scheme}/${width}] 正文裸词同色`, read.bareTerm.color === read.termLink.color,
        `bare=${read.bareTerm.color}`);
      assert(`[${scheme}/${width}] 未解析词条同色`, read.fallbackTerm.color === read.termLink.color,
        `fallback=${read.fallbackTerm.color}`);
      assert(`[${scheme}/${width}] 词条蓝不是品牌绿（绿 #07c160 = 7,193,96）`,
        inkTriple !== '7,193,96', inkTriple);
      assert(`[${scheme}/${width}] 共享卡元信息存在且内容正确`,
        read.tipMetaText === '类型：词条　出处：glossary.md', `text=${read.tipMetaText}`);

      // ── 断言：375 宽不溢出 ────────────────────────────────────
      assert(`[${scheme}/${width}] 索引条目在 ${width} 宽不横向溢出`,
        read.indexRow && read.indexRow.overflows === false,
        JSON.stringify(read.indexRow));

      // ── 对比度（实际计算色 vs 阅读面合成背景）──────────────────
      const ink = parseColor(read.termLink.color);
      const glass = parseColor(read.tokens.glassBg);
      const base = parseColor(read.tokens.bodyBg);
      const backdrop = over(glass, base).map((v) => Math.round(v));
      const ratio = contrast(ink.slice(0, 3), backdrop);
      contrastNumbers[scheme] = +ratio.toFixed(2);
      console.log(`  对比度 ${scheme}: 词条蓝 ${read.termLink.color} vs 阅读面背景 rgb(${backdrop.join(',')}) = ${ratio.toFixed(2)}:1`);
      assert(`[${scheme}/${width}] 词条蓝对比度 ≥ 4.5:1`, ratio >= 4.5, `${ratio.toFixed(2)}:1`);

      const png = join(OUT_DIR, `term-v2-render-${width}-${scheme}.png`);
      await page.screenshot({ path: png, fullPage: false });
      const bytes = statSync(png).size;
      assert(`[${scheme}/${width}] 截图落盘 ${png}`, bytes > 20000, `${bytes} bytes`);
      await ctx.close();
    }
  }

  await browser.close();

  console.log('\n── 对比度汇总（WCAG 2.x，正文门槛 4.5:1）──');
  for (const [k, v] of Object.entries(contrastNumbers)) console.log(`  ${k}: ${v}:1`);
  const worst = Math.min(...Object.values(contrastNumbers));
  console.log(`  最差档: ${worst}:1`);

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${failed.length ? 'FAIL' : 'PASS'}：${results.length - failed.length}/${results.length} 条断言通过`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('harness 崩了：', e);
  process.exit(2);
});
