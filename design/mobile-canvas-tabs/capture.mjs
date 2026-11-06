#!/usr/bin/env node
// capture.mjs — 手机端 Canvas 顶部标签页「现状」取证 + 三方案底衬截图（设计批，不改产品代码）
//
// 装置：零端口、零进程 —— page.route 从磁盘服务 worktree 的 web 树 + MockWS 桩帧
// （配方同 scripts/lib/visual-harness.mjs，本脚本自建 context 只为能把
//  deviceScaleFactor 提到 2：字号 15px 的字形核在 DSF1 下取样面不够）。
//
// 产出（design/mobile-canvas-tabs/shots/）：
//   current-<theme>.png   现状：环签 + 两个悬浮圆钮 + 正文从签底下穿过（375×812 @2x）
//   backdrop-<theme>.png  同一帧**摘掉环签**——三方案对照页的底衬（真实页面，不是灰块）
//   probe.json            几何/计算样式读数（条高、签盒、钮盒、链变量、滚动位）
//
// 用法：node design/mobile-canvas-tabs/capture.mjs

import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, settle, webRoot } from '../../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'shots');
mkdirSync(OUT, { recursive: true });

const VIEWPORT = { width: 375, height: 812 };
const DSF = 2;

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8', '.webmanifest': 'application/manifest+json',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.map': 'application/json',
  '.wasm': 'application/wasm',
};

// ── 夹具：三张真实感的文档卡（正文够长，能滚到签底下）────────────────────
const DOCS = [
  {
    name: '设计规格.md',
    body: [
      '# 手机端 Canvas 顶部标签页 · 设计规格',
      '',
      '&emsp;&emsp;这份规格写的是手机端 Canvas 页顶部那一条标签栏：它现在怎么长、要改成什么样、改动面落在哪几个文件。阅读顺序是先看形态结论，再看几何与令牌。',
      '',
      '## 一、形态结论',
      '',
      '&emsp;&emsp;标签栏只承担一件事：**在已打开的文档之间换页**。它不承载关闭、不承载堆叠、不承载计数——那三件事分别由左上的叉号键、右上的堆叠键、底部的三点指示器承担。',
      '',
      '## 二、几何',
      '',
      '| 项 | 值 | 说明 |',
      '|---|---|---|',
      '| 条高 | 72px | 簇顶 16 + 钮 48 + 呼吸 8 |',
      '| 签位 | 顶 40 | 签心 = 圆钮垂直中心 |',
      '| 邻签 | 66×32 | 缩径 0.87 |',
      '| 中签 | 112×32 | 缩径 1.10 |',
      '',
      '## 三、滚动',
      '',
      '&emsp;&emsp;正文从签底下穿过：初态让位 72px（首行不被遮挡），之后一路可以滚到顶边，滚过去的行就压在玻璃面底下。',
    ].join('\n'),
  },
  {
    name: '批报告.md',
    body: [
      '# 批报告 · 顶部标签栏重做',
      '',
      '&emsp;&emsp;本批把环签的位姿从「流内 72px 首件」改成「离流绝对层 + 带零视觉底」，正文滚动面因此吃满整页，签底下可以过字。',
      '',
      '## 读数',
      '',
      '- 环带高 72，签心 y=40，与叉号 / 堆叠两钮同水平线（垂直中心极差 ≤1px）',
      '- 邻签朝中心半幅可读 11.76:1（暗）/ 11.95:1（亮），字形核中位 10.52 / 9.47',
      '- 中签 14.45:1（暗）/ 16.08:1（亮）',
      '- 命中盒 44×44（伪元素反向缩放，热区不随环位姿缩）',
      '',
      '## 已知问题',
      '',
      '&emsp;&emsp;邻签的毛玻璃层被 mask 与 backdrop 的合成器冲突挤掉了：同一元素上 mask-image 与 backdrop-filter 并存时，遮罩会被架空，于是 blur 只留给无 mask 的中签。作者看到的「侧签不透」就是这个读数。',
      '',
      '## 待办',
      '',
      '&emsp;&emsp;标签栏的玻璃形态要重新定：是把整条带做成一层面，还是把签收进一枚胶囊，还是让边缘化开。三条路的结构、层级与滚动行为都不一样。',
    ].join('\n'),
  },
  {
    name: '验收记录.md',
    body: [
      '# 验收记录 · 375×812',
      '',
      '&emsp;&emsp;基准确认：375 宽为本批的基准档，安全区不参与截图但几何上必须算进去（顶部净空 58 那套另有一条 --cv-safe-top）。这一份记录写满两屏，好让正文真的从顶部标签栏底下滚过去——穿过态才是这一批要看的形态。',
      '',
      '## 四拍',
      '',
      '- 375 亮 / 375 暗 / 390 亮 / 390 暗',
      '- 焦点环不参与（玻璃面无焦点态）',
      '- 滚动位置：初态 0 / 穿过态约 118',
      '- 触屏证据只认真触屏（CDP 输入），指针模拟不算',
      '',
      '## 判据',
      '',
      '&emsp;&emsp;文字对比度按 WCAG 正文 4.5:1 判；签与圆钮的中线对齐按极差 ≤1px 判；可点热区按 44×44 判。玻璃面一律只许消费 --nf-glass-* 那一套令牌，门禁脚本必须 0 违规。',
      '',
      '&emsp;&emsp;对比度不按令牌折算，按真渲染像素取：同一裁剪区拍两帧（一帧正常、一帧把文字藏起来），逐像素配对，前帧 = 文字的实际合成色、后帧 = 同点无字时的背景色，再按相对亮度公式算比值。按令牌算出来的数只说明设计意图，说明不了合成之后的样子。',
      '',
      '## 几何读数',
      '',
      '| 项 | 读数 | 判据 |',
      '|---|---|---|',
      '| 环带 | 375×72 @ y0 | 与簇几何同高 |',
      '| 邻签 | 57.4×27.8 @ y26.1 | 签心 40 |',
      '| 中签 | 119.5×35.2 @ y22.4 | 签心 40 |',
      '| 圆钮 | 48×48 @ y16 / x16·x311 | 签心 = 钮心 |',
      '| 命中盒 | 44×44（反向缩放） | ≥44 |',
      '',
      '## 尾注',
      '',
      '&emsp;&emsp;这一份记录只写读数，不写结论——结论在批报告里。下一段是留给滚动用的填充：签底下的字要能被看清、也要能被糊掉，这两件事得同时成立才算形态对了。',
      '',
      '&emsp;&emsp;填充段之二：一条标签栏能不能读，先看它的字压在什么东西上。压在纯色上，任何一块半透明底都够；压在正文上，就只剩两条路——要么把底做得足够不透明（字看得见，但颜色进不来），要么把背面的内容真的糊掉（颜色进得来，字看不出来）。这两条路的切换点就是模糊半径与底色透明度的分工。',
      '',
      '&emsp;&emsp;填充段之三：翻页这件事在手机上没有 hover 可借，位姿只能靠常驻的层级表达。谁是当前签，只能靠尺寸、位置、实色三种手段之一或组合来说，不能靠「鼠标停在上面才亮」这种桌面语言。',
    ].join('\n'),
  },
];

