#!/usr/bin/env node
// tests/canvas-tab-reference.spec.mjs — 手机看板页「引用」钮门禁
// （canvas-tab-reference 批 2026-11-18；作者令逐字：
//   「手机端我现在无法引用 canvas 标签页 我需要点击标签页顶部 展开之后 我可以有一个
//    引用按钮 现在是一个箭头 没什么意义」）
//
// 为什么要有这个闸：这一面（展开签内的引用钮）改前是**看起来在、手指点不到**的控件
// —— 断言若只读 DOM / 只读 chip 出现，两种改前形态（死控件 / 装饰符箭头）都会全绿。
// 所以本闸全部打在**真手指路径 + 会话真收**上：
//
//   R1  现状定位：展开态引用钮在挂、可见、**渲染命中盒** ≥44×44（手机触控下限）
//   R2  明确含义（图标钮）：钮内可见面 = 单源引用入口 svg（`data-icon="quote-entry"`）、
//       **零文本子节点 / 零可见文字**（作者令 2026-11-18 22:10「只有一个图标…不需要文字」），
//       名字靠 `aria-label`；箭头装饰符 `↗` 整条退役（textContent 不含）
//   R3  指针可达：钮盒内 5 点 elementFromPoint 顶层 ∈ {钮自身, 钮的后代}
//   R12 可见面零边框：元素本体 + 两伪元素逐点断 border-width 0 ∧ border-style none
//       （作者令 2026-11-18 飞书「canvas引用按钮不要边框」）
//   R4  真手指点得到：**touchscreen tap** 钮 → composer 附件条出引用 chip
//   R5  真引用身份：该 chip 的 refType / refId / 标签名 / 源路径 = 被点那一签的身份
//   R6  会话真收到：提交后 WS 帧 refs[0].source.path = 该看板文件的绝对路径
//   R7  无 composer 不静默：activeView 置空后点钮 → 屏上出现明确提示（既有 toast）
//   R8  不误触：点签名（收起）/ 签条横滑 都不得产生引用 chip
//   R9  展开态既有内容与几何回归：展开 ≥ 常态宽、钮 ⊆ 签、展开签不与两侧悬浮钮交叠
//       （canvas-overlap-guard 2026-11-17 既有合同）
//   R10 桌面不回归：桌面腿零手机控件 + 既有右键「引用当前页」通路照旧出 chip
//   R11 无障碍名字按 locale（en 档 = "Quote this Canvas"）：图标钮无可见文字，
//       名字全在 aria-label 上，而 aria-label 是 locale 派生的 ⇒ 不能只验 zh 档
//
// ── 唯一引用通路（零第三套引用体系）────────────────────────────────────────
// 本钮 → `quoteActiveCardRef()` →（canvas.js）`refForTab` → `appendRefToActiveView`
// —— 与桌面右键「引用当前页」同一条通路；chip 组件 = composer 附件条既有
// `.att-ref`（js/reference.js）。另一支 feat/todo-card-click-left 的「点待办卡片补
// `#` 待办引用」走同一 `appendRefToActiveView`。本闸只认这条通路的产物（`.att-ref`
// 与 WS 帧 refs），任何第三套引用形态都不会被本闸认账。
//
// 用法：
//   node tests/canvas-tab-reference.spec.mjs                 # 门禁（默认仓内 web 树）
//   node tests/canvas-tab-reference.spec.mjs --web=<dir>     # 跑在另一棵检出上（改前实证）
//   node tests/canvas-tab-reference.spec.mjs --selftest      # 注入红自检（见下）
//   node tests/canvas-tab-reference.spec.mjs --shots=<dir>   # 另存 A/B 帧
// 退出码：0 = 全绿（--selftest 下 = 注入后**确实**报红）；1 = 有 FAIL（--selftest 下 =
//         注入后仍绿 ⇒ 假绿）；2 = 装置错误。
//
// 装置：零宿主接触 —— 本地静态服务器（默认端口 18471）+ 全本地 WS mock（routeWebSocket，
// 走真实 ws.js 分发路径）；结构性不可能碰到宿主 6138/8080/8097。

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const argOf = (k, d) => {
  const a = process.argv.find((x) => x.startsWith(`--${k}=`));
  return a ? a.slice(k.length + 3) : d;
};
const SELFTEST = process.argv.includes('--selftest');
const WEB_DIR = resolve(argOf('web', process.env.NEBFLOW_WEB_ROOT || join(ROOT, 'src/main/resources/web')));
const PORT = Number(argOf('port', 18471));
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = argOf('shots', join(ROOT, 'evidence', 'canvas-tab-reference'));
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const R2 = (n) => Math.round(n * 100) / 100;

