// _human-card-img-harness.mjs — 「human 摘要卡片体也要出图」批（2026-11-05）的
// **渲染实证**装置。
//
// 背景（上一批 `_wiki-img-embed-harness.mjs` 收口时自己记下的那条前端观察）：
// 卡片机制把 ```human 摘要围栏**外**的一切折进默认收起的 `<details class=
// "nf-human-details">` 抽屉；而 `expandImageEmbeds()` 又**明确不展开围栏内嵌
// 入**（围栏当时按「代码面」处理）。两条叠起来的净效果：按「以图为主」纪律
// 写（图放卡片体里）的文档，首屏可见图 **0 张**；图放在围栏外，则被折进抽屉、
// 首屏仍是 0 张。同一篇文档只能二选一，两难。
//
// 作者裁定取「卡片体也出图」（2026-11-05 小修批）：`expandImageEmbeds()` 对
// **已闭合的 ```human 围栏正文**同样展开 `![[…]]` 图片嵌入 —— 围栏不是代码块，
// 是人面卡片体（utils.js `_transformHumanFace` 把正文重新当 markdown 渲）。
//
// 本装置证明「前端真的这么干」，不是「正则看着像」：
//   · 真渲染器：`import('/js/viewers/markdown.js')` 的 default.render —— 产品里
//     打开 markdown 页签走的同一条路（canvas.js openWorkspaceItem → fileViewers
//     → viewers/markdown.js），不复制任何一段解析逻辑。
//   · 真字节：`/api/nf-ticket` / `/api/nf-file` 由 `page.route` 从磁盘服务 vault
//     里的真文件（票据按「文件真存在」签发，缺文件按 not-found 驳回）。
//   · 零端口零进程：不起服务、不开端口、不碰 :6138。
//
// 判据（EXIT 0 = 全绿；`--before` 树跑同一套判据 = 验红，预期 FAIL）：
//   A 卡片体出图：围栏内 4 张 vault 相对路径全部 naturalWidth>0，零失败占位
//   B 首屏：图都在 `.nf-human-card` 里 ⇒ 首屏可见 = 全部（不被抽屉折走）
//   C 排版：卡片内 img 的 max-width:100% / 圆角 / 卡片无横向溢出（既有令牌，
//     不新造尺寸）
//   D 判定面不变：围栏内**失效路径**照样落「图片加载失败」占位（不静默吞掉）
//   E 判定面不变：围栏内**裸名**（图在子目录）照样落失败占位
//   F 通用围栏零回归：```js 围栏里的 `![[x.png]]` 仍是字面量（代码是代码）
//   G 未闭合的 ```human：不变卡片、不展开（流式中间态不错版）
//   H 行内代码 `![[x.png]]` 逐字不动
//   I 正文其余内容不被破坏：标题顺序、卡内文字、框外抽屉仍在且默认收起
//   J 零 pageerror
//   K 本批报告自身也按新纪律写（图在卡片体、vault 相对路径），且真的出图
//
// 跑法：
//   node tests/_human-card-img-harness.mjs                  # 当前树（改后，应全绿）
//   WIKI_IMG_WEB_ROOT=<before tree> OUT_KEY=before \
//     node tests/_human-card-img-harness.mjs                # 改前树（验红，预期 FAIL）
// 可选 env：WIKI_IMG_WEB_ROOT / WIKI_IMG_VAULT / HUMAN_CARD_OUT / OUT_KEY /
//           PLAYWRIGHT_ENTRY

import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.WIKI_IMG_WEB_ROOT
  ? normalize(process.env.WIKI_IMG_WEB_ROOT)
  : join(HERE, '..', 'src', 'main', 'resources', 'web');
const VAULT = process.env.WIKI_IMG_VAULT
  || join(homedir(), '.stand', 'projects', 'stand', '.stand', 'docs');
const OUT_KEY = process.env.OUT_KEY || 'after';
const OUT_DIR = process.env.HUMAN_CARD_OUT
  || join(VAULT, 'assets', `20261105_human-card-img-expand/${OUT_KEY}`);
const PLAYWRIGHT = process.env.PLAYWRIGHT_ENTRY
  || '/opt/homebrew/lib/node_modules/playwright/index.mjs';

