#!/usr/bin/env node
// canvas-bottom-clearance.spec.mjs — canvas 文档面「底部浮钮压住正文末行」修复批的机械断言
//
// 作者报障 2026-11-05 12:09（逐字）「因为我们现在canvas的文档的底部不是有按钮吗，
// 按钮会挡住底部的文字，所以要允许文档再进一步上滑，保证最底部的文字能够呈现在
// 按钮之上。不会被遮挡。」
//
// 装置：零端口零进程（复用 scripts/lib/visual-harness.mjs —— page.route 从磁盘服务
// src/main/resources/web + MockWS 走真实 ws.js 分发路径）。不起实例 ⇒ 结构上不可
// 能碰宿主 6138；NEBFLOW_WEB_ROOT 可指向另一棵 web 树（改前/改后对照用，见下）。
//
// 断言（真几何读数，全在浏览器里量，不读声明值）：
//   A1 滚到底：正文末块 bottom ≤ 底部浮钮带顶 − 8（末行完整可见 + 至少 8px 呼吸）
//   A2 呼吸上限：浮钮带顶 − 末块 bottom ≤ 60（不是滚出一大段空白）
//   A3 机制一致：--canvas-bottom-clearance = round(pane.bottom − 浮钮带顶 + 12)
//   A4 长文档确实可滚：maxScroll > 0 且已到 max
//   A5 留白刚好用完：viewer 内容底缘 = 滚动盒底 − padding-bottom（±1.5）
//   A6 短文档（不满一屏）：scrollHeight ≤ clientHeight + 1（无滚动条、无空白滚动区）
//   A7 无浮钮（全隐藏）：变量 = 0px ∧ 计算后的 padding-bottom = 0px（不留空白）
//   A7b 浮钮恢复显示：留白随实测回填
//   A8 浮钮变高（40 → 64）：留白跟随实测涨 24px（ResizeObserver 腿）
//   A9 纯文本面（.canvas-txt-scroll）同判据通过
//   B0…B4 手机 390 窄屏（body.mobile-layer）同判据 + 手机档 76px 地板仍成立
//
// 运行：
//   node tests/canvas-bottom-clearance.spec.mjs [outDir]
//   改前对照（验红）：先导出一棵改前 web 树再指过来
//     git archive <base> src/main/resources/web | tar -x -C /tmp/nf-web-prefix
//     NEBFLOW_WEB_ROOT=/tmp/nf-web-prefix/src/main/resources/web \
//       node tests/canvas-bottom-clearance.spec.mjs /tmp/out-before
//
// 退出码：0 = 全绿；1 = 有 FAIL（读数照旧全打印，便于改前取证）；2 = 装置错误。
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, shoot, webRoot } from '../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const OUT = process.argv[2] || join(ROOT, 'reports-evidence', 'canvas-bottom-clearance');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const r2 = (n) => Math.round(n * 100) / 100;

// ── 夹具：长文档（远超一屏）/ 短文档（明显不足一屏）────────────────────
const LONG_MD = [
  '# 底部让位验收夹具',
  '',
  ...Array.from({ length: 40 }, (_, i) => [
    `## 第 ${i + 1} 节`,
    '',
    `这一段用于撑高滚动面，让「滚到底」这件事真的发生（第 ${i + 1} 段）。` +
    '正文内容本身不重要，重要的是末行的几何位置：滚到底之后它必须停在底部浮钮带之上。',
    '',
    '- 列表项 A',
    '- 列表项 B',
    '',
  ].join('\n')),
  '末行标记：END-OF-DOCUMENT-末行',
].join('\n');

const SHORT_MD = ['# 短文档', '', '这份文档只有两行正文。', '', '末行标记：SHORT-END。'].join('\n');

const LONG_TXT = Array.from({ length: 120 }, (_, i) => `第 ${i + 1} 行：纯文本阅读面也需要让位。`).join('\n')
  + '\n末行标记：TXT-END-OF-DOCUMENT';

