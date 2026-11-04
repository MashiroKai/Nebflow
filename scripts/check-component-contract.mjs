#!/usr/bin/env node
// check-component-contract.mjs — 前端「组件契约」防回退门禁（ask-paradigm-a 批 2026-11-03）
//
// Why a static gate：本轮把 Ask 面从「插件目录里的一段 UI 代码」改成真组件
// （`<nf-ask-dock>`，light DOM，对外只暴露 open/close/syncSession），并把 ask.css 的
// 字面量换成 --ds-* token。**契约只写在文档里就等于没做**——下一个改 ask 的人（或
// 下一个模仿它写组件的人）不会有任何东西提醒他「阴影根不许开、跨面不许直查 DOM、
// 事件名不许写裸字面量、组件内部类名不许外泄、innerHTML 不许裸赋值」。五条规则
// 里每一条都是本批实际付过代价的坑，因此每条都配一条机器判据；闸红了就是契约被
// 回退，而不是「风格偏好」。
//
// ── 五条规则（逐条口径）─────────────────────────────────────────────────
// R1 no-attach-shadow
//   面：web/js/**、web/css/**、web/index.html。出现 `attachShadow` 即红。
//   口径：light DOM 是**本仓的组件形态决定**（样式单点、调试单点、无 shadow 边界的
//   样式穿透问题）。白名单 = 下面的 SHADOW_ALLOWLIST（Map<相对路径, 理由>），默认
//   空表；**逐条写理由，通配一律不许**——新增条目必须是一个具体文件 + 一句为什么
//   它必须开 Shadow DOM。
// R2 cross-face-dom
//   面：web/js/**。跨面 DOM 直查即红（同面放行）。
//   命中形态（单引号/双引号/模板字符串字面量都算）：
//     · getElementById('<face>-…')   —— id 字面量以 `<face>-` 开头
//     · querySelector/querySelectorAll/closest 的**选择器字面量里出现 `#<face>-`**
//       （位置不限：`.a #ask-x` 也是跨面直查，所以不比 brief 的「以 # 开头」更松）
//   面前缀表 = **从 web/css/*.css 自动生成**（不维护第二份名单）：
//     每个 css 文件统计 `.<prefix>-` 形态类名出现次数，取最高频、长度 ≥3 的小写
//     前缀作为该面的前缀；`--print-faces` 打印当前表。冲突（两个 css 文件同前缀）
//     取计数高者，平手按文件名序，落选文件记录在 conflict 字段（可见但不静默）。
//   归属判定（确定性，无隐式约定）：模块 `js/<rest>.js` 属于面 F **iff** 路径以
//     `js/<F>` 为**大小写不敏感前缀**。例：face=ask ⇒ js/askDock.js、js/askPending.js
//     属 ask 面；face=fm ⇒ js/flowMap/* **不**属（按跨面算）。
//   边界（已知且有意）：本判据只看「字面量里的面前缀」，运行期拼出来的选择器抓不到
//     ——那条腿由代码评审承担，闸只负责「不许再写一个新的」。
// R3 event-literal
//   面：web/js/**。`new CustomEvent('<字面量>'…)` / `new CustomEvent("<字面量>"…)`
//   在**基线里没有的文件**出现即红；基线内文件计数**超过**基线即红。
//   口径 = **文本级**（与 `grep -rE "new CustomEvent\("` 对齐，当日 113 处 / 44 文件），
//   不剥注释：注释里贴一段旧写法同样是复制源，要动它就走 --update-baseline 显式收紧。
//   合法形态 = 实参不含字符串字面量（`new CustomEvent(EVENTS.X, {...})`）——此类
//   永不计入。事件名的单源在本仓已存在（events 常量表），裸字面量 = 又开一个源。
// R4 component-dom-leak
//   面：**只含** `web/js/**`，绝不含 tests/ —— 15 个既有 spec 必须继续直查
//   `.ask-*`（那是验收面，不是违规）。
//   组件定义模块 = 出现 `customElements.define('nf-…'` 或 `define({ tag: 'nf-…'`
//   的文件；其内部类名 = 同文件 `export const INTERNAL_CLASSES = [...]`。
//   其它 web/js 文件把这些类名用作选择器（出现在 querySelector*/closest/matches 的
//   选择器字面量里，`.<class>` 形态）即红；**注释里提到不算**（注释先行剥除）。
//   口径：组件对外只暴露方法面（open/close/syncSession），内部结构类名是私有的
//   —— 外面按类名取节点 = 把内部结构焊进调用方，改一次内部就炸一片。
// R5 bare-innerhtml
//   面：web/js/**。裸 `innerHTML =` 在**新文件**出现即红；基线内文件计数超过基线
//   即红。口径：本仓走 mdHtmlGate/构建器写 DOM，裸赋值 = 绕过净化面；存量 670 处 /
//   115 文件是既有债（不在本批范围），只许减不许增。
//   口径 = **文本级**（与 `grep -rE "innerHTML\s*="` 对齐），不剥注释（理由同 R3）。
//
// ── 基线机制（只减不增）───────────────────────────────────────────────
// scripts/component-contract-baseline.json 记录每个 web/ 相对路径的五项计数。
//   判据：命中数 **> 基线 ⇒ 红**；基线里**没有**的文件出现**任何**命中 ⇒ 红
//   （新文件一个字面量都不许有）；命中数 **< 基线** ⇒ 只记一条
//   「改善，建议下次 --update-baseline 收紧」note，不红。
//   基线 JSON 的 _comment 写明「升基线 = 放水，必须逐项说明理由」。
// 豁免纪律：本脚本**没有**行内 pragma/ignore 注释；唯一的例外通道是
//   SHADOW_ALLOWLIST（逐条 + 理由）。放水只有一种合法形态：批报告里逐项说明。
//
// ── 面前缀表（自动生成快照，2026-11-03 当日树，32 面）──────────────────
// 口径见 R2；判据里**不写死**（运行时从 css 重算），这里只是给人读的快照。
// 重跑 `node scripts/check-component-contract.mjs --print-faces` 得当前值。
//   格式：face ← css 文件(该前缀类名出现次数)
//   activity ← nav.css(22)          ask ← ask.css(63)             att ← input.css(64)
//   canvas ← split.css(206)         cfg ← sidebar.css(103)        conn ← liveness.css(2)
//   dropbox ← dropbox.css(48)       dvp ← delegatePanorama.css(151)
//   executor ← executors.css(21)    fcv ← diffView.css(9)         header ← base.css(12)
//   hit ← usage.css(9)              mobile ← mobileLayer.css(752) neblink ← neblink.css(52)
//   nfc ← cards.css(17)             option ← chat.css(123)        pdraft ← perception.css(17)
//   project ← projectPanel.css(59)  quick ← keyboard.css(28)      reminder ← reminder.css(94)
//   scroll ← scrollUnderGlass.css(7) search ← modal.css(44)       secrets ← secrets.css(53)
//   sidebar ← mobile.css(3)         social ← social.css(80)       solar ← flowMap.css(48)
//   task ← taskList.css(131)        tfl ← todo-face.css(210)      todo ← todo-page.css(21)
//   tool ← design-system.css(20)    voice ← voice.css(8)          vmo ← voiceMode.css(19)
// 派生是**纯机械**的（不做人工挑选），所以会出现 option/solar/hit 这种「不像面名」的
// 条目——它们就是该 css 的主导类前缀，规则对它们一视同仁（不挑即无争议）。
// 前缀冲突（两个 css 派生出同一前缀，取计数高者，落选者记在 conflicts）：
//   mobile ← mobileLayer.css(752) 胜 friends(10)；canvas ← split.css(206) 胜 glass(49)；
//   cfg ← sidebar.css(103) 胜 sapphire(48) / timeWheel(3)。
// 注意 flowMap.css 的主导前缀是 solar 而非 fm —— 所以 js/flowMap/* 属 solar 面，
// R2 对 `#solar-…` 是同面放行、对其它面一律跨面。
//
// ── 用法 ──────────────────────────────────────────────────────────────
//   node scripts/check-component-contract.mjs                  # 门禁（红/绿）
//   node scripts/check-component-contract.mjs --json           # 机器读
//   node scripts/check-component-contract.mjs --print-faces    # 打印自动生成的面表
//   node scripts/check-component-contract.mjs --update-baseline # 重生成基线
//                                                              # （只在「确实把数字
//                                                              # 降下来」时用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB_DIR = join(ROOT, 'src', 'main', 'resources', 'web');
const JS_DIR = join(WEB_DIR, 'js');
const CSS_DIR = join(WEB_DIR, 'css');
const INDEX_HTML = join(WEB_DIR, 'index.html');
const BASELINE_PATH = join(ROOT, 'scripts', 'component-contract-baseline.json');
const ARGS = new Set(process.argv.slice(2));

