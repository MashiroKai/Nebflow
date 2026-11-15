#!/usr/bin/env node
// check-glass-unify.mjs — 「毛玻璃只有一份」静态哨兵
// （glass-unify 批 2026-11-05；作者令逐字：
//  「我们的毛玻璃效果没有统一，我们stand只允许一个毛玻璃效果……把毛玻璃效果作为
//    一个唯一的标准和术语，放到插件和前端组件。这样我说毛玻璃效果，就能找到那个
//    统一的设计标准。」）
//
// Why a gate：本批之前台账是 128 个含 backdrop-filter 的规则块、散在 33 个文件里，
// 配方有 30 种不同写法（blur 8/10/12/14/16/20/24/26px、saturate 1.1/1.15/1.2/
// 1.3/1.4/1.6/1.8，底色有 .55/.60/.68/.72/.88/.92 六档白度）。作者看到的
// 「图1 半透明能看见底色 / 图2 不透明」就是这个分裂的末端表现——同一句「毛玻璃」
// 在哪个文件里长成什么样，取决于写那一面的人当天的手感。手工审查抓不住 128 处，
// 所以把「配方只有一处」变成机器判据。
//
// ── 六条判据 ────────────────────────────────────────────────────────────
//  G1 双源背衬：**css/glass.css 之外**任何文件出现非 `none` 的 backdrop-filter /
//     -webkit-backdrop-filter ⇒ 红。`none` 放行：那不是第二套毛玻璃，是**摘除**
//     毛玻璃（降级档 @supports / 无 backdrop 引擎回落实底），性质相反不算违规。
//  G2 配方走样：glass.css 内部，每条非 none 的 backdrop-filter 值必须是
//     `var(--nf-glass-input-filter)`（幕布类 .nf-glass-scrim 允许 `var(--nf-glass-scrim-blur)`）
//     ⇒ 否则红。数字重新长回文件里 = 配方又开始分裂。
//     （单一质感批 2026-11-15：作者令逐字「我们只允许存在一种毛玻璃质感。你看看
//      主输入框用的是哪种，然后统一为规则和检查，只准使用这一种。」—— 唯一材质
//      = 主输入框 #input-bar 配方，recalibrate 的 40px 通透档退役，基准件
//      carve-out 一并撤销：没有「某些面可以用另一种」，只有一种。）
//  G3 标准跑出区间（**单一质感批 2026-11-15 重写**）：唯一玻璃底
//     `--nf-glass-input-bg` 解析出的 **α 必须落在 [0.90, 1.0] 区间内**，
//     亮/暗/媒体查询三档全查（作者 11-11 令「毛玻璃一律不透底」；唯一材质 =
//     近实底 α .968/.941）。同时出生地钉死：旧档名 --nf-glass-bg / -filter
//     必须仍是指向唯一值的别名，--nf-glass-blur 必须是 24px、
//     --nf-glass-saturate 必须是 1.15 —— 第二份字面值 = 第二种材质，红。
//     --nf-glass-bg-hover 单列 G3-H：它是唯一材质上的「hover 升一档」交互
//     层级（扁平六态批 2026-11-05 作者令），亮 #ffffff / 暗 #181c26fa 钉死；
//     写成同底（var(--nf-glass-input-bg)）或其它字面值同样红。
//  G4 令牌双源：`--nf-glass-*` 只许在 css/stand-tokens.css 里**定义**（`x: 字面量`）；
//     其它文件出现 `--nf-glass-*: <字面量>` ⇒ 红（引用 `var(--nf-glass-*)` 不限）。
//  G5 绕过工具类：canvas ask 的气泡与输入条必须挂 `.nf-glass`
//     （作者令 2026-11-05 16:50「这三处全部吃唯一毛玻璃标准，不许为 ask 气泡
//      另造一套等效样式」）⇒ js/askDock.js 里造这两个节点时缺类即红。
//  G6 配方被降档（单一质感批 2026-11-15 重写）：唯一模糊配方
//     `--nf-glass-input-filter` 必须**逐值**等于 `blur(24px) saturate(1.15)`
//     （= 主输入框配方，参照物逐值）。理由 = 唯一材质的定义本身就是这组值：
//     近实底（α .968/.941）下内容残留 ≈ (1−α)·g(blur) ≈ 3%，blur/饱和度不再
//     承担通透档时代的权重，任何偏离 = 偷偷换了一种质感。只钉 α 挡不住
//     「α 对、半径退回去」这种看起来合规的降档，故逐值进闸。
//
//  G7 顶部悬浮面 = 毛玻璃（**glass-top-overlays 批 2026-11-06 新增**）。作者令
//     逐字：「所有悬浮在顶部 可以透出背景的 都用毛玻璃效果 比如消息和联系人的
//     手机端的搜索框 这个要成为标准」
//     Why a gate：G1–G6 管的是「毛玻璃只有一份配方」，**管不住「某个面干脆不用
//     毛玻璃」** —— 一条 `background: rgba(128,128,128,.16) + backdrop-filter:none`
//     在旧判据下完全合法（`none` 是明许的摘除档），而它正是作者点名的那一面：
//     搜索条 17:34 离开文档流、浮在列表之上以后，α .16 的洗色底 + 零模糊 = 底下
//     的会话名清清楚楚穿上来（规范里「内容透底」那一端）。手工审查抓不住「哪个
//     面浮在内容上、有没有模糊」，所以把「顶部悬浮 ⇒ 必须吃唯一标准」变成判据。
//     三支判据：
//       · G7a 新面未走玻璃：**半透明底（α ∈ (0, .90)）+ 无模糊** 的面，只要它的
//         定位是「顶部悬浮」——① `position:sticky` 带 top（钉在滚区顶缘、内容从
//         底下过）；② 顶带（fixed/absolute + top ≤ 160px 或变量 + 左右贴边 0）；
//         ③ 顶带的子件（选择器链里含顶带复合选择器）—— 即红。
//         α = 0 不算面（零画带：手势穿透带，见规范 §7.2），α ≥ .90 不算透明面
//         （不透明的实底不承接背景，标准管不到它）。
//       · G7b 登记面材质漂移：规范 §7.1「顶部悬浮面清单」逐行机检 —— 每个登记面
//         必须仍然在场，且材质仍是它登记的那一种（毛玻璃 / 零画 / 例外）。
//         防「今天统一了、明天又被人改成洗色底」。
//       · G7c 例外表卫生：规范 §6 例外清单逐行 —— 理由为空 ⇒ 红；例外指向的选择器
//         在 CSS 里根本不存在（陈旧例外）⇒ 红（防「豁免一条、然后面没了也没人删」）。
//     例外清单**不在本脚本里**（与 flat-buttons 同规）：豁免 = 改规范，脚本只执行。
//
// 判据与术语的**规范侧**（人读条文、例外登记）在
//   src/main/resources/seed/plugins/design-language/skills/design-language/
//   references/glass-spec.md
// 本脚本只做它机械可判的那部分；改判据要先改规范。
//
// 用法：
//   node scripts/check-glass-unify.mjs            # 门禁（红/绿）
//   node scripts/check-glass-unify.mjs --json     # 机器读
//   node scripts/check-glass-unify.mjs --inventory# 盘点（按文件/形态统计）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// `--root=<dir>` 允许把判据跑在**另一个检出**上（改前/改后对照实证用：
//   git archive <main-tip> | tar -x -C /tmp/before
//   node scripts/check-glass-unify.mjs --root=/tmp/before
// 判据本身与目录无关 —— 同一份脚本、同一套红绿，才能证明「改前多少点、改后零」）。
const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
const TOKENS = join(CSS_DIR, 'stand-tokens.css');
const SINGLE_SOURCE = join(CSS_DIR, 'glass.css');
const ASK_DOCK = join(JS_DIR, 'askDock.js');
const SOCIAL_SEARCH = join(JS_DIR, 'mobile', 'socialSearch.js');
const INDEX_HTML = join(WEB, 'index.html');
/** 规范正文（G7 的登记面清单与例外清单都在这里，脚本只读不写）。 */
const SPEC = join(
  ROOT, 'src', 'main', 'resources', 'seed', 'plugins', 'design-language',
  'skills', 'design-language', 'references', 'glass-spec.md',
);