const MD_PATH = '/tmp/nf-clearance-long.md';
const MD_SHORT_PATH = '/tmp/nf-clearance-short.md';
const TXT_PATH = '/tmp/nf-clearance-long.txt';

// ── 结果收集 ─────────────────────────────────────────────────────────────
const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}

// ── 一帧内几何探针（不 mock 布局；读数全部来自 getBoundingClientRect）──
const PROBE = `(() => {
  const R = (n) => Math.round(n * 100) / 100;
  const box = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    if (!r.width && !r.height) return null;
    return { x: R(r.x), y: R(r.y), w: R(r.width), h: R(r.height), right: R(r.right), bottom: R(r.bottom) };
  };
  const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden' && !el.hidden;
  const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane.active')
            || document.querySelector('.canvas-tab-pane.active');
  if (!pane) return { ok: false, reason: 'no active pane' };
  const scroller = pane.querySelector('.canvas-md-scroll, .canvas-txt-scroll')
                || (pane.classList.contains('scrollable') ? pane : null);
  const host = pane.querySelector('.canvas-md-viewer');
  const CHROME = ['.canvas-source-toggle', '.canvas-ref-select', '.canvas-find-toggle', '.canvas-zoom-bar'];
  const chrome = [];
  for (const sel of CHROME) {
    for (const el of pane.querySelectorAll(sel)) {
      if (!vis(el)) { chrome.push({ sel, visible: false }); continue; }
      const b = box(el);
      if (!b) { chrome.push({ sel, visible: false }); continue; }
      chrome.push({ sel, visible: true, top: b.y, bottom: b.bottom, h: b.h });
    }
  }
  const shell = pane.parentElement;
  const refresh = shell ? shell.querySelector(':scope > .canvas-refresh-toggle') : null;
  if (refresh) {
    const b = box(refresh);
    chrome.push({ sel: '.canvas-refresh-toggle', visible: !!b && vis(refresh), top: b ? b.y : null, bottom: b ? b.bottom : null, h: b ? b.h : null });
  }
  const shown = chrome.filter((c) => c.visible && typeof c.top === 'number');
  const paneBox = box(pane);
  const chromeTop = shown.length ? Math.min(...shown.map((c) => c.top)) : null;
  // 正文末块：md 面取 .canvas-md-viewer 最后一个可见元素子；txt 面取最后一个 <p>
  let lastBlock = null;
  if (host) {
    for (const ch of [...host.children].reverse()) {
      if (!vis(ch)) continue;
      const b = box(ch);
      if (b) { lastBlock = { tag: ch.tagName, ...b, text: (ch.textContent || '').slice(-28) }; break; }
    }
  } else {
    const ps = [...pane.querySelectorAll('.canvas-txt-seg p')];
    for (const p of ps.reverse()) {
      if (!vis(p)) continue;
      const b = box(p);
      if (b) { lastBlock = { tag: p.tagName, ...b, text: (p.textContent || '').slice(-28) }; break; }
    }
  }
  const csS = scroller ? getComputedStyle(scroller) : null;
  const sc = scroller ? {
    sh: R(scroller.scrollHeight), ch: R(scroller.clientHeight), st: R(scroller.scrollTop),
    maxScroll: R(Math.max(0, scroller.scrollHeight - scroller.clientHeight)),
    padBottom: csS ? R(parseFloat(csS.paddingBottom) || 0) : null,
    padBottomRaw: csS ? csS.paddingBottom : null,
    scrollPadBottom: csS ? csS.scrollPaddingBottom : null,
    isPane: scroller === pane,
    box: box(scroller),
  } : null;
  const csP = getComputedStyle(pane);
  const varRaw = csP.getPropertyValue('--canvas-bottom-clearance').trim();
  return {
    ok: true,
    mobile: document.body.classList.contains('mobile-layer'),
    paneVisible: csP.visibility === 'visible',
    vw: window.innerWidth, vh: window.innerHeight,
    pane: paneBox,
    paneClass: pane.className,
    scroller: sc,
    viewer: box(host),
    chrome, chromeTop: chromeTop === null ? null : R(chromeTop),
    clearanceVar: varRaw,
    clearanceVarPx: parseFloat(varRaw) || 0,
    padBottom: csS ? R(parseFloat(csS.paddingBottom) || 0) : null,
    lastBlock,
    predicted: (chromeTop === null || !paneBox) ? null : R(paneBox.bottom - chromeTop + 12),
  };
})()`;

