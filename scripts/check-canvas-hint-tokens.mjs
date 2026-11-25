#!/usr/bin/env node
// check-canvas-hint-tokens.mjs — Canvas 提示文字「令牌唯一性」静态门禁
//
// canvas-hint-text 批（2026-11-05，作者令「canvas 内所有的提示文字，要统一制作
// 规范」）。运行面对应 tests/canvas-hint-safe-area.spec.mjs（真几何），本文件是
// **静态面**：把「提示文字的样式只能来自规范三字档令牌」这条规矩钉成可机检。
//
// 为什么另立一支静态闸（与真几何腿不重复）：
//   几何腿只能量「看得见的那几态」；静态腿量**全部声明**——包括用户这一次没触发的
//   空态/加载态/错误态。一旦有人给某处提示文字写死一个 px 或色值，几何腿可能全绿
//   （他刚好没打开那一态），静态腿立刻红。
//
// 判据（每条都有命中数读数）：
//   R1 规范层存在：css/canvasHint.css 声明三字档角色全部令牌，且**零裸 px / 零裸色值**
//   R2 四边安全区令牌齐备：css/sapphire.css 里 --cv-safe-{top,right,bottom,left,gap}
//      五个全部在场（本批从单边扩成四边的判据）
//   R3 清单规则零裸值：清单内每条提示文字规则里不许出现 `Npx` 字号 / `#hex` /
//      `rgb(...)`（`rgb(var(--sapphire) / α)` 属令牌通道，放行）
//   R4 清单规则用规范字档：font-size 只许取三字档令牌（或其等值 --ds-fs-* 令牌），
//      color 只许取规范色令牌（或其等值 --color-frame-text-* 令牌）
//   R5 清单规则用三档行高：line-height 只许取 --cv-hint-lh-* / --ds-lh-*，不许裸比值
//      （豁免：单行控件类 --ds-lh-tight 已在档内；`line-height: 1` 之类裸值不放行）
//   R6 安全区容器只写令牌：`.cv-safe-pad` 规则里的 padding 四条只许 var(--cv-safe-*)
//   R7 标记白名单：全仓 `data-cv-hint="X"` 的 X 必须 ∈ 规范形态白名单
//
// 用法：
//   node scripts/check-canvas-hint-tokens.mjs                    # 当前树（门禁用）
//   node scripts/check-canvas-hint-tokens.mjs --web-root <dir>   # 另一棵树（改前取证）
//   node scripts/check-canvas-hint-tokens.mjs --json             # 机器读
// 退出码：0 = 全绿；1 = 有 FAIL。
import { readFileSync, existsSync } from 'node:fs';
import { join, isAbsolute, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('..', import.meta.url));
const argv = process.argv.slice(2);
const argVal = (name, def = null) => {
  const i = argv.indexOf(`--${name}`);
  if (i >= 0 && argv[i + 1]) return argv[i + 1];
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(eq.indexOf('=') + 1) : def;
};
const JSON_OUT = argv.includes('--json');
const WEB_SPEC = argVal('web-root', '') || join(HERE, 'src/main/resources/web');
const WEB = normalize(isAbsolute(WEB_SPEC) ? WEB_SPEC : join(HERE, WEB_SPEC));

/** 规范形态白名单（与 css/canvasHint.css 头注同一份）。 */
const FORMS = ['page', 'divider', 'empty', 'loading', 'foot', 'tip', 'status', 'meta', 'placeholder'];

/** 三字档令牌（规范 §①）：字号 / 行高 / 颜色。 */
const FS_TOKENS = ['--cv-hint-fs-h', '--cv-hint-fs-m', '--cv-hint-fs-placeholder'];
const FS_EQUIV = ['--ds-fs-hint', '--ds-fs-meta', '--ds-fs-body'];
const LH_TOKENS = ['--cv-hint-lh-h', '--cv-hint-lh-m'];
const LH_EQUIV = ['--ds-lh-std', '--ds-lh-tight', '--ds-lh-title', '--ds-lh-body'];
const COLOR_TOKENS = ['--cv-hint-color', '--cv-hint-color-dim', '--cv-hint-color-strong', '--cv-hint-color-accent'];
const COLOR_EQUIV = ['--color-frame-text', '--color-frame-text-muted', '--color-frame-text-dim', '--color-frame-text-bright'];