// ── 夹具：三篇**长标题** markdown（长签 = 展开档宽度压力面）─────────────────
const FIX = '/tmp/nf-ctab-ref-gate';
mkdirSync(FIX, { recursive: true });
const NAMES = [
  '统一面板迁移总览与验收看板含截稿清单',
  '知识图谱多库合并与索引重建看板',
  '会话身份头同行批回归清单看板',
];
const DOCS = NAMES.map((n) => join(FIX, `${n}.md`));
DOCS.forEach((d, i) => writeFileSync(d, `# ${NAMES[i]}\n\n第 ${i + 1} 篇正文段落。\n`));
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.ico': 'image/x-icon',
};
if (!existsSync(join(WEB_DIR, 'index.html'))) {
  console.error(`装置错误：--web 指向的树里没有 index.html（${WEB_DIR}）`);
  process.exit(2);
}
const server = createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, BASE).pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  let buf;
  try { buf = readFileSync(file); } catch { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
  res.end(buf);
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// 注入红自检：把本批修的两条病根**原样**注回修后树（同特异性、文档末位 ⇒ 生效）。
//  ① 命中层修复回退（position static + z-index auto）⇒ 真手指点不到 ⇒ R3/R4 必红；
//  ② 钮内图标摘掉（`↗` 换成图标是本批的②；摘掉图标 = 回到零含义）⇒ R2 必红。
// 门禁若注了这两条还全绿 = 假绿（断言没打在真手指/真图标上）。
const INJECT_BREAK = `
body.mobile-layer .mf-strip-quote { position: static; z-index: auto; }
body.mobile-layer .mf-strip-quote svg { display: none; }
`;
// ③（R13 闸的注入）：xmlns 缺失的渲染后果 = path 失去描边、落黑填充 ⇒ R13 必红。
const INJECT_XMLNS_BREAK = `
body.mobile-layer .mf-strip-quote path { stroke: none; fill: #000; }
`;

const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}

const FILES = new Map(DOCS.map((d) => [d, 'markdown']));
const pwRes = loadPlaywright();
const pw = pwRes.mod.playwright || pwRes.mod;
const { chromium } = pw;
const browser = await chromium.launch();
const jserr = [];

/** 起一档视口（手机 hasTouch 才能走真 touchscreen tap）。locale 可按档指定：
 *  钮是**图标钮**（无可见文字），名字源是 locale 派生的 `aria-label`（zh「引用此看板」/
 *  en「Quote this Canvas」）⇒ 无障碍名字必须在两档 locale 下各验一次（R2 = zh 档，
 *  R11 = en 档）。 */
async function boot({ width, height, mobile, locale = 'zh-CN' }) {
  const ctx = await browser.newContext({
    viewport: { width, height }, colorScheme: 'dark', locale, hasTouch: !!mobile,
  });
  await ctx.addInitScript((loc) => {
    for (const k of ['nebflow', 'neblink']) {
      localStorage.setItem(k + '_token', 't');
      localStorage.setItem(k + '_locale', loc);
      localStorage.setItem(k + '_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    }
    localStorage.setItem('stand_sw', '0');
  }, locale);
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
  const sent = [];
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      sent.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const t = FILES.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path, content: t ? readFileSync(m.path, 'utf8') : '',
          itemType: t || 'markdown', fileName: (m.path || '').split('/').pop(), size: 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => jserr.push(`${width}x${height} pageerror: ${String(e.message || e).slice(0, 160)}`));
  await page.goto(`${BASE}/index.html`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 20000 });
  await sleep(900);
  return { ctx, page, sent };
}

const openDoc = async (page, p, pinned) => {
  await page.evaluate(({ path, pin }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + path, itemType: 'markdown', title: path.split('/').pop().replace(/\.md$/, ''), absPath: path, pinned: pin },
    }));
  }, { path: p, pin: !!pinned });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${p}"] .canvas-md-scroll`, { timeout: 15000, state: 'attached' });
  await sleep(450);
};