async function probe(page) { return page.evaluate(PROBE); }

async function scrollToBottom(page) {
  await page.evaluate(() => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane.active')
              || document.querySelector('.canvas-tab-pane.active');
    const sc = pane && (pane.querySelector('.canvas-md-scroll, .canvas-txt-scroll')
             || (pane.classList.contains('scrollable') ? pane : null));
    if (sc) sc.scrollTop = sc.scrollHeight;
  });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  await sleep(250);
}

async function settleChrome(page) {
  // 让位观测腿（ResizeObserver / MutationObserver → rAF）跑完再读数
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  await sleep(150);
}

// ── 打开一个文件页签（workspace-open-item 是产品入口，不直写 DOM）──────
async function openDoc(s, { id, title, itemType, content, absPath }) {
  await s.page.evaluate((item) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...item, pinned: true } }));
  }, { id, title, itemType, content, absPath });
  await s.page.waitForSelector(`.canvas-tab-pane[data-tab-id="${id}"]`, { timeout: 20000, state: 'attached' });
  await settle(s.page, 900);
  // 手机层：canvas 页要在页面栈前台才可见（非前台页是 visibility: hidden，但它仍
  // 占满屏 —— 高度判据不可用），故显式点一次 dock 的 canvas 键。
  const isMobile = await s.page.evaluate(() => document.body.classList.contains('mobile-layer'));
  const paneVisible = () => s.page.evaluate((tid) => {
    const p = document.querySelector(`.canvas-tab-pane[data-tab-id="${tid}"]`);
    if (!p) return false;
    const cs = getComputedStyle(p);
    return cs.visibility === 'visible' && p.getBoundingClientRect().height > 1;
  }, id);
  if (isMobile) {
    for (let i = 0; i < 3; i++) {
      if (await paneVisible()) break;
      await s.page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click().catch(() => {});
      await settle(s.page, 900);
    }
  }
  await settle(s.page, 400);
}

// ── 主流程 ───────────────────────────────────────────────────────────────
const { mod: pw } = loadPlaywright();
const { browser, label } = await launchBrowser(pw);
console.log(`canvas-bottom-clearance: web=${webRoot()} browser=${label} out=${OUT}`);

const pageErrors = [];

async function newSession(opts) {
  const s = await createSession(browser, opts);
  s.page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 240)));
  // 文件读取面：照夹具回同一份正文，避免激活刷新腿（scheduleFileRefresh）把面刷成错误态
  await s.page.route('**/api/nf-file**', (route) => {
    const u = decodeURIComponent(route.request().url());
    const body = u.includes('nf-clearance-short') ? SHORT_MD
      : u.includes('nf-clearance-long.txt') ? LONG_TXT
        : LONG_MD;
    const type = u.includes('.txt') ? 'text/plain; charset=utf-8' : 'text/markdown; charset=utf-8';
    return route.fulfill({ status: 200, contentType: type, body });
  });
  await s.boot();
  return s;
}

// ═══ 桌面 1440：长 md 文档 ═══════════════════════════════════════════════
const desk = await newSession({ width: 1440, height: 900, colorScheme: 'dark' });
await openDoc(desk, { id: `file:${MD_PATH}`, title: 'nf-clearance-long.md', itemType: 'markdown', content: LONG_MD, absPath: MD_PATH });
await scrollToBottom(desk.page);
await settleChrome(desk.page);
const D = await probe(desk.page);
writeFileSync(join(OUT, 'probe-desktop-long.json'), JSON.stringify(D, null, 2));
writeFileSync(join(SHOTS, 'desktop-long-bottom.png'), await shoot(desk.page, { selector: '#canvas-panel' }));

