#!/usr/bin/env node
// build.mjs — 生成三方案对照页（自包含 HTML）+ 总览页
//
// 原则（本批硬要求）：
//   ① 底衬 = **真实页面截图**（capture.mjs 拍的同帧），不是灰块；
//   ② 方案 CSS = variants/*.css **原文**（同一份文本既跑门禁、又渲页面，零改写）；
//   ③ 基础几何/位姿 = 从产品树**逐条抽取**的真实规则（mobileLayer.css / glass.css /
//      design-system.css），不手抄；令牌 = 真实页面计算样式（probe.json，亮/暗各一份）；
//   ④ 自包含：图片 base64 内嵌、无外链、无网络请求，直接双击可开；
//   ⑤ 六格同文档渲染（不用 iframe）：backdrop-filter 在 iframe 内实测不生效
//      （Chromium：iframe 里的非 fixed 元素采样不到外层底衬 —— 本轮 A/B 实证，
//      q-childneg / w-bgimg 两组探针都是「声明生效、画面无模糊」），
//      故改用「同一文档 + 类前缀作用域」，渲染结果与产品页同构。
//
// 用法：node design/mobile-canvas-tabs/build.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '../..');
const WEB = join(REPO, 'src/main/resources/web');
const SHOTS = join(HERE, 'shots');

const probe = JSON.parse(readFileSync(join(SHOTS, 'probe.json'), 'utf8'));

// ── 真实规则抽取（顶层规则 + @media 内规则；@supports 降级块整块跳过）───────
function extractRules(css, pred) {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const out = [];
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf('{', i);
    if (open < 0) break;
    const prelude = text.slice(i, open).trim();
    let depth = 0;
    let j = open;
    for (; j < text.length; j++) {
      if (text[j] === '{') depth++;
      else if (text[j] === '}') { depth--; if (depth === 0) break; }
    }
    const body = text.slice(open + 1, j);
    if (/^@supports/.test(prelude)) {
      // 降级路径（`@supports not (backdrop-filter…)` → backdrop-filter:none）整块跳过：
      // 拍平进页面会把玻璃关掉（本轮实证过：不进页面）。
    } else if (/^@media/.test(prelude)) {
      for (const r of extractRules(body, pred)) out.push(r);
    } else if (prelude && pred(prelude)) {
      out.push({ selector: prelude, body });
    }
    i = j + 1;
  }
  return out;
}

const mobileLayer = readFileSync(join(WEB, 'css/mobileLayer.css'), 'utf8');
const glassCss = readFileSync(join(WEB, 'css/glass.css'), 'utf8');
const designSystem = readFileSync(join(WEB, 'css/design-system.css'), 'utf8');

const BASE_RULES = [
  ...extractRules(mobileLayer, (sel) => /\.mf-strip|\.mf-cv-toolbar|\.mf-cv-close|\.mf-cv-stack|\.mf-cv-count/.test(sel)),
  ...extractRules(glassCss, (sel) => /\.g2-fab/.test(sel)),
  ...extractRules(designSystem, (sel) => /\.ds-chrome-band/.test(sel)),
];

// ── 作用域化：把 `body.mobile-layer` 换成面板壳，其余选择器一律前缀 ─────────
function scopeRules(rules, scopeSel) {
  return rules.map(({ selector, body }) => {
    const parts = selector.split(',').map((p) => {
      const s = p.trim().replace(/^body\.mobile-layer\b/, '').trim();
      return s ? `${scopeSel} ${s}` : scopeSel;
    });
    return `${parts.join(', ')} {${body}}`;
  }).join('\n');
}

// ── 令牌：真实页面计算样式（只取方案用得到的族）────────────────────────────
const TOKEN_PREFIX = ['--nf-glass', '--ds-', '--mfs-', '--mchrome', '--mpad', '--mtap', '--mtitle', '--micon', '--g2-', '--glass', '--cv-', '--color-', '--sapphire', '--ease', '--dur'];
function tokenBlock(theme) {
  const all = { ...(probe.themes[theme].probe.tokens || {}), ...(probe.themes[theme].probe.bodyTokens || {}) };
  return Object.entries(all)
    .filter(([k]) => TOKEN_PREFIX.some((p) => k.startsWith(p)))
    .map(([k, v]) => `  ${k}: ${v};`)
    .join('\n');
}

