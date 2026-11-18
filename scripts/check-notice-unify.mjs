#!/usr/bin/env node
// check-notice-unify.mjs — 通知族「2–3 种类型 + 单一来源」静态门禁
// （notice-unify 批，2026-11-18；作者令：「要形成 lint 检查或者测试检查……先盘点，
// 然后统一到 2-3 种风格和类型的通知」）。
//
// Why a gate：收敛前全站有**五份各自的瞬时提示**（.nebflow-toast 单源 + .cfg-toast
// 自绘玻璃卡 + .fm-modal-toast 窗内 pill + .delegate-archive-toast 抽屉内 toast，外加
// 若干就地 notice），每份各写一套位置/材质/时长/图标；只靠人工审查 ⇒ 下一处新通知
// 又会自造第七种画法，没有任何东西变红。本闸把「只允许声明的 2–3 种类型」变成机器
// 判据：**未声明的通知类名一旦出现即红**（第四种风格 / 内联手写通知样式）。
//
// ── 判据（任一不成立即红）────────────────────────────────────────────────
//  N1 **类型白名单**：全站 css/js 里出现的通知类名 token 必须落在声明的三型之一
//     （toast 瞬时浮动 / banner 常驻横幅 / inline 就地提示）。未声明的 token ⇒ 红
//     （这正是「第四种风格」与「内联手写通知样式」的形态）。
//  N2 **toast 单一来源（文件面）**：toast 型类名只准出现在 `js/modal.js` /
//     `css/modal.css` / `css/glass.css`（glass 只把 .nebflow-toast 并入玻璃材质清单）。
//     其它文件出现即红 ⇒ 又一个自绘 toast。
//  N3 **banner 单一来源（文件面）**：banner 型类名只准出现在声明的那几处宿主
//     （notificationBanner.js / ws.js / chat.css / liveness.css / glass.css）。
//  N4 **类型数**：`js/modal.js` 的 `TOAST_TYPES` 必须恰为 ['error','info','success']
//     （新增第四种语义类型 ⇒ 红）。
//
// ── 用法 ────────────────────────────────────────────────────────────────
//   node scripts/check-notice-unify.mjs                 # 当前树（0 绿 / 1 红 / 2 环境错）
//   node scripts/check-notice-unify.mjs --web-root DIR  # 另一棵树（改前取证）
//   node scripts/check-notice-unify.mjs --json          # 机器读

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, isAbsolute, normalize } from 'node:path';
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

// ── 通知族三型白名单（本次收敛的最终形态；新增类名必须在本表声明，否则 N1 红） ──
//  toast  = 瞬时浮动提示（右下角、自动消失、可关/带下一步/带动作）；唯一源 = showToast。
//  banner = 常驻横幅（顶部条、可关闭或状态自消失）。
//  inline = 就地提示（依附各自语境，不浮动、不占全局层）。
const TYPES = {
  toast: {
    files: ['js/modal.js', 'css/modal.css', 'css/glass.css'],
    classes: [
      'nebflow-toast', 'nebflow-toast-error', 'nebflow-toast-info', 'nebflow-toast-success',
      'nebflow-toast-icon', 'nebflow-toast-msg', 'nebflow-toast-link',
      // 形状开关（closable / hint / action）——不改类型数，见 modal.js showToast。
      'nebflow-toast-rich', 'nebflow-toast-body', 'nebflow-toast-hint',
      'nebflow-toast-close', 'nebflow-toast-action',
    ],
  },
  banner: {
    files: ['js/notificationBanner.js', 'js/ws.js', 'css/chat.css', 'css/liveness.css', 'css/glass.css'],
    classes: [
      'notification-banner', 'notif-item', 'notif-icon', 'notif-text', 'notif-close',
      'conn-banner', 'conn-banner-glyph', 'conn-banner-text',
    ],
  },
  inline: {
    files: null, // 就地提示承载面依附各自语境（不占全局层），只登记类名，不约束宿主文件。
    classes: [
      'fm-modal-toast',            // 好友窗内**作用域内**一过性消息（必须留在该窗，见 messages.js 注释）
      'notice-card', 'notice-info',
      'perm-danger-banner',
      'pp-paused-banner',
      'mp-banner', 'mp-banner-text', 'mp-banner-view',
      'attachment-banner',
      'dropbox-notice',
      'nf-fence-notice',
      'neblink-kicked-notice',
      'pd-device-notice',
      'settings-identity-notice',
      'agent-notif-dot',
    ],
  },
};

/** 一个类名 token 是否属于「通知族」——必须含通知关键词且带连字符（裸 `notice`/
 *  字符串 `toast` 不误伤；i18n 键 `delegate.archive.toast` 用点分隔也不误伤）。 */
const NOTICE_TOKEN = /(^|-)(toast|snackbar|banner|notif|notification|notice)(-|$)/;

function walk(dir, ext) {
  const out = [];
  let entries = [];
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p, ext));
    else if (e.name.endsWith(ext)) out.push(p);
  }
  return out;
}

const stripBlockComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ');
/** JS：去块注释 + 去整行 `//` 注释，再取**引号串内容**里的类名 token。 */
function jsTokens(src) {
  const cleaned = stripBlockComments(src).replace(/^[ \t]*\/\/[^\n]*$/gm, ' ');
  const tokens = new Set();
  const strRe = /['"`]([^'"`\n]{0,300})['"`]/g;
  let m;
  while ((m = strRe.exec(cleaned))) {
    for (const w of m[1].split(/[^a-zA-Z0-9_-]+/)) {
      // 尾部 '-' = 字符串拼接碎片（'notif-' + n / 'nebflow-toast-' + type），不是类名。
      if (w.endsWith('-')) continue;
      if (w.includes('-') && NOTICE_TOKEN.test(w)) tokens.add(w);
    }
  }
  return tokens;
}
/** CSS：去块注释，取 `.class` 选择器 token。 */
function cssTokens(src) {
  const cleaned = stripBlockComments(src);
  const tokens = new Set();
  const re = /\.([a-zA-Z_][a-zA-Z0-9_-]*)/g;
  let m;
  while ((m = re.exec(cleaned))) {
    const w = m[1];
    if (w.endsWith('-')) continue;
    if (w.includes('-') && NOTICE_TOKEN.test(w)) tokens.add(w);
  }
  return tokens;
}

const allClasses = new Set(Object.values(TYPES).flatMap((t) => t.classes));
const typeOf = (cls) => Object.entries(TYPES).find(([, t]) => t.classes.includes(cls))?.[0];

const cssFiles = walk(join(WEB, 'css'), '.css');
const jsFiles = walk(join(WEB, 'js'), '.js');
if (cssFiles.length === 0 || jsFiles.length === 0) {
  console.error(`[notice-unify] cannot read web sources under ${WEB} — environment error`);
  process.exit(2);
}

const violations = [];
const hits = []; // {rel, cls, type}

function scan(files, extract, kind) {
  for (const f of files) {
    const rel = relative(WEB, f).split('\\').join('/');
    const src = readFileSync(f, 'utf8');
    for (const cls of extract(src)) hits.push({ rel, cls, kind });
  }
}
scan(cssFiles, cssTokens, 'css');
scan(jsFiles, jsTokens, 'js');

// N1 —— 白名单：未声明的通知类名 ⇒ 红（第四种风格 / 内联手写通知样式）
for (const h of hits) {
  if (!allClasses.has(h.cls)) {
    violations.push(`N1 未声明的通知类名 \`.${h.cls}\`（${h.rel}）—— 不在三型白名单内，第四种风格即红`);
  }
}
// N2 / N3 —— toast / banner 型的**样式定义**宿主白名单（只查 css；js 消费同一批类名
//   是「走单一来源」的正解，不是另开一份 —— 例如 activityBar/neblink 的带链接 toast
//   复用 .nebflow-toast*，不另立类名）。
for (const h of hits) {
  if (h.kind !== 'css') continue;
  const type = typeOf(h.cls);
  if (type !== 'toast' && type !== 'banner') continue;
  const allowed = TYPES[type].files;
  if (!allowed.includes(h.rel)) {
    violations.push(`N${type === 'toast' ? '2' : '3'} ${type} 型样式 \`.${h.cls}\` 定义在非单源宿主 ${h.rel} —— 又被自绘了一份 ${type} 样式`);
  }
}

// N4 —— 类型数恰为三（modal.js 的类型白名单）
let modal = '';
try { modal = readFileSync(join(WEB, 'js', 'modal.js'), 'utf8'); } catch { /* handled */ }
const typesMatch = /export const TOAST_TYPES\s*=\s*\[([^\]]*)\]/.exec(modal);
const declared = typesMatch
  ? typesMatch[1].split(',').map((s) => s.trim().replace(/^['"]|['"]$/g, '')).filter(Boolean)
  : [];
const EXPECTED_TYPES = ['error', 'info', 'success'];
if (JSON.stringify(declared) !== JSON.stringify(EXPECTED_TYPES)) {
  violations.push(`N4 modal.js TOAST_TYPES = [${declared.join(', ')}]，期望恰为 [${EXPECTED_TYPES.join(', ')}]`);
}

// 去重同类违规（同一类名可能命中同一文件多次）
const uniq = [...new Set(violations)];

if (JSON_OUT) {
  console.log(JSON.stringify({
    web: WEB, cssFiles: cssFiles.length, jsFiles: jsFiles.length,
    declaredTypes: declared, ok: uniq.length === 0, violations: uniq,
  }, null, 2));
} else {
  console.log(`[notice-unify] 扫描 ${cssFiles.length} css + ${jsFiles.length} js；声明三型共 ${allClasses.size} 个类名。`);
  if (uniq.length === 0) {
    console.log('[notice-unify] PASS —— 通知族仅三型、单一来源，无第四种风格。');
  } else {
    console.error(`[notice-unify] ${uniq.length} 处违规：`);
    for (const v of uniq) console.error('  ✗ ' + v);
    console.error('\n修复口径：新通知必须归入已声明的 toast / banner / inline 三型之一；瞬时提示一律');
    console.error('          走 js/modal.js 的 showToast（类型只声明 error/info/success），禁止另起一份类名与样式。');
  }
}
process.exit(uniq.length === 0 ? 0 : 1);
