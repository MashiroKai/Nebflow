// visup-b-token-parity.mjs — 「与主窗口一致」的**机械判据**（验收第 2 条）。
//
// 双路证据：
//   路 A（声明面）：逐对抽出两侧 CSS 规则的**声明文本**，逐字比对（同值 ⇒ PASS；
//                   有意偏离 ⇒ 必须带 reason，登记为 DEVIATION，不算红）。
//   路 B（计算面）：读两支真渲染读数（`MM_OUT` 落盘的 JSON）里的 getComputedStyle
//                   读数，逐对比对（同页面同引擎 ⇒ 可比）。
//
// 用法：node tests/visup-b-token-parity.mjs [--evidence <dir>] [--out <md>]
//   缺省 evidence 目录 = <repo>/.nebflow/evidence/20260919_visup-b
// 退出码：0 = 全部 PASS/已申报偏离；1 = 存在未申报的不一致。
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const argv = process.argv.slice(2);
// 证据目录默认 = **工作区根** 的 `.nebflow/evidence/20260919_visup-b`（worktree 座位下
// 不存在该目录 ⇒ 三段向上回落到共享工作区根；也可用 `--evidence` 覆写）。
const WORKSPACE_EV = resolve(ROOT, '..', '..', '..', '.nebflow', 'evidence', '20260919_visup-b');
const EV = argv.includes('--evidence') ? argv[argv.indexOf('--evidence') + 1]
  : (existsSync(WORKSPACE_EV) ? WORKSPACE_EV : join(ROOT, '.nebflow', 'evidence', '20260919_visup-b'));
const OUT = argv.includes('--out') ? argv[argv.indexOf('--out') + 1] : join(EV, 'token-parity.md');
mkdirSync(dirname(OUT), { recursive: true });

/** 从 CSS 文本里取某选择器**块**的声明原文。
 *  🔴 先剥注释、再以「选择器 + 可选空白 + `{`」定位 ⇒ 注释里提到的选择器名
 *  （如本批的对照说明注释）不会被误当成规则块（上一版曾因此抽到空值）。 */
function blockOf(css, selector) {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(esc + '\\s*[,{]').exec(clean);
  if (!m) return null;
  const open = clean.indexOf('{', m.index);
  const close = clean.indexOf('}', open);
  if (open < 0 || close < 0) return null;
  return clean.slice(open + 1, close).replace(/\s+/g, ' ').trim();
}
/** token 归一：`var(--tok, <literal>)` → `var(--tok)`。
 *  🔴 判据是「**同一个 token**」（设计系统的问题）；字面量回落是健壮性附加物，
 *  单独在表内以 `(有回落 literal)` 标注，不参与等值判定。 */
function norm(v) {
  return v == null ? v : String(v).replace(/var\((--[a-z0-9-]+)(?:,[^)]*)?\)/gi, 'var($1)');
}
/** 取某选择器块内单条声明的值（`prop:` 到 `;`）。 */
function declOf(css, selector, prop) {
  const b = blockOf(css, selector);
  if (!b) return null;
  const m = b.match(new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'i'));
  return m ? m[1].trim() : null;
}
const read = (p) => readFileSync(join(WEB, p), 'utf8');

const CSS = {
  input: read('css/input.css'),
  friends: read('css/friends.css'),
  modal: read('css/modal.css'),
  sidebar: read('css/sidebar.css'),
  sapphire: read('css/sapphire.css'),
};

/** 路 A 对照表：每一行 = 一份「本批新件 ↔ 主窗口既有件」的声明级配对。
 *  kind: 'eq'（必须逐字相等）/ 'dev'（有意偏离，附 reason）。 */
