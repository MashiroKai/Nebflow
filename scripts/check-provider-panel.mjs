#!/usr/bin/env node
// check-provider-panel.mjs — 「服务商面板只有一套 + 类型枚举单源 + 溢出处置合规」
// 静态哨兵（provider-add-tabs 批 2026-11-06；作者令逐字：
//  「现在添加服务商是每个类型的模型各有一个独立的添加入口，我们统一一下，在
//   『添加服务商』顶部加几个标签……把整个配置和显示流程都统一起来。」+
//  「这里一定要加断言，保证所有文字都不能溢出」）
//
// Why a gate：本批把三套分散的服务商入口（LLM 内联列表 / #jev-advance-* 下钻节 /
// #imagegen-advance-* 下钻节）并成唯一面板（sidebar/providerPanel.js）。历史教训是
// 「统一」最怕回潮：某个后续批嫌 providerPanel 大，随手在别处再画一张服务商卡、
// 或前端再拼一份适配器字面量表、或给某个长文本件加个 white-space:nowrap 救急——
// 每一条都会把批正在修的问题静默带回来。手工审查抓不住这类回潮，所以落机器判据。
//
// ── 六条判据 ────────────────────────────────────────────────────────────
//  P1 旧入口零回潮：五类已退役结构不得在任何 web 文件重现——
//     `jev-provider-list` / `imagegen-provider-list` / `btn-add-jev-provider` /
//     `btn-add-imagegen-provider` / `jev-provider-form`（retired id 全集，注释提名字
//     也会红——注释一样是回潮的种子）。
//  P2 面板唯一：`nf-provider-panel` 结构与 `bindProviderPanel` 调用只许出现在
//     sidebar/providerPanel.js（定义单点）与 sidebar.js（唯一挂点）。其它 js/css
//     文件出现 `.nf-provider-panel` 或 `data-provider-panel` ⇒ 红（第二张面板）。
//  P3 类型标签唯一：`nf-ptab` / `nf-ptabs` / `data-provider-type` 只许出现在
//     providerPanel.js + sidebar.css（分段控件单实现）。
//  P4 枚举单源（前端零拼写）：web/ 下的 js 不得出现适配器字面量拼写表——
//     `['cogview'`、`"cogview"` 形态的数组字面量、以及
//     `PROVIDER_TYPES` / `ADAPTERS` / `JEV_PROVIDER_TYPES` / `IMAGEGEN_PROVIDER_TYPES`
//     这类前端类型表名字。类型清单只许来自 `/api/config/provider-types`。
//  P5 后端枚举单源：ProviderDomains.scala 的 All 清单必须与
//     ConfigService.providerTypes 投影的域 id 一一对应（静态读文件比对，改后端
//     枚举漏改投影 = 红）；ProviderDomains.scala 只许有一份（新文件复制它 = 红）。
//  P6 溢出处置合规（真渲染判据见 tests/provider-panel-overflow.spec.mjs，本条是
//     静态面）：css 里禁止「裸 nowrap」——`white-space: nowrap` 的规则块必须同时含
//     `text-overflow: ellipsis`（省略号档），否则红（换行档 = normal/anywhere，天然
//     不命中）。`overflow-x: hidden` 单独出现（同块无 ellipsis）也算违规嫌疑：
//     裁剪处置已被批规范禁止，只许换行或省略号。
//
// Run: node scripts/check-provider-panel.mjs   （CI 步 Provider panel gate 同命令）

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SCALA = join(ROOT, 'src', 'main', 'scala');

/** @param {string} dir @param {(p:string)=>void} fn */
function walk(dir, fn) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, fn);
    else fn(p);
  }
}

/** @type {string[]} */
const fails = [];
const err = (rule, file, msg) => fails.push(`[${rule}] ${file.replace(ROOT + '/', '')}: ${msg}`);

