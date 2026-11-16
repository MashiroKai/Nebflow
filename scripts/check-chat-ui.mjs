#!/usr/bin/env node
// Full-scope conversation-UI vocabulary lint (chat-ui-package 批④，2026-11-16).
//
// 单源词表 = js/chatUi.js 的字符串字面量**机械抽取**（模板 class=/id= 属性 +
// 工厂类名串）：气泡 / composer / footer / 搜索四个选择器族在 css/ 与 js/ 里
// 出现的每个族内 token 必须 ∈ 词表 ∪ 豁免名单 —— 族内私设拼写一律红。
//   · 族 = 共享对话 UI 原语的**词根**（bubble / ask-bub / fm-msg-bubble、
//     input-bar / send-btn / ds-turn-btn / fm-input…、duration-badge / fm-msg-meta…、
//     search-modal / search-tab / search-keyword…）；
//   · 豁免 = 显式名单（Ask 壳族冻结、fm footer extraClass、搜索结果内容族等），
//     每条带理由；新增豁免必须在此可见。
// 配套消费方要求（既有 chat-ui-unify 批的 per-consumer 检查，随工厂化更新）
// 与「重复原语」反查保留在后半段。
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const webRoot = fileURLToPath(new URL('../src/main/resources/web/', import.meta.url));
const web = webRoot.endsWith('/') ? webRoot : webRoot + '/';
const read = p => readFileSync(join(web, p), 'utf8');

// ── 词表：chatUi.js 字面量机械抽取 ───────────────────────────────────────────
// 三个抽取器都只吃「类名形态」字符段（小写/数字/-/_/空格），错误文案、路径、
// 注释散文（含大写/标点/中文）自然排除；词表只在族判定命中时才起作用。
const chatUiSrc = read('js/chatUi.js');
const vocab = new Set();
for (const m of chatUiSrc.matchAll(/\b(?:class|id)\s*=\s*"([^"]+)"/g))
  for (const tok of m[1].split(/\s+/)) if (tok) vocab.add(tok);
for (const m of chatUiSrc.matchAll(/'([^'\n]+)'/g))
  if (/^[a-z0-9 _-]+$/.test(m[1])) for (const tok of m[1].split(/\s+/)) if (tok) vocab.add(tok);