/** 盘点清单 = 规范文档「盘点表」的机器可读镜像（新增一处提示文字 ⇒ 这里加一行）。 */
const INVENTORY = [
  // 文件, 选择器（CSS 里必须存在这条规则）, 形态, 说明
  ['css/sidebar.css', '.plugins-loading', 'loading', '插件面·加载态'],
  ['css/sidebar.css', '.plugins-stats', 'foot', '插件面·统计行'],
  ['css/sidebar.css', '.plugins-section-title', 'divider', '插件面·小节标签'],
  ['css/sidebar.css', '.plugins-section-hint', 'page', '插件面·区块说明行'],
  ['css/sidebar.css', '.plugins-none', 'empty', '插件面·空态'],
  ['css/sidebar.css', '.agent-mgr-loading,\n.agent-mgr-empty', 'empty', 'agent 面·空态/加载'],
  ['css/sidebar.css', '.agent-detail-face-hint', 'page', 'agent 面·详情说明行'],
  ['css/sidebar.css', '.agent-detail-builtin-note', 'foot', 'agent 面·内置微注'],
  ['css/sidebar.css', '.agent-detail-extends', 'foot', 'agent 面·继承微注'],
  ['css/split.css', '.nf-findbar-count', 'foot', '搜索面·命中计数'],
  ['css/split.css', '.wg-stats', 'foot', '知识图谱面·统计行'],
  ['css/split.css', '.wg-hint', 'divider', '知识图谱面·操作图例'],
  ['css/split.css', '.wg-detail-empty', 'empty', '知识图谱面·详情空态'],
  ['css/split.css', '.wg-crumbs', 'foot', '知识图谱面·面包屑'],
  ['css/split.css', '.document-term-card > strong', 'tip', '文档面·悬浮提示标题'],
  ['css/split.css', '.document-term-summary', 'tip', '文档面·悬浮提示正文'],
  ['css/split.css', '.document-term-meta', 'tip', '文档面·悬浮提示脚注'],
  ['css/split.css', '.epub-progress-tip', 'tip', 'epub 面·进度提示'],
  ['css/split.css', '.epub-toc-title', 'divider', 'epub 面·目录标题'],
  ['css/split.css', '.canvas-md-viewer .nf-backlinks', 'foot', '文档面·文末脚注'],
  ['css/split.css', '.deck-card .n-sub', 'foot', '叠层卡·副标题'],
  ['css/flowMap.css', '.fm-barrier-hint', 'status', '流程图面·barrier 状态提示'],
  ['css/flowMap.css', '.fm-panel-empty', 'empty', '流程图面·归档面板空态'],
  ['css/flowMap.css', '.fm-panel-guide', 'foot', '流程图面·读取指引'],
  ['css/flowMap.css', '.fm-panel-count', 'foot', '流程图面·面板计数'],
  ['css/projectPanel.css', '.project-empty-tag', 'empty', '项目面·空标记'],
  ['css/projectPanel.css', '.dev-hintline', 'page', '项目面·远端语义提示行'],
  ['css/diffView.css', '.nf-fc-empty', 'empty', '差异面·空态'],
  ['css/diffView.css', '.nfp-empty', 'empty', '差异面·空态（旧类）'],
];

/** 占位（::placeholder）清单：只查「色走令牌 + 透明度归 1」。 */
const PLACEHOLDERS = [
  ['css/split.css', '.nf-findbar-input::placeholder', '搜索面·占位'],
  ['css/split.css', '.wg-search-input::placeholder', '知识图谱面·占位'],
  ['css/ask.css', '.ask-input textarea::placeholder', 'ask 面·占位'],
  ['css/ask.css', '.note-body textarea::placeholder', 'note 面·占位'],
];