/**
 * R1 白名单：Map<相对 web/ 的路径, 理由>。默认空表。
 * **逐条写理由，通配一律不许**——不要加 `js/**` 这种，也不要加一个「临时验证用」
 * 却不写理由的条目。加条目的同时在批报告里说明「为什么这个文件必须开 Shadow DOM」。
 */
const SHADOW_ALLOWLIST = new Map([
  // 例：'js/legacyThing.js': '第三方 viewer 的 <video> 控件样式无法从外部穿透',
]);

/** 五条规则在基线里的键名（顺序即报告顺序）。 */
const RULES = ['attachShadow', 'crossFaceDom', 'eventLiteral', 'componentDomLeak', 'bareInnerHtml'];

const RULE_LABEL = {
  attachShadow: 'R1 no-attach-shadow',
  crossFaceDom: 'R2 cross-face-dom',
  eventLiteral: 'R3 event-literal',
  componentDomLeak: 'R4 component-dom-leak',
  bareInnerHtml: 'R5 bare-innerhtml',
};

const fails = [];
const notes = [];
const add = (file, line, detail, rule) => fails.push({ file, line, detail, rule });

/**
 * 逐条命中明细（**不直接判红**）：判红只在「基线比较」一处发生（单点判据）——
 * 命中数超基线 / 基线外新文件出现命中。这样 --update-baseline 之后明细自动消音，
 * 而超基线时又不会丢掉 file:line 证据。
 */