// 引用钮读数（本闸的唯一读数口；R1/R2/R3 都读它）。
const QUOTE_PROBE = `(() => {
  const R = n => Math.round(n * 100) / 100;
  const chip = document.querySelector('.mf-strip-chip.is-expanded');
  if (!chip) return { found: false, expanded: false };
  const q = chip.querySelector('.mf-strip-quote');
  if (!q) return { found: false, expanded: true };
  const r = q.getBoundingClientRect();
  const cs = getComputedStyle(q);
  const rsc = parseFloat(chip.style.getPropertyValue('--rsc')) || 1;
  const label = q.querySelector('.mf-strip-quote-label');   // 已退役：恒 null（负向对账）
  /** 钮子元素里的**文本节点**集合（图标钮必须为空：无文本子节点、零可见文字）。 */
  const textNodes = (() => {
    const out = [];
    const walk = (n) => {
      for (const c of n.childNodes) {
        if (c.nodeType === 3) { if ((c.nodeValue || '').trim()) out.push(c.nodeValue); }
        else if (c.nodeType === 1) walk(c);
      }
    };
    walk(q);
    return out;
  })();
  /** 可访问名字（aria-label 优先；无则退到 role=button 的 textContent）——唯一名字源。 */
  const accName = (q.getAttribute('aria-label') || '').trim();
  // 指针可达：钮盒内 5 点（中心 + 四分之一内点）顶层命中
  const pts = [0.5, 0.25, 0.75].flatMap(fx => [0.35, 0.65].map(fy => ({ fx, fy })));
  const hits = pts.map(({ fx, fy }) => {
    const el = document.elementFromPoint(r.left + r.width * fx, r.top + r.height * fy);
    return el ? (el === q || q.contains(el) ? 'quote' : el.tagName + '.' + String(el.className).slice(0, 30)) : 'null';
  });
  const svg = q.querySelector('svg');
  /** 可见面（元素本体 + 两个伪元素）的边框读数 —— R12 的零边框断言用。
   *  为什么要三个点都查：边框可以藏在伪元素上（本钮改前正是「本体零边框 + ::after
   *  一条发丝边」的形态），只查元素本体等于给这种形态开后门。 */
  const borderOf = (el, pseudo) => {
    if (!el) return null;
    const cs = getComputedStyle(el, pseudo || undefined);
    if (!cs) return null;
    const w = ['Top', 'Right', 'Bottom', 'Left'].map((s) => parseFloat(cs['border' + s + 'Width']) || 0);
    const st = ['Top', 'Right', 'Bottom', 'Left'].map((s) => cs['border' + s + 'Style']);
    return {
      content: cs.content, width: w, style: st, color: cs.borderTopColor,
      radius: cs.borderRadius, background: cs.backgroundColor,
      zero: w.every((x) => x === 0) && st.every((s) => s === 'none'),
    };
  };
  const borders = {
    self: borderOf(q),
    before: borderOf(q, '::before'),
    after: borderOf(q, '::after'),
  };
  // 不存在的伪元素（content:none）不算「带边」—— 它没有盒，也就没有可见面。
  const borderZeroAll = !!borders.self && borders.self.zero
    && (!borders.before || borders.before.zero || borders.before.content === 'none')
    && (!borders.after || borders.after.zero || borders.after.content === 'none');
  return {
    found: true, expanded: true,
    tabId: chip.dataset.tabId || '',
    cls: q.className,
    box: { w: R(r.width), h: R(r.height), l: R(r.left), r: R(r.right), t: R(r.top) },
    unscaled: { w: R(r.width / rsc), h: R(r.height / rsc) },
    display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
    pointerEvents: cs.pointerEvents, position: cs.position, zIndex: cs.zIndex,
    text: q.textContent,                          // 可见文字（有字即红：本钮是图标钮）
    textNodes,                                    // 文本节点集合（必须空）
    accName,                                      // 可访问名字（aria-label）
    legacyLabelEl: !!label,                       // 退役的可见文字元素是否复辟（恒 false）
    borders, borderZeroAll,                       // R12：可见面零边框（元素 + 两伪元素）
    aria: q.getAttribute('aria-label'), role: q.getAttribute('role'), tabindex: q.getAttribute('tabindex'),
    title: q.getAttribute('title'),
    svgIcon: svg ? svg.getAttribute('data-icon') : null,
    // R13 读数：图标必须是**真 SVG 命名空间**且路径按描边字形上色（改前 xmlns 缺失 ⇒
    // path 落 null 命名空间，stroke=none、fill=黑 ⇒ 图标不可见）。
    svgNs: svg ? svg.namespaceURI : null,
    pathPaint: svg ? [...svg.querySelectorAll('path')].map((p) => {
      const pc = getComputedStyle(p);
      return { stroke: pc.stroke, fill: pc.fill };
    }) : [],
    svgBox: (() => { if (!svg) return null; const b = svg.getBoundingClientRect(); return { w: R(b.width), h: R(b.height) }; })(),
    hits, hitAllQuote: hits.every(h => h === 'quote'),
    chipBox: (() => { const c = chip.getBoundingClientRect(); return { w: R(c.width), h: R(c.height), l: R(c.left), r: R(c.right) }; })(),
    chipLabel: (chip.querySelector('.mf-strip-label') || {}).textContent || '',
    chipMax: getComputedStyle(chip).maxWidth,
  };
})()`;