const gapD = D.chromeTop !== null && D.lastBlock ? r2(D.chromeTop - D.lastBlock.bottom) : null;
record('A1', '桌面 1440 · 滚到底：末行完整停在底部浮钮带之上（≥8px 呼吸）',
  !!D.lastBlock && D.chromeTop !== null && D.lastBlock.bottom <= D.chromeTop - 8,
  `末块 <${D.lastBlock?.tag}> bottom=${D.lastBlock?.bottom}；浮钮带顶=${D.chromeTop}；空隙=${gapD}px（阈值 ≥8）；` +
  `末块文字="${D.lastBlock?.text}"；浮钮=${D.chrome.filter((c) => c.visible).map((c) => `${c.sel}(top ${c.top},h ${c.h})`).join(' / ')}`);
record('A2', '桌面 1440 · 呼吸上限：空隙 ≤60px（不是滚出一大段空白）',
  gapD !== null && gapD <= 60,
  `空隙=${gapD}px（阈值 ≤60；= 浮钮带让位 12 呼吸 + md 面自身 20px 内距）`);
record('A3', '桌面 1440 · 机制一致：--canvas-bottom-clearance = pane 底距 − 浮钮带顶 + 12',
  !!D.clearanceVar && Math.abs(D.clearanceVarPx - (D.predicted ?? -999)) <= 0.5,
  `变量=${D.clearanceVar || '(未写)'}；独立算得 ${D.predicted}px；计算后 padding-bottom=${D.padBottom}px`);
record('A4', '桌面 1440 · 长文档确实可滚且已到 max',
  !!D.scroller && D.scroller.maxScroll > 100 && Math.abs(D.scroller.st - D.scroller.maxScroll) <= 2,
  `scrollTop=${D.scroller?.st} / max=${D.scroller?.maxScroll}（sh=${D.scroller?.sh} ch=${D.scroller?.ch}）`);
record('A5', '桌面 1440 · 留白刚好用完：内容底缘 = 滚动盒底 − padding-bottom',
  !!D.viewer && !!D.scroller && Math.abs((D.scroller.box.bottom - D.padBottom) - D.viewer.bottom) <= 1.5,
  `内容底缘=${D.viewer?.bottom}；滚动盒底=${D.scroller?.box?.bottom}；padding-bottom=${D.padBottom}；` +
  `差=${D.viewer && D.scroller ? r2(D.scroller.box.bottom - D.padBottom - D.viewer.bottom) : 'n/a'}px（阈值 |·|≤1.5）`);

// ── A6 短文档：不满一屏、不许出现空白滚动区 ────────────────────────────
await openDoc(desk, { id: `file:${MD_SHORT_PATH}`, title: 'nf-clearance-short.md', itemType: 'markdown', content: SHORT_MD, absPath: MD_SHORT_PATH });
await scrollToBottom(desk.page);
await settleChrome(desk.page);
const S = await probe(desk.page);
writeFileSync(join(OUT, 'probe-desktop-short.json'), JSON.stringify(S, null, 2));
writeFileSync(join(SHOTS, 'desktop-short.png'), await shoot(desk.page, { selector: '#canvas-panel' }));
record('A6', '桌面 1440 · 短文档（不满一屏）：无滚动条 / 无空白滚动区',
  !!S.scroller && S.scroller.sh <= S.scroller.ch + 1,
  `scrollHeight=${S.scroller?.sh} ≤ clientHeight=${S.scroller?.ch} +1 ⇒ maxScroll=${S.scroller?.maxScroll}；` +
  `末块 bottom=${S.lastBlock?.bottom} vs 浮钮带顶 ${S.chromeTop}；padding-bottom=${S.padBottom}（垫存在但装得下 ⇒ 零滚动）`);