const rows = [];
let pass = 0, fail = 0;
function record(id, name, ok, readout) {
  rows.push({ id, name, verdict: ok ? 'PASS' : 'FAIL', readout });
  if (!JSON_OUT) console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}\n        ${readout}`);
  if (ok) pass++; else fail++;
}

const readWeb = (rel) => {
  const p = join(WEB, rel);
  if (!existsSync(p)) return null;
  return readFileSync(p, 'utf8');
};
const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');

/** 抽出所有 `selector-list { body }` 块（CSS 已去注释；不做嵌套媒体查询的语义还原，
 *  但把每个块的完整选择器文本与 body 都取到 —— 清单里的选择器按「整段相等」匹配）。 */
function blocks(css) {
  const out = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(css))) {
    out.push({ sel: m[1].trim().replace(/\s+/g, ' '), body: m[2], index: m.index });
  }
  return out;
}

/** 按清单选择器找规则：选择器整段（含逗号列表）相等，或作为列表成员出现。 */
function findRules(all, sel) {
  const want = sel.replace(/\s+/g, ' ');
  const wanted = want.split(',').map((s) => s.trim()).filter(Boolean);
  return all.filter((b) => {
    const have = b.sel.split(',').map((s) => s.trim()).filter(Boolean);
    // 清单项可以是「多选择器一组」：要求目标的每一项都在该块的选择器列表里
    return wanted.every((w) => have.includes(w));
  });
}

const decls = (body) => body.split(';').map((d) => d.trim()).filter(Boolean).map((d) => {
  const i = d.indexOf(':');
  return i < 0 ? { prop: d, val: '' } : { prop: d.slice(0, i).trim(), val: d.slice(i + 1).trim() };
});

const BARE_PX = /(^|[^\w-])\d*\.?\d+px\b/;
const BARE_HEX = /#[0-9a-fA-F]{3,8}\b/;
const BARE_RGB = /rgba?\(\s*\d/;

// ── R1 规范层存在 + 零裸值 ──────────────────────────────────────────────
{
  const css = readWeb('css/canvasHint.css');
  if (!css) {
    record('R1', '规范层 css/canvasHint.css 存在且三字档令牌齐备', false, '文件不存在 —— 规范没有可执行面');
  } else {
    const body = stripComments(css);
    const need = [...FS_TOKENS, '--cv-hint-fw', '--cv-hint-fw-s', '--cv-hint-fw-strong',
      ...LH_TOKENS, ...COLOR_TOKENS, '--cv-hint-lines', '--cv-hint-lines-tip'];
    const missing = need.filter((t) => !new RegExp(`${t}\\s*:`).test(body));
    // 令牌声明区（:root 块）零裸 px / 零裸色值
    const rootBlocks = blocks(body).filter((b) => /:root/.test(b.sel));
    const dirty = [];
    for (const b of rootBlocks) {
      for (const d of decls(b.body)) {
        if (!d.prop.startsWith('--')) continue;
        if (BARE_PX.test(d.val)) dirty.push(`${d.prop}: ${d.val}（裸 px）`);
        if (BARE_HEX.test(d.val)) dirty.push(`${d.prop}: ${d.val}（裸 hex）`);
        if (BARE_RGB.test(d.val)) dirty.push(`${d.prop}: ${d.val}（裸 rgb）`);
      }
    }
    record('R1', '规范层 css/canvasHint.css 存在且三字档令牌齐备（声明区零裸 px / 零裸色值）',
      missing.length === 0 && dirty.length === 0,
      `令牌 ${need.length - missing.length}/${need.length}` + (missing.length ? ` · 缺 ${missing.join(',')}` : '') +
      ` · 裸值 ${dirty.length} 处` + (dirty.length ? `：${dirty.join(' / ')}` : ''));
  }
}

// ── R2 四边安全区令牌 ──────────────────────────────────────────────────
{
  const css = stripComments(readWeb('css/sapphire.css') || '');
  const need = ['--cv-safe-top', '--cv-safe-right', '--cv-safe-bottom', '--cv-safe-left', '--cv-safe-gap'];
  const missing = need.filter((t) => !new RegExp(`${t}\\s*:`).test(css));
  // 四边值必须是令牌引用（var(...)），不许写死像素
  const hard = [];
  for (const t of need) {
    const m = css.match(new RegExp(`${t}\\s*:\\s*([^;]+);`));
    if (!m) continue;
    const v = m[1].trim();
    if (!v.includes('var(')) hard.push(`${t}: ${v}`);
  }
  record('R2', '四边安全区令牌齐备（top/right/bottom/left/gap 全在场且取令牌值）',
    missing.length === 0 && hard.length === 0,
    `在场 ${need.length - missing.length}/${need.length}` + (missing.length ? ` · 缺 ${missing.join(',')}` : '') +
    ` · 写死值 ${hard.length}` + (hard.length ? `：${hard.join(' / ')}` : ''));
}

// ── R3/R4/R5 清单规则的裸值与字档 ─────────────────────────────────────
{
  const cache = new Map();
  const all = (file) => {
    if (!cache.has(file)) cache.set(file, blocks(stripComments(readWeb(file) || '')));
    return cache.get(file);
  };
  const bare = [], offRecipe = [], offLh = [], notFound = [];
  for (const [file, sel, form, why] of INVENTORY) {
    const rules = findRules(all(file), sel);
    if (!rules.length) { notFound.push(`${file} ${sel}`); continue; }
    const merged = decls(rules.map((r) => r.body).join(';'));
    const fsD = merged.filter((d) => d.prop === 'font-size' || d.prop === 'font');
    const colD = merged.filter((d) => /^color$/.test(d.prop));
    const lhD = merged.filter((d) => d.prop === 'line-height' || d.prop === 'font');
    for (const d of [...fsD, ...colD, ...lhD]) {
      if (BARE_PX.test(d.val) && !d.val.includes('var(')) bare.push(`${file} ${sel} → ${d.prop}: ${d.val}`);
      if (BARE_HEX.test(d.val)) bare.push(`${file} ${sel} → ${d.prop}: ${d.val}（裸 hex）`);
      if (BARE_RGB.test(d.val)) bare.push(`${file} ${sel} → ${d.prop}: ${d.val}（裸 rgb）`);
    }
    // 字号：必须取三字档（或等值 --ds-fs-*）
    if (!fsD.length) offRecipe.push(`${file} ${sel} → 无 font-size 声明（形态 ${form}）`);
    else {
      const v = fsD.map((d) => d.val).join(' ');
      const ok = [...FS_TOKENS, ...FS_EQUIV].some((t) => v.includes(`var(${t})`));
      if (!ok) offRecipe.push(`${file} ${sel} → font-size: ${v}`);
    }
    // 颜色
    if (!colD.length) offRecipe.push(`${file} ${sel} → 无 color 声明（形态 ${form}）`);
    else {
      const v = colD.map((d) => d.val).join(' ');
      const ok = [...COLOR_TOKENS, ...COLOR_EQUIV].some((t) => v.includes(`var(${t})`));
      // status 形态的强调色同样走规范令牌（--cv-hint-color-accent）
      if (!ok) offRecipe.push(`${file} ${sel} → color: ${v}`);
    }
    // 行高：必须取三档令牌（font 简写里的行高也算，裸比值不放行）
    const lhText = lhD.map((d) => d.val).join(' ');
    if (lhText) {
      const ok = [...LH_TOKENS, ...LH_EQUIV].some((t) => lhText.includes(`var(${t})`));
      const bareRatio = /(^|[\s/])\d*\.?\d+(\s|$|\/)/.test(lhText.replace(/var\([^)]*\)/g, ''));
      if (!ok || bareRatio) offLh.push(`${file} ${sel} → ${lhD.map((d) => `${d.prop}: ${d.val}`).join('; ')}`);
    }
  }
  record('R3', '清单规则零裸 px / 零裸色值（改后残留 0）', bare.length === 0,
    bare.length ? `${bare.length} 处：\n        - ` + bare.join('\n        - ') : `0 处裸值（清单 ${INVENTORY.length} 条规则全部走令牌）`);
  record('R4', '清单规则字档 = 规范三字档 + 规范色令牌', offRecipe.length === 0 && notFound.length === 0,
    offRecipe.length || notFound.length
      ? `${offRecipe.length} 处越档 / ${notFound.length} 条规则未找到：\n        - ` + [...offRecipe, ...notFound].join('\n        - ')
      : `${INVENTORY.length} 条规则全部取 var(--cv-hint-fs-*) / var(--cv-hint-color*)（或等值 --ds-fs-* / --color-frame-text-*）`);
  record('R5', '清单规则行高 = 三档令牌（无裸比值）', offLh.length === 0,
    offLh.length ? `${offLh.length} 处：\n        - ` + offLh.join('\n        - ') : '全部走 --cv-hint-lh-* / --ds-lh-*');
}

// ── R6 安全区容器只写令牌 ──────────────────────────────────────────────
{
  const css = stripComments(readWeb('css/canvasHint.css') || '');
  const rule = blocks(css).find((b) => b.sel === '.cv-safe-pad');
  let ok = true; const notes = [];
  if (!rule) { ok = false; notes.push('.cv-safe-pad 规则缺失'); } else {
    const ds = decls(rule.body).filter((d) => /^padding-/.test(d.prop));
    const bad = ds.filter((d) => !/var\(--cv-safe-(top|right|bottom|left)\)/.test(d.val));
    if (ds.length !== 4) { ok = false; notes.push(`padding 声明 ${ds.length} 条（应为 4 条 longhand）`); }
    if (bad.length) { ok = false; notes.push(`${bad.length} 条不取安全区令牌：${bad.map((d) => `${d.prop}: ${d.val}`).join('; ')}`); }
    notes.push(`四边 = ${ds.map((d) => `${d.prop.replace('padding-', '')}=${d.val}`).join(' ')}`);
    // 消费者：按文件名列出（.cv-safe-pad 的使用点）
    const consumers = ['js/plugins.js', 'js/agentManager.js']
      .filter((f) => (readWeb(f) || '').includes('cv-safe-pad'));
    notes.push(`消费者 = ${consumers.join(', ') || '（无）'}`);
    if (!consumers.length) ok = false;
  }
  record('R6', '安全区容器 .cv-safe-pad 四边只取 --cv-safe-* 令牌（且有真实消费者）', ok, notes.join(' · '));
}

// ── R7 标记白名单 ──────────────────────────────────────────────────────
{
  const files = ['js/plugins.js', 'js/agentManager.js', 'js/wikiGraph.js', 'js/fileSearch.js',
    'js/viewers/markdown.js', 'js/viewers/readerChrome.js', 'js/flowMapArchive.js', 'js/fmNodeCard.js',
    'js/diffPanel.js', 'js/diffCanvas.js', 'js/diffRender.js', 'js/projectTab.js', 'index.html'];
  const hits = [];
  for (const f of files) {
    const txt = readWeb(f);
    if (!txt) continue;
    const re = /data-cv-hint="([^"]+)"/g;
    let m;
    while ((m = re.exec(txt))) hits.push({ f, form: m[1] });
  }
  const bad = hits.filter((h) => !FORMS.includes(h.form));
  record('R7', `标记白名单（data-cv-hint ∈ {${FORMS.join(',')}}）`, bad.length === 0,
    `命中 ${hits.length} 处 · 非法 ${bad.length}` + (bad.length ? `：${bad.map((b) => `${b.f}=${b.form}`).join(',')}` : ''));
}

// ── 占位腿 ─────────────────────────────────────────────────────────────
{
  const cache = new Map();
  const all = (file) => {
    if (!cache.has(file)) cache.set(file, blocks(stripComments(readWeb(file) || '')));
    return cache.get(file);
  };
  const bad = [];
  for (const [file, sel, why] of PLACEHOLDERS) {
    const rules = findRules(all(file), sel);
    if (!rules.length) { bad.push(`${file} ${sel} 规则缺失`); continue; }
    const ds = decls(rules.map((r) => r.body).join(';'));
    const col = ds.find((d) => d.prop === 'color');
    const op = ds.find((d) => d.prop === 'opacity');
    if (!col) bad.push(`${file} ${sel} 无 color`);
    else {
      const ok = [...COLOR_TOKENS, ...COLOR_EQUIV].some((t) => col.val.includes(`var(${t})`));
      if (!ok) bad.push(`${file} ${sel} color: ${col.val}`);
    }
    if (BARE_HEX.test(col ? col.val : '')) bad.push(`${file} ${sel} 裸 hex`);
    if (op && op.val.trim() !== '1') bad.push(`${file} ${sel} opacity: ${op.val}（应显式归 1）`);
    if (!op) bad.push(`${file} ${sel} 缺 opacity: 1（透明度必须显式归 1，不许变相调色）`);
  }
  record('R8', '占位（::placeholder）色走令牌 + 透明度归 1', bad.length === 0,
    bad.length ? `${bad.length} 处：\n        - ` + bad.join('\n        - ') : `${PLACEHOLDERS.length} 处全部合规`);
}

if (JSON_OUT) {
  console.log(JSON.stringify({ web: WEB, pass, fail, rows }, null, 1));
} else {
  console.log(`\n═══ canvas-hint-tokens: web=${WEB} · PASS ${pass} / FAIL ${fail} ═══`);
  if (fail) {
    for (const r of rows.filter((x) => x.verdict === 'FAIL')) console.log(`  - ${r.id} ${r.name}`);
  }
}
process.exit(fail ? 1 : 0);