// 模板字面量：含 ${} 的也吃**静态段**（如 `bubble ${role}` → 'bubble'）；段级
// 字符集过滤把注释散文（大写/标点/中文）排除在外。
for (const m of chatUiSrc.matchAll(/`([^`]*)`/g))
  for (const part of m[1].split(/\$\{[^}]*\}/))
    if (/^[a-z0-9 \n_-]+$/.test(part))
      for (const tok of part.split(/\s+/)) if (tok) vocab.add(tok);

// ── 族定义（token → 族）────────────────────────────────────────────────────
const FAMILY_STEMS = {
  bubble: ['bubble', 'ask-bub', 'fm-msg-bubble'],
  composer: [
    'input-bar', 'input-wrap', 'attachment-preview', 'ask-indicator', 'skill-indicator',
    'compact-indicator', 'skip-freeze-btn', 'skip-freeze-label', 'attach-btn', 'attach-input',
    'send-btn', 'stop-btn', 'ds-turn-btn', 'mic-btn', 'fm-input', 'ask-input',
    'mic-orb-wrap', 'micbubble',
  ],
  footer: ['duration-badge', 'fm-msg-meta', 'fm-msg-divider', 'fm-msg-time'],
  search: [
    'search-modal', 'search-tab', 'search-keyword', 'search-scope', 'search-agent',
    'search-tool', 'search-go', 'search-status', 'search-results', 'search-result',
    'search-controls', 'search-filters', 'search-overlay', 'search-date-popover',
    'search-media', 'search-group', 'search-hit-flash', 'search-sentinel',
    'search-more-tail', 'search-batch-open',
  ],
};
// 复合后缀（fm-*/ask-* 等变体键位）也属 composer 族。
const COMPOSER_SUFFIXES = ['-send-btn', '-stop-btn', '-mic-btn', '-attach-btn', '-input-bar', '-input-wrap', '-turn-btn'];
function familyOf(tok) {
  for (const [fam, stems] of Object.entries(FAMILY_STEMS))
    for (const s of stems)
      if (tok === s || tok.startsWith(s + '-')) return fam;
  if (COMPOSER_SUFFIXES.some(s => tok.endsWith(s) && tok.length > s.length)) return 'composer';
  return null;
}

// ── 豁免名单（显式，逐条理由）──────────────────────────────────────────────
// [tokenMatcher, fileMatcher, 理由]。tokenMatcher: string | RegExp; fileMatcher: RegExp（web 相对路径）。
const EXEMPTIONS = [
  // Ask 壳族冻结：作者裁定（2026-11-13）Ask 视觉单选未做、壳不动 —— 存量 ask-*
  // 私设拼写放行（ask-input / ask-bub 已在词表；此条兜住其余 ask-* 键位拼写）。
  [/^ask-(?!input$|bub$)[a-z0-9-]+$/, /^js\/ask[A-Z]/, 'Ask 壳族冻结（作者裁定视觉单选未做）'],
  [/^ask-(?!input$|bub$)[a-z0-9-]+$/, /^css\/ask\.css$/, 'Ask 壳族冻结（同上，css 侧）'],
  // fm footer extraClass：chatFooter*() 的 extraClass 参数拼在工厂产物上，拼写
  // 单点 = messages.js 调用处（per-consumer 要求已钉）；css 归 friends.css，
  // sendState/sapphire 只按钩子寻址（断连态/触控目标档），不产拼写。
  ['fm-msg-meta', /^(js\/(messages|sendState)\.js|css\/friends\.css)$/, "chatFooter('fm-msg-meta') extraClass + sendState 断连态寻址"],
  ['fm-msg-divider', /^(js\/messages\.js|css\/friends\.css)$/, "chatFooterDivider('fm-msg-divider') extraClass"],
  ['fm-msg-time', /^(js\/messages\.js|css\/(friends|sapphire)\.css)$/, "chatFooterTime('fm-msg-time') extraClass + sapphire 触控档寻址"],
  // 静态壳锚 id 引用（id 拼写在 index.html / chatMount 装配标记，非类名族）：
  // #input-bar 与 #search-overlay 是 index.html 留的装配锚，引用方只寻址。
  ['input-bar', /^js\/(main|micOrb|input\/attachments)\.js$/, '#input-bar 静态壳锚 id 引用'],
  ['search-overlay', /^js\/(chatSearch|chatMount|mobile\/navController|mobile\/searchFace|gesture\/backSwipe)\.js$/, '#search-overlay 静态壳锚 id 引用'],
  // 主输入条的浮层附件弹窗形态：input.css 与 #input-bar **同块成对** element-scoped
  // （.fa-input-bar 挂浮层、#input-bar 挂主位），attachments.js 单点选择器组。
  ['fa-input-bar', /^(js\/input\/attachments\.js|css\/input\.css)$/, '附件浮层弹窗输入条（与 #input-bar 同块成对，input.css 单源）'],
  // dropbox 面板私有输入条：面板命名空间 dropbox-*（js/css 同名单源）——
  // 命名撞 composer 族后缀但语义独立（文件投递面板，非对话 composer）。
  [/^dropbox-(input-bar|send-btn|attach-btn)$/, /^(js\/dropbox\.js|css\/dropbox\.css)$/, 'dropbox 面板命名空间族（面板私有输入条）'],
  // 死选择器：.fm-attach-btn 无任何生产者（fm 纸夹键 = `.icon-btn`，无面级钩子
  // 类——见 fmComposer.js ③注释的既有形态）；选择器从未命中 ⇒ 视觉零参与，
  // 留待清理批删除。禁新增引用（本豁免只认 css 侧存量三处）。
  ['fm-attach-btn', /^css\/(flatButtons|mobileLayer|sapphire)\.css$/, '死选择器（无生产者，待清理批删除）'],
  // 搜索结果内容族：壳（searchShell 共用件）之外的结果行/媒体卡/分组/批量选择
  // 等由 chatSearch.js 单点渲染（modal.css / chat.css / mobileLayer.css 配套）。
  // 壳族 token（search-modal*/search-tab 基名/keyword/scope/…）不在此列 —— 那些只在词表。
  [/^search-(result|media|group|hit-flash|sentinel|more-tail|batch-open|date-clear|tab-label)(-[a-z0-9-]+)?$/,
    /^(js\/chatSearch\.js|css\/(chat|modal|mobileLayer)\.css)$/, '搜索结果内容族（chatSearch 单点）'],
  [/^search-(select-files|open-selected-files|multi-sel-style|return-pill)$/,
    /^(js\/(chatSearch|mobile\/searchReturnPill)\.js|css\/mobileLayer\.css)$/, '搜索批量选择/返回胶囊（chatSearch·searchReturnPill 单点）'],
];

// ── 扫描器 ──────────────────────────────────────────────────────────────────
const stripJsComments = s => s
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1 '); // 保留 'https://' 内的 //（前面字符判断）
const stripCssComments = s => s.replace(/\/\*[\s\S]*?\*\//g, ' ');
const tokensOfJs = src => {
  const out = new Set();
  const add = lit => {
    for (const tok of lit.split(/[^a-z0-9_-]+/)) {
      const t = tok.replace(/-+$/, '');
      if (t.length > 2) out.add(t);
    }
  };
  const noComments = stripJsComments(src);
  for (const m of noComments.matchAll(/'([^'\n]*)'|"([^"\n]*)"/g)) add(m[1] ?? m[2] ?? '');
  for (const m of noComments.matchAll(/`([^`]*)`/g))
    for (const part of m[1].split(/\$\{[^}]*\}/)) add(part);
  return out;
};
const tokensOfCss = src => {
  const out = new Set();
  for (const m of stripCssComments(src).matchAll(/\.(-?[a-z][a-z0-9_-]*)/gi))
    if (m[1].length > 2) out.add(m[1].toLowerCase());
  return out;
};
function walkJs(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'locales') out.push(...walkJs(join(dir, e.name))); }
    else if (e.name.endsWith('.js') && e.name !== 'chatUi.js') out.push(join(dir, e.name));
  }
  return out;
}