async function makeSession(browser, { colorScheme }) {
  const page = await browser.newPage({
    viewport: VIEWPORT,
    deviceScaleFactor: DSF,
    colorScheme,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  });

  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e && e.message ? e.message : e).slice(0, 300)));

  await page.addInitScript(() => {
    try {
      localStorage.setItem('nebflow_token', 'design-batch-token');
      localStorage.setItem('neblink_token', 'design-batch-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('neblink_locale', 'zh-CN');
      localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
      localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    } catch { /* ignore */ }
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) {
        this.url = u;
        this.readyState = MockWS.CONNECTING;
        window.__wsMock = this;
        window.__wsSent = [];
      }
      send(data) {
        let f = null;
        try { f = JSON.parse(data); } catch { /* ignore */ }
        if (f) window.__wsSent.push(f);
        if (f && f.type === 'listDir') {
          const reply = { type: 'dirListing', path: f.path, resolvedPath: f.path, entries: [] };
          setTimeout(() => { try { this.onmessage && this.onmessage({ data: JSON.stringify(reply) }); } catch { /* ignore */ } }, 0);
        }
      }
      close() { this.readyState = MockWS.CLOSED; }
      onopen = null; onmessage = null; onclose = null; onerror = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  });

  const root = normalize(webRoot());
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    const file = normalize(join(root, p));
    if (!file.startsWith(root)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });

  const frame = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);

  await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
  await page.evaluate(() => {
    window.__wsMock.readyState = 1;
    window.__wsMock.onopen && window.__wsMock.onopen();
  });
  await page.waitForTimeout(250);
  const sid = 'canvas-tabs-root';
  await frame({ type: 'sessionList', sessionId: sid, activeId: sid, folders: [], sessions: [{ id: sid, name: 'Nebula', agentName: 'Nebula' }] });
  await frame({ type: 'configData', config: JSON.stringify({ llm: { providers: {} }, features: { friends: false } }), configured: true, onboarding: 'done' });
  await frame({ type: 'historyPage', sessionId: sid, messages: [], hasMore: false, offset: 0 });
  await settle(page);
  return { page, errors };
}