/** 唯一合法配方值（G2 白名单）：全站只有主输入框一种毛玻璃质感；
 *  幕布（scrim）不是毛玻璃面，借自己的 8px 单源 token。 */
const ALLOWED_FILTER_VALUES = new Set([
  'var(--nf-glass-input-filter)',
  'var(--nf-glass-scrim-blur)',
]);

/** G3：唯一玻璃底不透明度的**合格区间**。低于下限 = 内容透底
 *  （作者 2026-11-11 令「毛玻璃一律不透底」）；唯一材质近实底
 *  （亮 .968 / 暗 .941），故上限为 1。 */
const GLASS_ALPHA_MIN = 0.90;
const GLASS_ALPHA_MAX = 1.0;

/** G3 出生地钉死：旧档名必须仍指向唯一值；第二份字面值 = 第二种材质。
 *  --nf-glass-bg-hover 例外（同函数下方 G3-H 检查）：它是唯一材质上的
 *  「hover 升一档」交互层级（扁平六态批 2026-11-05 作者令），不是第二种
 *  质感 —— 按 G3-H 钉死亮/暗两档值。 */
const PINNED_TOKEN_ALIASES = {
  '--nf-glass-bg': 'var(--nf-glass-input-bg)',
  '--nf-glass-filter': 'var(--nf-glass-input-filter)',
};
/** G3-H：hover 层级钉死（唯一材质上只提不透一档；Δα ≥ §9-⑤ 门槛 8/255）。
 *  亮 .968 → 1.0（Δ 8.16/255）；暗 .941 → .980（Δ 10/255）。 */
const PINNED_BG_HOVER_LIGHT = '#ffffff';
const PINNED_BG_HOVER_DARK = '#181c26fa';
const PINNED_GLASS_BLUR = '24px';
const PINNED_GLASS_SATURATE = '1.15';

/** G6：唯一模糊配方逐值（= 主输入框配方，参照物逐值）。 */
const PINNED_INPUT_FILTER = 'blur(24px) saturate(1.15)';

const rel = (p) => relative(ROOT, p);

// ── 解析工具 ─────────────────────────────────────────────────────────────

/** 去注释但保留行号（等量换行）。 */
function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * 极简 CSS 块遍历器 → { selector, decls:[{prop,value,line}], line, atStack }。
 * 只关心规则块；at-rule 的 prelude 压栈，用于把命中归到 `@media` 语境。
 */
function* walkRules(src) {
  const text = stripCssComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  let i = 0;
  const stack = [];
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      if (prelude.startsWith('@')) {
        stack.push(prelude.replace(/\s+/g, ' '));
        i++;
        selStart = i;
        continue;
      }
      const bodyStart = i + 1;
      let j = bodyStart;
      let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') {
          if (inner === 0) break;
          inner--;
        }
        j++;
      }
      const body = text.slice(bodyStart, j);
      const decls = [];
      let cur = '';
      let declStart = bodyStart;
      for (let k = 0; k <= body.length; k++) {
        const c = body[k];
        if (c === ';' || k === body.length) {
          const raw = cur;
          const colon = raw.indexOf(':');
          if (colon > 0) {
            decls.push({
              prop: raw.slice(0, colon).trim().toLowerCase(),
              value: raw.slice(colon + 1).trim().replace(/\s+/g, ' '),
              line: lineOf(declStart),
            });
          }
          cur = '';
          declStart = bodyStart + k + 1;
        } else {
          if (c === '\n') declStart = bodyStart + k + 1;
          cur += c;
        }
      }
      yield { selector: prelude.replace(/\s+/g, ' '), decls, line: lineOf(selStart), atStack: [...stack] };
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}') {
      if (stack.length) stack.pop();
      i++;
      selStart = i;
      continue;
    }
    i++;
  }
}

