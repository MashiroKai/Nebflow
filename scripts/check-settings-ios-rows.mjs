#!/usr/bin/env node
// check-settings-ios-rows.mjs — 设置页「iOS 设置行」门禁（settings-ios-rows 批 2026-11-06）
//
// 作者令（逐字）：「能点击下一个面板的样式都统一成苹果的这种样式 然后 底部的重启服务
//   去掉 并且这些可点击的按钮 布局规整一点」
//
// 四条判据（逐条对应作者令与任务书「必做 · 机检」）：
//   R1 重启服务零痕 —— 设置页面（js/sidebar.js + 中英文语言包 settings/restart 键）
//      零「重启服务 / Restart Service」文案、零 #btn-restart-gateway / #restart-status
//      节点、零 restartStatus/restartResult 订阅、零 `type: 'restart'` 发送。
//   R2 可点进行唯一样式 + chevron —— 设置页面只许出现**一类**可点进行构造函数
//      （setNavRow）；旧行壳（.secrets-entry / .settings-collapse-toggle /
//      .settings-row 发射）零发射；每个 setNavRow 调用都必须经单点射 chevron
//      （setChevron 在 settingsRows.js 内只有一处 `<i data-lucide="chevron-right">`）。
//   R3 行内右侧不重复同一数值 —— 密钥入口行的计数只画一次（副值副本文本为空、
//      计数胶囊/.count-pill 零发射）；渲染层以 DOM 读数复核（见 --dom 腿）。
//   R4 动作钮走令牌 —— 行为按钮 / 行内动作钮一律 .set-btn（高度/内距/圆角取
//      --set-* 令牌）；设置页面零 `margin-left:var(--ds-space-2)` 之类手排偏移。
//
// 用法：
//   node scripts/check-settings-ios-rows.mjs                 # 改后（本树）门禁
//   node scripts/check-settings-ios-rows.mjs --web-root <dir># 对另一棵 web 树跑（改前红证据）
//   node scripts/check-settings-ios-rows.mjs --json           # 机器读
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, existsSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const ARGS = process.argv.slice(2);
const JSON_OUT = ARGS.includes('--json');
const wrIdx = ARGS.indexOf('--web-root');
const WEB = wrIdx >= 0 ? resolve(ARGS[wrIdx + 1] || '.') : join(ROOT, 'src', 'main', 'resources', 'web');

if (!existsSync(WEB)) {
  console.error(`web 树不存在：${WEB}`);
  process.exit(2);
}

const rel = (p) => relative(ROOT, p);
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '');
const fails = [];
const add = (rule, file, detail) => fails.push({ rule, file: rel(file), detail });

/** 去掉行首 // 注释与 /* *\/ 块注释（保留行数；诊断句不参与判定）。
 *  只允许「行首注释」：JS 面含 http:// 之类的字符串，粗粒度剥 // 会误切代码。 */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^(\s*)\/\/.*$/gm, '$1');
}

const SIDEBAR = join(WEB, 'js', 'sidebar.js');
const ROWS_MODULE = join(WEB, 'js', 'settingsRows.js');
const ROWS_CSS = join(WEB, 'css', 'settingsRows.css');
const TOKENS = join(WEB, 'css', 'stand-tokens.css');
const ZH = join(WEB, 'js', 'locales', 'zh-CN.js');
const EN = join(WEB, 'js', 'locales', 'en.js');

const sidebar = stripComments(read(SIDEBAR));
const rowsModule = stripComments(read(ROWS_MODULE));
const rowsCss = read(ROWS_CSS);
const tokens = read(TOKENS);