// 打开文档卡（产品入口 = workspace-open-item 事件，不直写 DOM）
async function openDoc(page, doc) {
  await page.evaluate((d) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: `file:/tmp/canvas-tabs/${d.name}`, title: d.name, itemType: 'markdown',
        content: d.body, absPath: `/tmp/canvas-tabs/${d.name}`, pinned: true,
      },
    }));
  }, doc);
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:/tmp/canvas-tabs/${doc.name}"]`, { timeout: 20000, state: 'attached' });
}

async function goCanvas(page) {
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  for (let i = 0; i < 4; i++) {
    const live = await page.evaluate(() => {
      const p = document.querySelector('.mobile-page[data-page="canvas"]');
      return !!(p && p.classList.contains('live'));
    });
    if (live) break;
    await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click().catch(() => {});
    await page.waitForTimeout(700);
  }
  await page.waitForSelector('.mf-strip .mf-strip-chip', { timeout: 15000 });
  await settle(page, 600);
}

// 滚到「正文正压在签底下」的位置（穿过态）
async function scrollUnder(page, px) {
  const got = await page.evaluate((want) => {
    const sc = document.querySelector('.mf-cv-host .canvas-md-scroll')
      || document.querySelector('.mf-cv-host .canvas-txt-scroll')
      || document.querySelector('.canvas-tab-pane.active .canvas-md-scroll')
      || document.querySelector('.canvas-md-scroll');
    if (!sc) return null;
    sc.scrollTop = want;
    return { st: sc.scrollTop, sh: sc.scrollHeight, ch: sc.clientHeight };
  }, px);
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  await page.waitForTimeout(200);
  return got;
}

const PROBE = `(() => {
  const R = (n) => Math.round(n * 100) / 100;
  const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return r.width || r.height ? { x: R(r.x), y: R(r.y), w: R(r.width), h: R(r.height), right: R(r.right), bottom: R(r.bottom) } : null; };
  const cs = (el, p) => { const s = getComputedStyle(el, p || undefined); return {
    bg: s.backgroundColor, bgImage: s.backgroundImage, border: s.borderTopWidth + ' ' + s.borderTopColor,
    backdrop: s.backdropFilter || s.webkitBackdropFilter || 'none',
    mask: s.maskImage || s.webkitMaskImage || 'none',
    color: s.color, fontSize: s.fontSize, fontWeight: s.fontWeight, opacity: s.opacity,
    filter: s.filter, boxShadow: s.boxShadow, borderRadius: s.borderRadius, padding: s.padding,
    transform: s.transform, position: s.position, zIndex: s.zIndex, overflow: s.overflow,
  }; };
  const strip = document.querySelector('.mf-strip');
  const chips = strip ? [...strip.querySelectorAll('.mf-strip-chip')] : [];
  const root = getComputedStyle(document.documentElement);
  const body = getComputedStyle(document.body);
  const tokens = {};
  for (const p of [...root]) if (p.startsWith('--')) tokens[p] = root.getPropertyValue(p).trim();
  // 手机上还有一层 body.mobile-layer 令牌（--mchrome-* / --mtap* / --mpad / --mfs-*）——
  // 这些定义在 body 上，:root 取不到，必须并进来（否则对照页里 calc() 全失效）
  const bodyTokens = {};
  for (const p of [...body]) if (p.startsWith('--')) bodyTokens[p] = body.getPropertyValue(p).trim();
  const sc = document.querySelector('.mf-cv-host .canvas-md-scroll')
    || document.querySelector('.mf-cv-host .canvas-txt-scroll')
    || document.querySelector('.canvas-tab-pane.active .canvas-md-scroll')
    || document.querySelector('.canvas-md-scroll');
  const blocks = sc ? [...sc.querySelectorAll('h1,h2,p,li,table')].map((el) => ({ tag: el.tagName, box: box(el), text: (el.textContent || '').slice(0, 24) })) : [];
  const fabs = [...document.querySelectorAll('.mf-cv-toolbar .g2-fab')].map((el) => ({ cls: el.className, box: box(el), style: cs(el) }));
  return {
    vw: window.innerWidth, vh: window.innerHeight, dpr: window.devicePixelRatio,
    theme: document.documentElement.dataset.theme || '(auto)',
    prefersDark: matchMedia('(prefers-color-scheme: dark)').matches,
    vars: {
      mchromeTop: body.getPropertyValue('--mchrome-top').trim(),
      mchromeBtn: body.getPropertyValue('--mchrome-btn').trim(),
      mchromeClear: body.getPropertyValue('--mchrome-clear').trim(),
      mchromeInset: body.getPropertyValue('--mchrome-inset').trim(),
      cvSafeTop: body.getPropertyValue('--cv-safe-top').trim(),
      glassBg: root.getPropertyValue('--nf-glass-bg').trim(),
      glassBlur: root.getPropertyValue('--nf-glass-blur').trim(),
      glassSat: root.getPropertyValue('--nf-glass-saturate').trim(),
      glassBorder: root.getPropertyValue('--nf-glass-border-color').trim(),
      glassFilter: root.getPropertyValue('--nf-glass-filter').trim(),
      dsText: root.getPropertyValue('--ds-text').trim(),
      mfsSub: root.getPropertyValue('--mfs-sub').trim(),
    },
    strip: strip ? { box: box(strip), style: cs(strip) } : null,
    tokens,
    bodyTokens,
    chips: chips.map((c) => ({
      id: c.dataset.tabId, on: c.classList.contains('on'), cls: c.className,
      text: (c.textContent || '').trim(), box: box(c), style: cs(c), after: cs(c, '::after'),
      label: cs(c.querySelector('.mf-strip-label')),
    })),
    toolbar: fabs,
    scroller: sc ? { st: sc.scrollTop, sh: sc.scrollHeight, ch: sc.clientHeight, box: box(sc), padTop: getComputedStyle(sc).paddingTop } : null,
    blocks: blocks.filter((b) => b.box && b.box.bottom > -40 && b.box.y < 200),
  };
})()`;