// ── P1 旧入口零回潮 ─────────────────────────────────────────────────────────
const RETIRED_IDS = [
  'jev-provider-list', 'imagegen-provider-list', 'btn-add-jev-provider',
  'btn-add-imagegen-provider', 'jev-provider-form',
];
const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
walk(WEB, (p) => {
  const ext = extname(p);
  if (!['.js', '.css', '.html'].includes(ext)) return;
  const src = stripComments(readFileSync(p, 'utf8')); // 注释里的退役清单是文档（面板头必须记录退役了什么），不算回潮
  for (const id of RETIRED_IDS) {
    if (src.includes(id)) err('P1', p, `退役 id 复活（活代码）：「${id}」（旧入口不许回潮，统一走 providerPanel.js）`);
  }
});

// ── P2/P3 面板与标签唯一 ────────────────────────────────────────────────────
const PANEL_SINGLETON = ['nf-provider-panel', 'data-provider-panel', 'bindProviderPanel', 'PROVIDER_PANEL_HOST_ID'];
const TAB_SINGLETON = ['nf-ptab', 'nf-ptabs', 'data-provider-type'];
const panelAllowed = (p) => p.endsWith('sidebar/providerPanel.js') || p.endsWith(`${WEB}/js/sidebar.js`) && PANEL_SINGLETON.includes('bindProviderPanel') === false
  ? p.endsWith('sidebar/providerPanel.js') || (p.endsWith(`${WEB}/js/sidebar.js`))
  : false;
walk(WEB, (p) => {
  const ext = extname(p);
  if (!['.js', '.css', '.html'].includes(ext)) return;
  if (p.includes('/tests/')) return; // 判据文件出现这些键是本职（measure/断言），放行
  const src = readFileSync(p, 'utf8');
  const okPanel = p.endsWith('js/sidebar/providerPanel.js') || p.endsWith('js/sidebar.js') || p.endsWith('css/sidebar.css');
  const okTabs = p.endsWith('js/sidebar/providerPanel.js') || p.endsWith('css/sidebar.css');
  if (!okPanel) for (const k of PANEL_SINGLETON) if (src.includes(k)) err('P2', p, `服务商面板结构外泄：「${k}」（唯一实现 = sidebar/providerPanel.js；样式单点 = sidebar.css）`);
  if (!okTabs) for (const k of TAB_SINGLETON) if (src.includes(k)) err('P3', p, `类型标签结构外泄：「${k}」（分段控件唯一实现 = providerPanel.js + sidebar.css）`);
});