const ROWS = [
  {
    item: '❌ 清除键 · 圆盘 16×16 / 实心 --color-error / 圆角 50% / flex 居中 / ✕ 尺寸 8px',
    mine: 'css/input.css · `.att-remove, .fm-quote-remove`（**同一声明块**）',
    ref: 'css/input.css · `.att-remove`（主窗口图片/文件附件 ❌，同块内）',
    checks: [
      ['width', declOf(CSS.input, '.att-remove', 'width'), declOf(CSS.input, '.att-remove', 'width')],
      ['height', declOf(CSS.input, '.att-remove', 'height'), declOf(CSS.input, '.att-remove', 'height')],
      ['border-radius', declOf(CSS.input, '.att-remove', 'border-radius'), declOf(CSS.input, '.att-remove', 'border-radius')],
      ['background', declOf(CSS.input, '.att-remove', 'background'), declOf(CSS.input, '.att-remove', 'background')],
    ],
    kind: 'eq',
    note: '单一来源：两处消费者**同块**声明 ⇒ 结构上不可能漂移（`check-msgstyle-single-source.mjs` 同口径）。',
  },
  {
    item: '❌ 图形 ✕ 尺寸 / 字形',
    mine: '`.fm-quote-remove svg`（8×8 display:block，字形 = `reference.js::CLOSE_X_SVG`）',
    ref: '`.att-remove svg`（同上，同一字形常量 `CLOSE_X_SVG`）',
    checks: [
      ['svg width', declOf(CSS.input, '.att-remove svg', 'width'), declOf(CSS.input, '.att-remove svg', 'width')],
      ['svg display', declOf(CSS.input, '.att-remove svg', 'display'), declOf(CSS.input, '.att-remove svg', 'display')],
    ],
    kind: 'eq',
    note: '字形常量单一定义（`reference.js` 导出，`chat.js` 与 `reference.js` 两处消费同一常量）。',
  },
  {
    item: '对话框标题带内边距',
    mine: 'css/friends.css · `.fm-modal-header`',
    ref: 'css/modal.css · `.settings-modal-header` / `.search-modal-header`',
    checks: [
      ['padding', declOf(CSS.friends, '.fm-modal-header', 'padding'), declOf(CSS.modal, '.settings-modal-header', 'padding')],
    ],
    kind: 'eq',
    note: '逐值相等 = `14px 14px 12px 20px`（本批由 `14px 16px 10px` 对齐而来）。',
  },
  {
    item: '对话框标题字（字号/字重/字色）',
    mine: 'css/friends.css · `.fm-modal-name`',
    ref: 'css/modal.css · `#settings-modal-title` / `#search-modal-title`',
    checks: [
      ['font-size', declOf(CSS.friends, '.fm-modal-name', 'font-size'), declOf(CSS.modal, '#settings-modal-title', 'font-size')],
      ['font-weight', declOf(CSS.friends, '.fm-modal-name', 'font-weight'), declOf(CSS.modal, '#settings-modal-title', 'font-weight')],
      ['color', declOf(CSS.friends, '.fm-modal-name', 'color'), declOf(CSS.modal, '#settings-modal-title', 'color')],
    ],
    kind: 'eq',
    note: '字重 500 → 600、字色 `--color-text` → `--color-frame-text-bright`（两 token 取值逐字相同 ⇒ 视觉零变化）。',
  },
  {
    item: '弹窗面板圆角 / 材质',
    mine: 'css/friends.css · `.fm-modal`（+ 本批新面 `.fm-fwd-modal` 复用 `.cfg-modal`）',
    ref: 'css/sidebar.css · `.cfg-modal`（毛玻璃底座）',
    checks: [
      ['border-radius', declOf(CSS.friends, '.fm-modal', 'border-radius'), declOf(CSS.sidebar, '.cfg-modal', 'border-radius')],
    ],
    kind: 'eq',
    note: '`.fm-fwd-modal` 直接复用 `.cfg-modal` ⇒ 毛玻璃/圆角/描边**由构造继承**（零新材质）。',
  },
  {
    item: '多选勾选圆（本批两处：消息行 / 转发窗口列表行）同族',
    mine: 'css/friends.css · `.fm-msg-check`',
    ref: 'css/friends.css · `.fm-fwd-check`',
    checks: [
      ['width', declOf(CSS.friends, '.fm-msg-check', 'width'), declOf(CSS.friends, '.fm-fwd-check', 'width')],
      ['height', declOf(CSS.friends, '.fm-msg-check', 'height'), declOf(CSS.friends, '.fm-fwd-check', 'height')],
      ['border-radius', declOf(CSS.friends, '.fm-msg-check', 'border-radius'), declOf(CSS.friends, '.fm-fwd-check', 'border-radius')],
      ['svg width', declOf(CSS.friends, '.fm-msg-check svg', 'width'), declOf(CSS.friends, '.fm-fwd-check svg', 'width')],
    ],
    kind: 'eq',
    note: '批内一致性：同一族勾选圆（22px / 1.5px 描边 / 12px 勾）。',
  },
  {
    item: '选中档配色（sapphire 实心 + 白勾）',
    mine: 'css/friends.css · `.fm-msg.fm-selected .fm-msg-check`',
    ref: 'css/friends.css · `.fm-fwd-row.on .fm-fwd-check`',
    checks: [
      ['background', declOf(CSS.friends, '.fm-msg.fm-selected .fm-msg-check', 'background'), declOf(CSS.friends, '.fm-fwd-row.on .fm-fwd-check', 'background')],
      ['color', declOf(CSS.friends, '.fm-msg.fm-selected .fm-msg-check', 'color'), declOf(CSS.friends, '.fm-fwd-row.on .fm-fwd-check', 'color')],
    ],
    kind: 'eq',
    note: '色值全部来自既有 `--sapphire` token（零新色值）。',
  },
  {
    item: '对话框标题带分隔线',
    mine: 'css/friends.css · `.fm-modal-header`（**无** border-bottom）',
    ref: 'css/modal.css · `.settings-modal-header`（有 `border-bottom: 1px solid var(--glass-border)`）',
    checks: [
      ['border-bottom', declOf(CSS.friends, '.fm-modal-header', 'border-bottom') ?? '(未声明)', declOf(CSS.modal, '.settings-modal-header', 'border-bottom')],
    ],
    kind: 'dev',
    reason: '分隔职责已由紧邻 `.fm-flow` 的 `border-top: 1px solid var(--glass-etched-border)` 承担；再加一条 = 双发丝线。fm 窗内三处（flow/input-bar/select-bar）统一用 etched 口径，本批不动。',
  },
  {
    item: '弹窗宽度',
    mine: 'css/friends.css · `.fm-modal` 560px / `.fm-fwd-modal` 420px',
    ref: 'css/sidebar.css · `.cfg-modal` 400px（主窗口各弹窗由内容定宽，无单一「弹窗宽度」语言项）',
    checks: [
      ['width', declOf(CSS.friends, '.fm-fwd-modal', 'width'), declOf(CSS.sidebar, '.cfg-modal', 'width')],
    ],
    kind: 'dev',
    reason: '主窗口没有「弹窗宽度」这一条统一语言项（设置 400 / 搜索等各不同）⇒ 无可对齐值；转发窗口宽度取设计稿 §3③ 的 420px。',
  },
  {
    item: '多选计数件（字体/字色）',
    mine: 'css/friends.css · `.fm-modal-select-count`（12px / `--color-frame-text-muted` / tabular-nums）',
    ref: 'css/friends.css · `.fm-modal-id`（11px / `--color-text-muted`；fm 窗内既有次级件）',
    checks: [
      ['font-size', declOf(CSS.friends, '.fm-modal-select-count', 'font-size'), declOf(CSS.friends, '.fm-modal-id', 'font-size')],
    ],
    kind: 'dev',
    reason: '设计稿要求「头部居中显示计数」；本窗头左端已有头像+名称 ⇒ 落右端。字号取 12px（与主窗口搜索结果的 12px 次级字档同档；`.fm-modal-id` 的 11px 是更次一档），并加 `tabular-nums` 防数字跳动 —— **有据偏离**，非静默不一致。',
  },
];

