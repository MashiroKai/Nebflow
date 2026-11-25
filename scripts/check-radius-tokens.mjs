#!/usr/bin/env node
// check-radius-tokens.mjs — 全面板圆角令牌覆盖静态闸（2026-11-23 作者第三次点名：
// 「怎么还有按钮是方的 不应该统一的是圆的吗」）。
//
// 扫描 src/main/resources/web/css（排除 vendor）全部 border-radius / border-*-radius 声明：
//   · 合法：var(--…) 令牌引用；50%（圆点/圆钮）；inherit（跟随宿主）；细件槽 2px（--ds-radius-thin）。
//   · 违例 A（硬编码）：字面 px / 999px / 0 / 百分比（除 50%）未经令牌 ——
//       按钮/卡片/菜单/弹层类选择器上 ⇒ 红（作者裁定：按钮统一圆）。
//   · 违例 B（方角）：按钮/卡片/菜单/弹层类选择器上 border-radius 为 0 ⇒ 红。
//   · 其余选择器上的字面量：计入 info（非按钮结构件，如气泡尾角 4px），不阻断，但必须在 ALLOW 表登记理由。
//
// 用法：node scripts/check-radius-tokens.mjs            # 闸（违例即 exit 1）
//       node scripts/check-radius-tokens.mjs --list     # 打印全量清单（不阻断）
// Exit：0 = 全绿；1 = 违例；2 = 环境缺失。

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
// STAND_RADIUS_WEB 仅供负对照（把注入了回归的临时副本指给闸）；缺省 = 本仓 web 根。
const WEB_ROOT = process.env.STAND_RADIUS_WEB || join(ROOT, 'src/main/resources/web');
const CSS_DIR = join(WEB_ROOT, 'css');
const LIST = process.argv.includes('--list');

// 令牌阶梯（design-system.css ⑤）：只有这些字面值在令牌化之前可被「等价」识别。
const TOKEN_VALUES = {
  '2px': '--ds-radius-thin', '4px': '--ds-radius-badge', '6px': '--ds-radius-chip',
  '8px': '--ds-radius-input', '10px': '--ds-radius-button', '12px': '--ds-radius-xl',
  '14px': '--ds-radius-card-md', '16px': '--ds-radius-card-lg', '18px': '--ds-radius-2xl',
  '20px': '--ds-radius-shell', '999px': '--ds-radius-pill',
};

// 按钮/卡片/菜单/弹层类选择器（结构件关键词，大小写不敏感）。
const UI_CLASS = /(btn|button|chip|pill|card|menu|modal|popover|dropdown|option|toggle|switch|tab|chat-?row|-row\b|row\b|sheet|overlay|dialog|panel|-item\b)/i;

// 显式允许表：选择器子串 → 理由。仅登记「有设计依据的非圆角」，禁止空放行。
const ALLOW = [
  // 画布/全屏/文档类容器：整面贴边，方角是设计语义（非按钮）。
  { sel: 'canvas-tab-pane', why: 'Canvas 页签内容区贴边全屏' },
  { sel: 'canvas-monaco-container', why: 'Monaco 编辑器内嵌' },
  { sel: 'overflow-guard', why: 'Monaco 内部几何' },
  { sel: 'canvas-pdf-viewer', why: 'PDF iframe 贴边' },
  { sel: 'card-window', why: '卡片窗口形态全屏 canvas（#canvas-panel 贴窗）' },
  { sel: '#todo-page', why: '待办页全屏整面（非卡片）' },
  { sel: 'ds-resize-handle', why: '分隔条细件' },
  // 手机整屏弹层：全宽全高 + !important 贴边，圆角无可见意义（作者待确认）。
  // 收编面页面化（mobileLayer.css 1066 起，dock 批）：页面体内 in-flow 全幅，逐段登记。
  { sel: 'mobile-page-body >', why: '收编面页面化：手机页面体内全幅 in-flow（dock 批，作者待确认）' },
  // 手机整屏弹层族（mobileLayer.css 4074 起，同块 100%×100% !important）。
  { sel: '#modal-box', why: '手机整屏弹层 100%×100% !important' },
  { sel: '#delete-box', why: '手机整屏弹层 100%×100% !important' },
  { sel: '#conflict-box', why: '手机整屏弹层 100%×100% !important' },
  { sel: '#rules-modal', why: '手机整屏弹层 100%×100% !important' },
  { sel: '#memory-modal', why: '手机整屏弹层 100%×100% !important' },
  { sel: '#secrets-modal', why: '手机整屏 secrets 弹层 100%×100% !important' },
  { sel: '.wsp-panel', why: '手机整屏工作区面板 100%×100% !important' },
  { sel: '.cfg-modal', why: '手机整屏配置弹层 100%×100% !important' },
  { sel: '#model-panel-overlay', why: '手机整屏模型面板 100%×100% !important' },
  { sel: 'daemon-config-overlay', why: '手机整屏守护配置页' },
  { sel: 'dropbox-modal', why: '手机整屏 dropbox 弹层（100%×100%）' },
  { sel: 'bgt-modal', why: '手机整屏后台任务弹层（100%×100%）' },
  { sel: 'secrets-modal', why: '手机整屏 secrets 弹层（100%×100%）' },
  { sel: 'flow-agent-modal', why: '手机整屏 flow-agent 弹层（.fullscreen）' },
  { sel: 'nebflow-login-modal', why: '手机整屏登录页（100%×100%）' },
  { sel: 'fm-context-menu', why: '手机全屏动作表（B 族：100% 贴边 + !important）' },
  { sel: '#profile-face-overlay .pf-modal', why: '个人面全屏（100%×100%）' },
  { sel: '#fm-chat-overlay.fm-chat-host > .fm-modal-main', why: '好友聊天全屏面，贴窗' },
  // 页面本体（非悬浮卡）：border:none 填满容器，「卡 = 页面本身」已退役卡外框（mobileLayer.css 同段注释）。
  { sel: '.tfl-mobile', why: '手机待办页本体；mobileLayer.css 注释：卡外框整条退役（待作者确认）' },
  { sel: '.flowmap-card', why: 'Flow Map 页面本体（border:none，填满容器，待作者确认）' },
  // 无可见底的件：圆角不产生可见差异。
  { sel: 'ob-ghost-seat', why: '幽灵座去面去圆角（style-2 设计意图，无可见底）' },
  { sel: 'thinking-dot', why: '思考点纯文字脉冲（无底，圆角无可见差）' },
  { sel: 'nf-wl-spin', why: '纯文字 spinner（border:0，无可见底）' },
];