// 真图（上一批的实证截图，vault 里已在）：全部写成 **vault 相对路径**。
const IMG_A = 'assets/20261105_agent-panel-rows/after-desktop-dark-agents.png';
const DEAD = 'assets/20261105_human-card-img-expand/does-not-exist.png';
const BARE = 'after-states-dark.png';                 // 裸名 ⇒ 六格全落空（R17）

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
  '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/** 夹具外壳：只搭产品 DOM 骨架，正文由**真 render 函数**灌进去。 */
const SHELL = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>human-card-img 实证装置</title>
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/stand-tokens.css">
<link rel="stylesheet" href="/css/design-system.css">
<link rel="stylesheet" href="/css/sapphire.css">
<link rel="stylesheet" href="/css/humanFace.css">
<link rel="stylesheet" href="/css/split.css">
<script src="/vendor/marked.min.js"></script>
<script src="/vendor/highlight.min.js"></script>
<script src="/vendor/lucide.min.js"></script>
<style>
  html, body { height: 100%; margin: 0; }
  body { display: flex; align-items: stretch; }
  #canvas-panel { flex: 1 1 auto; width: 100%; position: relative; }
  .canvas-tab-pane { position: relative; height: 100%; }
</style>
</head>
<body class="canvas-open">
<div id="canvas-panel">
  <div id="canvas-content">
    <div class="canvas-tab-pane active" id="pane"></div>
  </div>