const hits = {};   // rel -> {ruleKey: [{line, detail}]}
const record = (rel, key, line, detail) => {
  ((hits[rel] ||= {})[key] ||= []).push({ line, detail });
};
const emitHits = (rel, key, ruleLabel) => {
  for (const h of (hits[rel] && hits[rel][key]) || []) add(rel, h.line, h.detail, ruleLabel);
};

// ── 环境闸（exit 2 = 环境错，不是红）───────────────────────────────────
if (!existsSync(WEB_DIR) || !existsSync(JS_DIR)) {
  console.error(`environment error: 扫描根不存在 ${WEB_DIR}`);
  process.exit(2);
}

const relWeb = (abs) => relative(WEB_DIR, abs).replaceAll('\\', '/');

function walk(dir, exts, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const name of readdirSync(dir).sort()) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, exts, acc);
    else if (exts.includes(extname(p))) acc.push(p);
  }
  return acc;
}

// ── 源码预处理 ────────────────────────────────────────────────────────
const CSS_COMMENT_RE = /\/\*[\s\S]*?\*\//g;
const stripCssComments = (src) => src.replace(CSS_COMMENT_RE, (m) => m.replace(/[^\n]/g, ' '));

/**
 * JS 注释剥除（**保留行号**）：把注释内容换成等长空白/换行。
 * 必须走状态机而不是正则：`'https://x'` 里的 `//`、模板字符串里的 `*` 都会被正则
 * 误当注释；模板内 `${…}` 也要回到代码态（里面可能有注释）。
 */