const REF_PROBE = `(() => {
  const boxes = [...document.querySelectorAll('#attachment-preview .att-ref')];
  return {
    n: boxes.length,
    chips: boxes.map((c) => ({
      cls: c.className, refType: c.dataset.refType, refId: c.dataset.refId,
      aria: c.getAttribute('aria-label'), title: c.title,
      label: (c.querySelector('.att-ref-label') || {}).textContent || null,
      meta: (c.querySelector('.att-ref-meta') || {}).textContent || null,
    })),
    livePage: (document.querySelector('.mobile-page.live') || {}).dataset
      ? document.querySelector('.mobile-page.live').dataset.page : null,
    navTop: window.__nfMobileNav ? window.__nfMobileNav.top : null,
  };
})()`;

const TOAST_PROBE = `(() => {
  const nodes = [...document.querySelectorAll('.nebflow-toast, [class*="toast"]')]
    .filter((n) => n.getClientRects().length > 0);
  return { n: nodes.length, texts: nodes.map((n) => (n.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 120)) };
})()`;

async function mobileLeg(tag, selftest) {
  const { ctx, page, sent } = await boot({ width: 375, height: 812, mobile: true });
  for (const d of [DOCS[0], DOCS[1]]) await openDoc(page, d, true);
  await openDoc(page, DOCS[2], false);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(1000);
  if (selftest) await page.addStyleTag({ content: INJECT_BREAK + INJECT_XMLNS_BREAK });

  const tabCount = await page.evaluate(() => document.querySelectorAll('.mf-strip-chip').length);

  // ── 展开（作者路径第一步：点标签页顶部 = 点活签）────────────────────────
  await page.tap('.mf-strip-chip.on').catch((e) => { jserr.push(`tap chip: ${String(e).slice(0, 100)}`); });
  await sleep(500);
  const q = await page.evaluate(QUOTE_PROBE);
  await page.screenshot({ path: join(OUT, `${tag}-expanded.png`) });

  // ── R1 现状定位：在挂 + 可见 + 渲染命中盒 ≥44×44 ────────────────────────
  record('R1', '展开态引用钮在挂、可见、命中盒 ≥44×44（手机触控下限）',
    !!q.found && q.display !== 'none' && q.visibility === 'visible'
      && q.box.w >= 44 && q.box.h >= 44 && q.unscaled.w >= 44 && q.unscaled.h >= 44,
    `chips=${tabCount} expanded=${q.expanded} found=${!!q.found} cls=${q.cls} box=${JSON.stringify(q.box)}`
    + ` unscaled=${JSON.stringify(q.unscaled)} display=${q.display} pos=${q.position} z=${q.zIndex}`);

  // ── R2 图标钮：单源图标 + 零文本子节点 + 有可达名字；`↗` 装饰符退役 ───────
  // 口径（作者令 2026-11-18 22:10「引用只需要一个小图标就行了 不需要文字」）：本钮
  // **不是有字控件**，可见面只有一枚单源图标；「它是什么」由 aria-label 承担。
  const r2ok = !!q.found && q.svgIcon === 'quote-entry' && !!q.svgBox && q.svgBox.w > 0 && q.svgBox.h > 0
    && q.textNodes.length === 0 && String(q.text || '').trim() === '' && !/↗/.test(String(q.text || ''))
    && q.legacyLabelEl === false && String(q.accName || '') === '引用此看板' && q.title === q.accName
    && q.role === 'button' && q.tabindex === '0';
  record('R2', '钮 = 图标钮（单源引用入口 svg）：零文本子节点、零可见文字、名字靠 aria-label，箭头 ↗ 已退役',
    r2ok,
    `svgIcon=${q.svgIcon} svgBox=${JSON.stringify(q.svgBox)} textNodes=${JSON.stringify(q.textNodes)}`
    + ` textContent=${JSON.stringify(q.text)} legacyLabel=${q.legacyLabelEl} accName=${JSON.stringify(q.accName)}`
    + ` aria=${JSON.stringify(q.aria)} role=${q.role} tabindex=${q.tabindex}`);

  // ── R13 图标真可见：SVG 命名空间 + 路径描边上色、不填充（xmlns 缺失病根的闸）──
  // 改前病根：QUOTE_ENTRY_ICON 无 xmlns ⇒ XML 解析后 path 不是 SVG 元素 ⇒ stroke=none、
  // fill=黑 ⇒ 手机展开签的引用钮「看不见」（作者 22:31 报）。两条都要成立：
  // ns 必须是 SVG 命名空间；每条 path 的 computed stroke ≠ none 且 fill = none。
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const r13ok = !!q.found && q.svgNs === SVG_NS && q.pathPaint.length > 0
    && q.pathPaint.every((p) => p.stroke !== 'none' && p.fill === 'none');
  record('R13', '图标真可见：svg 为 SVG 命名空间，路径 stroke≠none 且 fill=none（描边字形，非黑块/不可见）',
    r13ok,
    `svgNs=${q.svgNs} pathPaint=${JSON.stringify(q.pathPaint)}`);

  // ── R12 可见面零边框（作者令 2026-11-18 飞书逐字「canvas引用按钮不要边框」）────
  // 口径：钮的**可见面**不带边框 —— 元素本体与其两个伪元素逐点断
  // `border-*-width: 0` ∧ `border-*-style: none`；某伪元素 `content: none`（不存在）
  // 不算带边（没有盒就没有面）。今日产品形态 = 面整块退役（内侧胶囊底与签主胶囊面
  // 逐值相同 ⇒ 去边后零贡献），故本条的牙口由「把边加回去」的注入证明（见 evidence
  // selftest/inject-D*、inject-E*）。
  record('R12', '钮的可见面零边框（元素 + ::before/::after 逐点：border-width 0 ∧ border-style none）',
    !!q.found && q.borderZeroAll === true,
    `self=${JSON.stringify(q.borders && q.borders.self)} before=${JSON.stringify(q.borders && q.borders.before)}`
    + ` after=${JSON.stringify(q.borders && q.borders.after)} borderZeroAll=${q.borderZeroAll}`);

  // ── R3 指针可达：钮盒内 5 点顶层命中恒为钮（或其子）────────────────────
  record('R3', '指针可达：钮盒内 5 点 elementFromPoint 顶层 = 钮自身/其后代',
    !!q.found && q.hitAllQuote === true,
    `hits=${JSON.stringify(q.hits)} pe=${q.pointerEvents}`);

  // ── R4 真手指点得到（touchscreen tap）→ composer 出引用 chip ────────────
  const before = await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length);
  let tapErr = null;
  try { await page.tap('.mf-strip-chip.is-expanded .mf-strip-quote', { timeout: 6000 }); }
  catch (e) { tapErr = String(e.message).split('\n')[0]; }
  await sleep(1400);
  const ref = await page.evaluate(REF_PROBE);
  await page.screenshot({ path: join(OUT, `${tag}-after-quote-tap.png`) });
  record('R4', '真手指（touchscreen tap）点引用钮 → composer 附件条出引用 chip',
    before === 0 && ref.n === 1 && tapErr === null,
    `before=${before} after=${ref.n} tapErr=${tapErr} livePage=${ref.livePage} navTop=${ref.navTop} chips=${JSON.stringify(ref.chips)}`);

  // ── R5 真引用身份：= 被点那一签的身份 ──────────────────────────────────
  const tappedName = String(q.tabId || '').split('/').pop().replace(/\.md$/, '');
  const c0 = ref.chips[0] || {};
  record('R5', '引用身份 = 被点那一签（refType / refId / 标签名 / 源路径）',
    ref.n === 1 && !!c0.refId && (c0.refType === 'file' || c0.refType === 'document')
      && c0.label === tappedName && String(c0.meta || '').includes(tappedName + '.md'),
    `tappedTab=${q.tabId} expectedLabel=${tappedName} chip=${JSON.stringify(c0)}`);

  // ── R6 会话真收到：提交后 WS 帧 refs[0].source.path = 该看板绝对路径 ─────
  const ta = await page.$('#input-bar textarea');
  if (ta) {
    await ta.evaluate((el) => { el.value = '这条引用请把身份读给我'; el.dispatchEvent(new Event('input', { bubbles: true })); });
  }
  await sleep(300);
  sent.length = 0;                                     // 只认这一拍提交出去的帧
  await page.click('#send-btn').catch((e) => { jserr.push(`send: ${String(e).slice(0, 90)}`); });
  await sleep(1600);
  await page.screenshot({ path: join(OUT, `${tag}-after-send.png`) });
  const userFrames = sent.filter((f) => Array.isArray(f.refs) && f.refs.length > 0);
  const rf = (userFrames[0] || {}).refs ? userFrames[0].refs[0] : null;
  const expectedAbs = `${FIX}/${tappedName}.md`;
  record('R6', '会话/模型侧真收到：提交帧 refs[0].source.path = 被点看板的绝对路径',
    userFrames.length === 1 && !!rf && rf.source && rf.source.path === expectedAbs
      && (rf.refType === 'file' || rf.refType === 'document') && !!rf.id
      && Object.prototype.hasOwnProperty.call(rf, 'anchor'),
    `frames=${sent.length} withRefs=${userFrames.length} ref=${JSON.stringify(rf)} expectPath=${expectedAbs}`
    + ` frame=${JSON.stringify((userFrames[0] || {}).content)}`);
  await ctx.close();
}