// ── 方案 CSS：拆出两段（glass 成员段 / mobileLayer 追加段）──────────────────
function variantParts(file) {
  const src = readFileSync(join(HERE, 'variants', file), 'utf8');
  const grab = (tag) => {
    const m = new RegExp(`/\\* @${tag}:start \\*/([\\s\\S]*?)/\\* @${tag}:end \\*/`).exec(src);
    if (!m) return [];
    return extractRules(m[1], () => true);
  };
  return { glass: grab('glass'), mobile: grab('mobile') };
}

const VARIANTS = [
  {
    id: 'a', key: 'A', name: '通栏玻璃带', file: 'a-band.css',
    intent: '与内容割裂', backdrop: 'nochrome',
    line: '整条 72px 顶栏是一个玻璃面：叉号键 + 三个等宽签 + 堆叠键同住一条带里。',
    depth: '背后 = 文档正文全宽滚过（玻璃接的是整幅正文的颜色，不是某枚签下面那一小块）。',
    scroll: '贴顶钉住；正文始终从带底下穿过，无让位、无收起。',
    gap: '两枚圆钮降为**带内扁平图标键**（与签同一水平线 40，玻璃只有一层，不叠第二层玻璃）。',
    change: 'mobileLayer.css 追加块 + glass.css 成员表一行 + navController 环位姿停写（3 文件，零新令牌）。',
  },
  {
    id: 'b', key: 'B', name: '悬浮玻璃胶囊', file: 'b-capsule.css',
    intent: '可读性', backdrop: 'plain',
    line: '三个签收进一枚 231×44 的胶囊，左右各让 72px 给圆钮；三签等宽，每签约 74px 绘制宽。',
    depth: '背后 = 正文；胶囊两侧各 8px 气隙里正文保持原样（读作“浮在内容上的一枚透镜”）。',
    scroll: '贴顶钉住（top 18 / 中心 40 与圆钮同线）；正文从胶囊下方穿过。',
    gap: '与两枚圆钮同一条水平中心线（40），左右各留 8px 气隙（64→72 / 303→311）。',
    change: 'mobileLayer.css 追加块 + glass.css 成员表一行 + navController 环位姿停写 / 横滑行程重算（3 文件，零新令牌）。',
  },
  {
    id: 'c', key: 'C', name: '玻璃底 + 内容透出渐隐边', file: 'c-fade.css',
    intent: '贴顶太硬', backdrop: 'plain',
    line: '顶栏没有下边缘：0–64px 是玻璃，64–92px 是一条 28px 渐隐带，正文从模糊里化出来。',
    depth: '背后 = 正文；同一行字滚过时依次经过“清晰 → 半洗 → 全糊”三档（剖面读数见 manifest）。',
    scroll: '贴顶钉住；滚走的行是被“化掉”的，不是被一条线“切掉”的。',
    gap: '两枚圆钮原样保留在带的两端（带通栏，玻璃与圆钮各自成层，不叠在同一块面上）。',
    change: 'mobileLayer.css 追加块 + glass.css 成员表一行；navController **零改动**（环签原样，2 文件，零新令牌）。',
  },
];

const img = (name) => `data:image/png;base64,${readFileSync(join(SHOTS, name)).toString('base64')}`;
const ICON = {
  x: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>',
  layers: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/></svg>',
};

// 环签 DOM（与 navController 的 buildCanvasPage / layoutRing 逐值同构：k=-1/0/+1）
const CHIPS = [
  { text: '设计规格.md', k: 1 },
  { text: '批报告.md', k: -1 },
  { text: '验收记录.md', k: 0 },
];
function chipsHTML() {
  return CHIPS.map(({ text, k }) => {
    const dx = 93.5 * k;
    const s = k === 0 ? 1.10 : 0.87;
    const op = k === 0 ? 1 : 0.9;
    const blur = k === 0 ? 0 : 0.4;
    const rot = -24 * k;
    const cls = ['mf-strip-chip', k === 0 ? 'on' : (k < 0 ? 'side-l' : 'side-r')].join(' ');
    return `<button type="button" class="${cls}" role="tab" aria-selected="${k === 0}" title="${text}"`
      + ` style="--rdx:${dx}px;--rsc:${s};--rop:${op};--rbl:${blur}px;--rrot:${rot}deg;--sdx:0px">`
      + `<span class="mf-strip-label">${text}</span></button>`;
  }).join('');
}