function stripJsCommentsKeepLines(src) {
  const out = new Array(src.length);
  const stack = [{ s: 'code' }];
  for (let i = 0; i < src.length; i++) {
    const top = stack[stack.length - 1];
    const ch = src[i];
    const nx = src[i + 1];
    if (top.s === 'line') {
      out[i] = ch === '\n' ? '\n' : ' ';
      if (ch === '\n') stack.pop();
      continue;
    }
    if (top.s === 'block') {
      if (ch === '*' && nx === '/') { out[i] = ' '; out[i + 1] = ' '; i++; stack.pop(); continue; }
      out[i] = ch === '\n' ? '\n' : ' ';
      continue;
    }
    if (top.s === 'sq' || top.s === 'dq') {
      out[i] = ch;
      if (ch === '\\' && i + 1 < src.length) { out[i + 1] = src[i + 1]; i++; continue; }
      if ((top.s === 'sq' && ch === "'") || (top.s === 'dq' && ch === '"')) stack.pop();
      continue;
    }
    if (top.s === 'tmpl') {
      out[i] = ch;
      if (ch === '\\' && i + 1 < src.length) { out[i + 1] = src[i + 1]; i++; continue; }
      if (ch === '`') { stack.pop(); continue; }
      if (ch === '$' && nx === '{') { out[i + 1] = '{'; i++; stack.push({ s: 'code', tmpl: true, depth: 0 }); continue; }
      continue;
    }
    // code
    if (ch === '/' && nx === '/') { out[i] = ' '; out[i + 1] = ' '; i++; stack.push({ s: 'line' }); continue; }
    if (ch === '/' && nx === '*') { out[i] = ' '; out[i + 1] = ' '; i++; stack.push({ s: 'block' }); continue; }
    if (ch === "'") { out[i] = ch; stack.push({ s: 'sq' }); continue; }
    if (ch === '"') { out[i] = ch; stack.push({ s: 'dq' }); continue; }
    if (ch === '`') { out[i] = ch; stack.push({ s: 'tmpl' }); continue; }
    if (top.tmpl) {
      if (ch === '{') { out[i] = ch; top.depth++; continue; }
      if (ch === '}') {
        if (top.depth > 0) { out[i] = ch; top.depth--; continue; }
        out[i] = ch; stack.pop(); continue;
      }
    }
    out[i] = ch;
  }
  return out.join('');
}

const lineOf = (src, index) => src.slice(0, index).split('\n').length;