// ── 手机腿（真手指 + 会话真收）─────────────────────────────────────────────
await mobileLeg(SELFTEST ? 'selftest-m375' : 'm375', SELFTEST);

// ── R7 无 composer 不静默（独立一档视口，避免污染 R4/R5 的读数）────────────
{
  const { ctx, page } = await boot({ width: 375, height: 812, mobile: true });
  for (const d of [DOCS[0], DOCS[1]]) await openDoc(page, d, true);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(900);
  await page.tap('.mf-strip-chip.on').catch(() => {});
  await sleep(450);
  // 制造「没有可用 composer」：把活动视图置空（appendRefToActiveView 的 view 空臂）。
  const cleared = await page.evaluate(async () => {
    const cv = await import('/js/chatView.js');
    cv.setActiveView(null);
    return true;
  });
  const before7 = await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length);
  await page.tap('.mf-strip-chip.is-expanded .mf-strip-quote', { timeout: 6000 }).catch(() => {});
  await sleep(1200);
  const toast = await page.evaluate(TOAST_PROBE);
  const after7 = await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length);
  await page.screenshot({ path: join(OUT, `${SELFTEST ? 'selftest-' : ''}m375-no-composer.png`) });
  record('R7', '没有可用 composer 时不静默：屏上给明确提示（既有 toast 单源，零第四种形态）',
    cleared && before7 === 0 && after7 === 0 && toast.n >= 1 && toast.texts.some((t) => /输入框/.test(t)),
    `cleared=${cleared} refBefore=${before7} refAfter=${after7} toasts=${JSON.stringify(toast)}`);
  await ctx.close();
}