</div body>
</body>
</html>`;

const results = [];
function assert(name, ok, detail) {
  results.push({ name, ok: !!ok, detail: detail || '' });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
}

function serveStatic(route, pathname) {
  const file = normalize(join(WEB, pathname));
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
}

/** 票据/字节两个端点的离线实现（形状与宿主一致：{tickets, rejected}）。 */
function installRoutes(page) {
  let ticketSeq = 0;
  const issued = new Map();
  return page.route('**/*', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = decodeURIComponent(url.pathname);
    if (p === '/' || p === '/__harness.html') {
      return route.fulfill({ status: 200, contentType: 'text/html', body: SHELL });
    }
    if (p === '/api/nf-ticket') {
      let body = {};
      try { body = JSON.parse(req.postData() || '{}'); } catch { body = {}; }
      const paths = Array.isArray(body.paths) ? body.paths : [];
      const tickets = {};
      const rejected = [];
      for (const raw of paths) {
        const abs = String(raw).startsWith('~')
          ? join(homedir(), String(raw).slice(1))
          : String(raw);
        let okFile = false;
        try { okFile = existsSync(abs) && statSync(abs).isFile(); } catch { okFile = false; }
        if (!okFile) { rejected.push({ path: raw, reason: 'not-found' }); continue; }
        const tk = 'TK' + (++ticketSeq);
        issued.set(tk, abs);
        tickets[raw] = tk;
      }
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ tickets, rejected }),
      });
    }
    if (p === '/api/nf-file') {
      const tk = url.searchParams.get('ticket');
      // 票据校验的**离线放宽**（诚实申报，同 _wiki-img-embed-harness）：账本腿
      // 取数时会剥掉凭据参数再 GET，本装置按「path 存在即可」放行；铸票面的
      // 判决（文件不存在 ⇒ rejected: not-found）保持真实——失败面都在铸票判决。
      if (tk && !issued.has(tk)) return route.fulfill({ status: 403, body: 'ticket' });
      const abs = (tk && issued.get(tk)) || url.searchParams.get('path');
      let okFile = false;
      try { okFile = !!abs && existsSync(abs) && statSync(abs).isFile(); } catch { okFile = false; }
      if (!okFile) return route.fulfill({ status: 404, body: 'not found' });
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(abs)] || 'application/octet-stream',
        body: readFileSync(abs),
      });
    }
    if (p === '/api/wiki/graph') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ nodes: [], edges: [] }) });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    return serveStatic(route, p);
  });
}

/** 用真 render 函数渲一篇文档，再把「图到底出没出 / 在不在首屏」读回来。 */
async function renderCase(page, { content, absPath, fileName }) {
  await page.evaluate(() => {
    const pane = document.getElementById('pane');
    pane.innerHTML = '';
    delete pane._renderedMdContent;
  });
  await page.evaluate(async ({ content, absPath, fileName }) => {
    const mod = await import('/js/viewers/markdown.js');
    const pane = document.getElementById('pane');
    await mod.default.render(pane, { content, absPath, fileName, anchor: null, readOnly: true });
  }, { content, absPath, fileName });
  await page.waitForFunction(
    () => Array.from(document.images).every((i) => i.complete), null, { timeout: 15000 },
  ).catch(() => {});
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  return page.evaluate(() => {
    const pane = document.getElementById('pane');
    const scroller = pane.querySelector('.canvas-md-scroll');
    const viewH = scroller ? scroller.clientHeight : window.innerHeight;
    const imgs = Array.from(pane.querySelectorAll('img')).map((i) => {
      const r = i.getBoundingClientRect();
      const sr = scroller ? scroller.getBoundingClientRect() : { top: 0, bottom: viewH };
      const inCard = !!i.closest('.nf-human-card');
      const inClosedDetails = !!i.closest('details') && !i.closest('details').hasAttribute('open');
      const relTop = r.top - sr.top;
      return {
        src: i.getAttribute('src') || '',
        naturalWidth: i.naturalWidth,
        naturalHeight: i.naturalHeight,
        embed: i.dataset.nfMdEmbed || '',
        path: i.dataset.nfMdPath || '',
        inCard,
        inClosedDetails,
        // 两个不同的「首屏」读数，别混：
        //   ① 卡片机制维度 = 这张图**有没有被折进默认收起的抽屉**（作者说的
        //      「展开后 9/9」就是这个维度的对照）；visibleImgs 数它。
        //   ② 几何维度 = 它**有没有真的落在 900px 视口的第一屏里**（一张 760px
        //      高的截图自己就吃掉整屏）——只作 NOTE 报，不当判据。
        onFirstScreen: !inClosedDetails
          && relTop < viewH - 8 && relTop + r.height > 8,
        css: {
          maxWidth: getComputedStyle(i).maxWidth,
          borderRadius: getComputedStyle(i).borderRadius,
          width: Math.round(r.width),
        },
      };
    });
    const fails = Array.from(pane.querySelectorAll('.nf-md-img-fail')).map((e) => ({
      path: (e.querySelector('.nf-md-img-fail-path') || {}).textContent || '',
      reason: (e.querySelector('.nf-md-img-fail-reason') || {}).textContent || '',
      status: e.getAttribute('data-nf-md-img-status') || '',
      inCard: !!e.closest('.nf-human-card'),
    }));
    const literal = (pane.innerText || '').match(/!\[\[[^\]\n]+\]\]/g) || [];
    // 改前的**真实落点**（不是字面量、也不是 <img>）：围栏内的 `![[x.png]]` 被
    // renderWikilinks 做成「指向一个叫 x.png 的文档」的未解析引用锚（蓝字死链）。
    const danglingImgAnchors = Array.from(pane.querySelectorAll('a[data-wiki-target]'))
      .filter((el) => /\.(png|jpe?g|gif|webp|svg|avif|bmp)$/i.test(el.dataset.wikiTarget || ''))
      .map((el) => ({ target: el.dataset.wikiTarget, inCard: !!el.closest('.nf-human-card') }));
    const drawers = Array.from(pane.querySelectorAll('details.nf-human-details'))
      .map((d) => ({ label: (d.querySelector('summary') || {}).textContent || '',
        open: d.hasAttribute('open') }));
    const cards = Array.from(pane.querySelectorAll('.nf-human-card')).map((c) => ({
      imgs: c.querySelectorAll('img').length,
      fails: c.querySelectorAll('.nf-md-img-fail').length,
      text: (c.innerText || '').slice(0, 120),
      // 卡片有没有被图撑破：内容盒宽 vs 滚动容器宽（允许 1px 取整误差）
      cardW: Math.round(c.getBoundingClientRect().width),
      scrollW: scroller ? Math.round(scroller.clientWidth) : 0,
      overflowX: scroller ? scroller.scrollWidth - scroller.clientWidth : 0,
    }));
    const headings = Array.from(pane.querySelectorAll('h1,h2,h3')).map((h) => h.textContent.trim());
    // ① 卡片机制维度：没被折进「默认收起的抽屉」的图 = 读者不用点开就看得到
    const visibleImgs = imgs.filter((i) => !i.inClosedDetails).length;
    // 失败面「属性残渣」读数：整枚标签必须被替换干净，正文里不许留
    // `alt="…" data-nf-md-embed="…">` 这种半截标签字面量。
    // 用 **textContent**（不是 innerText）：innerText 会跳过**收起抽屉**里的
    // 内容，残渣躲进抽屉就测不到了（本装置第一次跑就踩了这个盲区 —— 卡外失效
    // 路径在改前树上「PASS」，实际残渣只是躺在收起抽屉里）。
    const leakedTagText = ((pane.textContent || '').match(/data-nf-md-embed=/g) || []).length;
    return { imgs, fails, literal, danglingImgAnchors, drawers, cards, headings, visibleImgs,
      leakedTagText,
      summaryLabels: Array.from(pane.querySelectorAll('summary')).map((s) => (s.textContent || '').trim()),
      preCode: Array.from(pane.querySelectorAll('pre code')).map((c) => (c.textContent || '').slice(0, 120)),
      htmlLen: pane.innerHTML.length };
  });
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
  if (!existsSync(VAULT)) { console.error(`环境错误：vault 不存在 ${VAULT}`); process.exit(2); }
  for (const rel of [IMG_A]) {
    if (!existsSync(join(VAULT, rel))) { console.error(`环境错误：夹具图不存在 ${rel}`); process.exit(2); }
  }
  // 真图：**就是**实报障那篇 9 图文档用的 9 张（vault 里已在），全部写成
  // **vault 相对路径**。这样「首屏可见图」的读数直接对得上作者报的那篇文档。
  const FIGS = [
    'assets/20261105_agent-panel-rows/after-states-dark.png',
    'assets/20261105_agent-panel-rows/before-desktop-dark-agents.png',
    'assets/20261105_agent-panel-rows/after-desktop-dark-agents.png',
    'assets/20261105_agent-panel-rows/after-desktop-light-agents.png',
    'assets/20261105_agent-panel-rows/after-name-expanded-dark.png',
    'assets/20261105_agent-panel-rows/before-desktop-light-tasks.png',
    'assets/20261105_agent-panel-rows/after-desktop-light-tasks.png',
    'assets/20261105_agent-panel-rows/before-mobile375-dark.png',
    'assets/20261105_agent-panel-rows/after-mobile375-dark.png',
  ];
  const IMG_DRAWER = 'assets/20261105_wiki-img-embed/after-doc-figure.png';
  for (const rel of [...FIGS, IMG_DRAWER]) {
    if (!existsSync(join(VAULT, rel))) { console.error(`环境错误：夹具图不存在 ${rel}`); process.exit(2); }
  }

  mkdirSync(OUT_DIR, { recursive: true });
  const DOC_NAME = 'human-card-fixture.md';
  const DOC_ABS = join(VAULT, 'assets', '20261105_human-card-img-expand', DOC_NAME);

  // ── 夹具文档：**图主导 + 九张图全在卡片体里**（新纪律的标准形态）──────
  const cardDoc = [
    '---', 'type: report', 'status: done', '---', '',
    '# 卡片体出图：九图实证', '',
    '```human',
    '## 结论',
    '九张图全部写在 **卡片体**里，用 **vault 相对路径**——首屏就能看见。',
    '',
    ...FIGS.flatMap((rel, i) => [`![[${rel}]]`, `图 ${i + 1} 第 ${i + 1} 张实拍。`, '']),
    '```',
    '',
    '## 细节在抽屉里',
    '',
    '<details><summary>过程</summary>',
    '',
    `![[${IMG_DRAWER}]]`,
    '',
    '抽屉里的图默认不参与首屏。',
    '',
    '</details>',
    '',
  ].join('\n');

  // ── 其它判定面的隔离样例 ────────────────────────────────────────────
  const deadDoc = `# 失效路径反例\n\n\`\`\`human\n## 结论\n这张图不存在。\n\n![[${DEAD}]]\n\`\`\`\n\n围栏外没有图。\n`;
  const bareDoc = `# 裸名反例\n\n\`\`\`human\n## 结论\n裸名（图真身在 assets/ 子目录）。\n\n![[${BARE}]]\n\`\`\`\n\n围栏外没有图。\n`;
  const codeDoc = `# 通用围栏零回归\n\n\`\`\`js\nconst a = "![[${IMG_A}]]";\n\`\`\`\n\n卡外正文。\n`;
  const unclosedDoc = `# 未闭合的 human 围栏\n\n\`\`\`human\n## 结论\n![[${IMG_A}]]\n\n（围栏在这里还没闭合）\n`;
  const inlineDoc = `# 行内代码\n\n\`\`\`human\n## 结论\n写 \`![[${IMG_A}]]\` 这样。\n\`\`\`\n\n卡外正文。\n`;
  // 卡**外**的失效路径（对照 D）：证明失败面「整枚标签替换干净」这条不是卡片
  // 专属，而是同一个 IMG_REWRITE_RE 的口径。
  const deadOutsideDoc = `# 卡外失效路径\n\n\`\`\`human\n## 结论\n围栏里没有图。\n\`\`\`\n\n![[${DEAD}]]\n\n图 1 这一张不存在。\n`;

  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 900 }, colorScheme: 'dark', deviceScaleFactor: 2,
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e && e.message || e)));
  await installRoutes(page);
  await page.goto('http://localhost:1/__harness.html');
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
  await page.waitForLoadState('load');

  const shots = [];
  /** **首屏**截图：滚动位钉在 0，视口定死 1280×900 —— 改前改后同一位，成对。 */
  const firstScreenShot = async (name) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.evaluate(() => {
      const s = document.querySelector('.canvas-md-scroll');
      if (s) s.scrollTop = 0;
      window.scrollTo(0, 0);
    });
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const p = join(OUT_DIR, `${name}.png`);
    await page.screenshot({ path: p });
    shots.push(p);
    return p;
  };
  /** 整篇（撑高到内容高）—— 给「正文其余内容没坏」留一张全景证据。 */
  const fullShot = async (name, maxCss = 2600) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const need = await page.evaluate(() => {
      const s = document.querySelector('.canvas-md-scroll');
      if (s) { s.style.height = ''; s.style.maxHeight = ''; s.style.overflow = ''; s.scrollTop = 0; }
      return Math.ceil(s ? s.scrollHeight : document.body.scrollHeight);
    });
    const H = Math.min(maxCss, Math.max(900, need));
    await page.setViewportSize({ width: 1280, height: H });
    await page.evaluate((hh) => {
      const s = document.querySelector('.canvas-md-scroll');
      if (s) { s.style.height = hh + 'px'; s.style.maxHeight = 'none'; s.style.overflow = 'visible'; s.scrollTop = 0; }
      window.scrollTo(0, 0);
    }, H);
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const p = join(OUT_DIR, `${name}.png`);
    const scroll = await page.$('.canvas-md-scroll');
    if (scroll) await scroll.screenshot({ path: p, animations: 'disabled' });
    else await page.screenshot({ path: p });
    shots.push(p);
    return p;
  };

  // ── A/B/C/I 卡片体出图 + 首屏 + 排版 + 内容完整 ─────────────────────
  const a = await renderCase(page, { content: cardDoc, absPath: DOC_ABS, fileName: DOC_NAME });
  await firstScreenShot('first-screen');
  await fullShot('full-page');
  const aLoaded = a.imgs.filter((i) => i.naturalWidth > 0).length;
  const aInCard = a.imgs.filter((i) => i.inCard).length;
  const aFirstScreen = a.imgs.filter((i) => i.onFirstScreen && i.naturalWidth > 0).length;
  console.log(`NOTE  卡片夹具：img=${a.imgs.length} 出图=${aLoaded} 卡内=${aInCard}`
    + ` 不被折走(卡片机制维度)=${a.visibleImgs} 落在第一屏(几何维度)=${aFirstScreen}`
    + ` 失败占位=${a.fails.length} 死链锚=${a.danglingImgAnchors.length} 抽屉=${a.drawers.length}`);
  assert('A 卡片体出图：卡片里 9 张图全部 naturalWidth>0',
    a.imgs.length === 10 && aLoaded === 10 && aInCard === 9 && a.fails.length === 0,
    `img=${a.imgs.length} 出图=${aLoaded} 卡内=${aInCard} 失败=${a.fails.length}`);
  assert('A2 卡片体出图：零「指向 png 的死链锚」（围栏内嵌入不再被做成引用）',
    a.danglingImgAnchors.length === 0,
    a.danglingImgAnchors.map((x) => x.target).join(' | '));
  assert('B 首屏（卡片机制维度）：卡片里的 9 张全部不在收起抽屉里',
    a.visibleImgs === 9 && a.imgs.filter((i) => i.inCard && i.naturalWidth > 0).length === 9,
    `不被折走=${a.visibleImgs} 卡内出图=${a.imgs.filter((i) => i.inCard && i.naturalWidth > 0).length}`
    + ` 落在第一屏(几何)=${aFirstScreen}`);
  const cardImgCss = a.imgs.filter((i) => i.inCard)[0];
  assert('C 排版：卡内图 max-width:100% + 既有圆角令牌 + 无横向溢出',
    !!cardImgCss && cardImgCss.css.maxWidth === '100%'
    && cardImgCss.css.borderRadius !== '0px' && cardImgCss.css.borderRadius !== ''
    && a.cards.every((c) => c.overflowX === 0),
    cardImgCss ? `maxWidth=${cardImgCss.css.maxWidth} radius=${cardImgCss.css.borderRadius}`
      + ` 卡宽=${a.cards[0] && a.cards[0].cardW} 滚动宽=${a.cards[0] && a.cards[0].scrollW}`
      + ` overflowX=${a.cards.map((c) => c.overflowX).join('/')}` : '卡内没有 img');
  assert('I 内容完整：标题顺序在 + 作者折叠块仍在 + 框外抽屉默认收起',
    a.headings.join('|') === '卡片体出图：九图实证|结论|细节在抽屉里'
    && a.summaryLabels.includes('过程')
    && a.drawers.length >= 1 && a.drawers.every((x) => x.open === false),
    `headings=${a.headings.join('/')} summaries=${a.summaryLabels.join('/')}`
    + ` drawers=${JSON.stringify(a.drawers)}`);

  // ── D 失效路径反例（围栏内）────────────────────────────────────────
  const d = await renderCase(page, { content: deadDoc, absPath: DOC_ABS, fileName: 'dead.md' });
  await fullShot('dead-inside-card');
  assert('D 围栏内失效路径 ⇒ 明确失败占位（不静默吞掉）',
    d.fails.length === 1 && d.fails[0].inCard && /does-not-exist\.png/.test(d.fails[0].path),
    `失败占位=${d.fails.length} 卡内=${d.fails[0] && d.fails[0].inCard}`
    + ` path=${d.fails[0] && d.fails[0].path} 原因=${d.fails[0] && d.fails[0].reason}`);
  assert('D2 失效图不变成「无图」：卡片里既没有 <img> 也没有字面量残留',
    d.imgs.every((i) => i.naturalWidth === 0) && d.literal.length === 0,
    `img=${d.imgs.length} 字面量=${d.literal.join('|')}`);
  assert('D3 失败面替换**整枚标签**：正文不留 `alt="…" data-nf-md-embed=…>` 残渣',
    d.leakedTagText === 0 && a.leakedTagText === 0,
    `卡内残渣=${d.leakedTagText} 正常文档残渣=${a.leakedTagText}`);

  // ── D4 卡外失效路径（同一口径的对照）────────────────────────────────
  const d4 = await renderCase(page, { content: deadOutsideDoc, absPath: DOC_ABS, fileName: 'dead-outside.md' });
  await fullShot('dead-outside-card');
  assert('D4 卡外失效路径：失败占位在 + 零属性残渣',
    d4.fails.length === 1 && !d4.fails[0].inCard && d4.leakedTagText === 0,
    `失败占位=${d4.fails.length} 卡内=${d4.fails[0] && d4.fails[0].inCard}`
    + ` 残渣=${d4.leakedTagText}`);

  // ── E 裸名反例（围栏内）────────────────────────────────────────────
  const e1 = await renderCase(page, { content: bareDoc, absPath: DOC_ABS, fileName: 'bare.md' });
  await fullShot('bare-name-inside-card');
  const e1Failed = e1.fails.length === 1 || (e1.imgs.length === 1 && e1.imgs[0].naturalWidth === 0);
  assert('E 围栏内裸名（图在子目录）⇒ 仍出不了图（R17 不放宽）',
    e1Failed && e1.imgs.every((i) => i.naturalWidth === 0),
    `img=${e1.imgs.length} 出图=${e1.imgs.filter((i) => i.naturalWidth > 0).length}`
    + ` 失败占位=${e1.fails.length}`);

  // ── F 通用围栏零回归 ───────────────────────────────────────────────
  const f = await renderCase(page, { content: codeDoc, absPath: DOC_ABS, fileName: 'code.md' });
  assert('F ```js 围栏里的嵌入仍是字面量（代码是代码）',
    f.imgs.length === 0 && f.literal.length === 1 && f.preCode.length === 1,
    `img=${f.imgs.length} 字面量=${JSON.stringify(f.literal)} pre=${f.preCode.length}`);

  // ── G 未闭合的 human 围栏 ──────────────────────────────────────────
  const g = await renderCase(page, { content: unclosedDoc, absPath: DOC_ABS, fileName: 'unclosed.md' });
  assert('G 未闭合的 ```human 不变卡片、不展开（流式中间态）',
    g.imgs.length === 0 && g.cards.length === 0 && g.literal.length === 1,
    `img=${g.imgs.length} 卡片=${g.cards.length} 字面量=${JSON.stringify(g.literal)}`);

  // ── H 行内代码 ─────────────────────────────────────────────────────
  const h = await renderCase(page, { content: inlineDoc, absPath: DOC_ABS, fileName: 'inline.md' });
  assert('H 卡内行内代码里的嵌入逐字不动',
    h.imgs.length === 0 && h.literal.length === 1 && h.cards.length === 1,
    `img=${h.imgs.length} 字面量=${JSON.stringify(h.literal)} 卡片=${h.cards.length}`);

  // ── J 零 pageerror ────────────────────────────────────────────────
  assert('J 全进程零 pageerror', pageErrors.length === 0, pageErrors.join(' | '));

  // ── K 本批报告自身（纪律对自己生效；报告还没写就跳过）──────────────
  let reportReading = null;
  const REPORT_REL = process.env.HUMAN_CARD_REPORT
    || 'human-card-image-expand__20261106_002813.md';
  const reportAbs = join(VAULT, REPORT_REL);
  if (existsSync(reportAbs)) {
    const k = await renderCase(page, {
      content: readFileSync(reportAbs, 'utf8'), absPath: reportAbs, fileName: REPORT_REL,
    });
    await firstScreenShot('report-first-screen');
    const kLoaded = k.imgs.filter((i) => i.naturalWidth > 0).length;
    reportReading = {
      img: k.imgs.length, loaded: kLoaded, in_card: k.imgs.filter((i) => i.inCard).length,
      visible: k.visibleImgs, fails: k.fails.length,
      dangling: k.danglingImgAnchors.length,
    };
    console.log(`NOTE  本批报告：img=${k.imgs.length} 出图=${kLoaded}`
      + ` 卡内=${reportReading.in_card} 不被折走=${k.visibleImgs}`
      + ` 失败占位=${k.fails.length} 死链锚=${k.danglingImgAnchors.length}`);
    assert('K 本批报告自身：全部图片出图、零失败占位、零死链锚',
      k.imgs.length >= 4 && kLoaded === k.imgs.length && k.fails.length === 0
      && k.danglingImgAnchors.length === 0,
      `img=${k.imgs.length} 出图=${kLoaded} 失败=${k.fails.length}`
      + ` 死链锚=${k.danglingImgAnchors.length}`
      // 规则原文里的 `![[x.png]]` 在 ```text 代码块里，按纪律必须保持字面（代码是代码）
      + ` 代码块内字面=${k.literal.length}`);
    assert('K2 本批报告自身：开卡片体里的图不被折走（首屏可见）',
      reportReading.in_card >= 2 && k.visibleImgs >= reportReading.in_card,
      `卡内=${reportReading.in_card} 不被折走=${k.visibleImgs}`);
  }

  const evidence = {
    generated_at: new Date().toISOString(),
    out_key: OUT_KEY,
    web_root: WEB,
    vault: VAULT,
    card_doc: { name: DOC_NAME, content: cardDoc },
    cases: { card: a, dead: d, bare: e1, code: f, unclosed: g, inline: h, report: reportReading },
    shots: shots.map((p) => basename(p)),
    page_errors: pageErrors,
    results,
  };
  writeFileSync(join(OUT_DIR, 'evidence.json'), JSON.stringify(evidence, null, 2));

  await browser.close();
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n[${OUT_KEY}] ${failed === 0 ? 'ALL PASS' : `FAILED: ${failed}`}`);
  console.log(`截图与证据：${OUT_DIR}`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('装置异常：', (e && e.stack) || e);
  process.exit(2);
});