const JS_DIR = join(WEB_ROOT, 'js');

function walk(dir, ext, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'vendor') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, ext, out);
    else if (name.endsWith(ext)) out.push(p);
  }
  return out;
}

// 扫描面 = 全部 CSS + 全部 JS（JS 内嵌样式表 / 内联 style 字符串同样计入：flowCss.js 等是整块样式表）。
const files = [...walk(CSS_DIR, '.css'), ...walk(JS_DIR, '.js')];
if (!files.length) { console.error('check-radius-tokens: no css/js found'); process.exit(2); }

const DECL_IN_RULE = /(border(?:-[a-z]+)*-radius)\s*:\s*([^;]+);?/g;
// JS 字符串里的内联 style 声明（无选择器）：`border-radius:0 10px` 这类。
const DECL_INLINE = /(border(?:-[a-z]+)*-radius)\s*:\s*([^;'"`}]+)/g;

// 逐段放行：组合选择器（a, b, c）的每一段都必须被某条允许项覆盖，才整条放行。
// 子串整条匹配会让一个关键词（如 fm-context-menu）把串在同一规则里的按钮一并放掉。
function allowedFor(sel) {
  const segs = sel.split(',').map((s) => s.trim()).filter(Boolean);
  if (!segs.length) return null;
  const reasons = [];
  for (const seg of segs) {
    const hit = ALLOW.find((a) => seg.includes(a.sel));
    if (!hit) return null;
    reasons.push(hit.why);
  }
  return reasons[0];
}

function classify(val) {
  if (/var\(/.test(val)) return 'token';
  if (val === 'inherit') return 'inherit';
  if (val === '50%') return 'circle';
  if (/^0(px)?$/.test(val)) return 'square';
  if (TOKEN_VALUES[val]) return 'literal-token-equiv';   // 字面量但值与令牌同档
  return 'literal-off-ladder';                            // 档外值 / 复合字面量（多角字面量同此档）
}

// 待办专属表的规则不受全局 ALLOW 或选择器关键词影响。允许多个令牌组成
// 四角值；fallback 也只能是圆角令牌，不能藏字面量或其他变量。
function strictRadiusValue(value) {
  let rest = value.replace(/\s*!important\s*$/i, '').trim();
  if (rest === 'inherit') return true;
  if (!rest) return false;
  function token() {
    const start = rest.match(/^var\(\s*--ds-radius-[a-z0-9-]+\s*/);
    if (!start) return false;
    rest = rest.slice(start[0].length);
    if (rest.startsWith(',')) {
      rest = rest.slice(1).trimStart();
      if (!token()) return false;
      rest = rest.trimStart();
    }
    if (!rest.startsWith(')')) return false;
    rest = rest.slice(1);
    return true;
  }
  while (rest) {
    if (!token()) return false;
    if (rest && !/^\s/.test(rest)) return false;
    rest = rest.trimStart();
  }
  return true;
}

const strictViolations = [];
let strictCount = 0;
const rows = [];
for (const file of files) {
  const raw = readFileSync(file, 'utf8');
  const isJs = file.endsWith('.js');
  // 去注释（保行号）。JS 的 // 行注释只剥 CSS 块注释之外不动（避免误伤 URL/字符串）。
  const src = raw.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const lineOf = (idx) => src.slice(0, idx).split('\n').length;
  if (file === join(CSS_DIR, 'mobile-todo-apple.css')) {
    // 整文件扫描声明，避免 @media / 选择器及全局 allow 表造成盲区。
    const strictDecl = /\b(border-radius|border-(?:top|bottom)-(?:left|right)-radius)\s*:\s*([^;}]*)(?:;|(?=\}))/gi;
    let decl;
    while ((decl = strictDecl.exec(src))) {
      strictCount++;
      if (!strictRadiusValue(decl[2])) {
        strictViolations.push({ file: relative(ROOT, file), line: lineOf(decl.index), prop: decl[1], val: decl[2].trim() });
      }
    }
  }
  const ruleRe = /([^{}]+)\{([^{}]*)\}/g;
  const seen = new Set();
  let m;
  while ((m = ruleRe.exec(src))) {
    const selRaw = m[1].trim();
    const body = m[2];
    if (!/border(-[a-z]+)*-radius\s*:/.test(body)) continue;
    if (/^@/.test(selRaw) || /^(from|to|\d+%)(\s*,\s*(from|to|\d+%))*$/.test(selRaw)) continue;
    const sel = selRaw.replace(/\s+/g, ' ').replace(/^['"`]+/, '');
    let d;
    DECL_IN_RULE.lastIndex = 0;
    while ((d = DECL_IN_RULE.exec(body))) {
      const val = d[2].replace(/!important/, '').trim();
      const line = lineOf(m.index + m[0].indexOf(d[0]));
      const key = `${file}:${line}:${d[1]}:${val}`;
      if (seen.has(key)) continue; seen.add(key);
      const kind = classify(val);
      const allowed = allowedFor(sel);
      rows.push({ file: relative(ROOT, file), line, sel, prop: d[1], val, kind, uiClass: UI_CLASS.test(sel), allowed: allowed || null });
    }
  }
  if (isJs) {
    let d;
    DECL_INLINE.lastIndex = 0;
    while ((d = DECL_INLINE.exec(src))) {
      const val = d[2].trim();
      const line = lineOf(d.index);
      const key = `${file}:${line}:${d[1]}:${val}`;
      if (seen.has(key)) continue; seen.add(key);
      // 动态拼接值（含 ${…}）不计：运行期由 JS 算定，非静态字面量。
      if (/\$\{/.test(val)) continue;
      rows.push({ file: relative(ROOT, file), line, sel: '(inline)', prop: d[1], val, kind: classify(val), uiClass: false, allowed: null });
    }
  }
}

// 违例判定（全局，不依赖选择器关键词——关键词会漏掉 .mf-me-entry 这类按钮式入口）：
//   · square（0 / 0px）与 literal-*（字面 px / 档外值）⇒ 红，除非命中 ALLOW（带理由）。
//   · circle（50%）/ inherit / var(--…) 恒合法。
// UI 列（关键词命中）只用于 --list 的人工核对，不决定红绿。
const violations = rows.filter((r) =>
  !r.allowed &&
  (r.kind === 'square' || r.kind === 'literal-token-equiv' || r.kind === 'literal-off-ladder'));

const tally = {};
for (const r of rows) tally[r.kind] = (tally[r.kind] || 0) + 1;

if (LIST) {
  for (const r of rows) console.log(`${r.kind.padEnd(20)} ${r.uiClass ? 'UI ' : '   '} ${r.file}:${r.line}  ${r.sel.slice(0, 80)}  ${r.prop}: ${r.val}${r.allowed ? '  [allow: ' + r.allowed + ']' : ''}`);
}

console.log(`check-radius-tokens: scanned ${files.length} css+js, ${rows.length} radius decls; ${JSON.stringify(tally)}`);
console.log(`check-radius-tokens: todo strict ${strictCount} declarations, ${strictViolations.length} violations`);
if (strictViolations.length) {
  for (const v of strictViolations) console.error(`  ${v.file}:${v.line}  ${v.prop}: ${v.val}  [todo-strict]`);
}
if (violations.length || strictViolations.length) {
  console.error(`FAIL ${violations.length} radius violation(s) (hardcoded / square; must use --ds-radius-* token or a ALLOW entry with a reason):`);
  for (const v of violations) console.error(`  ${v.file}:${v.line}  ${v.sel.slice(0, 90)}  ${v.prop}: ${v.val}  [${v.kind}]`);
  process.exit(1);
}
console.log('check-radius-tokens: green');