// ── R8 不误触（独立视口）──────────────────────────────────────────────────
{
  const { ctx, page } = await boot({ width: 375, height: 812, mobile: true });
  for (const d of [DOCS[0], DOCS[1]]) await openDoc(page, d, true);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(900);
  // ① 展开后点签名 = 收起（既有语义），不得出引用 chip
  await page.tap('.mf-strip-chip.on').catch(() => {});
  await sleep(450);
  const expandedBefore = await page.evaluate(() => !!document.querySelector('.mf-strip-chip.is-expanded'));
  await page.tap('.mf-strip-chip.is-expanded .mf-strip-label').catch(() => {});
  await sleep(600);
  const collapsed = await page.evaluate(() => !document.querySelector('.mf-strip-chip.is-expanded'));
  const refsAfterLabelTap = await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length);
  // ② 签条横滑（换签）不得出引用 chip
  await page.tap('.mf-strip-chip.on').catch(() => {});
  await sleep(450);
  const hb = await page.locator('.mf-strip').boundingBox();
  await page.touchscreen.tap(hb.x + 6, hb.y + 30).catch(() => {});
  await sleep(400);
  const refsAfterMisc = await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length);
  // ③ 点内容区空白不得出引用 chip
  await page.touchscreen.tap(187, 600).catch(() => {});
  await sleep(500);
  const refsAfterBlank = await page.evaluate(() => document.querySelectorAll('#attachment-preview .att-ref').length);
  await page.screenshot({ path: join(OUT, `${SELFTEST ? 'selftest-' : ''}m375-misfire.png`) });
  record('R8', '不误触：点签名（收起）/ 点签条边缘 / 点正文空白 均零引用 chip',
    expandedBefore && collapsed && refsAfterLabelTap === 0 && refsAfterMisc === 0 && refsAfterBlank === 0,
    `expanded=${expandedBefore} collapsedByLabelTap=${collapsed} refs(label)=${refsAfterLabelTap} refs(stripEdge)=${refsAfterMisc} refs(blank)=${refsAfterBlank}`);
  await ctx.close();
}

