// _wiki-img-embed-harness.mjs — 「图不显示」两个断点的**渲染实证**装置。
//
// 作者报障（2026-11-05）：《agent-panel-three-row__20261105_201500.md》文档里的
// 四张图一张都不显示。根因是两处**渲染器确定性断点**，不是一个 bug：
//   ① R17 裸名嵌入：前端 `imageCandidates()`（web/js/viewers/markdown.js:965）
//      的候选表只有六个固定格位，**不递归子目录**；正文写 `![[x.png]]` 而图真身
//      在 `assets/<batch>/x.png` ⇒ 六格全落空。
//   ② R18 围栏内嵌图：`expandImageEmbeds()`（同文件:920）**不展开围栏内嵌入**
//      （围栏是代码面）⇒ 图写在 ```human 摘要围栏里，路径再对也只显示字面量。
//      **2026-11-05 卡片出图批已反转这一条**：围栏是卡片体不是代码面，围栏内
//      嵌入现在同样展开（见 `humanCardBodyLines()`）；本装置的 C2 随新口径改判
//      「路径对 + 围栏内 ⇒ 出图」，围栏内**裸名**仍归 R17/R18 判失败面。
//
// 本装置证明的是「前端真的这么干」，不是「正则看着像」：
//   · 真渲染器：动态 `import('/js/viewers/markdown.js')`，调它的 **default.render**
//     —— 就是产品里打开 markdown 页签走的那条路（`web/js/canvas.js` 的同一入口），
//     不复制、不复刻任何一段解析逻辑。
//   · 真字节：`/api/nf-ticket` 与 `/api/nf-file` 由 `page.route` 从磁盘服务
//     vault 里的真文件（票据按「文件真存在」签发，缺文件按 not-found 驳回），
//     与宿主端点的应答形状一致（{tickets, rejected}）。
//   · 零端口零进程：不起服务、不开端口、不碰 :6138。
//
// 判据（EXIT 0 = 全绿）：
//   A 改后真文档（vault 里那一篇）：9 张图全部 naturalWidth>0，0 块「图片加载失败」
//   B 改前真文档（同内容副本，给了 WIKI_IMG_BEFORE_MD 才跑）：围栏内那张出图，
//     三处裸名仍是 3 块失败占位
//   C 隔离样例：路径对 + 围栏外 ⇒ 出图；路径对 + 围栏内 ⇒ 出图（卡片体，2026-11-05
//     卡片出图批起；原判据「不出图」已作废）；裸名 + 围栏外 ⇒ 失败占位（复现 R17）
//
// 跑法：node tests/_wiki-img-embed-harness.mjs
//   可选环境变量：WIKI_IMG_WEB_ROOT / WIKI_IMG_VAULT / WIKI_IMG_OUT / PLAYWRIGHT_ENTRY

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
const OUT_DIR = process.env.WIKI_IMG_OUT
  || join(VAULT, 'assets', '20261105_wiki-img-embed');
const PLAYWRIGHT = process.env.PLAYWRIGHT_ENTRY
  || '/opt/homebrew/lib/node_modules/playwright/index.mjs';

const DOC_REL = 'agent-panel-three-row__20261105_201500.md';
const BATCH_DIR = 'assets/20261105_agent-panel-rows';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
  '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/** 夹具外壳：只搭产品 DOM 骨架（#canvas-panel > .canvas-tab-pane > 空），
 *  正文由**真 render 函数**灌进去 —— 夹具不产出任何正文结构。 */
const SHELL = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>wiki-img-embed 实证装置</title>
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/stand-tokens.css">
<link rel="stylesheet" href="/css/design-system.css">
<link rel="stylesheet" href="/css/sapphire.css">
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
</div>
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
      // 票据校验的**离线放宽**（诚实申报）：账本腿（nfFileCache.nfViewerSource）
      // 取数时会剥掉凭据参数再 GET，所以这一腿在本装置里按「path 存在即可」放行；
      // 铸票面的判决（文件不存在 ⇒ rejected: not-found）保持真实——R17/R18 的
      // 失败面全部发生在**铸票判决**那一步，不依赖本腿的票据校验。
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