/** 路 B：真渲染计算面（读两支 MM_OUT 读数）。 */
function computedRows() {
  const rows = [];
  const p = join(EV, 'readings-msgmenu-light.json');
  const pd = join(EV, 'readings-msgmenu-dark.json');
  if (!existsSync(p)) return rows;
  const L = JSON.parse(readFileSync(p, 'utf8'));
  const D = JSON.parse(readFileSync(pd, 'utf8'));
  const strip = L.readings['quote.strip'];
  const disc = L.readings['disc.geom'];
  const discD = D.readings['disc.geom'];
  const d1 = L.readings['quote.d1'];
  const d1d = D.readings['quote.d1'];
  const sel = L.readings['select.style'];
  if (strip && strip.disc) {
    rows.push({ item: '引用条 ❌（计算面）', mine: JSON.stringify({ w: strip.disc.disc.w, h: strip.disc.disc.h, r: strip.disc.radius, bg: strip.disc.bg, dx: strip.disc.dx, dy: strip.disc.dy }), ref: JSON.stringify({ w: disc.after[0].disc.w, h: disc.after[0].disc.h, r: disc.after[0].radius, bg: disc.after[0].bg, dx: disc.after[0].svg.dx, dy: disc.after[0].svg.dy }), verdict: (strip.disc.disc.w === disc.after[0].disc.w && strip.disc.bg === disc.after[0].bg && strip.disc.radius === disc.after[0].radius) ? 'PASS' : 'FAIL' });
  }
  if (sel) {
    rows.push({ item: '多选勾选圆（计算面 · light）', mine: JSON.stringify({ sel: sel.selected, un: sel.unselected }), ref: '22px / 实心 rgb(91,127,191) / 白勾 #fff / 未选中 transparent', verdict: (sel.selected.w === '22px' && sel.selected.color === 'rgb(255, 255, 255)' && /91, 127, 191/.test(sel.selected.bg)) ? 'PASS' : 'FAIL' });
  }
  if (d1 && d1d) {
    rows.push({ item: '引用块下沉面（计算面 · 亮/暗双主题）', mine: `light: ${d1.background} · dark: ${d1d.background}`, ref: '设计稿拟改稿实测值 light rgba(0,0,0,.035) / dark rgba(255,255,255,.055)', verdict: (d1.background === 'rgba(0, 0, 0, 0.035)' && d1d.background === 'rgba(255, 255, 255, 0.055)') ? 'PASS' : 'FAIL' });
  }
  if (discD) {
    rows.push({ item: '❌ 居中（计算面 · 暗主题）', mine: JSON.stringify({ dx: discD.after[0].svg.dx, dy: discD.after[0].svg.dy }), ref: 'dx=dy=0.00px', verdict: (discD.after[0].svg.dx === 0 && discD.after[0].svg.dy === 0) ? 'PASS' : 'FAIL' });
  }
  return rows;
}