// ── R9 展开态既有内容与几何回归（独立视口；展开态常驻不收起）──────────────
{
  const { ctx, page } = await boot({ width: 375, height: 812, mobile: true });
  for (const d of [DOCS[0], DOCS[1]]) await openDoc(page, d, true);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(900);
  const geo = await page.evaluate(() => {
    const R = (n) => Math.round(n * 100) / 100;
    const chip = document.querySelector('.mf-strip-chip.on');
    const norm = chip.getBoundingClientRect();
    const q = chip.querySelector('.mf-strip-quote');
    const qr = q.getBoundingClientRect();
    const cr = chip.getBoundingClientRect();
    const others = [...document.querySelectorAll('.mf-cv-close, .mf-cv-stack')]
      .filter((e) => e.getClientRects().length > 0)
      .map((e) => { const b = e.getBoundingClientRect(); return { cls: e.className, l: R(b.left), t: R(b.top), r: R(b.right), b: R(b.bottom) }; });
    return { normW: R(norm.width), qInChip: qr.left >= cr.left - 0.5 && qr.right <= cr.right + 0.5, others };
  });
  await sleep(150);
  await page.tap('.mf-strip-chip.on').catch(() => {});
  await sleep(600);
  const geo2 = await page.evaluate(() => {
    const R = (n) => Math.round(n * 100) / 100;
    const chip = document.querySelector('.mf-strip-chip.is-expanded');
    if (!chip) return { expanded: false };
    const cr = chip.getBoundingClientRect();
    const q = chip.querySelector('.mf-strip-quote');
    const qr = q ? q.getBoundingClientRect() : null;
    const others = [...document.querySelectorAll('.mf-cv-close, .mf-cv-stack')]
      .filter((e) => e.getClientRects().length > 0)
      .map((e) => { const b = e.getBoundingClientRect(); return { cls: e.className, l: R(b.left), t: R(b.top), r: R(b.right), b: R(b.bottom) }; });
    return {
      expanded: true, w: R(cr.width), l: R(cr.left), r: R(cr.right), maxW: getComputedStyle(chip).maxWidth,
      labelW: R((chip.querySelector('.mf-strip-label') || { getBoundingClientRect: () => ({ width: 0 }) }).getBoundingClientRect().width),
      qInChip: qr ? (qr.left >= cr.left - 0.5 && qr.right <= cr.right + 0.5) : false,
      others,
    };
  });
  const ovl = (a, b) => {
    const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
    const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    return R2(Math.max(0, w) * Math.max(0, h));
  };
  const chipRect = geo2.expanded ? { left: geo2.l, right: geo2.r, top: 0, bottom: 812 } : null;
  const collisions = (geo2.others || []).map((o) => ({ cls: o.cls, area: chipRect ? ovl({ left: o.l, right: o.r, top: o.t, bottom: o.b }, chipRect) : 0 }))
    .filter((c) => c.area > 1);
  await page.screenshot({ path: join(OUT, `${SELFTEST ? 'selftest-' : ''}m375-expanded-geom.png`) });
  record('R9', '展开态既有内容与几何：展开 ≥ 常态宽 ∧ 引用钮 ⊆ 签 ∧ 零悬浮钮交叠（既有合同）',
    !!geo2.expanded && geo2.w > geo.normW && geo2.qInChip === true && collisions.length === 0,
    `normW=${geo.normW} expandedW=${geo2.w} maxW=${geo2.maxW} labelW=${geo2.labelW} qInChip=${geo2.qInChip}`
    + ` overlaps=${JSON.stringify(collisions)} liveBtns=${JSON.stringify(geo.others.map((o) => o.cls))}`);
  await ctx.close();
}

// ── R10 桌面不回归（零手机控件 + 既有右键引用通路照旧）─────────────────────
{
  const { ctx, page } = await boot({ width: 1440, height: 900, mobile: false });
  await openDoc(page, DOCS[0], true);
  const mobileOnly = await page.evaluate(() => document.querySelectorAll('.mf-strip-quote, .mf-strip').length);
  await page.click('#canvas-panel .canvas-header', { button: 'right' }).catch((e) => { jserr.push(`ctxmenu: ${String(e).slice(0, 90)}`); });
  await sleep(500);
  const menuText = await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    return m ? m.textContent.replace(/\s+/g, ' ').trim() : null;
  });
  await page.evaluate(() => {
    const m = document.querySelector('.canvas-tab-context-menu');
    const b = m && [...m.querySelectorAll('button')].find((x) => /引用当前页/.test(x.textContent));
    if (b) b.click();
  });
  await sleep(1000);
  const dref = await page.evaluate(REF_PROBE);
  const dtabs = await page.evaluate(() => ({
    tabs: document.querySelectorAll('#canvas-tab-bar .canvas-tab').length,
    tabQuote: document.querySelectorAll('.canvas-tab .mf-strip-quote').length,
  }));
  await page.screenshot({ path: join(OUT, `${SELFTEST ? 'selftest-' : ''}d1440.png`) });
  record('R10', '桌面不回归：零手机环带控件 ∧ 既有右键「引用当前页」照旧出引用 chip',
    mobileOnly === 0 && !!menuText && /引用当前页/.test(menuText) && dref.n === 1 && dtabs.tabQuote === 0,
    `mobileOnlyNodes=${mobileOnly} menu=${JSON.stringify(menuText)} refChips=${dref.n} chip=${JSON.stringify(dref.chips[0] || null)} desktopTabs=${JSON.stringify(dtabs)}`);
  await ctx.close();
}

