#!/usr/bin/env node
// check-secondary-composer.mjs — 「次级 LLM 输入框」单源闸
// （待办收件箱 UI 批 2026-11-21，作者令 ①「次级面板的输入框样式/大小/风格统一，与
//  ask 的输入框样式功能一致 —— 抽成标准组件复用，并加检查」）。
//
// 判据（源码级，确定性，零浏览器）：
//   S1 单源存在：js/input/secondaryComposer.js 导出 createSecondaryComposer +
//      两个自增高封顶常量（封顶只有一个声明点）。
//   S2 消费方登记：每一个「次级 LLM 输入面」的装配文件都必须 import 该工厂 ——
//      Canvas Ask 输入条（askDialog.js）/ 待办详情页回复条 + 快速决策卡页悬浮控件带
//      （mobile/todoAppleFace.js）。任何一面自己拼 textarea + 发送键即为红。
//   S3 无新第二套：凡引用了已登记的次级输入面身份类、又自己造 textarea、却没有
//      import 工厂的文件 ⇒ 红（不在 LEGACY 具名台账里的）。台账只许缩短，新增须
//      作者裁定。
//   S4 造型单源：次级输入面的背景/边框/圆角/阴影只许引用令牌（var(--…)），不许写
//      字面值 —— 值的唯一来源是 css/inputSurface.css 的成员表 + --nf-field-* 令牌。
//   S5 发送键单源：发送键造型归 design-system.css 的 .ds-turn-btn--send；其它文件
//      不得给它写字面造型。
//   S6 【接口预留】仓内前端规范正文：作者侧另一批（feat/design-spec-in-repo）正在把
//      规范落进仓内 docs 下的规范目录。本闸按 IN_REPO_SPEC_CANDIDATES 依次探测：
//        · 命中 ⇒ 按规范核对（输入面规范件必须在场且非空壳，本批输入面必须已登记）；
//        · 未命中 ⇒ 打印「接口预留」事实行并以 0 退出（本批口径 = 先与 ask 输入框
//          对齐；规范落仓后本闸自动接管，无需改判据）。
//
// 运行：node scripts/check-secondary-composer.mjs
// 退出码：0 = 全绿（含 S6 未落仓档）；1 = 有红。

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const web = (rel) => join(WEB, rel);
const readWeb = (rel) => readFileSync(web(rel), 'utf8');

/** 输入框单源工厂（唯一装配点），路径相对 web/。 */
const FACTORY = 'js/input/secondaryComposer.js';

/** 次级输入面的消费方登记（每一面都必须走同一装配点）。 */
const CONSUMERS = [
  { file: 'js/askDialog.js', face: 'Canvas Ask 输入条（.ask-input）' },
  { file: 'js/mobile/todoAppleFace.js', face: '待办详情页回复条（.ta-reply-input）+ 快速决策卡页悬浮控件带（.ta-qd-answer-input）' },
];

/** 已登记的次级输入面身份类（S3 的判据锚）。 */
const SURFACE_CLASSES = ['ta-qd-answer-input', 'ta-reply-input', 'tfl-hit-replyrow', 'ask-input'];
/** 本批拥有/改写的输入面（S4/S5 的作用面 —— 既有 ask/行内框的造型由
 *  scripts/check-input-surface.mjs 的 I1–I4 统管，本闸不另立第二套判据）。 */
const BATCH_SURFACES = ['ta-qd-answer-input', 'ta-reply-input'];

/** 具名台账：先于模板存在、本批不动的面（只许缩短；新增须作者裁定）。 */
const LEGACY = [
  {
    file: 'js/todoFace.js',
    reason: '桌面待办行内互动框（ui-unify-panels E 批 2026-11-17）先于模板存在，'
      + '走同一套裸件/外壳登记但未走工厂；模板迁移另批排期',
  },
];

/** 仓内前端规范正文的候选落点（作者侧 feat/design-spec-in-repo 批次的落点顺序）。 */
const IN_REPO_SPEC_CANDIDATES = [
  'docs/规范/frontend',
  'docs/规范/前端',
  'docs/spec/frontend',
  'docs/frontend/spec',
  'docs/design/规范',
];

/** 造型属性 → 是否允许（只许令牌，不许字面量）。 */
const DECOR = /(?:^|[\s;{])(background(?:-color)?|border(?:-[a-z]+)?|border-radius|box-shadow)\s*:\s*([^;}]+)/g;
const hasLiteral = (value) => {
  const stripped = value.replace(/var\([^)]*\)/g, '').trim();
  if (!stripped) return false;                       // 纯令牌 = 合规
  return /(?:#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(|\b\d+(?:\.\d+)?(?:px|em|rem|%)\b|\d+px)/i.test(stripped);
};

const errors = [];
const notes = [];
const fail = (msg) => errors.push(msg);

// ── S1 单源存在 ────────────────────────────────────────────────────────────
if (!existsSync(web(FACTORY))) {
  fail(`S1 缺少次级输入框单源：src/main/resources/web/${FACTORY}`);
} else {
  const src = readWeb(FACTORY);
  for (const need of ['export function createSecondaryComposer', 'export const SECONDARY_COMPOSER_MAX_H']) {
    if (!src.includes(need)) fail(`S1 ${FACTORY} 缺少导出：${need}`);
  }
  notes.push(`S1 单源在场：${FACTORY}`);
}

const importsFactory = (src) =>
  /import\s*\{[^}]*createSecondaryComposer[^}]*\}\s*from\s*['"][^'"]*input\/secondaryComposer\.js['"]/.test(src);