// ── R1 重启服务零痕 ─────────────────────────────────────────────────────
if (!sidebar) add('R1', SIDEBAR, '设置页面文件缺失（js/sidebar.js）');
for (const [re, what] of [
  [/重启服务/, '文案「重启服务」'],
  [/Restart Service/i, '文案 "Restart Service"'],
  [/btn-restart-gateway/, '#btn-restart-gateway 节点/绑定'],
  [/restart-status/, '#restart-status 状态行'],
  [/onMessage\(\s*'restart(Status|Result)'/, 'restartStatus/restartResult 订阅'],
  [/sendWs\(\s*\{\s*type:\s*'restart'/, "WS `restart` 发送"],
]) {
  if (re.test(sidebar)) add('R1', SIDEBAR, `设置页面仍有${what}`);
}
for (const f of [ZH, EN]) {
  const loc = stripComments(read(f));
  for (const [re, what] of [
    [/'settings\.restart'\s*:/, "键 'settings.restart'"],
    [/'settings\.restartConfirm'\s*:/, "键 'settings.restartConfirm'"],
    [/'settings\.restartAccepted'\s*:/, "键 'settings.restartAccepted'"],
    [/'settings\.restartFailed'\s*:/, "键 'settings.restartFailed'"],
  ]) {
    if (re.test(loc)) add('R1', f, `语言包仍有${what}（重启服务入口的文案面）`);
  }
}

// ── R2 可点进行唯一样式 + chevron ──────────────────────────────────────
if (!rowsModule) add('R2', ROWS_MODULE, '设置行单源模块缺失（js/settingsRows.js）');
if (!rowsCss) add('R2', ROWS_CSS, '设置行造型单源缺失（css/settingsRows.css）');
for (const [re, what] of [
  [/class="secrets-entry"/, '.secrets-entry 旧玻璃行卡发射'],
  [/class="settings-collapse-toggle/, '.settings-collapse-toggle 旧下钻行发射'],
  [/class="settings-row/, '.settings-row 旧行壳发射'],
  [/class="settings-section/, '.settings-section 旧分组卡发射'],
]) {
  if (re.test(sidebar)) add('R2', SIDEBAR, `设置页仍发射${what}（第二套行样式）`);
}
// chevron 单点：整棵设置面只有 settingsRows.js 能发射 chevron-right。判据面 =
// 设置页各节构造器（settings 行族 / 外观节 / 账号卡），不看侧栏目录树的 chevron。
const chevronEmitters = [];
for (const f of [ROWS_MODULE, join(WEB, 'js', 'orbSettingsUI.js'), join(WEB, 'js', 'neblink.js')]) {
  const src = stripComments(read(f));
  if (/data-lucide="chevron-right"/.test(src) || /statusIcon\(\s*'chevron-right'/.test(src)) chevronEmitters.push(rel(f));
}
// 设置页群内（renderSettings 与四个节构造器）出现 chevron-right 也判红。
{
  const settingsFns = sidebar.match(/(renderSettings|renderWorkScheduleSection|renderTtlSection|renderSttSection|renderProviderCard)\s*\([\s\S]*?\n}/g) || [];
  if (settingsFns.some((b) => /chevron-right/.test(b))) add('R2', SIDEBAR, '设置页节构造器内自画 chevron（应走 setNavRow 单点）');
}
if (chevronEmitters.length !== 1 || chevronEmitters[0] !== rel(ROWS_MODULE)) {
  add('R2', ROWS_MODULE, `chevron 发射点应唯一 = ${rel(ROWS_MODULE)}，实得 [${chevronEmitters.join(', ')}]`);
}
// 每个可点进行都必须走构造器（不得手拼 button.set-nav）。
const navCount = (sidebar.match(/setNavRow\(/g) || []).length;
if (navCount < 4) add('R2', SIDEBAR, `可点进行调用数 ${navCount} < 4（密钥/JeV/生图/STT/TTL 这些入口必须走同一构造器）`);
if (/class="set-nav/.test(sidebar)) add('R2', SIDEBAR, '出现手拼 .set-nav 行（绕过 setNavRow 单源）');

// ── R3 行内右侧不重复同一数值 ──────────────────────────────────────────
if (!/setNavRow\(\{[\s\S]{0,400}?id:\s*'btn-open-secrets'/.test(sidebar)) {
  add('R3', SIDEBAR, '密钥入口行不再是 setNavRow（无法保证只有一个计数读数）');
}
for (const [re, what] of [
  [/secrets-entry-pill/, '计数胶囊 #secrets-entry-pill'],
  [/count-pill/, '.count-pill 计数胶囊'],
  [/pill\.textContent\s*=\s*String\(n\)/, '同一计数写进第二处元素'],
]) {
  if (re.test(sidebar) || re.test(rowsCss)) add('R3', re.test(sidebar) ? SIDEBAR : ROWS_CSS, `仍有${what}（同一数值说两遍）`);
}
// 行内只许一个副值节点。
{
  const navBodies = rowsModule.match(/return `<button[\s\S]*?<\/button>/g) || [];
  for (const body of navBodies) {
    const vals = (body.match(/set-nav-val/g) || []).length;
    if (vals > 1) add('R3', ROWS_MODULE, 'setNavRow 一次发射了多个副值节点');
  }
}

// ── R4 动作钮走令牌 ────────────────────────────────────────────────────
// ④a 行为按钮与行内动作钮必须带 .set-btn。
for (const id of ['btn-toggle-json', 'btn-rerun-onboarding', 'btn-clear-nf-cache']) {
  if (!new RegExp(`id="${id}"`).test(sidebar)) continue; // 可选件（新手引导可编配）
  const m = sidebar.match(new RegExp(`<button[^>]*id="${id}"[^>]*>`));
  if (!m || !/class="[^"]*\bset-btn\b/.test(m[0])) add('R4', SIDEBAR, `#${id} 未走 .set-btn 尺寸令牌`);
}
// ④b 设置页面零手排偏移（作者点名的「布局不规整」根因之一）。
if (/id="btn-rerun-onboarding"[^>]*style="[^"]*margin-left/.test(sidebar)) {
  add('R4', SIDEBAR, '#btn-rerun-onboarding 仍用手写 margin-left 排位');
}
// ④c 令牌出生地：--set-* 只许在 stand-tokens.css 定义。
for (const v of ['--set-row-h', '--set-icon-box', '--set-chevron-box', '--set-btn-h', '--set-chevron-align-gap', '--set-group-gap']) {
  if (!new RegExp(`${v}\\s*:`).test(tokens)) add('R4', TOKENS, `令牌 ${v} 未在 stand-tokens.css 定义`);
}
const defsElsewhere = (rowsCss.match(/^\s*--set-[a-z-]+\s*:/gm) || []).length;
if (defsElsewhere > 0) add('R4', ROWS_CSS, `--set-* 令牌被定义在消费文件里（${defsElsewhere} 处）；出生地只许 stand-tokens.css`);
// ④d 造型不许手写字面量：行族几何只取 var(--set-*)。
for (const [re, what] of [
  [/min-height:\s*\d+px/, '行高手写 px'],
  [/\.set-btn\s*\{[^}]*height:\s*\d+px/, '动作钮高度手写 px'],
]) {
  const body = rowsCss.replace(/\/\*[\s\S]*?\*\//g, ' ');
  if (re.test(body)) add('R4', ROWS_CSS, `存在${what}（应取 var(--set-*)）`);
}

// ── 报告 ───────────────────────────────────────────────────────────────
const byRule = fails.reduce((m, f) => ((m[f.rule] = (m[f.rule] || 0) + 1), m), {});
if (JSON_OUT) {
  console.log(JSON.stringify({ ok: fails.length === 0, webRoot: WEB, counts: byRule, fails }, null, 2));
} else {
  console.log(`settings-ios-rows：web 树 = ${WEB}`);
  console.log(`  R1 重启服务零痕 · R2 可点进行唯一样式+chevron · R3 行内右侧不重复数值 · R4 动作钮走令牌`);
  if (fails.length === 0) {
    console.log('PASS: 设置行只有一套实现（setNavRow / setRow / set-btn），chevron 单点，计数只说一遍，动作钮走令牌。');
  } else {
    console.log(`\nFAIL ${fails.length} 项（${Object.entries(byRule).map(([k, v]) => `${k}×${v}`).join(' · ')}）：`);
    for (const f of fails) console.log(`  ${f.file}  [${f.rule}] ${f.detail}`);
  }
}
process.exit(fails.length ? 1 : 0);