/** 颜色字面量 → α。支持 #rrggbbaa / rgba() / rgb(a b c / a)。无法判定返回 null。 */
function alphaOf(value) {
  const v = String(value).trim();
  let m = v.match(/^#([0-9a-f]{8})$/i);
  if (m) return parseInt(m[1].slice(6, 8), 16) / 255;
  m = v.match(/^#([0-9a-f]{6})$/i);
  if (m) return 1;
  m = v.match(/^#([0-9a-f]{3})$/i);
  if (m) return 1;
  m = v.match(/^rgba?\(([^)]*)\)$/i);
  if (m) {
    const parts = m[1].split(/[,/]/).map((s) => s.trim()).filter(Boolean);
    if (parts.length >= 4) {
      const a = parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
      return Number.isFinite(a) ? a : null;
    }
    return 1;
  }
  return null;
}

/** 去 JS 行注释与块注释（尽量保住行号）。 */
function stripJsComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
}

// ── 扫描 ────────────────────────────────────────────────────────────────

const violations = [];
const stats = { cssFiles: 0, jsFiles: 0, blocks: 0, members: 0, noneOptOuts: 0, files: new Map() };
const add = (v) => violations.push(v);

// G1/G2 —— CSS 全量
for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()) {
  const path = join(CSS_DIR, f);
  const src = readFileSync(path, 'utf8');
  stats.cssFiles++;
  const isSource = path === SINGLE_SOURCE;
  for (const rule of walkRules(src)) {
    for (const d of rule.decls) {
      if (!/^(-webkit-)?backdrop-filter$/.test(d.prop)) continue;
      const value = d.value.replace(/\s*!important\s*$/i, '').trim();
      if (/^none$/i.test(value)) {
        stats.noneOptOuts++;
        continue;
      }
      stats.blocks++;
      stats.files.set(f, (stats.files.get(f) || 0) + 1);
      if (!isSource) {
        add({
          rule: 'G1',
          file: rel(path),
          line: d.line,
          selector: rule.selector,
          detail: `${d.prop}: ${value}`,
          why: '毛玻璃双源：glass.css 之外不许出现 backdrop-filter',
        });
        continue;
      }
      if (!ALLOWED_FILTER_VALUES.has(value)) {
        add({
          rule: 'G2',
          file: rel(path),
          line: d.line,
          selector: rule.selector,
          detail: `${d.prop}: ${value}`,
          why: '配方走样：唯一合法值是 var(--nf-glass-input-filter)（幕布类 .nf-glass-scrim 允许 var(--nf-glass-scrim-blur)）；全站只准一种毛玻璃质感（作者 2026-11-15 令）',
        });
      }
    }
  }
}

