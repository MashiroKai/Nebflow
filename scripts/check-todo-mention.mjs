#!/usr/bin/env node
// check-todo-mention.mjs — `#` 待办引用 + 两段化 + 拼音匹配的机械门禁
// （待办结构化批, 2026-11-05 作者令「name + detail 架构 / `#` 引用待办 /
// 中文拼音优化」）。Node stdlib only，零依赖。
//
// 断言三组（全部跑**真实源码**，跑影子拷贝一律不算）：
//   A. `#` 分词门禁矩阵 —— 从 tokenComplete.js 锚点抽 parseToken + isInsideCode
//      原文执行：行首/空白/汉字后开 token；词中（ASCII 字母数字前）不开；
//      `#692`（任务号）、`#fff`/`#ffffff`（色值）、代码围栏/行内代码内一律不开；
//      `@` 的既有 8 例作为**回归腿**逐条重跑（触发面零漂移）。
//   B. 拼音 + 排序 —— 真实 import pinyin.js（纯数据/纯函数，无 DOM 依赖）验
//      全拼/前缀全拼/首字母三档；rankCandidates 从 tokenComplete.js 抽出原文
//      执行（只注入真实 pinyinScore/hasHan + locale 桩），钉住不变式：
//      名称前缀 > 名称中段 > 全拼 > 首字母 > detail，拼音永不压过字面前缀。
//   C. 两段化 —— 真实 import todoText.js：旧行投影零丢失（stripWs 逐字相等）、
//      标题长度上限、句末标点切分；并静态钉渲染面（列表行显 name、detail 默认
//      hidden + 折角钮）与 chip 形态（data-todo-id、未识别不吞字）。
//
// Exit: 0 = 全绿 | 1 = 有断言红 | 2 = 抽取/形状失败（响亮失败，绝不静默放行）。
//
// 用法：node scripts/check-todo-mention.mjs

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(root, 'src/main/resources/web/js');
const TOKEN_PATH = join(WEB, 'tokenComplete.js');
const UTILS_PATH = join(WEB, 'utils.js');
const FACE_PATH = join(WEB, 'todoFace.js');
const FACE_CSS_PATH = join(root, 'src/main/resources/web/css/todo-face.css');
const TODO_COMPLETE_PATH = join(WEB, 'todoComplete.js');

const fail2 = (msg) => {
  console.error('EXTRACT-FAIL: ' + msg);
  process.exit(2);
};

const read = (p) => readFileSync(p, 'utf8');

/** 从 `lines` 的 startIdx 行起做花括号深度扫描，返回整块文本。 */
function blockAt(lines, startIdx) {
  let depth = 0;
  for (let i = startIdx; i < lines.length; i++) {
    for (const ch of lines[i]) {
      if (ch === '{') depth++;
      else if (ch === '}') depth--;
    }
    if (depth === 0 && i > startIdx) return lines.slice(startIdx, i + 1).join('\n');
  }
  return null;
}

/** 按锚点正则找函数定义块（`function name(args) {` 单行形式）。 */
function extractFn(lines, anchorRe, label) {
  const idx = lines.findIndex((l) => anchorRe.test(l));
  if (idx < 0) fail2(`${label} anchor line not found`);
  const text = blockAt(lines, idx);
  if (!text) fail2(`${label} block never closes (brace scan)`);
  return text;
}

const results = [];
const add = (id, pass, detail) => {
  if (!pass) results.push({ id, pass, detail });
  else results.push({ id, pass, detail });
};

// ── A. `#` 分词门禁（真实 parseToken / isInsideCode 原文执行）──────────────
const tokenSrc = read(TOKEN_PATH);
const tokenLines = tokenSrc.split('\n');

const wsLine = tokenLines.find((l) => /^const WHITESPACE_RE = \/.+\/;\s*$/.test(l));
if (!wsLine) fail2('WHITESPACE_RE declaration not found in tokenComplete.js');
const hexLine = tokenLines.find((l) => /^const HEX_COLOR_RE = .+;\s*$/.test(l));
if (!hexLine) fail2('HEX_COLOR_RE declaration not found in tokenComplete.js');