// ── S2 消费方登记 ──────────────────────────────────────────────────────────
for (const c of CONSUMERS) {
  if (!existsSync(web(c.file))) { fail(`S2 登记的消费方文件不存在：${c.file}（${c.face}）`); continue; }
  const src = readWeb(c.file);
  if (!importsFactory(src)) {
    fail(`S2 ${c.file} 未从 ${FACTORY} import createSecondaryComposer —— ${c.face} 变成了第二套装配`);
  } else if (!src.includes('createSecondaryComposer(')) {
    fail(`S2 ${c.file} 只 import 不装配：${c.face} 的输入条不是工厂产出的`);
  } else {
    notes.push(`S2 ${c.file} 走同一装配点（${c.face}）`);
  }
}

// ── S3 无新第二套（js 侧）─────────────────────────────────────────────────
const jsFiles = [];
(function walk(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.js')) jsFiles.push(p);
  }
})(join(WEB, 'js'));
const legacyFiles = new Set(LEGACY.map((l) => l.file));
/** 形态指纹：**同一处**既造 textarea 又挂次级输入面身份类（避免把「只是提到类名」
 *  的文件误判成第二套装配）。 */
const makesSurfaceTextarea = (src) => src.split('\n').some((line) => {
  if (!/textarea/.test(line)) return false;
  const near = line;
  return SURFACE_CLASSES.some((c) => near.includes(c));
});
for (const abs of jsFiles) {
  const rel = relative(WEB, abs).split('\\').join('/');
  if (rel === FACTORY) continue;                                  // 单源本身
  const src = readFileSync(abs, 'utf8');
  if (!makesSurfaceTextarea(src)) continue;
  if (importsFactory(src)) continue;
  if (legacyFiles.has(rel)) continue;
  fail(`S3 ${rel} 自己拼了次级输入面的 textarea 却没有走 ${FACTORY}（第二套；若确属既有面请登记进本闸的 LEGACY 台账并说明理由）`);
}

// ── S4/S5 造型与发送键单源（css 侧）───────────────────────────────────────
const cssFiles = readdirSync(join(WEB, 'css')).filter((n) => n.endsWith('.css'));
for (const name of cssFiles) {
  if (name === 'inputSurface.css' || name === 'design-system.css') continue;  // 造型/键位的单源本身
  const clean = readWeb(`css/${name}`).replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const sel = m[1].replace(/\s+/g, ' ').trim();
    if (!BATCH_SURFACES.some((c) => sel.includes(`.${c}`))) continue;
    for (const d of m[2].matchAll(DECOR)) {
      if (hasLiteral(d[2])) {
        fail(`S4 css/${name} 在次级输入面上写了造型字面值：${sel} { ${d[1]}: ${d[2].trim()} } —— 造型只许取 css/inputSurface.css 的成员表与 --nf-field-* 令牌`);
      }
    }
    if (/-send\b/.test(sel)) {
      for (const d of m[2].matchAll(DECOR)) {
        if (hasLiteral(d[2])) fail(`S5 css/${name} 给次级输入面的发送键写了造型字面值：${sel} { ${d[1]}: ${d[2].trim()} } —— 发送键唯一来源是 .ds-turn-btn--send`);
      }
    }
  }
}
// 内芯/外壳登记：两个面的 textarea 必须在裸件成员表里（否则落到盒式档 = 框里套框）。
const inputSurface = readWeb('css/inputSurface.css');
for (const c of ['ta-qd-answer-input', 'ta-reply-input']) {
  if (!inputSurface.includes(`.${c} textarea`)) fail(`S4 css/inputSurface.css 裸件成员表缺少 .${c} textarea（内芯会被盒式档收编）`);
  if (!new RegExp(`\\.${c}\\s*[,{ ]`).test(inputSurface)) fail(`S4 css/inputSurface.css 外壳成员表缺少 .${c}（外壳圆角无来源）`);
}

// ── S6 仓内规范正文（接口预留）────────────────────────────────────────────
function findInRepoSpec() {
  for (const rel of IN_REPO_SPEC_CANDIDATES) {
    const abs = join(ROOT, rel);
    if (!existsSync(abs)) continue;
    const md = readdirSync(abs).filter((n) => n.endsWith('.md'));
    if (md.length) return { rel, dir: abs, files: md };
  }
  return null;
}
const spec = findInRepoSpec();
if (!spec) {
  notes.push('S6 接口预留：仓内前端规范正文尚未落仓（另一批 feat/design-spec-in-repo 在途）'
    + ' ⇒ 本批按「与 ask 输入框对齐」判据执行；规范落进 docs 下的规范目录后本闸自动接管，无需改判据。');
} else {
  const surfaceSpec = spec.files.find((n) => /input|输入/.test(n));
  if (!surfaceSpec) {
    fail(`S6 已探测到仓内规范目录 ${spec.rel}/，但没有输入面规范件（期望文件名含 input/输入）`);
  } else {
    const text = readFileSync(join(spec.dir, surfaceSpec), 'utf8');
    if (text.trim().length < 200) fail(`S6 仓内输入面规范件过短（<200 字），按空壳处理：${spec.rel}/${surfaceSpec}`);
    for (const c of ['ta-qd-answer-input', 'ta-reply-input']) {
      if (!inputSurface.includes(`.${c}`)) fail(`S6 规范已落仓（${surfaceSpec}），但 css/inputSurface.css 未登记本批输入面 .${c}`);
    }
    notes.push(`S6 仓内规范已落仓：${spec.rel}/${surfaceSpec} —— 按规范核对通过`);
  }
}

// ── 结论 ──────────────────────────────────────────────────────────────────
for (const n of notes) console.log(`· ${n}`);
if (errors.length) {
  console.error(`\nsecondary-composer: ${errors.length} 处红`);
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exitCode = 1;
} else {
  console.log(`secondary-composer: PASS（消费方 ${CONSUMERS.length} 面 / 台账 ${LEGACY.length} 条）`);
}