// ── A7 无浮钮（全隐藏）：不许留空白 ─────────────────────────────────────
const noChrome = await desk.page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane.active');
  const els = [...pane.querySelectorAll('.canvas-source-toggle, .canvas-ref-select, .canvas-find-toggle, .canvas-zoom-bar')];
  const shell = pane.parentElement;
  const refresh = shell.querySelector(':scope > .canvas-refresh-toggle');
  if (refresh) els.push(refresh);
  for (const el of els) el.style.display = 'none';
  return els.length;
});
await settleChrome(desk.page);
await settleChrome(desk.page);
const N = await probe(desk.page);
writeFileSync(join(OUT, 'probe-desktop-nochrome.json'), JSON.stringify(N, null, 2));
record('A7', '桌面 1440 · 底部浮钮全部隐藏：留白归零（不留多余空白）',
  noChrome > 0 && N.clearanceVarPx === 0 && N.padBottom === 0,
  `隐藏 ${noChrome} 颗浮钮 → 变量=${N.clearanceVar || '(空)'}；计算后 padding-bottom=${N.padBottom}px（阈值 0）`);
await desk.page.evaluate(() => {
  document.querySelectorAll('.canvas-tab-pane.active .canvas-source-toggle, .canvas-tab-pane.active .canvas-ref-select, .canvas-tab-pane.active .canvas-find-toggle, .canvas-tab-pane.active .canvas-zoom-bar, #canvas-content > .canvas-refresh-toggle').forEach((el) => { el.style.display = ''; });
});
await settleChrome(desk.page);
await settleChrome(desk.page);
const N2 = await probe(desk.page);
record('A7b', '桌面 1440 · 浮钮恢复显示：留白随实测回填',
  Math.abs(N2.clearanceVarPx - (N2.predicted ?? -999)) <= 0.5 && N2.clearanceVarPx > 0,
  `变量=${N2.clearanceVar}；独立算得 ${N2.predicted}px；padding-bottom=${N2.padBottom}px`);

// ── A8 浮钮变高（40 → 64）：留白跟随实测 ───────────────────────────────
await desk.page.evaluate(() => {
  const btn = document.querySelector('.canvas-tab-pane.active .canvas-source-toggle');
  if (btn) { btn.dataset.nfH0 = btn.style.height || ''; btn.style.height = '64px'; }
});
await settleChrome(desk.page);
await settleChrome(desk.page);
const H = await probe(desk.page);
writeFileSync(join(OUT, 'probe-desktop-tallchrome.json'), JSON.stringify(H, null, 2));
writeFileSync(join(SHOTS, 'desktop-tallchrome.png'), await shoot(desk.page, { selector: '#canvas-panel' }));
record('A8', '桌面 1440 · 浮钮变高 40→64：留白跟随实测（ResizeObserver 腿）',
  Math.abs(H.clearanceVarPx - (D.clearanceVarPx + 24)) <= 1 && Math.abs(H.clearanceVarPx - (H.predicted ?? -999)) <= 0.5,
  `改前变量=${D.clearanceVar} → 钮高 64 后变量=${H.clearanceVar}（应 +24 = ${r2(D.clearanceVarPx + 24)}）；` +
  `独立算得 ${H.predicted}px；padding-bottom=${H.padBottom}px`);
await desk.page.evaluate(() => {
  const btn = document.querySelector('.canvas-tab-pane.active .canvas-source-toggle');
  if (btn) btn.style.height = btn.dataset.nfH0 || '';
});

// ── A9 纯文本面（.canvas-txt-scroll）同一判据 ──────────────────────────
await openDoc(desk, { id: `file:${TXT_PATH}`, title: 'nf-clearance-long.txt', itemType: 'code', content: LONG_TXT, absPath: TXT_PATH });
await scrollToBottom(desk.page);
await settleChrome(desk.page);
const T = await probe(desk.page);
writeFileSync(join(OUT, 'probe-desktop-txt.json'), JSON.stringify(T, null, 2));
writeFileSync(join(SHOTS, 'desktop-txt-bottom.png'), await shoot(desk.page, { selector: '#canvas-panel' }));
const gapT = T.chromeTop !== null && T.lastBlock ? r2(T.chromeTop - T.lastBlock.bottom) : null;
record('A9', '桌面 1440 · 纯文本面（.canvas-txt-scroll）滚到底：末行在浮钮带之上',
  !!T.lastBlock && T.chromeTop !== null && T.lastBlock.bottom <= T.chromeTop - 8 && gapT !== null && gapT <= 60,
  `末行 bottom=${T.lastBlock?.bottom}；浮钮带顶=${T.chromeTop}；空隙=${gapT}px（阈值 8…60）；` +
  `变量=${T.clearanceVar}；padding-bottom=${T.padBottom}px；max=${T.scroller?.maxScroll} st=${T.scroller?.st}`);