// ── 面前缀表：从 web/css/*.css 自动生成 ────────────────────────────────
function deriveFaces() {
  const faces = new Map();     // prefix -> { css, count, conflicts: [] }
  for (const abs of walk(CSS_DIR, ['.css'])) {
    const stem = basename(abs, '.css');
    const src = stripCssComments(readFileSync(abs, 'utf8'));
    const tally = new Map();
    for (const m of src.matchAll(/\.([a-z][a-z0-9]*)-/g)) {
      const p = m[1];
      if (p.length < 3) continue;
      tally.set(p, (tally.get(p) || 0) + 1);
    }
    const ranked = [...tally.entries()].sort((a, b) => (b[1] - a[1]) || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
    if (ranked.length === 0) continue;
    const [prefix, count] = ranked[0];
    const prev = faces.get(prefix);
    if (!prev) {
      faces.set(prefix, { css: stem, count, conflicts: [] });
    } else if (count > prev.count) {
      prev.conflicts.push(`${prev.css}(${prev.count})`);
      faces.set(prefix, { css: stem, count, conflicts: prev.conflicts });
    } else {
      prev.conflicts.push(`${stem}(${count})`);
    }
  }
  return faces;
}

const FACES = deriveFaces();

/** 模块所属面集合（js/<face> 大小写不敏感前缀）。 */
function moduleFaces(rel) {
  const lower = rel.toLowerCase();
  const owned = [];
  for (const prefix of FACES.keys()) {
    if (lower.startsWith(`js/${prefix}`)) owned.push(prefix);
  }
  return owned;
}

// ── 选择器/ID 调用的字面量提取（R2 / R4 共用单点）──────────────────────
const CALL_RE = /\b(getElementById|querySelectorAll|querySelector|closest|matches)\s*\(/g;

/**
 * 提取 `fn('字面量'` 形态的第一个实参字符串（单/双/模板引号）。
 * @returns {{fn:string, arg:string|null, index:number}[]}
 */
function selectorCalls(src) {
  const out = [];
  for (const m of src.matchAll(CALL_RE)) {
    const fn = m[1];
    let i = m.index + m[0].length;
    while (i < src.length && /\s/.test(src[i])) i++;
    const q = src[i];
    let arg = null;
    if (q === "'" || q === '"' || q === '`') {
      let j = i + 1;
      let buf = '';
      while (j < src.length) {
        const ch = src[j];
        if (ch === '\\') { buf += src[j + 1] ?? ''; j += 2; continue; }
        if (ch === q) break;
        buf += ch;
        j += 1;
      }
      arg = buf;
    }
    out.push({ fn, arg, index: m.index });
  }
  return out;
}

// ── 扫描：逐文件计数 ──────────────────────────────────────────────────
const jsFiles = walk(JS_DIR, ['.js']);
const cssFiles = walk(CSS_DIR, ['.css']);
const counts = {};   // rel(web/) -> {attachShadow, crossFaceDom, eventLiteral, componentDomLeak, bareInnerHtml}
const emptyBox = () => ({ attachShadow: 0, crossFaceDom: 0, eventLiteral: 0, componentDomLeak: 0, bareInnerHtml: 0 });
const bump = (rel, key, n = 1) => {
  const box = (counts[rel] ||= emptyBox());
  box[key] += n;
  return box;
};

// R4 第一遍：找组件定义模块 + 其内部类名表
const internalClasses = new Map();  // rel -> string[]
for (const abs of jsFiles) {
  const rel = relWeb(abs);
  const src = stripJsCommentsKeepLines(readFileSync(abs, 'utf8'));
  const isDef = /customElements\.define\(\s*(['"`])nf-[^'"`]*\1/.test(src)
    || /\bdefine\(\s*\{\s*tag\s*:\s*(['"`])nf-[^'"`]*\1/.test(src);
  if (!isDef) continue;
  const decl = src.match(/export\s+const\s+INTERNAL_CLASSES\s*=\s*\[([\s\S]*?)\]/);
  const names = decl ? [...decl[1].matchAll(/(['"`])([^'"`]+)\1/g)].map((m) => m[2]) : [];
  internalClasses.set(rel, names);
}

for (const abs of jsFiles) {
  const rel = relWeb(abs);
  const rawSrc = readFileSync(abs, 'utf8');
  // 注释剥除版只给 R1/R2/R4 用（R4 口径明写「注释里提到不算」）；R3/R5 是文本级
  // 计数（grep 对齐口径，见头注释），因此用 rawSrc。
  const src = stripJsCommentsKeepLines(rawSrc);

  // R1（js 面）
  if (!SHADOW_ALLOWLIST.has(rel)) {
    const n = (src.match(/attachShadow/g) || []).length;
    if (n) {
      bump(rel, 'attachShadow', n);
      record(rel, 'attachShadow', 1, `attachShadow ×${n}——本仓组件是 light DOM；确需开须在 SHADOW_ALLOWLIST 逐条写理由`);
    }
  }

  // R3：new CustomEvent('<字面量>')（文本级；实参非字面量形态永不计入）
  for (const m of rawSrc.matchAll(/new\s+CustomEvent\s*\(\s*(['"`])/g)) {
    bump(rel, 'eventLiteral', 1);
    record(rel, 'eventLiteral', lineOf(rawSrc, m.index),
      'new CustomEvent(\'字面量\')——事件名必须单源（走 events 常量表，如 new CustomEvent(EVENTS.X)）');
  }

  // R5：裸 innerHTML =（文本级）
  for (const m of rawSrc.matchAll(/\binnerHTML\s*=/g)) {
    bump(rel, 'bareInnerHtml', 1);
    record(rel, 'bareInnerHtml', lineOf(rawSrc, m.index),
      '裸 innerHTML = 赋值——写 DOM 走 mdHtmlGate / 构建器（绕过净化面 = 回退）');
  }

  // R2 / R4：选择器字面量
  const owned = moduleFaces(rel);
  for (const call of selectorCalls(src)) {
    if (call.arg == null) continue;
    const arg = call.arg;
    const lower = arg.toLowerCase();

    // R2 cross-face-dom
    for (const prefix of FACES.keys()) {
      let n = 0;
      if (call.fn === 'getElementById') {
        if (lower.startsWith(`${prefix}-`)) n = 1;
      } else {
        n = (lower.match(new RegExp(`#${prefix}-`, 'g')) || []).length;
      }
      if (!n) continue;
      if (owned.includes(prefix)) continue;   // 同面放行
      bump(rel, 'crossFaceDom', n);
      record(rel, 'crossFaceDom', lineOf(src, call.index),
        `${call.fn}("${arg.slice(0, 60)}") 跨面 DOM 直查 #${prefix}-…（本模块属 [${owned.join(',') || '无面'}]，面 ${prefix} 前缀来自 css/${FACES.get(prefix).css}.css）`);
    }

    // R4 component-dom-leak
    if (internalClasses.has(rel)) continue;   // 定义模块自用内部类名合法
    for (const [defRel, names] of internalClasses) {
      for (const name of names) {
        if (new RegExp(`\\.${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w-])`).test(arg)) {
          bump(rel, 'componentDomLeak', 1);
          record(rel, 'componentDomLeak', lineOf(src, call.index),
            `组件内部类名外泄：${call.fn}("${arg.slice(0, 60)}") 用到 .${name}，它是 ${defRel} 的私有结构（组件对外只暴露方法面）`);
        }
      }
    }
  }
}

// R1（css / index.html 面）
for (const abs of [...cssFiles, INDEX_HTML]) {
  if (!existsSync(abs)) continue;
  const rel = relWeb(abs);
  if (SHADOW_ALLOWLIST.has(rel)) continue;
  const src = stripCssComments(readFileSync(abs, 'utf8'));
  const n = (src.match(/attachShadow/g) || []).length;
  if (n) {
    bump(rel, 'attachShadow', n);
    record(rel, 'attachShadow', 1, `attachShadow ×${n}（${rel}）——本仓组件是 light DOM；确需开须在 SHADOW_ALLOWLIST 逐条写理由`);
  }
}

// ── 基线比较（只减不增）──────────────────────────────────────────────
if (ARGS.has('--update-baseline')) {
  const totals = Object.fromEntries(RULES.map((k) => [k, Object.values(counts).reduce((s, c) => s + (c[k] || 0), 0)]));
  const payload = {
    _comment: 'check-component-contract.mjs 存量基线（前端组件契约门禁，ask-paradigm-a 批 2026-11-03）。'
      + '数字只许降不许升：判据 = 超过基线即红、基线外新文件出现任何命中即红。'
      + '**升基线 = 放水，必须逐项说明理由**（在批报告里写清「为什么这一处必须新增」）。'
      + '重生成：node scripts/check-component-contract.mjs --update-baseline',
    generatedAt: new Date().toISOString(),
    counts,
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(payload, null, 2) + '\n');
  console.log(`基线已写入 ${relative(ROOT, BASELINE_PATH)}（${Object.keys(counts).length} 文件）`);
  console.log('  合计：' + RULES.map((k) => `${k} ${totals[k]}`).join(' / '));
  process.exit(0);
}

const baseline = existsSync(BASELINE_PATH)
  ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8')).counts
  : {};

const RULE_HINT = {
  attachShadow: 'attachShadow（light DOM 是组件形态决定）',
  crossFaceDom: '跨面 DOM 直查（改用组件方法面 / 同面选择器）',
  eventLiteral: 'new CustomEvent(裸字面量)（走 events 常量表）',
  componentDomLeak: '组件内部类名外泄（对外的只有方法面）',
  bareInnerHtml: '裸 innerHTML =（走 mdHtmlGate / 构建器）',
};

for (const [rel, c] of Object.entries(counts)) {
  const base = baseline[rel];
  if (!base) {
    const detail = RULES.filter((k) => (c[k] || 0) > 0).map((k) => `${k}=${c[k]}`).join(' ');
    add(rel, 0, `基线外新文件出现组件契约命中（${detail}）——新文件五项计数必须全 0`, 'new-file');
    // 明细照列，红输出里要能直接看到命中了哪条规则、哪一行
    for (const key of RULES) emitHits(rel, key, RULE_LABEL[key]);
    continue;
  }
  for (const key of RULES) {
    const now = c[key] || 0;
    const was = base[key] || 0;
    if (now > was) {
      add(rel, 0, `${RULE_HINT[key]} 从基线 ${was} 增到 ${now}（只许减不许增）`, `baseline-${key}`);
      emitHits(rel, key, RULE_LABEL[key]);
    } else if (now < was) notes.push(`${rel}: ${key} ${was} → ${now}（改善，建议下次 --update-baseline 收紧）`);
  }
}
// 基线有、当前树已无命中的文件 = 改善
for (const rel of Object.keys(baseline)) {
  if (!counts[rel]) notes.push(`${rel}: 全部归零（改善，建议下次 --update-baseline 收紧）`);
}

// ── 报告 ─────────────────────────────────────────────────────────────
const totals = Object.fromEntries(RULES.map((k) => [k, Object.values(counts).reduce((s, c) => s + (c[k] || 0), 0)]));
const grand = Object.values(totals).reduce((s, n) => s + n, 0);

if (ARGS.has('--print-faces')) {
  const table = [...FACES.entries()].map(([p, v]) => ({ face: p, css: `${v.css}.css`, classHits: v.count, conflicts: v.conflicts }));
  console.log(JSON.stringify(table, null, 2));
  console.log(`face 表：${FACES.size} 面（来源 web/css/*.css 主导前缀，口径见脚本头注释 R2）`);
}

if (ARGS.has('--json')) {
  console.log(JSON.stringify({
    ok: fails.length === 0,
    totals,
    grandTotal: grand,
    filesWithHits: Object.keys(counts).length,
    baselineFiles: Object.keys(baseline).length,
    faces: Object.fromEntries([...FACES.entries()].map(([p, v]) => [p, { css: `${v.css}.css`, classHits: v.count, conflicts: v.conflicts }])),
    fails,
    notes,
  }, null, 2));
} else {
  console.log(`component-contract：${jsFiles.length} 个 js / ${cssFiles.length} 个 css / 面 ${FACES.size} 个 / 命中文档 ${Object.keys(counts).length} 个 / 基线条目 ${Object.keys(baseline).length} 个`);
  console.log('  ' + RULES.map((k) => `${RULE_LABEL[k]} = ${totals[k]}`).join('\n  ') + `\n  TOTAL = ${grand}`);
  for (const n of notes) console.log(`  ↑ ${n}`);
  if (fails.length === 0) {
    console.log('PASS: 五条组件契约（light DOM / 跨面 DOM / 事件字面量 / 组件内部类名 / 裸 innerHTML）零新增，均不超基线。');
  } else {
    console.log(`\nFAIL ${fails.length} 项：`);
    for (const f of fails) console.log(`  ${f.file}:${f.line}  [${f.rule}] ${f.detail}`);
  }
}
process.exit(fails.length ? 1 : 0);