// ── P4 前端零拼写表 ─────────────────────────────────────────────────────────
const FORBIDDEN_TABLE_NAMES = ['PROVIDER_TYPES', 'JEV_PROVIDER_TYPES', 'IMAGEGEN_PROVIDER_TYPES', 'ADAPTER_OPTIONS'];
walk(WEB, (p) => {
  if (!p.endsWith('.js') || p.includes('/tests/')) return;
  const src = stripComments(readFileSync(p, 'utf8'));
  for (const name of FORBIDDEN_TABLE_NAMES) {
    if (new RegExp(`\\b${name}\\b`).test(src)) err('P4', p, `前端类型/适配器拼写表「${name}」——类型枚举唯一来源 = GET /api/config/provider-types`);
  }
  // 适配器字面量数组（['cogview', …]）：引号内 cogview/imagen/typesafe-jev/laya
  // 出现在数组字面量里 = 手工清单回潮。
  if (/\[\s*(['"])(cogview|openai-images|imagen|typesafe-jev|laya)\1[^\]]*\]/.test(src)) {
    err('P4', p, '适配器字面量数组（类型清单必须消费后端投影，不前端拼写）');
  }
});

// ── P5 后端枚举单源 ─────────────────────────────────────────────────────────
const domainsPath = join(SCALA, 'nebflow', 'shared', 'ProviderDomains.scala');
const servicePath = join(SCALA, 'nebflow', 'service', 'ConfigService.scala');
const domainsSrc = readFileSync(domainsPath, 'utf8');
const serviceSrc = readFileSync(servicePath, 'utf8');
// 投影里点名的域必须走既有枚举（投影不得自带第二份字面量清单）。
for (const m of serviceSrc.matchAll(/"(llm|imagegen|jev)"\s*->/g)) {
  const d = m[1];
  const viaEnum = d === 'llm' ? /LlmProtocol\.(Anthropic|OpenAI)/ : d === 'imagegen' ? /ImageGenAdapters\.All/ : /DecisionProvider\.AllIds/;
  if (!viaEnum.test(serviceSrc)) err('P5', servicePath, `投影的「${d}」段未走既有适配器枚举（单源被绕过）`);
}
// 第二份 ProviderDomains 文件 = 红。
const domainFiles = [];
walk(SCALA, (p) => { if (p.endsWith('ProviderDomains.scala')) domainFiles.push(p); });
if (domainFiles.length !== 1) err('P5', SCALA, `ProviderDomains.scala 应只有一份，实际 ${domainFiles.length} 份：${domainFiles.join(', ')}`);
// All 清单与三个 val 一致（val 定义了却没进 All = 投影会漏域）。
const valNames = [...domainsSrc.matchAll(/val (\w+):\s*String\s*=\s*"(\w+)"/g)].map((m) => ({ name: m[1], id: m[2] }));
const allLine = domainsSrc.match(/val All:\s*List\[String\]\s*=\s*List\(([^)]*)\)/);
if (allLine) {
  const allRefs = [...allLine[1].matchAll(/(\w+)/g)].map((m) => m[1]);
  for (const ref of allRefs) if (!valNames.some((v) => v.name === ref)) err('P5', domainsPath, `All 引用了未定义的 val「${ref}」`);
  for (const v of valNames) if (!allRefs.includes(v.name)) err('P5', domainsPath, `域 val「${v.name}」(${v.id}) 未入 All 清单（投影将漏该域）`);
} else err('P5', domainsPath, '找不到 All 清单定义');

// ── P6 溢出处置合规（静态面 · 只钉本批的面板家族）─────────────────────────
// 真渲染判据（tests/provider-panel-overflow.spec.mjs）才是零容忍本体；本条防回潮。
// 面板外的存量 nowrap/裁剪不在本批范围（全库清洗属独立批），不越权扫。
const PANEL_FAMILY_SEL = /nf-provider-panel|nf-ptab|cfg-card|cfg-model|stt-advance-toggle-row|stt-status|entry-name/;
walk(join(WEB, 'css'), (p) => {
  if (!p.endsWith('.css')) return;
  const stripped = readFileSync(p, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  for (const block of stripped.split('}')) {
    if (!block.includes('{')) continue;
    const [sel, ...rest] = block.split('{');
    const body = rest.join('{');
    if (!PANEL_FAMILY_SEL.test(sel)) continue;
    const hasNowrap = /white-space\s*:\s*nowrap/.test(body);
    const hasEllipsis = /text-overflow\s*:\s*ellipsis/.test(body);
    const hasHiddenX = /overflow-x\s*:\s*(hidden|clip)/.test(body);
    if (hasNowrap && !hasEllipsis) err('P6', p, `「${sel.trim().slice(0, 60)}」裸 white-space:nowrap（处置只许换行或 省略号+title）`);
    if (hasHiddenX && !hasEllipsis) err('P6', p, `「${sel.trim().slice(0, 60)}」overflow-x 裁剪处置（批规范禁止：只许换行或 省略号+title）`);
  }
});

if (fails.length) {
  console.error(`✗ check-provider-panel：${fails.length} 条违规`);
  for (const f of fails) console.error('  ' + f);
  process.exit(1);
}
console.log('✓ check-provider-panel：P1–P6 全绿（旧入口零回潮 / 面板与标签唯一 / 前后端枚举单源 / 溢出处置合规）');