// 一个方案面板：作用域壳 `.panel-stage.vt-<id>.th-<theme>`
function variantPanel(v, theme) {
  const parts = variantParts(v.file);
  const scope = `.vt-${v.id}.th-${theme}`;
  const css = [
    `${scope} {\n${tokenBlock(theme)}\n}`,
    scopeRules(BASE_RULES, scope),
    scopeRules(parts.mobile, scope),
    scopeRules(parts.glass, scope),
  ].join('\n');
  const backdrop = img(`backdrop-${v.backdrop === 'nochrome' ? 'nochrome-' : ''}${theme}.png`);
  const fab = v.backdrop === 'nochrome'
    ? `<div class="mf-cv-toolbar ds-chrome-band">`
      + `<button type="button" class="mf-cv-close g2-fab" aria-label="关闭文档">${ICON.x}</button>`
      + `<span class="mf-cv-count">2/3</span>`
      + `<button type="button" class="mf-cv-stack g2-fab" aria-label="堆叠卡视图">${ICON.layers}</button>`
      + `</div>`
    : '';
  return {
    css,
    html: `<div class="panel-stage vt-${v.id} th-${theme}" data-panel="${v.id}-${theme}">
  <img class="backdrop" src="${backdrop}" alt="">
  <div class="mf-canvas-page">
    <div class="mf-strip ds-chrome-band fade" role="tablist" aria-label="文档卡片">${chipsHTML()}</div>
  </div>
  ${fab}
</div>`,
  };
}

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function currentPanel(theme) {
  return {
    css: '',
    html: `<div class="panel-stage th-${theme}" data-panel="current-${theme}">
  <img class="backdrop" src="${img('current-' + theme + '.png')}" alt="">
</div>`,
  };
}

// ── 组装 ───────────────────────────────────────────────────────────────────
const panels = [];
for (const theme of ['light', 'dark']) {
  panels.push(currentPanel(theme));
  for (const v of VARIANTS) panels.push(variantPanel(v, theme));
}
const allCss = panels.map((p) => p.css).filter(Boolean).join('\n');

const frameCss = `
.panel-stage{position:relative;width:375px;height:812px;overflow:hidden;background:#f5f6f8}
.panel-stage .backdrop{position:absolute;left:0;top:0;width:375px;height:812px;display:block}
.panel-stage .mf-canvas-page{position:absolute;inset:0}
.panel-stage button{font-family:inherit}
.panel-stage *{box-sizing:border-box}
`;

function block(theme) {
  const cells = [
    `<figure class="panel" data-panel-wrap="current-${theme}">
  <div class="phone">${panels.find((p) => p.html.includes(`data-panel="current-${theme}"`)).html}</div>
  <figcaption>现状 · ${theme === 'dark' ? '暗' : '亮'}</figcaption>
</figure>`,
    ...VARIANTS.map((v) => {
      const p = panels.find((x) => x.html.includes(`data-panel="${v.id}-${theme}"`));
      return `<figure class="panel" data-panel-wrap="${v.id}-${theme}">
  <div class="phone">${p.html}</div>
  <figcaption>${v.key} · ${v.name} · ${theme === 'dark' ? '暗' : '亮'}</figcaption>
</figure>`;
    }),
  ].join('\n  ');
  return `<section class="row">
  <header class="rowhead"><h2>${theme === 'dark' ? '暗色档' : '亮色档'} · 375 × 812</h2>
    <p>同一段正文、同一滚动位（scrollTop 118）——正文正压在顶栏底下，是这批要看的形态。</p>
  </header>
  <div class="grid">
  ${cells}
  </div>
</section>`;
}