// ── R11 无障碍名字按 locale（en 档）：R2 的 en 侧对账 ────────────────────────
// 为什么必须有这一档：图标钮**没有可见文字**，名字全在 aria-label 上，而 aria-label 是
// locale 派生的（navController 的 tt：读 `key('locale')` 同源键 → zh「引用此看板」/
// en「Quote this Canvas」）。只在 zh 档断言等于把「按 locale 断言」漏成「按 zh 断言」；
// 产品侧任何把 aria 文案写死 / 落到 en 之外的字面，都会在这一档报红。图标钮的
// 「零可见文字」在 en 档同样要成立（两档都不许冒出文字）。
{
  const { ctx, page } = await boot({ width: 375, height: 812, mobile: true, locale: 'en' });
  for (const d of [DOCS[0], DOCS[1]]) await openDoc(page, d, true);
  await openDoc(page, DOCS[2], false);
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click({ timeout: 8000 }).catch(() => {});
  await sleep(1000);
  await page.tap('.mf-strip-chip.on').catch((e) => { jserr.push(`tap chip(en): ${String(e).slice(0, 100)}`); });
  await sleep(500);
  // 语言源读法：branding.js 的 key('locale') 前缀随品牌派生、且 boot 期会把 legacy
  // `nebflow_locale` 迁移成品牌前缀键并删掉旧键 ⇒ 不能写死键名，按后缀找现存那一枚。
  const loc = await page.evaluate(() => {
    const k = Object.keys(localStorage).find((x) => x.endsWith('_locale'));
    return k ? localStorage.getItem(k) : null;
  });
  const q = await page.evaluate(QUOTE_PROBE);
  record('R11', '无障碍名字按 locale（en 档 = "Quote this Canvas"）：图标钮零可见文字 ∧ title 同名',
    loc === 'en' && !!q.found && q.svgIcon === 'quote-entry'
      && String(q.accName || '') === 'Quote this Canvas' && /Quote/.test(String(q.aria || ''))
      && !/引用/.test(String(q.aria || '')) && q.title === q.accName && q.textNodes.length === 0
      && String(q.text || '').trim() === '' && q.role === 'button' && q.tabindex === '0'
      && q.box.w >= 44 && q.box.h >= 44,
    `appLocale=${JSON.stringify(loc)} accName=${JSON.stringify(q.accName)} aria=${JSON.stringify(q.aria)}`
    + ` title=${JSON.stringify(q.title)} textNodes=${JSON.stringify(q.textNodes)} textContent=${JSON.stringify(q.text)}`
    + ` svgIcon=${q.svgIcon} role=${q.role} tabindex=${q.tabindex} box=${JSON.stringify(q.box)}`);
  await page.screenshot({ path: join(OUT, `${SELFTEST ? 'selftest-' : ''}m375-en-quote.png`) });
  await ctx.close();
}

await browser.close();
server.close();

const stamp = new Date().toISOString();
const body = [
  `canvas-tab-reference gate — ${stamp}`,
  `webRoot=${WEB_DIR} selftest=${SELFTEST}`,
  `viewports: mobile 375x812 (hasTouch) | desktop 1440x900`,
  '',
  ...rows.map((r) => `${r.verdict}  ${r.id}  ${r.name}\n        ${r.readout}`),
  '',
  jserr.length ? `JS errors:\n  ${jserr.join('\n  ')}` : 'JS errors: none',
  '',
  `RESULT ${pass}/${pass + fail} PASS, ${fail} FAIL`,
].join('\n');
writeFileSync(join(OUT, `canvas-tab-reference${SELFTEST ? '-selftest' : ''}.log`), body + '\n');
console.log(`\nRESULT ${pass}/${pass + fail} PASS, ${fail} FAIL`);
console.log(`log: ${join(OUT, `canvas-tab-reference${SELFTEST ? '-selftest' : ''}.log`)}`);
if (jserr.length) console.log(`JS errors: ${jserr.length}\n  ${jserr.join('\n  ')}`);

if (SELFTEST) {
  // 自检语义：注入两条病根后，R2 与 R4 **必须**红（否则断言是假绿）。
  const r2 = rows.find((r) => r.id === 'R2');
  const r4 = rows.find((r) => r.id === 'R4');
  const r3 = rows.find((r) => r.id === 'R3');
  const r13 = rows.find((r) => r.id === "R13");
  const okBreak = r2 && r2.verdict === "FAIL" && r4 && r4.verdict === "FAIL" && r3 && r3.verdict === "FAIL" && r13 && r13.verdict === "FAIL";
  console.log(`\nSELFTEST: R2(文字)=${r2 && r2.verdict} R3(可达)=${r3 && r3.verdict} R4(真手指)=${r4 && r4.verdict}`
    + `  ⇒ ${okBreak ? '注入后确实报红（闸有牙）' : '注入后仍绿 = 假绿！'}`);
  process.exit(okBreak ? 0 : 1);
}
process.exit(fail ? 1 : 0);