// G1 —— JS / HTML 内联样式面（只认「属性名: 值」形态；`transition: … backdrop-filter …`
// 与 CSS.supports('backdrop-filter', …) 都不是声明，正则天然不命中）
for (const dir of [JS_DIR, WEB]) {
  const files = dir === WEB
    ? readdirSync(WEB).filter((x) => x.endsWith('.html')).map((x) => join(WEB, x))
    : readdirSync(JS_DIR).filter((x) => x.endsWith('.js')).map((x) => join(JS_DIR, x));
  for (const path of files) {
    const raw = readFileSync(path, 'utf8');
    if (!raw.includes('backdrop-filter')) continue;
    stats.jsFiles++;
    const text = stripJsComments(raw);
    const lines = text.split('\n');
    for (let n = 0; n < lines.length; n++) {
      const re = /(?:^|[^-\w])(-webkit-)?backdrop-filter\s*:\s*([^;'"}]*)/g;
      let m;
      while ((m = re.exec(lines[n])) !== null) {
        const value = m[2].trim();
        if (!value) continue;
        if (/^none\b/i.test(value)) {
          stats.noneOptOuts++;
          continue;
        }
        stats.blocks++;
        add({
          rule: 'G1',
          file: rel(path),
          line: n + 1,
          selector: '(内联样式串)',
          detail: `backdrop-filter: ${value.slice(0, 80)}`,
          why: '毛玻璃双源：JS 注入/内联样式里也不许出现 backdrop-filter',
        });
      }
    }
  }
}

// G3 —— 唯一玻璃底不许跑出区间 + 旧档出生地必须仍指向唯一值（单一材质批 2026-11-15）
const tokenSrc = readFileSync(TOKENS, 'utf8');
const inputBgDecls = [];
const aliasDecls = [];
const blurDecls = [];
const satDecls = [];
const inputFilterDecls = [];
for (const rule of walkRules(tokenSrc)) {
  for (const d of rule.decls) {
    const value = d.value.trim();
    if (d.prop === '--nf-glass-input-bg') {
      inputBgDecls.push({ prop: d.prop, value, line: d.line, atStack: rule.atStack, selector: rule.selector });
    }
    if (PINNED_TOKEN_ALIASES[d.prop]) aliasDecls.push({ prop: d.prop, value, line: d.line, selector: rule.selector });
    if (d.prop === '--nf-glass-blur') blurDecls.push({ value, line: d.line, selector: rule.selector });
    if (d.prop === '--nf-glass-saturate') satDecls.push({ value, line: d.line, selector: rule.selector });
    if (d.prop === '--nf-glass-input-filter') inputFilterDecls.push({ value, line: d.line, selector: rule.selector });
  }
}
if (!inputBgDecls.length) {
  add({
    rule: 'G3',
    file: rel(TOKENS),
    line: 0,
    selector: ':root',
    detail: '--nf-glass-input-bg 未定义',
    why: '毛玻璃标准本体缺失：设计令牌单一源里找不到唯一玻璃底 --nf-glass-input-bg',
  });
}
for (const d of inputBgDecls) {
  const a = alphaOf(d.value);
  if (a === null) {
    add({
      rule: 'G3',
      file: rel(TOKENS),
      line: d.line,
      selector: d.selector,
      detail: `${d.prop}: ${d.value}`,
      why: '无法解析不透明度（请用 #rrggbbaa 或 rgba()）',
    });
    continue;
  }
  if (a < GLASS_ALPHA_MIN || a > GLASS_ALPHA_MAX) {
    const which = a > GLASS_ALPHA_MAX ? '高于上限（α > 1 不是合法颜色值）'
      : '低于下限（内容透底：底下的字看得出来 = 作者说的「脏」；11-11 令「毛玻璃一律不透底」）';
    add({
      rule: 'G3',
      file: rel(TOKENS),
      line: d.line,
      selector: d.selector,
      detail: `${d.prop}: ${d.value}  ⇒ α=${a.toFixed(3)} 跑出 [${GLASS_ALPHA_MIN}, ${GLASS_ALPHA_MAX}]`,
      why: `标准失效：${which}`,
    });
  }
}
for (const d of aliasDecls) {
  if (d.value !== PINNED_TOKEN_ALIASES[d.prop]) {
    add({
      rule: 'G3',
      file: rel(TOKENS),
      line: d.line,
      selector: d.selector,
      detail: `${d.prop}: ${d.value}  ⇒ 应为 ${PINNED_TOKEN_ALIASES[d.prop]}`,
      why: '第二份字面值 = 第二种材质：旧档名只准是指向唯一值的别名（作者 2026-11-15 令「只准使用这一种」）',
    });
  }
}
// G3-H —— hover 层级钉死（单一材质批 2026-11-15 补正）：bg-hover 是唯一材质上
// 的交互底色阶，亮/暗各钉一档值；写成 var(--nf-glass-input-bg)（同底）或任何
// 其它字面值都红 —— 同底会把扁平六态批的 hover 层静默抹平（§9-⑤ Δα 归零实测）。
const hoverDecls = [];
for (const rule of walkRules(tokenSrc)) {
  for (const d of rule.decls) {
    if (d.prop === '--nf-glass-bg-hover') {
      hoverDecls.push({ value: d.value.trim(), line: d.line, selector: rule.selector, atStack: rule.atStack });
    }
  }
}
if (!hoverDecls.length) {
  add({
    rule: 'G3-H', file: rel(TOKENS), line: 0, selector: ':root',
    detail: '--nf-glass-bg-hover 未定义',
    why: 'hover 升一档层级缺失：扁平六态批（2026-11-05 作者令「不管 hover 还是点击」）的底色阶断链',
  });
}
for (const d of hoverDecls) {
  const sel = d.selector || '';
  const inDark = sel.includes('data-theme="dark"')
    || (d.atStack.some((a) => /prefers-color-scheme:\s*dark/i.test(a))
      && !sel.includes('data-theme="light"'));
  const want = inDark ? PINNED_BG_HOVER_DARK : PINNED_BG_HOVER_LIGHT;
  if (d.value.toLowerCase() !== want) {
    add({
      rule: 'G3-H', file: rel(TOKENS), line: d.line, selector: d.selector,
      detail: `--nf-glass-bg-hover: ${d.value}  ⇒ ${inDark ? '暗档' : '亮档'}应为 ${want}`,
      why: 'hover 升一档（唯一材质只提不透）：同底 = 层级被抹平（扁平六态批作者令），其它字面值 = 第二种材质（单一材质批 2026-11-15）',
    });
  }
}
for (const d of blurDecls) {
  if (d.value !== PINNED_GLASS_BLUR) {
    add({
      rule: 'G3', file: rel(TOKENS), line: d.line, selector: d.selector,
      detail: `--nf-glass-blur: ${d.value}  ⇒ 应为 ${PINNED_GLASS_BLUR}`,
      why: '唯一材质的模糊半径是基准件原值；偏离 = 偷偷换质感（单一材质批 2026-11-15）',
    });
  }
}
for (const d of satDecls) {
  if (d.value !== PINNED_GLASS_SATURATE) {
    add({
      rule: 'G3', file: rel(TOKENS), line: d.line, selector: d.selector,
      detail: `--nf-glass-saturate: ${d.value}  ⇒ 应为 ${PINNED_GLASS_SATURATE}`,
      why: '唯一材质的饱和度是基准件原值；偏离 = 偷偷换质感（单一材质批 2026-11-15）',
    });
  }
}
if (!blurDecls.length || !satDecls.length) {
  add({ rule: 'G3', file: rel(TOKENS), line: 0, selector: ':root', detail: '--nf-glass-blur / --nf-glass-saturate 缺失', why: '标准本体缺失：配方无法机检' });
}

// G6 —— 唯一模糊配方逐值（= 主输入框配方；只钉 α 挡不住「α 对、半径退回去」）
for (const d of inputFilterDecls) {
  if (d.value.replace(/\s+/g, '') !== PINNED_INPUT_FILTER.replace(/\s+/g, '')) {
    add({
      rule: 'G6', file: rel(TOKENS), line: d.line, selector: d.selector,
      detail: `--nf-glass-input-filter: ${d.value}`,
      why: `唯一模糊配方被改动：必须逐值等于 ${PINNED_INPUT_FILTER}（= 主输入框配方，作者 2026-11-15 令）`,
    });
  }
}
if (!inputFilterDecls.length) {
  add({ rule: 'G6', file: rel(TOKENS), line: 0, selector: ':root', detail: '--nf-glass-input-filter 缺失', why: '标准本体缺失：唯一配方无法机检' });
}

// G4 —— 令牌只许有一处出生地
for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()) {
  const path = join(CSS_DIR, f);
  if (path === TOKENS) continue;
  for (const rule of walkRules(readFileSync(path, 'utf8'))) {
    for (const d of rule.decls) {
      if (!/^--nf-glass-/.test(d.prop)) continue;
      if (/^var\(/.test(d.value)) continue;
      add({
        rule: 'G4',
        file: rel(path),
        line: d.line,
        selector: rule.selector,
        detail: `${d.prop}: ${d.value}`,
        why: '令牌双源：--nf-glass-* 只许在 stand-tokens.css 里定义',
      });
    }
  }
}

// G5 —— canvas ask 的气泡/输入条 + 手机常驻搜索条必须走 .nf-glass
if (!existsSync(ASK_DOCK)) {
  add({
    rule: 'G5',
    file: rel(ASK_DOCK),
    line: 0,
    selector: '-',
    detail: 'js/askDock.js 不存在',
    why: '环境异常：ask 面无法核对',
  });
} else {
  const askSrc = readFileSync(ASK_DOCK, 'utf8');
  const checks = [
    { re: /class:\s*'ask-bub nf-glass/, label: 'ask 气泡（ask-bub）', hint: "h('div', { class: 'ask-bub nf-glass …' })" },
    { re: /class:\s*'ask-input nf-glass/, label: 'ask 输入条（ask-input）', hint: "h('div', { class: 'ask-input nf-glass' })" },
  ];
  for (const c of checks) {
    if (!c.re.test(askSrc)) {
      add({
        rule: 'G5',
        file: rel(ASK_DOCK),
        line: 0,
        selector: c.label,
        detail: `缺少 .nf-glass（应形如 ${c.hint}）`,
        why: '绕过唯一毛玻璃工具类：canvas ask 面必须吃同一份标准',
      });
    }
  }
  // 手机搜索条（glass-top-overlays 批 2026-11-06）：作者点名面 —— 消息/联系人
  // 两页的搜索框浮在滚动列表之上，必须挂唯一工具类（本条管「DOM 真的挂上了」，
  // CSS 侧管「挂了以后没人把它冲掉」= G7b 的登记面）。
  if (!existsSync(SOCIAL_SEARCH)) {
    add({
      rule: 'G5', file: rel(SOCIAL_SEARCH), line: 0, selector: '-',
      detail: 'js/mobile/socialSearch.js 不存在',
      why: '环境异常：手机搜索条无法核对',
    });
  } else {
    const src = readFileSync(SOCIAL_SEARCH, 'utf8');
    if (!/row\.className\s*=\s*'mh-search-bar nf-glass'/.test(src)) {
      add({
        rule: 'G5',
        file: rel(SOCIAL_SEARCH),
        line: 0,
        selector: '手机搜索条（.mh-search-bar）',
        detail: "建节点处缺少 .nf-glass（应为 row.className = 'mh-search-bar nf-glass'）",
        why: '顶部悬浮可透出面绕过唯一毛玻璃工具类（作者点名面）',
      });
    }
  }
}

// ── G7 顶部悬浮面 = 毛玻璃 ────────────────────────────────────────────────
// 选择器/令牌工具（与 G1–G6 的 walkRules 复用，不改动它们的判据）

/** 按顶层分隔符切（括号内不切）：逗号切「多选择器」，空白/>+~ 切「复合链」。 */
function splitTop(s, seps) {
  const out = []; let depth = 0; let cur = '';
  for (const ch of s) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (depth === 0 && seps.test(ch)) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  out.push(cur);
  return out.map((x) => x.trim()).filter(Boolean);
}
const subjectOf = (cx) => { const p = splitTop(cx, /[\s>+~]/); return p[p.length - 1] || ''; };
const pseudoOf = (c) => { const m = c.match(/::([a-z-]+)(\([^()]*\))?/i); return m ? `::${m[1]}` : ''; };
/** 复合选择器的「元素基」（剥 :not()/伪类/伪元素），用于跨规则对齐同一元素。 */
const baseOf = (c) => c
  .replace(/:not\([^()]*\)/g, '')
  .replace(/::[a-z-]+(\([^()]*\))?/gi, '')
  .replace(/:[a-z-]+(\([^()]*\))?/gi, '')
  .trim();
/** 元素键 = 基 + 伪元素（`X::before` 与 `X` 是两个不同的绘制面，不能合并）。 */
const keyOf = (cx) => { const s = subjectOf(cx); return baseOf(s) + pseudoOf(s); };

/** CSS 加载序（= index.html 的 <link> 次序；缺失文件按字母序补尾）。
 *  级联近似只需「谁后声明谁赢」，无需真级联。 */
function cssLoadOrder() {
  const order = [];
  if (existsSync(INDEX_HTML)) {
    const html = readFileSync(INDEX_HTML, 'utf8');
    const re = /<link[^>]*>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
      const tag = m[0];
      if (!/rel\s*=\s*["']stylesheet["']/i.test(tag)) continue;
      const href = (tag.match(/href\s*=\s*["']([^"']+)["']/i) || [])[1];
      if (!href || !href.startsWith('css/')) continue;
      const f = href.slice(4).split(/[?#]/)[0];
      if (existsSync(join(CSS_DIR, f)) && !order.includes(f)) order.push(f);
    }
  }
  for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css')).sort()) {
    if (!order.includes(f)) order.push(f);
  }
  return order;
}

const g7 = { sticky: 0, bands: 0, surfaces: 0, hits: [], exemptHits: [], registry: [], exemptRows: [] };
{
  const order = cssLoadOrder();
  const rulesOf = new Map();
  const tokenDefs = new Map();   // --x: 值（按加载序覆盖，:root 级联近似）
  for (const f of order) {
    const src = readFileSync(join(CSS_DIR, f), 'utf8');
    const rules = [...walkRules(src)];
    rulesOf.set(f, rules);
    for (const r of rules) for (const d of r.decls) if (d.prop.startsWith('--')) tokenDefs.set(d.prop, d.value);
  }
  /** 令牌解引用（含 `var(--x, fallback)`；只解「整值是 var()」这一层，够 G7 用）。 */
  const deref = (v, depth = 0) => {
    if (depth > 10) return v;
    let s = String(v).trim();
    const m = s.match(/^var\(\s*(--[a-z0-9-]+)\s*(?:,([\s\S]*))?\)$/i);
    if (m) {
      const hit = tokenDefs.get(m[1]);
      if (hit !== undefined) return deref(hit, depth + 1);
      if (m[2]) return deref(m[2].replace(/^,/, ''), depth + 1);
      return s;
    }
    return s;
  };
  /** α 判定（比 G3 的 alphaOf 多认两件：现代 `rgb(r g b / a)` 与 `rgb(var(--x) / a)`）。 */
  const alphaG7 = (value) => {
    const v = deref(value);
    let m = v.match(/^#([0-9a-f]{8})$/i);
    if (m) return parseInt(m[1].slice(6, 8), 16) / 255;
    if (/^#([0-9a-f]{6}|[0-9a-f]{3})$/i.test(v)) return 1;
    if (/^rgba?\(/i.test(v)) {
      const inner = v.slice(v.indexOf('(') + 1, v.lastIndexOf(')'));
      if (inner.includes('/')) {
        const tail = inner.slice(inner.lastIndexOf('/') + 1).trim();
        const a = tail.endsWith('%') ? parseFloat(tail) / 100 : parseFloat(tail);
        return Number.isFinite(a) ? a : null;
      }
      const p = inner.split(',').map((x) => x.trim()).filter(Boolean);
      if (p.length >= 4) {
        const a = p[3].endsWith('%') ? parseFloat(p[3]) / 100 : parseFloat(p[3]);
        return Number.isFinite(a) ? a : null;
      }
      return 1;
    }
    m = v.match(/^color-mix\(in srgb,[\s\S]+?\s+([\d.]+)%\s*,\s*transparent\)$/i);
    if (m) return parseFloat(m[1]) / 100;
    if (/^(transparent|none|initial|unset)$/i.test(v)) return 0;
    return null;
  };
  const isBackdropProp = (p) => /^(?:-webkit-)?backdrop-filter$/.test(p);
  const hasBlur = (r) => r.decls.some((d) => isBackdropProp(d.prop) && !/^none$/i.test(d.value.trim()));
  /** 降级档（@supports not …）：那是「引擎不支持 ⇒ 摘掉采样」，与「另立一档」性质相反，
   *  G1 已有同款宽容口径（`none` 放行），G7 一并豁免这类块（否则会把正常配方面判红）。 */
  const isDegradeBlock = (r) => r.atStack.some((a) => /^@supports\s+not\b/i.test(a));

  // ① 玻璃供体（glass.css 里带 blur 的规则 → 其元素基 = 玻璃面）
  const glassBases = new Set();
  for (const r of rulesOf.get('glass.css') || []) {
    if (!hasBlur(r)) continue;
    for (const cx of splitTop(r.selector, /,/)) { const b = baseOf(subjectOf(cx)); if (b) glassBases.add(b); }
  }

  // ② 顶部悬浮根：sticky 带 top / 顶带（fixed|absolute + top 小 + 左右贴边 0）
  const stickyRoots = new Map();
  const bandRoots = new Map();
  const TOP_OK = (v) => {
    const t = String(v).trim();
    if (/^0(px)?$/.test(t)) return true;
    const px = t.match(/^(\d+(?:\.\d+)?)px$/);
    if (px) return parseFloat(px[1]) <= 160;
    return /^(var|calc|env|min|max)\(/.test(t);
  };
  const ZERO = (d) => d && /^0(px)?$/.test(d.value.trim());
  for (const f of order) {
    for (const r of rulesOf.get(f)) {
      const decl = (p) => r.decls.find((d) => d.prop === p);
      const pos = decl('position');
      if (!pos) continue;
      const pv = pos.value.trim().toLowerCase();
      const top = decl('top');
      const left = decl('left');
      const right = decl('right');
      const inset = decl('inset');
      const insetInline = decl('inset-inline');
      const fullBleed = (ZERO(left) && ZERO(right))
        || (inset && /^0(px)?(\s+0(px)?){0,3}$/.test(inset.value.trim()))
        || ZERO(insetInline);
      for (const cx of splitTop(r.selector, /,/)) {
        // 伪元素不是「带」（::after 往往是命中盒外扩层）——根只认宿主元素
        if (pseudoOf(subjectOf(cx))) continue;
        const b = baseOf(subjectOf(cx));
        if (!b) continue;
        if (pv === 'sticky' && top && TOP_OK(top.value)) {
          if (!stickyRoots.has(b)) stickyRoots.set(b, { file: f, line: r.line, top: top.value });
          continue;
        }
        if ((pv === 'fixed' || pv === 'absolute') && top && TOP_OK(top.value) && fullBleed) {
          if (!bandRoots.has(b)) bandRoots.set(b, { file: f, line: r.line, top: top.value });
        }
      }
    }
  }

  // ③ 逐元素键算「有效材质」（后声明者胜）
  //    「后声明者胜」用全局声明序近似（文件加载序 × 文件内次序）：同一元素上
  //    `backdrop-filter: none` 与成员表的 blur 谁生效，取决于谁在后面 —— 搜索条
  //    正是这个形态（glass.css 成员供 blur、mobileSearchFlat.css 晚到把它摘掉）。
  //    特异性不参与近似（同一面两条规则通常同档），该近似口径记在规范 §4 注。
  const eff = new Map();
  let seq = 0;
  for (const f of order) {
    for (const r of rulesOf.get(f)) {
      seq++;
      if (isDegradeBlock(r)) continue;
      const blur = hasBlur(r);
      const optedOut = r.decls.some((d) => isBackdropProp(d.prop) && /^none$/i.test(d.value.trim()));
      for (const cx of splitTop(r.selector, /,/)) {
        if (/::-webkit-scrollbar/.test(cx)) continue;   // 滚动条不是面
        const key = keyOf(cx);
        if (!key) continue;
        const cur = eff.get(key) || {
          bg: null, bgFile: null, bgLine: 0, blur: false, optOut: false,
          blurSeq: 0, optOutSeq: 0, bandAnc: null,
        };
        const bg = r.decls.find((d) => d.prop === 'background' || d.prop === 'background-color');
        if (bg) { cur.bg = bg.value; cur.bgFile = f; cur.bgLine = r.line; }
        if (blur) { cur.blur = true; cur.blurSeq = seq; }
        if (optedOut) { cur.optOut = true; cur.optOutSeq = seq; }
        const chain = splitTop(cx, /[\s>+~]/);
        for (let k = 0; k < chain.length - 1; k++) {
          const ab = baseOf(chain[k]);
          if (bandRoots.has(ab)) { cur.bandAnc = ab; break; }
        }
        if (!cur.bandAnc && bandRoots.has(baseOf(subjectOf(cx)))) cur.bandAnc = `self:${baseOf(subjectOf(cx))}`;
        eff.set(key, cur);
      }
    }
  }
  /** 有效模糊 = 最后一次「声明 blur」晚于最后一次「摘除 blur」。 */
  const effBlur = (e) => !!e && e.blur && !(e.optOut && e.optOutSeq > e.blurSeq);
  const effOptedOut = (e) => !!e && e.optOut && e.optOutSeq > e.blurSeq;
  g7.sticky = stickyRoots.size;
  g7.bands = bandRoots.size;
  g7.surfaces = eff.size;

  // ④ 规范里的两张表（登记面清单 / 例外清单）
  const specSrc = existsSync(SPEC) ? readFileSync(SPEC, 'utf8') : '';
  const tables = [];
  if (specSrc) {
    let cur = null;
    for (const line of specSrc.split('\n')) {
      const t = line.trim();
      if (!t.startsWith('|')) { cur = null; continue; }
      const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map((x) => x.trim());
      if (/^[-:| ]+$/.test(cells.join(''))) continue;     // 分隔行
      if (!cur) { cur = { header: cells, rows: [] }; tables.push(cur); continue; }
      cur.rows.push(cells);
    }
  }
  const regTable = tables.find((t) => t.header.some((h) => /期望材质/.test(h)));
  const excTable = tables.find((t) => t.header.some((h) => /^理由$/.test(h)) && t.header.some((h) => /选择器/.test(h)));
  const clean = (s) => String(s || '').replace(/`/g, '').trim();

  if (!regTable) {
    add({ rule: 'G7b', file: rel(SPEC), line: 0, selector: '-', detail: '缺少「顶部悬浮面清单」表', why: '没有登记面清单 = 顶部悬浮面无约束；先改规范再加面' });
  } else {
    for (const row of regTable.rows) {
      const [id, face, selector, material] = row;
      const sel = clean(selector);
      const want = clean(material);
      if (!sel) continue;
      const base = baseOf(sel);
      const key = pseudoOf(sel) ? base + pseudoOf(sel) : base;
      const known = eff.has(key) || glassBases.has(base) || [...eff.keys()].some((k) => k === base + '::after' || k === base + '::before');
      const entry = { id: clean(id), face: clean(face), selector: sel, want, ok: true, detail: '' };
      if (!known) {
        entry.ok = false;
        entry.detail = '登记面在 CSS 里找不到（陈旧登记？）';
      } else if (want === '零画') {
        // 零画 = 带本体不画任何东西：声明过的底必须是 α 0（或无底）
        const e = eff.get(key);
        const a = e && e.bg ? alphaG7(e.bg) : 0;
        if (a === null || a > 0) { entry.ok = false; entry.detail = `期望零画（α=0），实际底 = ${e ? e.bg : '(无)'}`; }
      } else if (want === '例外') {
        const hit = excTable && excTable.rows.some((r2) => clean(r2[0]) === sel);
        if (!hit) { entry.ok = false; entry.detail = '登记为「例外」但例外清单里没有对应行'; }
      } else if (want.startsWith('毛玻璃')) {
        // `毛玻璃` / `毛玻璃@<类>` / `毛玻璃/零画`（同一元素在另一个输入能力档
        // 让位成纯手势面：底 α=0，玻璃交给它的替代件 —— 例如粗指针横排档的
        // #canvas-panel .canvas-header 让位给 .cr-capsule）
        const viaClass = (want.split('@')[1] || '').trim();
        const e = eff.get(key);
        const a = e && e.bg ? alphaG7(e.bg) : null;
        if (want.includes('/零画') && (a === 0 || a === null)) { g7.registry.push(entry); continue; }
        const glassed = viaClass
          ? (glassBases.has(`.${viaClass}`) || glassBases.has(viaClass))
          : (effBlur(e) || (glassBases.has(base) && !effOptedOut(e)));
        const bandOk = a === null || (a >= GLASS_ALPHA_MIN && a <= GLASS_ALPHA_MAX);
        if (!glassed) {
          entry.ok = false;
          entry.detail = `期望毛玻璃，但该面没有任何模糊来源（${viaClass ? `.${viaClass} 不在 glass.css 供体里` : '既无成员也无类'}）`;
        } else if (!bandOk) {
          entry.ok = false;
          entry.detail = `玻璃底 α=${a.toFixed(3)} 跑出 [${GLASS_ALPHA_MIN}, ${GLASS_ALPHA_MAX}]（${e.bg}）`;
        }
      }
      g7.registry.push(entry);
      if (!entry.ok) {
        add({ rule: 'G7b', file: rel(SPEC), line: 0, selector: sel, detail: entry.detail, why: `登记面材质漂移：顶部悬浮面清单 ${entry.id}` });
      }
    }
  }

  // ⑤ 例外清单（判据号 + 理由；空理由 / 陈旧例外 ⇒ 红）
  const exemptSelectors = new Set();
  if (!excTable) {
    add({ rule: 'G7c', file: rel(SPEC), line: 0, selector: '-', detail: '缺少「例外清单」表', why: '例外必须有具名登记处；没有表 = 豁免无账本' });
  } else {
    for (const row of excTable.rows) {
      const sel = clean(row[0]);
      const ruleId = clean(row[1]);
      const reason = clean(row.slice(2).join(' | '));
      if (!sel) continue;
      g7.exemptRows.push({ sel, rule: ruleId, reason });
      if (!reason || reason.length < 8) {
        add({ rule: 'G7c', file: rel(SPEC), line: 0, selector: sel, detail: '例外理由为空/过短', why: '例外必须写明理由（空理由 = 无效例外）' });
      }
      const base = baseOf(sel);
      const realExists = eff.has(base) || eff.has(sel) || glassBases.has(base)
        || [...eff.keys()].some((k) => k.startsWith(base));
      if (!realExists) {
        add({ rule: 'G7c', file: rel(SPEC), line: 0, selector: sel, detail: '陈旧例外：CSS 里找不到这个元素', why: '例外指向的面已不存在 ⇒ 删掉这行（豁免不许空转）' });
      }
      exemptSelectors.add(base);
      exemptSelectors.add(sel);
    }
  }

  // ⑥ G7a 机械扫：顶部悬浮 + 半透明 + 无模糊 ⇒ 红（例外表放行）
  const isExempt = (key, base) => exemptSelectors.has(key) || exemptSelectors.has(base);
  for (const [key, e] of eff) {
    const base = baseOf(key);
    const isSticky = stickyRoots.has(base);
    const isBand = bandRoots.has(base) || !!e.bandAnc;
    if (!isSticky && !isBand) continue;
    const a = e.bg ? alphaG7(e.bg) : null;
    if (a === null || a <= 0 || a >= 0.9) continue;      // 零画 / 不透明：不是「可透出面」
    const glassed = effBlur(e) || (glassBases.has(base) && !effOptedOut(e));
    if (glassed) continue;
    const rec = {
      key, base, alpha: a, bg: e.bg, file: e.bgFile || '-', line: e.bgLine || 0,
      where: isSticky ? `sticky top=${stickyRoots.get(base).top}`
        : `顶带${e.bandAnc && !e.bandAnc.startsWith('self:') ? `（在 ${e.bandAnc} 内）` : ''}`,
      optedOut: effOptedOut(e), member: glassBases.has(base),
    };
    if (isExempt(key, base)) { g7.exemptHits.push(rec); continue; }
    g7.hits.push(rec);
    add({
      rule: 'G7a',
      file: rec.file,
      line: rec.line,
      selector: key,
      detail: `α=${a.toFixed(3)} 无模糊（${e.bg}）· ${rec.where}${rec.optedOut ? ' · 显式 backdrop-filter:none' : ''}`,
      why: '顶部悬浮可透出面必须吃唯一毛玻璃标准（挂 .nf-glass 或加进 glass.css 成员表）；半透明 + 无模糊 = 底下的内容直接穿上来',
    });
  }
}

// 成员台账（信息面，不判红）
const members = (() => {
  const src = readFileSync(SINGLE_SOURCE, 'utf8');
  let n = 0;
  for (const rule of walkRules(src)) {
    if (rule.decls.some((d) => /^(-webkit-)?backdrop-filter$/.test(d.prop) && !/^none$/i.test(d.value))) n++;
  }
  return n;
})();
stats.members = members;

// ── 输出 ────────────────────────────────────────────────────────────────

const argv = new Set(process.argv.slice(2).filter((a) => !a.startsWith('--root=')));

if (argv.has('--json')) {
  console.log(JSON.stringify({
    ok: violations.length === 0,
    singleSource: rel(SINGLE_SOURCE),
    tokenSource: rel(TOKENS),
    glassAlphaBand: [GLASS_ALPHA_MIN, GLASS_ALPHA_MAX],
    pinnedInputFilter: PINNED_INPUT_FILTER,
    blocksWithBackdrop: stats.blocks,
    noneOptOuts: stats.noneOptOuts,
    ruleBlocksInSingleSource: members,
    cssFiles: stats.cssFiles,
    topOverlays: {
      stickyRoots: g7.sticky,
      topBands: g7.bands,
      elementSurfaces: g7.surfaces,
      violations: g7.hits,
      exempted: g7.exemptHits,
      registry: g7.registry,
      exemptions: g7.exemptRows,
    },
    violations,
  }, null, 2));
  process.exit(violations.length ? 1 : 0);
}

if (argv.has('--inventory')) {
  console.log(`CSS 文件 ${stats.cssFiles} · 含 backdrop-filter 的规则块 ${stats.blocks}（none 摘除档 ${stats.noneOptOuts}）`);
  console.log(`单一源 glass.css 内的配方块 ${members} · 违规 ${violations.length}`);
  console.log('\n── 按文件（配方块数）──');
  for (const [f, n] of [...stats.files].sort((a, b) => b[1] - a[1])) console.log(`${String(n).padStart(4)}  ${f}`);
  console.log(`\n── G7 顶部悬浮面（sticky 根 ${g7.sticky} · 顶带 ${g7.bands} · 元素键 ${g7.surfaces}）──`);
  console.log(`登记面 ${g7.registry.length} 条（红 ${g7.registry.filter((r) => !r.ok).length}）· `
    + `例外 ${g7.exemptRows.length} 条（命中 ${g7.exemptHits.length} 个面）· 违规 ${g7.hits.length}`);
  console.log('\n登记面清单：');
  for (const r of g7.registry) {
    console.log(`  ${r.ok ? '✓' : '✗'} ${r.id.padEnd(3)} ${r.want.padEnd(12)} ${r.selector}${r.ok ? '' : `  ← ${r.detail}`}`);
  }
  if (g7.hits.length) {
    console.log('\nG7a 违规（顶部悬浮 + 半透明 + 无模糊）：');
    for (const h of g7.hits) console.log(`  ${h.file}:${h.line}  ${h.key}  α=${h.alpha.toFixed(3)} · ${h.where}`);
  }
  if (g7.exemptHits.length) {
    console.log('\n例外命中（已登记，不判红）：');
    for (const h of g7.exemptHits) console.log(`  ${h.file}:${h.line}  ${h.key}  α=${h.alpha.toFixed(3)} · ${h.where}`);
  }
  process.exit(0);
}

if (violations.length) {
  console.error('✗ glass-unify gate FAILED');
  console.error(`  含 backdrop-filter 的配方块 ${stats.blocks} · 单一源内 ${members} · 违规 ${violations.length}`);
  for (const v of violations) {
    console.error(`  ${v.file}:${v.line}  [${v.rule}] ${v.detail}`);
    if (v.selector && v.selector !== '-') console.error(`      ${v.selector}`);
    console.error(`      ${v.why}`);
  }
  console.error('\n处置：把这一面改成 .nf-glass（或加进 css/glass.css 的成员登记表）、');
  console.error(`        配方值只准 var(--nf-glass-input-filter)、唯一玻璃底 α 落进 [${GLASS_ALPHA_MIN}, ${GLASS_ALPHA_MAX}]、`);
  console.error(`        唯一模糊配方逐值 = ${PINNED_INPUT_FILTER}（幕布类允许 var(--nf-glass-scrim-blur)）。`);
  console.error('        顶部悬浮可透出面（G7）：不是挂 .nf-glass / 进成员表，就是进规范的');
  console.error('        例外清单写明理由 —— 不许半透明洗色底 + 零模糊悄悄留着。');
  console.error('        规范条文与例外登记：design-language/references/glass-spec.md');
  process.exit(1);
}

console.log(
  `✓ glass-unify gate passed — 配方块 ${stats.blocks} 全部在单一源（${members} 条规则）、` +
  `none 摘除档 ${stats.noneOptOuts}、唯一玻璃底 α ∈ [${GLASS_ALPHA_MIN}, ${GLASS_ALPHA_MAX}]、` +
  `唯一配方 ${PINNED_INPUT_FILTER}（主输入框质感，全站只此一种）、ask 面走 .nf-glass、` +
  `顶部悬浮面 ${g7.registry.length} 条全绿（违规 ${g7.hits.length} / 具名例外命中 ${g7.exemptHits.length}）`,
);
process.exit(0);