const parseTokenText = extractFn(tokenLines, /^function parseToken\(input, spec\) \{$/, 'parseToken');
const isInsideCodeText = extractFn(tokenLines, /^function isInsideCode\(text\) \{$/, 'isInsideCode');

let tokenFns;
try {
  tokenFns = new Function(
    wsLine + '\n' + hexLine + '\n' + parseTokenText + '\n' + isInsideCodeText +
    '\nreturn { parseToken, isInsideCode };'
  )();
} catch (e) {
  fail2('extracted token snippet failed to evaluate: ' + e.message);
}
const { parseToken, isInsideCode } = tokenFns;
if (typeof parseToken !== 'function' || typeof isInsideCode !== 'function') {
  fail2('parseToken / isInsideCode did not evaluate to functions');
}

/** provider spec 形状（只带 parseToken 读的字段）。 */
const TODO_SPEC = {
  trigger: '#', codeGate: true, blockDigitStart: true, blockHexColor: true,
};
const PROJECT_SPEC = { trigger: '@' };
/** @@ 只为形状合法（没有这些开关 = undefined，等同关闭）。 */

const asInput = (value, caret) => ({ value, selectionStart: caret == null ? value.length : caret });

const TOKEN_CASES = [
  // ── `#` 正例（应当开 token）──
  { id: '# line-start', spec: TODO_SPEC, value: '#买牛奶', expect: 'token' },
  { id: '# after-whitespace', spec: TODO_SPEC, value: '记一下 #买牛奶', expect: 'token' },
  { id: '# after-hanzi (CJK 无空格)', spec: TODO_SPEC, value: '顺便#买牛奶', expect: 'token' },
  { id: '# after-cjk-punct', spec: TODO_SPEC, value: '。（#买牛奶', expect: 'token' },
  { id: '# after-newline', spec: TODO_SPEC, value: '第一行\n#买牛奶', expect: 'token' },
  { id: '# help text (代码外观之外)', spec: TODO_SPEC, value: 'sizeof 是 #买牛奶 的意思', caret: 21, expect: 'null' },
  // ── `#` 反例（必须不开）──
  { id: '# mid-word after ascii letter (C#)', spec: TODO_SPEC, value: 'C#', expect: 'null' },
  { id: '# mid-word after ascii digit', spec: TODO_SPEC, value: 'a1#买', expect: 'null' },
  { id: '# task number #692', spec: TODO_SPEC, value: '#692', expect: 'null' },
  { id: '# task number #692 with suffix', spec: TODO_SPEC, value: '见 #692 台账', caret: 5, expect: 'null' },
  { id: '# hex color #fff', spec: TODO_SPEC, value: '#fff', expect: 'null' },
  { id: '# hex color #ffffff', spec: TODO_SPEC, value: 'color: #ffffff', expect: 'null' },
  { id: '# hex color #00ff00ff', spec: TODO_SPEC, value: '#00ff00ff', expect: 'null' },
  { id: '# space inside token closes', spec: TODO_SPEC, value: '#买牛 奶', expect: 'null' },
  { id: '# inside fenced code block', spec: TODO_SPEC, value: '```\n#买牛奶', expect: 'null' },
  { id: '# inside closed fence then prose', spec: TODO_SPEC, value: '```\ncode\n```\n#买牛奶', expect: 'token' },
  { id: '# inside inline code', spec: TODO_SPEC, value: '看 `#买牛奶', expect: 'null' },
  { id: '# after closing inline code', spec: TODO_SPEC, value: '看 `x` 后 #买牛奶', expect: 'token' },
  // ── `@` 回归腿（既有 8 例逐条重跑，触发面零漂移）──
  { id: '@ cjk-hanzi-prefix (regression)', spec: PROJECT_SPEC, value: '帮我@', expect: 'token' },
  { id: '@ cjk-punct-fullwidth-period (regression)', spec: PROJECT_SPEC, value: '。@nb', expect: 'token' },
  { id: '@ cjk-punct-fullwidth-paren (regression)', spec: PROJECT_SPEC, value: '（@nb', expect: 'token' },
  { id: '@ whitespace-prefix (regression)', spec: PROJECT_SPEC, value: ' @nb', expect: 'token' },
  { id: '@ line-start (regression)', spec: PROJECT_SPEC, value: '@nb', expect: 'token' },
  { id: '@ ascii-letter-email (defense kept)', spec: PROJECT_SPEC, value: 'foo@', expect: 'null' },
  { id: '@ ascii-digit (defense kept)', spec: PROJECT_SPEC, value: 'a1@', expect: 'null' },
  { id: '@ space-inside-token (regression)', spec: PROJECT_SPEC, value: '@nb fl', expect: 'null' },
];

const isLiveToken = (tok) =>
  tok !== null && typeof tok === 'object' &&
  Number.isInteger(tok.start) && typeof tok.query === 'string';

for (const c of TOKEN_CASES) {
  const tok = parseToken(asInput(c.value, c.caret), c.spec);
  const got = isLiveToken(tok) ? 'token' : (tok === null ? 'null' : 'shape!');
  add(c.id, got === c.expect, `value=${JSON.stringify(c.value)} expect=${c.expect} got=${got}`);
}

// 触发链静态断言：token 成立 ⇒ updateTokenDropdown ⇒ renderDropdown 打开弹层。
const wiring = [
  ['bindTokenComplete 绑 input 监听', /export function bindTokenComplete\(view\) \{/.test(tokenSrc)],
  ['input 监听走 updateTokenDropdown', /addEventListener\('input',[\s\S]{0,220}updateTokenDropdown\(view\)/.test(tokenSrc)],
  ['IME 组字中不触发（isImeComposing 守卫）', /isImeComposing\(e, input\)\) return;[\s\S]{0,120}updateTokenDropdown/.test(tokenSrc)],
  ['弹层打开类 on', /dd\.classList\.add\('on'\)/.test(tokenSrc)],
  ['Enter/Tab 确认插入', /\(e\.key === 'Enter' \|\| e\.key === 'Tab'\) && !e\.shiftKey && items\.length > 0/.test(tokenSrc)],
  ['Esc 关闭', /e\.key === 'Escape'[\s\S]{0,120}closeTokenDropdown\(\)/.test(tokenSrc)],
  ['↑↓ 导航', /e\.key === 'ArrowDown'/.test(tokenSrc) && /e\.key === 'ArrowUp'/.test(tokenSrc)],
];
for (const [id, ok] of wiring) add('wire: ' + id, !!ok, ok ? 'present' : 'missing');

// ── B. 拼音与排序 ─────────────────────────────────────────────────────────
const pinyinMod = await import(pathToFileURL(join(WEB, 'pinyin.js')).href);
const { pinyinScore, fullPinyin, initialsPinyin } = pinyinMod;

const LEIJI = '电离能损';
const PINYIN_CASES = [
  { id: 'pinyin 全拼 dianlinengsun', q: 'dianlinengsun', want: 2 },
  { id: 'pinyin 前缀全拼 dian（作者点名用例）', q: 'dian', want: 2 },
  { id: 'pinyin 中段全拼 lineng', q: 'lineng', want: 2 },
  { id: 'pinyin 首字母 dlns', q: 'dlns', want: 1 },
  { id: 'pinyin 首字母带空格 dl n', q: 'dl n', want: 1 },
  { id: 'pinyin 非命中 ddns（如实红/绿：见 detail）', q: 'ddns', want: 0 },
  { id: 'pinyin 英文名不受影响 (DOSTEL/dos)', q: 'dos', want: 2, name: 'DOSTEL' },
  { id: 'pinyin 混排名 (SiPM康普顿成像/kangpu)', q: 'kangpu', want: 2, name: 'SiPM康普顿成像' },
  { id: 'pinyin 混排名首字母 (SlideBlocks Studio/sbs)', q: 'sbs', want: 1, name: 'SlideBlocks Studio' },
];
for (const c of PINYIN_CASES) {
  const name = c.name || LEIJI;
  const got = pinyinScore(name, c.q);
  add(c.id, got === c.want,
    `name=${name} full=${fullPinyin(name)} init=${initialsPinyin(name)} want=${c.want} got=${got}`);
}

// rankCandidates：抽原文执行（只注入真实 pinyinScore/hasHan + locale 桩）
const rankText = extractFn(tokenLines, /^export function rankCandidates\(items, query\) \{$/, 'rankCandidates')
  .replace(/^export /, '');
let rank;
try {
  rank = new Function('pinyinScore', 'hasHan', 'getLocale',
    rankText + '\nreturn rankCandidates;')(pinyinScore, pinyinMod.hasHan, () => 'zh-CN');
} catch (e) {
  fail2('extracted rankCandidates failed to evaluate: ' + e.message);
}

const FIXTURE = [
  { name: '电离能损', detail: 'CZT 探测器物理图件四图核验', createdAt: 1 },
  { name: '电离能损复核', detail: '旧账', createdAt: 2 },
  { name: '写周报', detail: '把 dian 相关的图都附上', createdAt: 3 },
  { name: 'DOSTEL', detail: '探测器装配', createdAt: 4 },
];
const rankOf = (q) => rank(FIXTURE, q).map((x) => x.name);

// `dian`：名称前缀档为空，全拼命中档（3）独占前两名，detail 命中（1）排最后。
const dianRank = rankOf('dian');
add('rank dian 命中「电离能损」且压过 detail 档',
  dianRank.includes('电离能损') && dianRank.indexOf('电离能损') <= 1 && dianRank[dianRank.length - 1] === '写周报',
  'order=' + JSON.stringify(dianRank));
add('rank dian 的 detail 命中排最后（写周报）',
  dianRank[dianRank.length - 1] === '写周报', 'order=' + JSON.stringify(dianRank));
// 同档 tie-break = 最近创建优先（本 fixture 里「电离能损复核」createdAt 更新）
add('rank 同档按最近创建优先', dianRank[0] === '电离能损复核', 'order=' + JSON.stringify(dianRank));
// 字面前缀必须压过拼音：`电离` 是名称前缀，拼音档不得插队。
const literalRank = rankOf('电离能损');
add('rank 名称精确命中排第一', literalRank[0] === '电离能损', 'order=' + JSON.stringify(literalRank));
// 不变式：前缀命中 > 中段命中（写 优先于 电离能损 的拼音档）
const prefixRank = rankOf('写');
add('rank 名称前缀压过一切拼音档', prefixRank[0] === '写周报', 'order=' + JSON.stringify(prefixRank));
// 空查询 = locale 名称序（`@` 既有口径零漂移），**不是**最近创建序
const emptyRank = rankOf('');
const localeSorted = FIXTURE.map((x) => x.name)
  .sort((a, b) => a.localeCompare(b, 'zh-CN', { numeric: true }));
const recencySorted = FIXTURE.slice().sort((a, b) => b.createdAt - a.createdAt).map((x) => x.name);
add('rank 空查询 = locale 名称序（非活跃度序）',
  JSON.stringify(emptyRank) === JSON.stringify(localeSorted) &&
  JSON.stringify(emptyRank) !== JSON.stringify(recencySorted),
  'order=' + JSON.stringify(emptyRank) + ' locale=' + JSON.stringify(localeSorted) + ' recency=' + JSON.stringify(recencySorted));

// ── C. 两段化（真实 import todoText.js）───────────────────────────────────
const todoTextMod = await import(pathToFileURL(join(WEB, 'todoText.js')).href);
const { splitTodoText, projectTodoText, TODO_NAME_MAX } = todoTextMod;

const stripWs = (s) => String(s || '').replace(/\s+/g, '');

const LEGACY = [
  '修登录态过期提示。另外把 401 的重试逻辑也补上，别只改文案。',
  '买牛奶',
  '把周报模板整理进 memory：① 结构 ② 模板 ③ 提醒时间',
  '这是一段没有句末标点但是很长很长很长很长很长很长很长很长的待办内容需要被硬切',
  '',
  'Vincent 说这条要在 CI 绿之后再合并（需用户批准）',
];
for (const raw of LEGACY) {
  const { name, detail } = splitTodoText(raw);
  const preserved = stripWs(name + detail) === stripWs(raw);
  add(`两段化零丢失 ${JSON.stringify(raw.slice(0, 16))}…`, preserved,
    `name=${JSON.stringify(name)} detailLen=${detail.length} origLen=${raw.length}`);
  add(`两段化标题不超上限 ${JSON.stringify(raw.slice(0, 8))}…`, name.length <= TODO_NAME_MAX,
    `nameLen=${name.length} max=${TODO_NAME_MAX}`);
}

// 首句标点切分：句号归 name
const first = splitTodoText('修登录态。后面的都是详情。');
add('两段化 首句标点切分', first.name === '修登录态。' && first.detail === '后面的都是详情。',
  JSON.stringify(first));

// projectTodoText：新行（带 detail）直用后端两段；旧行（无 detail）投影
const newRow = projectTodoText({ name: '买牛奶', detail: '顺便买鸡蛋', content: '顺便买鸡蛋' });
add('projectTodoText 新行直用 name/detail',
  newRow.name === '买牛奶' && newRow.detail === '顺便买鸡蛋' && newRow.legacy === false, JSON.stringify(newRow));
const legacyRow = projectTodoText({ content: '买牛奶。顺便买鸡蛋' });
add('projectTodoText 旧行读时投影（legacy=true）',
  legacyRow.legacy === true && stripWs(legacyRow.name + legacyRow.detail) === '买牛奶。顺便买鸡蛋',
  JSON.stringify(legacyRow));

// 渲染面静态钉：列表行显 name、detail 默认 hidden + 折角钮
const faceSrc = read(FACE_PATH);
const faceCss = read(FACE_CSS_PATH);
add('渲染面 列表行标题取 item.name', /el\('span', 'tfl-row-title', item\.name\)/.test(faceSrc), 'todoFace.buildRow');
add('渲染面 detail 默认 hidden', /const detail = el\('div', 'tfl-row-detail', item\.detail\);\s*\n\s*detail\.hidden = true;/.test(faceSrc),
  'todoFace.buildRow');
add('渲染面 折角钮切 aria-expanded', /foldBtn\.setAttribute\('aria-expanded', String\(open\)\)/.test(faceSrc), 'todoFace.buildRow');
add('CSS detail[hidden] 真隐藏', /\.tfl-row-detail\[hidden\] \{ display: none; \}/.test(faceCss), 'todo-face.css');

// ── `#` 引用渲染侧：matchTodoAt 原文执行 + chip 形态静态钉 ────────────────
const utilsSrc = read(UTILS_PATH);
const utilsLines = utilsSrc.split('\n');
const utilsRe = (re, label) => {
  const line = utilsLines.find((l) => re.test(l));
  if (!line) fail2(label + ' declaration not found in utils.js');
  return line;
};
const utilDigitLine = utilsRe(/^const TODO_DIGIT_HEAD_RE = .+;\s*$/, 'TODO_DIGIT_HEAD_RE');
const utilHexLine = utilsRe(/^const TODO_HEX_SHAPE_RE = .+;\s*$/, 'TODO_HEX_SHAPE_RE');
const utilContLine = utilsRe(/^const TODO_WORD_CONT_RE = .+;\s*$/, 'TODO_WORD_CONT_RE');
const matchTodoText = extractFn(utilsLines, /^function matchTodoAt\(text, at, todos\) \{$/, 'matchTodoAt');

let matchTodoAt;
try {
  matchTodoAt = new Function(
    utilDigitLine + '\n' + utilHexLine + '\n' + utilContLine + '\n' + matchTodoText +
    '\nreturn matchTodoAt;'
  )();
} catch (e) {
  fail2('extracted matchTodoAt failed to evaluate: ' + e.message);
}

const TODOS = [
  { name: '买牛奶', id: 'a1' },
  { name: '买', id: 'a2' },
  { name: 'fff', id: 'a3' },
  { name: '692', id: 'a4' },
  { name: '写周报', id: 'a5' },
];
const MATCH_CASES = [
  { id: 'chip 汉字后中文逗号收尾也命中', text: '看#买牛奶，记得', at: 1, want: '买牛奶' },
  { id: 'chip 最长名优先（买牛奶 > 买）', text: '#买牛奶', at: 0, want: '买牛奶' },
  { id: 'chip 空格收尾', text: '#写周报 谢谢', at: 0, want: '写周报' },
  { id: 'chip #692 不识别（任务号）', text: '#692 台账', at: 0, want: null },
  { id: 'chip #fff 不识别（色值）', text: '#fff', at: 0, want: null },
  { id: 'chip 词中不识别（不吞字）', text: '#买牛奶abc', at: 0, want: null },
];
for (const c of MATCH_CASES) {
  const hit = matchTodoAt(c.text, c.at, TODOS);
  const got = hit ? hit.todo.name : null;
  add(c.id, got === c.want, `text=${JSON.stringify(c.text)} want=${String(c.want)} got=${String(got)}`);
}

// chip 输出形态：data-todo-id + 未命中不吞字（else 分支原样拼回）。
// 源码里属性串是跨行拼接的 ⇒ 先压掉空白再匹配（判据本身仍是原文顺序）。
const utilsFlat = utilsSrc.replace(/\s+/g, '');
add('chip 形态带 data-todo / data-todo-id / data-todo-detail',
  /<spanclass="todo-tag"data-todo="'\+nameAttr\+'"data-todo-id="'\+idAttr\+'"data-todo-detail="'\+detailAttr/.test(utilsFlat),
  'utils.restoreTodoRefs');
add('chip 未命中不吞字（else 分支拼回触发符）',
  utilsFlat.includes('result+=seg.slice(i,at+1);i=at+1;'), 'utils.protectTodoRefs');
add('渲染管线挂上 protectTodoRefs', /protectTodoRefs\(/.test(utilsSrc) &&
  /html = restoreTodoRefs\(html, todoBlocks\);/.test(utilsSrc), 'utils.renderMarkdownWithMath');

// 插入形态：`#<name>`（可读、可解析）
const todoCompleteSrc = read(TODO_COMPLETE_PATH);
add('插入形态 = #<待办名>', /insertText: \(item\) => '#' \+ item\.name/.test(todoCompleteSrc), 'todoComplete.insertText');
add('`#` provider 开三道门禁',
  /codeGate: true/.test(todoCompleteSrc) && /blockDigitStart: true/.test(todoCompleteSrc) &&
  /blockHexColor: true/.test(todoCompleteSrc), 'todoComplete.registerProvider');

// ── 读数 ─────────────────────────────────────────────────────────────────
const red = results.filter((r) => !r.pass);
console.log('== check-todo-mention ==  sources: tokenComplete.js / pinyin.js / todoText.js / utils.js / todoFace.js');
for (const r of results) {
  console.log((r.pass ? 'PASS' : 'FAIL') + '  ' + r.id + '  [' + r.detail + ']');
}
if (red.length > 0) {
  console.error('RED: ' + red.length + ' failing assertion(s) of ' + results.length);
  process.exit(1);
}
console.log('GREEN: ' + results.length + '/' + results.length + ' assertions pass');
process.exit(0);