const { mod: pw } = loadPlaywright();
const { browser, label } = await launchBrowser(pw);
console.log(`capture: web=${webRoot()} browser=${label} out=${OUT}`);

const manifest = { generatedAt: new Date().toISOString(), browser: label, viewport: VIEWPORT, dsf: DSF, themes: {} };

for (const theme of ['light', 'dark']) {
  const s = await makeSession(browser, { colorScheme: theme });
  const { page } = s;

  await page.route('**/api/nf-file**', (route) => {
    const u = decodeURIComponent(route.request().url());
    const doc = DOCS.find((d) => u.includes(d.name)) || DOCS[0];
    return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: doc.body });
  });

  for (const doc of DOCS) await openDoc(page, doc);
  await goCanvas(page);

  const count = await page.evaluate(() => document.querySelectorAll('.mf-strip .mf-strip-chip.on').length);
  const st = await scrollUnder(page, 118);
  console.log(`  ${theme}: chips-on=${count} scrollTop=${st}`);

  const currentPath = join(OUT, `current-${theme}.png`);
  writeFileSync(currentPath, await page.screenshot({ animations: 'disabled', caret: 'hide', scale: 'css' }));
  const probe = await page.evaluate(PROBE);

  // 底衬：同一帧摘掉环签（三方案对照页的底衬 = 真实页面，不是灰块）
  await page.evaluate(() => { const el = document.querySelector('.mf-strip'); if (el) el.style.visibility = 'hidden'; });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  const backdropPath = join(OUT, `backdrop-${theme}.png`);
  writeFileSync(backdropPath, await page.screenshot({ animations: 'disabled', caret: 'hide', scale: 'css' }));
  await page.evaluate(() => { const el = document.querySelector('.mf-strip'); if (el) el.style.visibility = ''; });

  // 方案 A 的底衬：连两枚悬浮圆钮一起摘掉（A 把两钮降为带内扁平键，底板不能带旧玻璃钮）
  await page.evaluate(() => {
    const strip = document.querySelector('.mf-strip');
    const toolbar = document.querySelector('.mf-cv-toolbar');
    if (strip) strip.style.visibility = 'hidden';
    if (toolbar) toolbar.style.visibility = 'hidden';
  });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  writeFileSync(join(OUT, `backdrop-nochrome-${theme}.png`), await page.screenshot({ animations: 'disabled', caret: 'hide', scale: 'css' }));
  await page.evaluate(() => {
    const strip = document.querySelector('.mf-strip');
    const toolbar = document.querySelector('.mf-cv-toolbar');
    if (strip) strip.style.visibility = '';
    if (toolbar) toolbar.style.visibility = '';
  });

  manifest.themes[theme] = {
    current: `shots/current-${theme}.png`,
    backdrop: `shots/backdrop-${theme}.png`,
    probe,
    pageErrors: s.errors,
  };
  writeFileSync(join(OUT, 'probe.json'), JSON.stringify(manifest, null, 2));
  console.log(`  ${theme}: probe strip=${JSON.stringify(probe.strip?.box)} chips=${probe.chips.map((c) => `${c.text}@${c.box.w}x${c.box.h}`).join(' | ')}`);
  await s.page.close();
}

writeFileSync(join(OUT, 'probe.json'), JSON.stringify(manifest, null, 2));
console.log('capture: done');
await browser.close();