const cards = VARIANTS.map((v) => `
<article class="card" id="card-${v.id}">
  <h3><b>${v.key} · ${v.name}</b><span class="tag">解决「${v.intent}」</span></h3>
  <p class="lead">${v.line}</p>
  <dl>
    <dt>玻璃层级</dt><dd>${v.depth}</dd>
    <dt>滚动行为</dt><dd>${v.scroll}</dd>
    <dt>与胶囊族</dt><dd>${v.gap}</dd>
    <dt>改动面</dt><dd>${v.change}</dd>
  </dl>
</article>`).join('\n');

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>手机端 Canvas 顶部标签页 · 三方案对照（375×812）</title>
<style>
:root{ --bg:#0e1015; --fg:#e8eaed; --muted:#9aa0aa; --line:#242833; --card:#151923; }
html,body{margin:0;background:var(--bg);color:var(--fg);font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Hiragino Sans GB","Helvetica Neue",Arial,sans-serif}
body{padding:36px 40px 64px}
h1{font-size:22px;font-weight:650;margin:0 0 6px}
.sub{color:var(--muted);font-size:13px;margin:0 0 26px;line-height:1.75;max-width:1700px}
.sub code{background:#1b1f2a;padding:1px 5px;border-radius:4px;font-size:12px}
.row{margin:0 0 40px}
.rowhead h2{font-size:15px;font-weight:600;margin:0 0 4px;letter-spacing:.01em}
.rowhead p{color:var(--muted);font-size:12px;margin:0 0 14px}
.grid{display:flex;gap:22px;align-items:flex-start;flex-wrap:wrap}
.panel{margin:0}
.phone{position:relative;width:375px;height:812px;border-radius:26px;overflow:hidden;background:#000;box-shadow:0 0 0 1px var(--line)}
figcaption{font-size:12px;color:var(--muted);margin-top:8px;letter-spacing:.02em}
.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin:8px 0 0}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
.card h3{margin:0 0 10px;font-size:14px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.card h3 b{font-weight:650}
.tag{font-size:11px;color:#c3c8ff;background:rgba(94,106,210,.18);border:1px solid rgba(94,106,210,.35);padding:2px 7px;border-radius:999px}
.card .lead{margin:0 0 12px;font-size:13px;line-height:1.7}
.card dl{margin:0;display:grid;grid-template-columns:64px 1fr;gap:6px 10px;font-size:12px}
.card dt{color:var(--muted)}
.card dd{margin:0;line-height:1.65}
.foot{margin-top:34px;color:var(--muted);font-size:12px;line-height:1.9;border-top:1px solid var(--line);padding-top:16px;max-width:1700px}
${frameCss}
${allCss}
</style>
</head>
<body>
<h1>手机端 Canvas 顶部标签页 · 三方案对照</h1>
<p class="sub">三个方案的顶栏都画在<strong>同一帧真实页面截图</strong>上（375×812，正文已滚到 scrollTop 118，正压在顶栏底下）。面板里的签、玻璃面、圆钮全部由 <code>variants/*.css</code> 原文渲染 —— 与跑门禁的是同一份文本；基础几何从产品树逐条抽取，令牌取真实页面计算样式。零外链，断网可开。</p>
${['light', 'dark'].map(block).join('\n')}
<section class="cards">
${cards}
</section>
<div class="foot">
<p>读法：现状那一格是产品当前渲染 —— 侧签<b>没有 blur</b>（同一元素上 mask × backdrop-filter 会被合成器架空，代码注释里写明「blur 只留给中签」），所以正文从侧签里穿过去时字还是清楚的。B 的两侧 8px 缝、C 的 64→92 渐隐坡，是这一批要请作者裁的形态差别。对比度读数与三门禁读数见 <code>manifest.json</code>。</p>
<h4>渲染说明（如实披露截图装置的边界）</h4>
<ol>
<li><b>同文档渲染，不用 iframe。</b>实测 Chromium 151 里 <code>backdrop-filter</code> 在 iframe 内完全不生效（计算样式是 <code>blur(40px)</code>、画面零模糊）。故六格全部改为「同一文档 + <code>.panel-stage.vt-&lt;id&gt;.th-&lt;theme&gt;</code> 作用域类前缀」，渲染链路与产品页同构。</li>
<li><b>Chromium 对「文字图层」的 backdrop 糊化偏弱。</b>A/B 对照实证：同一 <code>blur(40px)</code> 打在条纹背景上能把条纹彻底抹平（4/12/24/40 四档都抹平），打在文字背景上只表现为「变淡 + 轻微糊边」。这是合成器对文字层的既有弱化，不是方案 CSS 的问题 —— 沿用产品现网同样的属性组合，截出来的就是产品此刻的样子。</li>
<li><b>玻璃确实在起作用，有同帧读数自证。</b><code>readings.json</code> 的渐隐坡剖面（藏签藏钮后逐行亮度标准差峰值）：玻璃区内 5–33，正文区 80–86，压低 3–10 倍；文字对比度实测全部 ≥ 7.0:1。真实 iOS Safari 上同一 <code>blur(40px)</code> 的糊化更彻底（产品现网 <code>#input-bar</code> 已验证）。</li>
</ol>
</div>
</body>
</html>`;

writeFileSync(join(HERE, 'index.html'), html);
console.log(`index.html 已生成（${(html.length / 1024 / 1024).toFixed(2)} MB，自包含）`);

// ── 总览页：现状 + 三方案（一行亮、一行暗），供截图成 overall.png ─────────
function overviewRow(theme) {
  const cells = [`<figure class="cell"><div class="phone">${panels.find((p) => p.html.includes(`data-panel="current-${theme}"`)).html}</div><figcaption>现状 · ${theme === 'dark' ? '暗' : '亮'}</figcaption></figure>`]
    .concat(VARIANTS.map((v) => {
      const p = panels.find((x) => x.html.includes(`data-panel="${v.id}-${theme}"`));
      return `<figure class="cell"><div class="phone">${p.html}</div><figcaption>${v.key} ${v.name} · ${theme === 'dark' ? '暗' : '亮'}</figcaption></figure>`;
    }));
  return `<div class="orow">${cells.join('')}</div>`;
}
const overview = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
html,body{margin:0;background:#0e1015;color:#e8eaed;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue",Arial,sans-serif}
body{padding:28px 30px}
h1{font-size:19px;font-weight:650;margin:0 0 4px}
.sub{color:#9aa0aa;font-size:12px;margin:0 0 20px}
.orow{display:flex;gap:18px;margin-bottom:20px;align-items:flex-start}
.cell{margin:0}
.phone{position:relative;width:375px;height:812px;border-radius:20px;overflow:hidden;background:#000;box-shadow:0 0 0 1px #242833}
figcaption{font-size:11.5px;color:#9aa0aa;margin-top:7px;letter-spacing:.02em}
${frameCss}
${allCss}
</style></head><body>
<h1>现状 vs 三方案 · 手机端 Canvas 顶部标签页</h1>
<p class="sub">375×812 · 真实页面底衬 · 正文压在顶栏底下（scrollTop 118）· 上排亮色 / 下排暗色</p>
${overviewRow('light')}
${overviewRow('dark')}
</body></html>`;
writeFileSync(join(HERE, 'overview.html'), overview);
console.log('overview.html 已生成');

// ── 推荐页：只放 B 的亮/暗两格（vault 里的「推荐图」）──────────────────────
const pick = (id) => panels.find((x) => x.html.includes(`data-panel="${id}"`)).html;
const recommend = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
html,body{margin:0;background:#0e1015;color:#e8eaed;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue",Arial,sans-serif}
body{padding:24px 28px}
h1{font-size:17px;font-weight:650;margin:0 0 16px}
.orow{display:flex;gap:20px;align-items:flex-start}
.phone{position:relative;width:375px;height:812px;border-radius:20px;overflow:hidden;background:#000;box-shadow:0 0 0 1px #242833}
${frameCss}
${allCss}
</style></head><body>
<h1>推荐 · B 悬浮玻璃胶囊（左 亮 / 右 暗）</h1>
<div class="orow"><div class="phone">${pick('b-light')}</div><div class="phone">${pick('b-dark')}</div></div>
</body></html>`;
writeFileSync(join(HERE, 'recommend.html'), recommend);
console.log('recommend.html 已生成');