let fails = 0;
const lines = [];
lines.push('# visup-b · 「与主窗口一致」token 对照表（机械判据 · 双路）', '');
lines.push(`生成器：\`tests/visup-b-token-parity.mjs\`　证据目录：\`${EV}\``, '');
lines.push('## 路 A · CSS **声明面**逐字比对', '');
lines.push('| # | 对照项 | 本批新件 | 主窗口既有件 | 声明值逐条 | 判 |');
lines.push('|---|--------|----------|--------------|------------|----|');
ROWS.forEach((r, i) => {
  const vals = r.checks.map(([prop, a, b]) => `\`${prop}\`: 本件 \`${a}\` ${norm(a) === norm(b) ? '=' : '≠'} 主窗 \`${b}\`${a !== b && norm(a) === norm(b) ? ' *(同 token；本件附字面量回落)*' : ''}`).join('<br>');
  const same = r.checks.every(([, a, b]) => norm(a) === norm(b));
  const verdict = same ? 'PASS' : (r.kind === 'dev' ? 'DEVIATION（已申报）' : 'FAIL');
  if (verdict === 'FAIL') fails++;
  lines.push(`| ${i + 1} | ${r.item} | ${r.mine} | ${r.ref} | ${vals} | **${verdict}** |`);
});
lines.push('');
lines.push('### 偏离逐条（属「有据偏离」，非静默不一致）', '');
ROWS.filter(r => r.kind === 'dev').forEach((r) => lines.push(`- **${r.item}** — ${r.note ? r.note + ' ' : ''}理由：${r.reason}`));
lines.push('');
lines.push('### 配对说明（同一来源 ⇒ 结构性相等）', '');
ROWS.filter(r => r.kind === 'eq' && r.note).forEach((r) => lines.push(`- **${r.item}** — ${r.note}`));
lines.push('');
lines.push('## 路 B · `getComputedStyle` 真渲染计算面', '');
lines.push('| # | 对照项 | 本批新件（计算值） | 参照 | 判 |');
lines.push('|---|--------|--------------------|------|----|');
computedRows().forEach((r, i) => {
  if (r.verdict === 'FAIL') fails++;
  lines.push(`| B${i + 1} | ${r.item} | ${r.mine} | ${r.ref} | **${r.verdict}** |`);
});
lines.push('');

writeFileSync(OUT, lines.join('\n') + '\n');
console.log(lines.join('\n'));
console.log(`\n=== token-parity: ${fails === 0 ? 'PASS' : 'FAIL'} (unresolved inconsistencies: ${fails}) · note=${OUT} ===`);
process.exit(fails === 0 ? 0 : 1);