/** 用真 render 函数渲一篇文档，再把「图到底出没出」读回来。 */
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
    /** @type {any} */ (window).__rendered = true;
  }, { content, absPath, fileName });
  // 等图落定：所有 <img> complete + 两帧 rAF（级联 onerror 后再落一次）
  await page.waitForFunction(
    () => Array.from(document.images).every((i) => i.complete), null, { timeout: 15000 },
  ).catch(() => {});
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  return page.evaluate(() => {
    const pane = document.getElementById('pane');
    const imgs = Array.from(pane.querySelectorAll('img')).map((i) => ({
      src: i.getAttribute('src') || '',
      naturalWidth: i.naturalWidth,
      naturalHeight: i.naturalHeight,
      embed: i.dataset.nfMdEmbed || '',
      path: i.dataset.nfMdPath || '',
      err: i.dataset.nfMdErr || '',
    }));
    const fails = Array.from(pane.querySelectorAll('.nf-md-img-fail')).map((e) => ({
      path: (e.querySelector('.nf-md-img-fail-path') || {}).textContent || '',
      reason: (e.querySelector('.nf-md-img-fail-reason') || {}).textContent || '',
      status: e.getAttribute('data-nf-md-img-status') || '',
    }));
    const text = pane.innerText || '';
    const literal = (text.match(/!\[\[[^\]\n]+\]\]/g) || []);
    // 围栏内嵌图的**真实落点**（实测形态，不是字面量）：它被 renderWikilinks
    // 当成「指向一个叫 x.png 的文档」的引用，产出未解析 wikilink 锚（蓝字死链，
    // title=「未解析的引用」）。所以「围栏内那张图」的判据 = 一个 target 以图片
    // 扩展名结尾的未解析锚，而不是 `<img>`、也不是字面量。
    const danglingImgAnchors = Array.from(
      pane.querySelectorAll('a[data-wiki-target]'),
    ).filter((a) => /\.(png|jpe?g|gif|webp|svg|avif|bmp)$/i.test(a.dataset.wikiTarget || ''))
      .map((a) => ({ target: a.dataset.wikiTarget, kind: a.dataset.wikiTermKind || '' }));
    // 首屏可见性：卡片机制把**围栏外**内容折进 <details class="nf-human-details">，
    // 这里量「不在折叠抽屉里的图」有几张（本批不改前端，读数照实记）。
    const visibleImgs = Array.from(pane.querySelectorAll('img')).filter((i) => {
      const d = i.closest('details');
      return !d || d.hasAttribute('open');
    }).length;
    const drawers = Array.from(pane.querySelectorAll('details.nf-human-details'))
      .map((d) => ({ label: (d.querySelector('summary') || {}).textContent || '', open: d.hasAttribute('open') }));
    return { imgs, fails, literal, danglingImgAnchors, visibleImgs, drawers, html: pane.innerHTML.length,
      scrolls: Array.from(document.querySelectorAll('.canvas-md-scroll'))
        .map((n) => ({ parent: n.parentElement && n.parentElement.id, len: (n.innerText || '').length })) };
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
  mkdirSync(OUT_DIR, { recursive: true });

  const docAbs = join(VAULT, DOC_REL);
  if (!existsSync(docAbs)) { console.error(`环境错误：文档不存在 ${docAbs}`); process.exit(2); }
  const afterMd = readFileSync(docAbs, 'utf8');
  const beforeMd = process.env.WIKI_IMG_BEFORE_MD && existsSync(process.env.WIKI_IMG_BEFORE_MD)
    ? readFileSync(process.env.WIKI_IMG_BEFORE_MD, 'utf8')
    : null;

  const goodPath = `${BATCH_DIR}/after-states-dark.png`;
  const isolated = {
    after: `# 隔离样例 · 路径对 + 围栏外\n\n![[${goodPath}]]\n\n图 1 应当出图。\n`,
    inFence: `# 隔离样例 · 路径对 + 围栏内\n\n\`\`\`human\n## 结论\n路径对但写在围栏里。\n\n![[${goodPath}]]\n\`\`\`\n\n图 1 围栏外没有图。\n`,
    bareName: `# 隔离样例 · 裸名 + 围栏外\n\n![[after-states-dark.png]]\n\n图 1 应当失败。\n`,
  };

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1200 }, colorScheme: 'dark', deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await installRoutes(page);
  await page.goto('http://localhost:1/__harness.html');
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
  await page.waitForLoadState('load');

  const shots = [];
  const shot = async (name, maxCss = 1600) => {
    const path = join(OUT_DIR, `${name}.png`);
    // 视口先拉到「整篇内容的高度」再拍：元素截图一旦需要竖向拼接，跨段会带出
    // 上一张的残影（本批踩过一次），所以这里把滚动容器定高成内容高、一次成像。
    await page.setViewportSize({ width: 1440, height: 1200 });
    const need = await page.evaluate(() => {
      const s = document.querySelector('.canvas-md-scroll');
      if (s) { s.style.height = ''; s.style.maxHeight = ''; s.style.overflow = ''; s.scrollTop = 0; }
      return Math.ceil(s ? s.scrollHeight : document.body.scrollHeight);
    });
    const H = Math.min(maxCss, Math.max(1200, need));
    await page.setViewportSize({ width: 1440, height: H });
    await page.evaluate((hh) => {
      const s = document.querySelector('.canvas-md-scroll');
      if (s) { s.style.height = hh + 'px'; s.style.maxHeight = 'none'; s.style.overflow = 'visible'; s.scrollTop = 0; }
      window.scrollTo(0, 0);
    }, H);
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
    const scroll = await page.$('.canvas-md-scroll');
    if (scroll) await scroll.screenshot({ path, animations: 'disabled' });
    else await page.screenshot({ path });
    shots.push(path);
    return path;
  };

  // ── A 改后真文档 ────────────────────────────────────────────────────
  const a = await renderCase(page, { content: afterMd, absPath: docAbs, fileName: DOC_REL });
  await shot('after-doc-rendered');
  const aLoaded = a.imgs.filter((i) => i.naturalWidth > 0).length;
  assert('A 改后文档：9 张图全部出图', a.imgs.length === 9 && aLoaded === 9,
    `img=${a.imgs.length} 出图=${aLoaded} 失败占位=${a.fails.length} 死链锚=${a.danglingImgAnchors.length}`);
  assert('A 改后文档：零「图片加载失败」占位', a.fails.length === 0,
    a.fails.map((f) => f.path + ' ' + f.reason).join(' | '));
  assert('A 改后文档：零围栏内死链锚（R18 已清）', a.danglingImgAnchors.length === 0,
    a.danglingImgAnchors.map((x) => x.target).join(' | '));
  console.log(`NOTE  A 首屏可见图 ${a.visibleImgs}/${a.imgs.length}`
    + `（卡片机制把围栏外内容折进抽屉：${a.drawers.map((d) => d.label).join(' · ')}）`
    + ' —— 前端行为，本批不改，读数照实记录');
  // 证据第二张：把抽屉全部展开后再拍 —— 证明「图真的出」，而不是「图在抽屉里看不见就不算」。
  await page.evaluate(() => {
    document.querySelectorAll('details').forEach((d) => d.setAttribute('open', ''));
  });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  await shot('after-doc-rendered-open');
  // 再补一张「单张图本身」的证据：整页截图 2 万像素高，进文档只能看到一条细缝。
  const firstImg = await page.$('.canvas-md-scroll img');
  if (firstImg) {
    const p = join(OUT_DIR, 'after-doc-figure.png');
    await firstImg.screenshot({ path: p });
    shots.push(p);
  }

  // ── B 改前真文档（对照）────────────────────────────────────────────
  if (beforeMd) {
    const b = await renderCase(page, { content: beforeMd, absPath: docAbs, fileName: DOC_REL });
    await shot('before-doc-rendered');
    const bLoaded = b.imgs.filter((i) => i.naturalWidth > 0).length;
    // 实报障文档的三处裸名候选在**铸票面**就被全部驳回（六格全不存在）⇒ 落三块
    // 「图片加载失败」占位；围栏内那张（路径正确、写在 ```human 里）自 2026-11-05
    // 卡片出图批起**出图**。四张引用 ⇒ 1 个 <img>（卡内）+ 3 块占位。
    assert('B 改前文档：围栏内那张出图，三处裸名仍是失败占位',
      b.imgs.length === 1 && bLoaded === 1 && b.fails.length === 3
      && b.danglingImgAnchors.length === 0,
      `img=${b.imgs.length} 出图=${bLoaded} 失败占位=${b.fails.length} 死链锚=${b.danglingImgAnchors.length}`);
    assert('B 改前文档：三块失败占位（R17 裸名）', b.fails.length === 3,
      b.fails.map((f) => f.path).join(' | '));
  }

  // ── C 隔离样例（一条一条证明断点）──────────────────────────────────
  const c1 = await renderCase(page, { content: isolated.after, absPath: docAbs, fileName: 'iso-after.md' });
  await shot('iso-path-outside-fence');
  assert('C1 路径对 + 围栏外 ⇒ 出图',
    c1.imgs.length === 1 && c1.imgs[0].naturalWidth > 0 && c1.fails.length === 0,
    `img=${c1.imgs.length} nw=${c1.imgs[0] && c1.imgs[0].naturalWidth}`);

  const c2 = await renderCase(page, { content: isolated.inFence, absPath: docAbs, fileName: 'iso-fence.md' });
  await shot('iso-path-inside-fence');
  // 2026-11-05 卡片出图批：围栏内嵌图**放行**（围栏=卡片体），路径对就出图，
  // 而且出在卡片里（首屏可见）。原判据「不出图、只留死链锚」已作废。
  assert('C2 路径对 + 围栏内 ⇒ 出图（卡片体里，首屏可见）',
    c2.imgs.length === 1 && c2.imgs[0].naturalWidth > 0 && c2.danglingImgAnchors.length === 0
    && c2.fails.length === 0,
    `img=${c2.imgs.length} nw=${c2.imgs[0] && c2.imgs[0].naturalWidth}`
    + ` 死链锚=${c2.danglingImgAnchors.length} 失败占位=${c2.fails.length}`);

  const c3 = await renderCase(page, { content: isolated.bareName, absPath: docAbs, fileName: 'iso-bare.md' });
  await shot('iso-bare-name');
  const c3Failed = c3.fails.length === 1 || (c3.imgs.length === 1 && c3.imgs[0].naturalWidth === 0);
  assert('C3 裸名 + 围栏外 ⇒ 出不了图（R17）', c3Failed && c3.imgs.every((i) => i.naturalWidth === 0),
    `img=${c3.imgs.length} 出图=${c3.imgs.filter((i) => i.naturalWidth > 0).length} 失败占位=${c3.fails.length}`);

  // ── D 本批报告自身也要「图真的出」（纪律对自己生效）─────────────────
  const REPORT_REL = 'wiki-img-embed-and-figure-first__20261105_225600.md';
  const reportAbs = join(VAULT, REPORT_REL);
  if (existsSync(reportAbs)) {
    const d = await renderCase(page, {
      content: readFileSync(reportAbs, 'utf8'), absPath: reportAbs, fileName: REPORT_REL,
    });
    await shot('report-self-rendered');
    const dLoaded = d.imgs.filter((i) => i.naturalWidth > 0).length;
    assert('D 本报告自身：4 张图全部出图', d.imgs.length === 4 && dLoaded === 4,
      `img=${d.imgs.length} 出图=${dLoaded} 失败占位=${d.fails.length} 死链锚=${d.danglingImgAnchors.length}`);
    assert('D 本报告自身：首屏可见图 = 全部（不用 human 围栏 ⇒ 不被折进抽屉）',
      d.visibleImgs === d.imgs.length && d.drawers.length === 0,
      `首屏可见=${d.visibleImgs}/${d.imgs.length} 抽屉=${d.drawers.length}`);
  }

  const evidence = {
    generated_at: new Date().toISOString(),
    web_root: WEB,
    vault: VAULT,
    doc: DOC_REL,
    cases: { after: a, before: beforeMd ? undefined : null, isolated: { after: c1, in_fence: c2, bare_name: c3 } },
    shots: shots.map((p) => basename(p)),
    results,
  };
  writeFileSync(join(OUT_DIR, 'evidence.json'), JSON.stringify(evidence, null, 2));

  await browser.close();
  const failed = results.filter((r) => !r.ok).length;
  console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
  console.log(`截图与证据：${OUT_DIR}`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('装置异常：', e && e.stack || e);
  process.exit(2);
});