await desk.close();

// ═══ 手机 390（窄屏）：长 md 文档 ══════════════════════════════════════
const phone = await newSession({ width: 390, height: 844, colorScheme: 'dark' });
await openDoc(phone, { id: `file:${MD_PATH}`, title: 'nf-clearance-long.md', itemType: 'markdown', content: LONG_MD, absPath: MD_PATH });
await scrollToBottom(phone.page);
await settleChrome(phone.page);
const M = await probe(phone.page);
writeFileSync(join(OUT, 'probe-phone-long.json'), JSON.stringify(M, null, 2));
writeFileSync(join(SHOTS, 'phone-long-bottom.png'), await phone.page.screenshot({ animations: 'disabled', caret: 'hide' }));
const gapM = M.chromeTop !== null && M.lastBlock ? r2(M.chromeTop - M.lastBlock.bottom) : null;
record('B0', '手机 390 · 手机层已激活且 canvas 页在前台',
  M.mobile === true && M.paneVisible === true, `mobile=${M.mobile}；paneVisibility=${M.paneVisible}；视口=${M.vw}×${M.vh}`);
record('B1', '手机 390 · 滚到底：末行完整停在底部浮钮带之上（≥8px 呼吸）',
  !!M.lastBlock && M.chromeTop !== null && M.lastBlock.bottom <= M.chromeTop - 8,
  `末块 bottom=${M.lastBlock?.bottom}；浮钮带顶=${M.chromeTop}；空隙=${gapM}px（阈值 ≥8）；` +
  `浮钮=${M.chrome.filter((c) => c.visible).map((c) => `${c.sel}(top ${c.top},h ${c.h})`).join(' / ')}`);
record('B2', '手机 390 · 呼吸上限：空隙 ≤60px',
  gapM !== null && gapM <= 60, `空隙=${gapM}px（阈值 ≤60）`);
record('B3', '手机 390 · 机制一致：变量 = pane 底距 − 浮钮带顶 + 12（76px 地板仍成立）',
  Math.abs(M.clearanceVarPx - (M.predicted ?? -999)) <= 0.5 && M.padBottom >= 76
  && Math.abs(M.padBottom - Math.max(76, M.clearanceVarPx)) <= 0.5,
  `变量=${M.clearanceVar}；独立算得 ${M.predicted}px；计算后 padding-bottom=${M.padBottom}px（= max(76 地板, 实测)）`);
record('B4', '手机 390 · 长文档确实可滚且已到 max',
  !!M.scroller && M.scroller.maxScroll > 100 && Math.abs(M.scroller.st - M.scroller.maxScroll) <= 2,
  `scrollTop=${M.scroller?.st} / max=${M.scroller?.maxScroll}（sh=${M.scroller?.sh} ch=${M.scroller?.ch}）`);
await phone.close();

await browser.close();

// ── 汇总落盘 ────────────────────────────────────────────────────────────
const summary = {
  when: new Date().toISOString(),
  webRoot: webRoot(), browser: label, out: OUT,
  pass, fail, rows, pageErrors,
};
writeFileSync(join(OUT, 'assertions.json'), JSON.stringify(summary, null, 2));

console.log(`\n=== ${pass} PASS / ${fail} FAIL ===`);
if (pageErrors.length) console.log('page errors:\n  ' + pageErrors.slice(0, 8).join('\n  '));
console.log(`shots: ${SHOTS}`);
process.exit(fail === 0 ? 0 : 1);