const errors = [];
const violations = [];
const seen = new Set();
const note = (file, tok, fam) => {
  const rel = file.startsWith(web) ? file.slice(web.length) : file;
  const key = `${rel}|${tok}`;
  if (seen.has(key)) return;
  seen.add(key);
  const inVocab = vocab.has(tok);
  const listed = !inVocab && EXEMPTIONS.some(([t, f]) =>
    (typeof t === 'string' ? t === tok : t.test(tok)) && f.test(rel));
  violations.push({ rel, tok, fam, ok: inVocab || listed, inVocab });
  if (!inVocab && !listed) errors.push(`${rel}: family '${fam}' token '${tok}' not in chatUi vocabulary or exemption list`);
};

for (const file of walkJs(join(web, 'js')))
  for (const tok of tokensOfJs(readFileSync(file, 'utf8'))) {
    const fam = familyOf(tok);
    if (fam) note(file, tok, fam);
  }
for (const file of readdirSync(join(web, 'css')).filter(f => f.endsWith('.css'))) {
  const full = join(web, 'css', file);
  for (const tok of tokensOfCss(readFileSync(full, 'utf8'))) {
    const fam = familyOf(tok);
    if (fam) note(full, tok, fam);
  }
}

if (process.argv.includes('--dump')) {
  console.log(`vocab(${vocab.size}):`, [...vocab].filter(t => familyOf(t)).sort().join(' '));
  for (const v of violations.sort((a, b) => a.rel.localeCompare(b.rel) || a.tok.localeCompare(b.tok)))
    console.log(`${v.ok ? (v.inVocab ? 'vocab  ' : 'exempt ') : 'RED    '} ${v.rel} ${v.tok} (${v.fam})`);
}

// ── 消费方要求（chat-ui-unify 批既有项 + chat-ui-package 批①②③新形态）──────
const requirements = [
  ['js/chat/durationBadges.js', /chatFooter\(\)/, /chatFooterDivider\(\)/, /chatFooterTime\(\)/],
  ['js/messages.js', /chatFooter\('fm-msg-meta'\)/, /chatFooterDivider\('fm-msg-divider'\)/, /chatFooterTime\('fm-msg-time'/, /bubbleClass\(/, /fmBubbleRowClass\(/],
  ['js/messages/fmComposer.js', /composer\(\{\s*variant: 'compact'/],
  ['js/askDock.js', /chatComposerClass\('ask'\)/, /askBubbleClass\(m\.role\)/],
  ['js/main.js', /from '\.\/chatSearch\.js'/, /^import '\.\/chatMount\.js';$/m],
  ['js/chatMount.js', /composer\(\{ variant: 'main', mount: composerMount \}\)/, /searchShell\(\{ mount: searchMount \}\)/, /data-chatui-mount/],
  ['js/chat.js', /mainBubbleClass\('user'/, /mainBubbleClass\('ai'\)/],
  ['js/chatUi.js', /'ask-bub nf-glass '/, /'ask-input nf-glass'/, /'fm-input-bar nf-glass'/, /'fm-msg-bubble '/, /MAIN_COMPOSER_SHELL/, /SEARCH_SHELL/],
];
for (const [path, ...patterns] of requirements) {
  const text = read(path);
  for (const pattern of patterns) if (!pattern.test(text)) errors.push(`${path}: missing ${pattern}`);
}
// 重复原语反查：消费方不得再手写工厂已收口的类名串。
for (const [path, pattern] of [
  ['js/chat/durationBadges.js', /className\s*=\s*['"]duration-badge(?:-time|-divider)?['"]/],
  ['js/messages/fmComposer.js', /['"]fm-input-bar nf-glass['"]|className\s*=\s*['"]cfg-input fm-input/],
  ['js/askDock.js', /class:\s*['"](?:ask-input nf-glass|ask-bub nf-glass)/],
  ['js/messages.js', /`fm-msg-bubble \$\{/],
]) if (pattern.test(read(path))) errors.push(`${path}: duplicated migrated primitive`);

if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log(`chat-ui: vocabulary lint clean — vocab ${vocab.size} tokens, in-family occurrences ${violations.length} ` +
  `(vocab ${violations.filter(v => v.inVocab).length} / exemption-list ${violations.filter(v => v.ok && !v.inVocab).length}); consumers wired to chatUi factories`);
